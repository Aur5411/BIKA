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
     * @param prefetch true 表示这是预载请求（磁盘预热，用户此刻看不见）。
     * @return true 表示拿到名额；false 表示"限流中，这次预载应当放弃"。
     */
    suspend fun acquire(prefetch: Boolean): Boolean {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val waitMs = remainingCooldownMs(nowMs)
            if (waitMs > 0) {
                // 预载的目的是"让后面的页更快"，在已经被限速的时候继续排队，
                // 只会挤占可见页那点可怜的配额，并延长服务端把我们判为机器人的时间窗。
                if (prefetch) return false
                // 可见页：等到窗口过去。延迟在这里发生时 onTimeout 之外的重试上层负责。
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
     * 记一次被服务端限流。指数退避：1.2s → 2.4s → 4.8s … 上限 30s。
     *
     * 取指数而不是固定值：固定间隔在"服务端窗口比预期长"时会稳定地每轮都撞墙，
     * 既拖慢自己也招来更长封禁；指数退避能在几次试探后自然对齐真实窗口。
     */
    fun noteThrottled(code: Int) {
        if (code != HTTP_TOO_MANY_REQUESTS && code != HTTP_SERVICE_UNAVAILABLE) return
        val nowMs = System.currentTimeMillis()
        throttleStreak = (throttleStreak + 1).coerceAtMost(16)
        val factor = 1L shl (throttleStreak - 1).coerceAtMost(5)
        val waitMs = (BASE_COOLDOWN_MS * factor).coerceAtMost(MAX_COOLDOWN_MS)
        // 取 maxOf：并发的多个请求会同时上报，谁算出的窗口长就听谁的，
        // 避免后来者用较短的窗口覆盖掉同伴刚争取来的等待时间。
        cooldownUntilMs = maxOf(cooldownUntilMs, nowMs + waitMs)
    }

    private companion object {
        /** 常规在飞上限：够"预载窗口 + 可见页 + 封面列表"同时存在，又不至于打成一窝蜂。 */
        const val MAX_IN_FLIGHT = 16

        /** 降级期在飞上限：留给用户此刻真正要看的那一张，其余排队。 */
        const val MAX_IN_FLIGHT_DEGRADED = 3

        const val BASE_COOLDOWN_MS = 1_200L
        const val MAX_COOLDOWN_MS = 30_000L

        /** 名额争不到时的轮询间隔，兼顾响应性与空转开销。 */
        const val SLOT_POLL_MS = 25L

        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
