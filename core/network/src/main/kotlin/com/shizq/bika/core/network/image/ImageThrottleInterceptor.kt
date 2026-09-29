package com.shizq.bika.core.network.image

import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

private val logger = KotlinLogging.logger("ImageThrottle")

/**
 * 图片链路的最外层闸门：过 [ImageRateGovernor] 的名额，并在失败可挽回时自己重试，
 * 而不是把一个"再等一秒就能成功"的结果直接丢给用户。
 *
 * ## 位置
 *
 * 挂在拦截器链最前面（NetworkModule 里最先 add），因此看到的是下游（换源 + 降级竞速）
 * 的最终结果——无论失败发生在哪个候选域名上，都能在这里统一收口重试。
 *
 * ## 为什么必须由它来重试
 *
 * Coil 自身没有重试机制：一次 [ErrorResult] 就是这个请求的结局，只能等 Compose 侧
 * 重启同一请求（滑出去再滑回来会重触一次，停在原地则不会）。而下面两类失败明确是
 * **暂时性**的，判死纯属浪费：
 *
 * - **429 / 503**：服务端在说"你太快了"。此时正确的动作是停下来等窗口过去，
 *   而不是立刻换域名重试（配额通常按 IP/账号计，与打到哪个节点无关），
 *   更不应该继续让预载猛冲。
 * - **超时 / 连接被重置 / 5xx**：链路或服务端的一次抖动，多半第二次就成了。
 *
 * ## 预载与可见页的区别对待
 *
 * 冷却期内：可见页排队等待（用户正在等这一页），预载直接放弃——此刻预载的边际收益
 * 是负的，它只会占用留给可见页的配额并拉长封禁窗口。判别靠 [BlackholeDecoder]
 * （预载刻意不解码，只写磁盘缓存）。
 */
@OptIn(ExperimentalCoilApi::class)
internal class ImageThrottleInterceptor(
    private val governor: ImageRateGovernor,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val isRemote = data is String && data.startsWith("http", ignoreCase = true)
        if (!isRemote) return chain.proceed()

        // 磁盘预热请求：用户此刻看不见它，限流期间优先牺牲它。
        val isPrefetch = chain.request.decoderFactory is BlackholeDecoder.Factory

        var attempt = 0
        var waitedTotalMs = 0L
        while (true) {
            if (!governor.acquire(isPrefetch)) {
                logger.debug { "限流中，放弃本次预载: $data" }
                return ErrorResult(null, chain.request, IOException("skipped while throttled"))
            }

            val result = try {
                chain.proceed()
            } finally {
                governor.release()
            }

            if (result is SuccessResult) {
                governor.recordSuccess()
                return result
            }

            val throwable = (result as? ErrorResult)?.throwable
            // 取消是 UI 主动放弃（滑走了、章节切了），不是失败，重试没有任何意义。
            if (throwable is CancellationException) return result

            val throttledCode = throttledCodeOf(throwable)
            if (throttledCode != null) {
                // 登记退避。并发的多个请求会各自上报，冷却时长由 Governor 取最大值，
                // 不会有人把同伴刚争取来的等待时间覆盖掉。
                governor.noteThrottled(throttledCode)
            } else if (isPrefetch || attempt >= MAX_ATTEMPTS - 1 || !isTransient(throwable)) {
                // 预载不重试：它失败只影响"下一页会不会更快"，不值得为它再多给付请求量。
                return result
            }

            attempt++
            val waitMs = if (throttledCode != null) {
                governor.remainingCooldownMs()
            } else {
                RETRY_BACKOFF_MS * attempt
            }
            // 抖动：同屏十几张图同时失败时，不加抖动会整齐地再撞一次同一堵墙。
            val pauseMs = waitMs + Random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS)
            // 单张图的总等待有上限：宁可让这一张先报失败、用户滑走再滑回来时重来，
            // 也不要让用户对着同一个加载圈干等半分钟以上。
            if (waitedTotalMs + pauseMs > MAX_WAIT_TOTAL_MS) {
                logger.warn { "等待累计 ${waitedTotalMs}ms 仍未能取到图，本张先放弃: $data" }
                return result
            }
            waitedTotalMs += pauseMs
            logger.debug { "图片请求失败（${throwable?.javaClass?.simpleName}），${pauseMs}ms 后第 $attempt 次重试: $data" }
            delay(pauseMs)
        }
    }

    private fun throttledCodeOf(throwable: Throwable?): Int? {
        val code = (throwable as? HttpException)?.response?.code ?: return null
        return code.takeIf { it == HTTP_TOO_MANY_REQUESTS || it == HTTP_SERVICE_UNAVAILABLE }
    }

    /** 传输层异常与 5xx 属于"再试一次大概率能成"；其余（403/404 等）重试没有收益。 */
    private fun isTransient(throwable: Throwable?): Boolean {
        if (throwable == null) return false
        if (throwable is HttpException) return throwable.response.code >= 500
        return throwable is IOException
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 400L
        /** 一张图愿意等待的上限；超过就先交还给 UI，滑走再滑回来即是一轮新的重试。 */
        const val MAX_WAIT_TOTAL_MS = 25_000L
        const val JITTER_MIN_MS = 60L
        const val JITTER_MAX_MS = 400L
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
