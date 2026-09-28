package com.shizq.bika.core.network.plugin

import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger("DomainFallbackCoil")

/** 主请求慢于此值就启动降级竞速。 */
private const val SLOW_MAIN_THRESHOLD_MS = 2500L

/**
 * 小图（头像、列表缩略图）的判定上限（较长边，单位 px）。
 *
 * 头像只有 40dp、几百字节，主域名正常时 1 秒内就能回来。给它和整页大图一样
 * 2.5 秒的竞速窗口，等于列表里十几个头像同时去抢降级槽位：竞速为一张小图
 * 并发打 6 个域名，成本远高于收益，而真正需要降级的大图反而排不上队。
 *
 * 所以小图**不参与竞速**，直接走主域名单路请求；失败交给上层退避重试。
 */
private const val SMALL_IMAGE_MAX_EDGE_PX = 160

/**
 * 4xx 是永久性失败：资源在这个 path 上就是不存在（404）或无权限（403），
 * 换域名重试同一个 path 不会有不同结果，只会放大成 N 倍无效请求。
 * 只有 5xx 与传输层异常（超时、DNS、连接失败）才值得换域名。
 */
private fun Throwable?.isWorthFallback(): Boolean {
    if (this !is coil3.network.HttpException) return true

    val code = response.code
    return code >= 500
}

internal class FallbackMarker : AbstractCoroutineContextElement(FallbackMarker) {
    companion object Key : CoroutineContext.Key<FallbackMarker>
}

@OptIn(ExperimentalCoilApi::class)
class DomainFallbackInterceptor : Interceptor {
    @Volatile
    private var optimalFallbackHost: String? = null

    /**
     * 降级槽位。
     *
     * 原先是 2：评论页一屏十几个头像、详情页同时拉封面与章节图时，
     * 槽位瞬间占满，后面的请求全部阻塞在 `withPermit` 上——
     * 这才是"图片加载很慢"的放大器。竞速本身最多打 6 个域名，
     * 给到 6 个槽位既够用、又不会形成无界并发。
     */
    private val fallbackSlots = Semaphore(6)

    /**
     * 判断是否小图。
     *
     * [coil3.intercept.Interceptor.Chain.size] 已经是解析后的目标尺寸
     * （Coil 在进入拦截器链前就解析完了 sizeResolver），所以这里不需要再 suspend
     * 取一次。任一维是 `Dimension.Undefined`（请求方没给约束）就按大图处理：
     * 宁可按旧行为竞速，也不要因为尺寸未知把小图误判成可以不竞速。
     */
    private fun isSmallImage(chain: Interceptor.Chain): Boolean {
        val size = chain.size
        val w = (size.width as? Dimension.Pixels)?.px ?: return false
        val h = (size.height as? Dimension.Pixels)?.px ?: return false
        if (w <= 0 && h <= 0) return false
        // 取较大边：只有两边都小才是小图，避免把 1×1000 这种细长图误判
        val maxEdge = maxOf(w, h)
        return maxEdge in 1..SMALL_IMAGE_MAX_EDGE_PX
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult = coroutineScope {
        if (coroutineContext[FallbackMarker] != null) {
            logger.debug { "跳过降级竞速（本身就是降级请求）: ${chain.request.data}" }
            return@coroutineScope chain.proceed()
        }

        if (chain.request.data !is String) {
            return@coroutineScope chain.proceed()
        }

        val originalUrl = chain.request.data as String
        val httpUrl = try {
            originalUrl.toHttpUrl()
        } catch (e: Exception) {
            return@coroutineScope chain.proceed()
        }
        val failedHost = httpUrl.host

        if (failedHost !in DomainConfig.MANAGED_HOSTS) {
            return@coroutineScope chain.proceed()
        }

        // 小图：只等主域名，不竞速。失败交由上层退避重试。
        val diskPreload = chain.request.decoderFactory is BlackholeDecoder.Factory
        if (!diskPreload && isSmallImage(chain)) {
            logger.debug { "小图走单域名直连，不参与竞速: $originalUrl" }
            return@coroutineScope chain.proceed()
        }

        // 主域名请求。异常在内部收成 ErrorResult，避免抛出去连带取消整个 coroutineScope。
        val mainRequest = async {
            try {
                chain.proceed()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ErrorResult(null, chain.request, e)
            }
        }

        // 等一小会儿：主请求可能很快成功，也可能很快失败。
        // Disk preloads must not multiply into several downloads just because the mobile
        // connection is slow. Only try mirrors after the primary actually fails.
        val earlyResult = if (diskPreload) {
            mainRequest.await()
        } else {
            withTimeoutOrNull(SLOW_MAIN_THRESHOLD_MS.milliseconds) { mainRequest.await() }
        }

        if (earlyResult is SuccessResult) {
            return@coroutineScope earlyResult
        }

        if (earlyResult is ErrorResult) {
            if (!earlyResult.throwable.isWorthFallback()) {
                // 404 特殊处理：依次（串行、非并发）尝试候选域名直到成功，
                // 优先已知最优域名，避免并发风暴的同时覆盖“真404但换个镜像能取到”的场景。
                val throwable = earlyResult.throwable
                if (throwable is coil3.network.HttpException && throwable.response.code == 404) {
                    val currentOptimal = optimalFallbackHost
                    val candidateHosts = DomainConfig.MANAGED_HOSTS
                        .filter { it != failedHost }
                        .sortedByDescending { it == currentOptimal }

                    for (candidateHost in candidateHosts) {
                        logger.debug { "404 依次尝试候选域名: $candidateHost" }
                        val fallbackResult = tryFallbackHost(chain, httpUrl, candidateHost)
                        if (fallbackResult != null) {
                            optimalFallbackHost = candidateHost
                            return@coroutineScope fallbackResult
                        }
                    }
                }

                logger.warn(earlyResult.throwable) { "永久性失败，跳过降级: ${chain.request.data}" }
                return@coroutineScope earlyResult
            }
            logger.warn { "主域名 '$failedHost' 请求失败，开始降级竞速" }
        }

        val fallbackRequest = async {
            performFallbackRace(chain, httpUrl, failedHost, sequential = diskPreload)
        }
        // Keep observing the primary while mirrors run. Previously a completed primary
        // waited for the entire mirror race (and sometimes another timeout) to finish.
        val result = awaitPrimaryOrFallback(mainRequest, fallbackRequest) { it is SuccessResult }
        if (result is ErrorResult) {
            logger.error(result.throwable) { "所有域名均尝试失败: $originalUrl" }
        }
        result
    }

    /**
     * 并发竞速降级策略核心，集成了已知最优域名快速通道（Fast Path）与并发竞速通道
     */
    private suspend fun performFallbackRace(
        chain: Interceptor.Chain,
        httpUrl: HttpUrl,
        failedHost: String,
        sequential: Boolean,
    ): ImageResult? {
        val fallbackHosts = DomainConfig.MANAGED_HOSTS.filter { it != failedHost }
        if (fallbackHosts.isEmpty()) return null

        // 策略 1: Fast Path (快速通道)
        // 如果有已知的最佳域名，先单独尝试它，避免并发风暴
        val currentOptimal = optimalFallbackHost
        if (currentOptimal != null && currentOptimal in fallbackHosts) {
            logger.debug { "尝试已知最优域名（快速通道）: $currentOptimal" }
            val fastResult = tryFallbackHost(chain, httpUrl, currentOptimal)
            if (fastResult != null) {
                logger.info { "快速通道命中: $currentOptimal" }
                return fastResult
            }
            // 失效即清空：否则这个域名一旦挂掉，后续每张图都要先白等它 3 秒超时。
            if (optimalFallbackHost == currentOptimal) {
                optimalFallbackHost = null
            }
            logger.debug { "快速通道失败，已清空最优域名记录，转入竞速" }
        }

        // 去除刚才已经试过的最佳域名，剩下的一起竞速
        val hostsToRace = fallbackHosts.filter { it != currentOptimal }
        if (hostsToRace.isEmpty()) return null

        if (sequential) {
            for (host in hostsToRace) {
                val result = tryFallbackHost(chain, httpUrl, host) ?: continue
                optimalFallbackHost = host
                return result
            }
            return null
        }

        // 策略 2: Race Path (剩余域名通道并发竞速)
        return raceWithChannel(chain, httpUrl, hostsToRace)
    }

    /**
     * 使用 Channel 实现优雅且高性能的竞速
     */
    private suspend fun raceWithChannel(
        chain: Interceptor.Chain,
        originalHttpUrl: HttpUrl,
        hostsToRace: List<String>
    ): SuccessResult? = coroutineScope {
        logger.info { "开始竞速剩余域名: $hostsToRace" }

        val resultChannel = Channel<Pair<String, SuccessResult>>(1)

        val jobs = hostsToRace.map { host ->
            launch {
                val result = tryFallbackHost(chain, originalHttpUrl, host)
                if (result != null) {
                    resultChannel.trySend(host to result)
                }
            }
        }

        launch {
            jobs.joinAll()
            resultChannel.close()
        }

        var winnerResult: SuccessResult? = null

        for (msg in resultChannel) {
            optimalFallbackHost = msg.first
            winnerResult = msg.second
            logger.info { "竞速获胜域名: '${msg.first}'" }
            break
        }

        jobs.forEach { it.cancel() }

        if (winnerResult == null) {
            logger.error { "所有降级域名均竞速失败: $originalHttpUrl" }
        }

        winnerResult
    }

    /**
     * 单个备用域名的请求封装（包含独立的超时控制）
     */
    private suspend fun tryFallbackHost(
        chain: Interceptor.Chain,
        originalUrl: HttpUrl,
        newHost: String
    ): SuccessResult? {
        val newUrl = originalUrl.newBuilder().host(newHost).build().toString()
        val newRequest = chain.request.newBuilder().data(newUrl).build()

        return try {
            fallbackSlots.withPermit {
                withTimeoutOrNull(3000L.milliseconds) {
                    withContext(FallbackMarker()) {
                        val result = chain.withRequest(newRequest).proceed()
                        result as? SuccessResult
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }
}

private object DomainConfig {
    val imageDomains = listOf(
        "https://s3.picacomic.com",
        "https://s2.picacomic.com",
        "https://storage.diwodiwo.xyz",
        "https://storage1.picacomic.com",
        "https://storage.tipatipa.xyz",
        "https://www.picacomic.com",
        "https://storage-b.picacomic.com",
    )

    val MANAGED_HOSTS: Set<String> by lazy(LazyThreadSafetyMode.NONE) {
        imageDomains.map { it.toHttpUrl().host }.toSet()
    }
}
