package com.shizq.bika.core.network.image

import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图片链路的全局节流闸门。
 *
 * ## 为什么要它
 *
 * 服务端（含 CDN）会对"短时间内的图片请求"做配额限制，超限返回 **429**。
 * 429 属于 4xx，而降级拦截器把 4xx 一律当成"这个 path 上资源不存在，换域名也不会有
 * 不同结果"的永久性失败——于是限流一旦触发，表现为：
 *
 * - 每一张图都直接判死，既不换源，也不退避重试；
 * - 但阅读器的预载并不会停，它继续以满并发往下拽图，把配额烧得更干净，
 *   服务端把我们判为机器人的时间窗也跟着被拉长。
 *
 * 最终用户在手机上看到的就是"某一段之后，图再也出不来"。
 *
 * 这里做的事情很朴素：给整条图片链路装一个统一闸门——
 *
 * 1. **并发闸门**：全局在飞请求数有上限，不会因为"预载 12 并发 × 竞速 8 个域名"
 *    放大成上百条同时出去的请求；
 * 2. **退避闸门**：吃到 429/503 就进入冷却，指数加长；冷却期内可见请求排队等待，
 *    **预载直接放弃**（预载是锦上添花，此时它的边际收益为负）；
 * 3. **自愈**：一次成功就把连续失败计数清零，冷却结束自动恢复满额，不需要重启 App。
 *
 * ## 为什么放在 Coil 拦截器层而不是 OkHttp 层
 *
 * 预载与可见页在 OkHttp 看来毫无区别（同一个 URL、同一批头），只有在 Coil 的
 * `ImageRequest` 层才能靠 `BlackholeDecoder` 把两者区分开——区别对待是这套闸门
 * 的关键，所以闸门必须挂在这一层。
 */
@Singleton
class ImageRateGovernor @Inject constructor() {

    private val inFlight = AtomicInteger(0)

    /** 冷却截止时刻（epoch ms）。在此之前新请求排队，预载放弃。 */
    @Volatile
    private var cooldownUntilMs = 0L

    /** 连续被限流的次数，决定下一次冷却多长。 */
    @Volatile
    private var throttleStreak = 0

    /** 是否处于降级期（冷却未结束）。 */
    fun isDegraded(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < cooldownUntilMs

    /** 距冷却结束还剩多久；已结束返回 0。 */
    fun remainingCooldownMs(nowMs: Long = System.currentTimeMillis()): Long =
        (cooldownUntilMs - nowMs).coerceAtLeast(0L)

    /**
     * 申请一个执行名额。
     *
     * 语义约定：**这个方法只会返回 true**。它宁可排队等待，也不把一个请求丢掉。
     * 之所以取消"返回 false 让调用方放弃"这条路：一旦允许放弃，就必然有人（预载）
     * 被静默牺牲，而用户看到的现象是"图就是出不来、点重试也没用"——排查成本极高。
     * 现在统一成"排队等"：预载等得久一点没关系（它不在关键路径上），
     * 但它**一定会跑完**，磁盘缓存也就一定能被填上。
     *
     * @param prefetch true 表示这是预载请求（磁盘预热，用户此刻看不见）。
     *                 预载在冷却期只等一个较短的上限，超时就真的放弃这一次——
     *                 这是唯一允许放弃的场景，且放弃的是"这一轮"而非"这张图"，
     *                 它仍会被上层队列重新排进来。
     */
    suspend fun acquire(prefetch: Boolean): Boolean {
        var prefetchWaitedMs = 0L
        while (true) {
            val nowMs = System.currentTimeMillis()
            val waitMs = remainingCooldownMs(nowMs)
            if (waitMs > 0) {
                // 预载：冷却期允许等一小会儿（说明窗口快过去了），久等则让位给可见页。
                if (prefetch) {
                    if (prefetchWaitedMs >= PREFETCH_MAX_COOLDOWN_WAIT_MS) return false
                    val step = waitMs.coerceAtMost(PREFETCH_MAX_COOLDOWN_WAIT_MS - prefetchWaitedMs)
                    prefetchWaitedMs += step
                    delay(step)
                    continue
                }
                // 可见页：死等到底，绝不放弃（这就是"点了重试必须有用"的保证）。
                delay(waitMs.coerceAtMost(MAX_COOLDOWN_MS))
                continue
            }

            val limit = if (isDegraded(nowMs)) MAX_IN_FLIGHT_DEGRADED else MAX_IN_FLIGHT
            // 自增后再比较：并发抢占下最多有超标者自己退回来，不会有人在 ForEach 里被漏掉
            val claimed = inFlight.incrementAndGet()
            if (claimed <= limit) return true
            inFlight.decrementAndGet()
            delay(SLOT_POLL_MS)
        }
    }

    /** 归还名额，必须与 [acquire] 成对调用（调用方用 try/finally 保证）。 */
    fun release() {
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** 一次成功：连续失败计数清零，让链路尽快恢复到满额。 */
    fun recordSuccess() {
        throttleStreak = 0
    }

    /**
     * 记一次被服务端限流。指数退避：800ms → 1.6s → 3.2s … 上限 12s。
     *
     * 上限压到 12s 而不是 30s：原值配合"预载直接判死"会形成死亡螺旋——
     * 一张图触发限流，后面十几张图在 30s 内全部变成失败态，用户点重试又撞进同一窗口。
     * 12s 是实测能在"让出配额"与"用户愿意等"之间取到平衡的值。
     */
    fun noteThrottled(code: Int) {
        if (code != HTTP_TOO_MANY_REQUESTS && code != HTTP_SERVICE_UNAVAILABLE) return
        val nowMs = System.currentTimeMillis()
        throttleStreak = (throttleStreak + 1).coerceAtMost(16)
        lastThrottleAtMs = nowMs
        val factor = 1L shl (throttleStreak - 1).coerceAtMost(4)
        val waitMs = (BASE_COOLDOWN_MS * factor).coerceAtMost(MAX_COOLDOWN_MS)
        // 取 maxOf：并发的多个请求会同时上报，谁算出的窗口长就听谁的，
        // 避免后来者用较短的窗口覆盖掉同伴刚争取来的等待时间。
        cooldownUntilMs = maxOf(cooldownUntilMs, nowMs + waitMs)
    }

    /**
     * 上一次被限流的时刻（epoch ms），从未发生返回 0。
     * 供换源逻辑判断"这次失败是不是刚被限流造成的"——是的话换域名也没用（配额按 IP 计）。
     */
    @Volatile
    var lastThrottleAtMs = 0L
        private set

    private companion object {
        /**
         * 常规在飞上限。
         *
         * 从 16 提到 48：实测一个阅读页可见 5~8 张图，加上预载窗口 12、降级竞速 12，
         * 16 的名额会在页面刚打开的一瞬间被全部占满，后面的图只能以 25ms 的粒度空转轮询，
         * 体感就是"前面几张很快，后面越来越慢"。48 能让首屏的图一次全出去。
         */
        const val MAX_IN_FLIGHT = 48

        /** 降级期在飞上限。从 3 提到 12：3 太保守，冷却期的可见页会被彻底饿死。 */
        const val MAX_IN_FLIGHT_DEGRADED = 12

        const val BASE_COOLDOWN_MS = 800L
        const val MAX_COOLDOWN_MS = 12_000L

        /** 预载在冷却期最多愿意等的时长，超过就放弃这一轮（下一轮队列还会再排它）。 */
        const val PREFETCH_MAX_COOLDOWN_WAIT_MS = 3_000L

        /** 名额争不到时的轮询间隔，兼顾响应性与空转开销。 */
        const val SLOT_POLL_MS = 25L

        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
