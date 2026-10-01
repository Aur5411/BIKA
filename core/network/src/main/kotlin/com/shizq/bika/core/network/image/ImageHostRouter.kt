package com.shizq.bika.core.network.image

import jakarta.inject.Inject
import jakarta.inject.Singleton

/** 最快源的保鲜期：超过这个时间重新接受一次竞速结果，防止网络环境切换后一直吊死在旧源。 */
private const val PREFERRED_HOST_TTL_MS = 10 * 60 * 1000L

/**
 * 兜底源打头阵失败后的冷却期。
 *
 * 期间不再拿它当"冷启动首选"，让请求回到"打 API 给的 fileServer + 竞速"的
 * 原路径；过了冷却期重新允许尝试——网络环境会变（切 Wi-Fi、开代理、
 * 服务端换节点），永久拉黑只会变成新的"固定失败"。
 */
private const val STARTUP_FALLBACK_COOLDOWN_MS = 5 * 60 * 1000L

/**
 * 记住"当前最快的图片源"，让后续每张图直接走它。
 *
 * ## 为什么需要它
 *
 * 只有 [com.shizq.bika.core.network.plugin.DomainFallbackInterceptor] 时，
 * 每张图都是"先打 API 给的 fileServer，慢/失败才竞速"。也就是说：
 * 用户每翻一页，都要先在慢源上耗掉一个竞速窗口（见 `SLOW_MAIN_THRESHOLD_MS`），
 * 竞速学到的"哪个源快"只被用在同一次请求的后续重试里，下一张图又从头再来。
 *
 * 有这个路由后，竞速一旦决出胜者就记下来，之后的图片请求在进 Coil 链之前
 * 就被改写到胜者域名（见 [PreferredHostInterceptor]）——第一张图之后的每一张
 * 都直接打最快的源。
 *
 * ## 缓存不会因此失效
 *
 * 阅读器与下载链路在构造 `ImageRequest` 时都显式写了 `diskCacheKey = 原始 url`
 * （见 `ComicItem` / `ChapterPagePreloadProvider`），换域名不改变缓存键，
 * 因此换源不会导致同一张图重复下载、重复占盘。
 *
 * ## 冷启动的第一张图
 *
 * 上面的机制有个真空区：**进程刚起来、还没有任何竞速结论时**，
 * [preferredHost] 为 null，第一张图只能原样去打 API 给的 fileServer——
 * 而那个源常常是连不上的污染节点，于是要先等满一个 `SLOW_MAIN_THRESHOLD_MS`
 * 才开始竞速。这正是"第一次打开特别慢"的主要来源。
 *
 * 补这个真空区的是 [startupHostOrNull]：没有结论时给出一个已知可用的兜底源，
 * 让第一张图直接从这里起步。
 *
 * ## 失效
 *
 * 源挂掉、被限流都会表现为请求失败，调用方用 [forget] 清掉记录，
 * 下一张图重新走"主源 + 竞速"，选路过程重来一遍。
 */
@Singleton
class ImageHostRouter @Inject constructor() {

    @Volatile
    private var preferred: String? = null

    @Volatile
    private var decidedAtEpochMs: Long = 0L

    /** 兜底源上一次打头阵失败的时刻；null 表示本进程还没失败过。 */
    @Volatile
    private var fallbackFailedAtMs: Long? = null

    /**
     * 当前最快源；超过保鲜期或从未选出时返回 null（调用方按原样走主源）。
     */
    fun preferredHost(nowMs: Long = System.currentTimeMillis()): String? {
        val host = preferred ?: return null
        return if (nowMs - decidedAtEpochMs > PREFERRED_HOST_TTL_MS) null else host
    }

    /**
     * 本进程还没有任何选路结论时，用来给**第一张图**打头阵的源。
     *
     * ## 它解决什么问题
     *
     * 冷启动时 [preferredHost] 恒为 null，于是第一张图会原样去打 API 下发的
     * `fileServer`。而那个值常常是 `storage1.picacomic.com` 这类国内被 DNS 污染的
     * 节点——连接根本建不起来，请求要**等满 `SLOW_MAIN_THRESHOLD_MS`（900ms）**
     * 才被判为慢、才开始竞速。用户侧的感受就是"第一次打开特别慢"，
     * 而这段白等完全可以避免。
     *
     * 这里直接给出 [ImageHosts.DEFAULT_HOST]：它是实测容错面最宽、无需跨域跳转、
     * 且被 [com.shizq.bika.core.network.plugin.DirectDns] 的后缀规则覆盖
     * （不会走被污染的系统 DNS）的源。第一张图直接打它，等于把这段白等整段省掉。
     *
     * ## 返回 null 的两种情形
     *
     * - **还有有效的选路结论**——交给正常选路，这里不越权覆盖已经学过的东西；
     * - **兜底源打头阵失败过、且还在冷却期内**——此时再打它只会让每张图都多失败
     *   一次。冷却期结束后重新允许尝试。
     *
     * 判据用的是 [preferredHost] 而不是"本进程是否 remember 过"：
     * 结论**过期**（超过 `PREFERRED_HOST_TTL_MS`）与"从未有过结论"是同一件事
     * ——都等于当前没有可用的选路结果。若把过期排除在外，每过一个保鲜期，
     * 下一次打开又会退回"先等满 900ms 再竞速"的老路，等于把首图慢的问题
     * 每十分钟复现一次。
     *
     * 过期后由谁重新选路：[ImageConnectionWarmup.warmUp] 在每次进入阅读器时跑，
     * 只要没有有效结论就会在后台补一轮竞速并把胜者 [remember] 回来。
     *
     * 注意：它只影响"从哪个源起步"，不改变换源/竞速逻辑——一旦失败，
     * [PreferredHostInterceptor] 会 [forget] 并回落到原始 URL，后面的降级链照常工作。
     */
    fun startupHostOrNull(nowMs: Long = System.currentTimeMillis()): String? {
        if (preferredHost(nowMs) != null) return null
        val failedAt = fallbackFailedAtMs
        if (failedAt != null && nowMs - failedAt <= STARTUP_FALLBACK_COOLDOWN_MS) return null
        return ImageHosts.DEFAULT_HOST
    }

    /** 竞速/串行尝试胜出时调用。 */
    fun remember(host: String, nowMs: Long = System.currentTimeMillis()) {
        preferred = host
        decidedAtEpochMs = nowMs
    }

    /** 该源失败时调用；只清掉与传入 host 相符的记录，避免误清新学到的源。 */
    fun forget(host: String) {
        if (preferred == host) {
            preferred = null
            decidedAtEpochMs = 0L
        }
        // 兜底源失败要单独记账：它可能还没被 remember 过（走的是
        // startupHostOrNull 直接打头阵的路径），此时上面的 `preferred == host`
        // 不成立，但同样必须进入冷却，否则每张图都会先陪它失败一次。
        if (host == ImageHosts.DEFAULT_HOST) {
            fallbackFailedAtMs = System.currentTimeMillis()
        }
    }
}
