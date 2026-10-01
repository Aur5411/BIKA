package com.shizq.bika.core.network.image

import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片存储域名的**健康度跟踪**，用于把「长期不可达的源」从换源遍历里摘出去。
 *
 * ## 为什么必须有它
 *
 * 候选池 [ImageHosts] 有 7 个域名，但本机实测（`curl` 直连 + 代理双路验证）
 * **只有 3 个在当前的国内网络环境里真的能取到图**：
 *
 * | 域名 | 结果 |
 * |---|---|
 * | `s3` / `s2` / `storage1` / `storage-b`.picacomic.com | **000 完全不可达**（DNS 被污染，解析到虚假 `2001::` 地址） |
 * | `storage-b.diwodiwo.xyz` | 200 + 完整字节（真实源站，无需跳转） |
 * | `storage.diwodiwo.xyz` | 200 + 完整字节（需经 301 跳转） |
 * | `storage.tipatipa.xyz` | 200 + 完整字节 |
 *
 * 而 API 下发的 `fileServer` 恰恰常常是 `storage1.picacomic.com` 这个**不可达**的源。
 * 于是每张图的换源流程都是：打主源 → 连接失败 → 遍历候选域名 → **先陪 6 个死源
 * 各等一次 `connectTimeout`（3 秒）** → 才轮到可用的 diwodiwo。
 *
 * 单张图白等 18 秒，而这 18 秒里它一直占着一个 `fallbackSlots` 名额（只有 12 个）。
 * 一屏 5~8 张图 + 预载窗口 16 张同时存在，槽位瞬间耗尽，后面的图连尝试的机会
 * 都没有——这就是「第 19 页修好了，第 37、38 页又出问题」的真实机制：
 * **失败点在移动，因为被饿死的总是排在队尾的那几张**。
 *
 * ## 设计要点
 *
 * - **只对"连接层失败"计数**。HTTP 404/403 是**功能级**结果（这个源上没有这张图），
 *   不代表源不可用——把 404 也算进去会把好源误判成坏源，重蹈覆辙。
 *   连接失败/超时/DNS 失败才是"这个源现在够不着"。
 * - **隔离是有期限的**。网络环境会变（换 Wi-Fi、开代理、服务端换节点），
 *   永久拉黑会变成新的"固定几页失败"。到期自动允许重试。
 * - **一次成功立刻恢复**。判定永远是"最近一次真实结果说了算"。
 * - **隔离的是"尝试顺序"，不是"可用性白名单"**：被隔离的域名仍会排在候选列表里，
 *   只是排到最后；当所有未隔离的域名都失败时，它们依然会被尝试。
 *   这样服务端换节点后不会出现"新域名永远试不到"。
 */
@Singleton
class ImageHostHealth @Inject constructor() {

    /** host -> 连续连接层失败次数。 */
    private val failures = ConcurrentHashMap<String, Int>()

    /** host -> 隔离截止时刻（epoch ms）。 */
    private val quarantinedUntilMs = ConcurrentHashMap<String, Long>()

    /** 连续失败多少次后进入隔离。 */
    private val failureThreshold = FAILURE_THRESHOLD

    /**
     * 记一次**连接层**失败（超时、DNS、连接被拒/重置），累计到阈值即隔离。
     *
     * 调用方必须在拿到"非 HTTP 响应"的失败时才调用它。
     * HTTP 404/403/500 都有真实回执，说明源是可达的，不该走这里。
     */
    fun noteUnreachable(host: String, nowMs: Long = System.currentTimeMillis()) {
        val count = failures.merge(host, 1, Int::plus) ?: 1
        if (count >= failureThreshold) {
            quarantinedUntilMs[host] = nowMs + QUARANTINE_MS
        }
    }

    /**
     * 记一次**成功**（或拿到任何 HTTP 响应）：立即清零失败计数、解除隔离。
     *
     * 拿到 HTTP 状态码（哪怕是 404）也走这里：源是活的，只是这张图没有，
     * 换源遍历仍应优先试它（别的图可能正好在它上面）。
     */
    fun noteReachable(host: String) {
        failures.remove(host)
        quarantinedUntilMs.remove(host)
    }

    /** 该域名当前是否处于隔离期。 */
    fun isQuarantined(host: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = quarantinedUntilMs[host] ?: return false
        if (until <= nowMs) {
            // 过期即解除：网络环境可能已经变了，给它一次重新证明自己的机会。
            quarantinedUntilMs.remove(host)
            failures.remove(host)
            return false
        }
        return true
    }

    /**
     * 按健康度重排候选域名，**越靠前越先试**。
     *
     * 排序规则（从优到劣）：
     * 1. 未隔离的域名（正常可用）；
     * 2. 已隔离的域名（仍会排在后面，作为最后的兜底——不影响"服务端换节点后能试到"）。
     *
     * 同档内保持传入顺序，让调用方的经验排序（如 [ImageHosts.DEFAULT_HOST]）继续生效。
     */
    fun orderCandidates(
        hosts: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> = hosts.sortedBy { if (isQuarantined(it, nowMs)) 1 else 0 }

    /**
     * 把候选域名拆成「健康」与「已隔离」两组，[first] 为健康、[second] 为已隔离。
     *
     * ## 为什么需要"拆分"而不是"排序"
     *
     * 并发竞速时，排序**解决不了问题**：竞速会把列表里的每个域名都同时发起，
     * 被排在后面的死源照样会占住并发名额整整一个 `connectTimeout`（3 秒）。
     * 一屏十几张图 × 7 个候选 = 上百条竞速连接，而额度只有十几个 ——
     * 死源把额度吃干，真正可能成功的源反而排不上队。
     *
     * 所以竞速必须**只对健康域名发起**；已隔离的域名改为「健康域名全灭后
     * 再串行试一遍」的最后兜底。这样既消除了死源对额度的稀释，
     * 又保住了"服务端换节点后新域名仍能被取到"这一条。
     *
     * 组内保持传入顺序，让调用方的经验排序继续生效。
     */
    fun partitionByHealth(
        hosts: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<List<String>, List<String>> = hosts.partition { !isQuarantined(it, nowMs) }

    /** 供测试与日志：当前被隔离的域名集合。 */
    fun quarantinedHosts(nowMs: Long = System.currentTimeMillis()): Set<String> =
        quarantinedUntilMs.keys.filter { isQuarantined(it, nowMs) }.toSet()

    private companion object {
        /**
         * 连续 2 次连接层失败即隔离。
         *
         * 取 2 而不是 1：单次连接失败可能只是瞬时抖动（切基站、DNS 一次超时），
         * 立刻隔离会把好源误伤——那正是「某几页固定失败」的另一种成因。
         * 取 2 也要比 3 小，因为死源（DNS 污染）的失败是**确定性**的，
         * 多试一次只是多浪费 3 秒。
         */
        const val FAILURE_THRESHOLD = 2

        /**
         * 隔离时长。
         *
         * 取 3 分钟：覆盖"读完几章"的时间尺度，让隔离期间的每张图都省下
         * 陪死源等待的时间；又不至于长到网络环境变化后还醒不过来。
         * 每次真实成功都会立即解除，所以这个值只影响"多久主动重试一次死源"。
         */
        const val QUARANTINE_MS = 3 * 60 * 1000L
    }
}
