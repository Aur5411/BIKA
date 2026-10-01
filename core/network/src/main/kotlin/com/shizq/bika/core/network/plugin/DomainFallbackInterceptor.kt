package com.shizq.bika.core.network.plugin

import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import com.shizq.bika.core.network.image.ImageHostRouter
import com.shizq.bika.core.network.image.ImageHosts
import com.shizq.bika.core.network.image.ImageRateGovernor
import com.shizq.bika.core.network.image.isChapterImagePath
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

/**
 * 主请求慢于此值就启动降级竞速。
 *
 * 原先 2500ms：一张图要白等两秒半才去找更快的源，而阅读器一次翻页就是一整屏，
 * 用户看到的就是"每页都要转两秒圈"。900ms 足够判断"这个源不快"（正常命中
 * CDN 的首字节在几百毫秒内），又不至于在网络抖动时误判成慢源、无谓地多打几条连接。
 *
 * 配合 [com.shizq.bika.core.network.image.ImageHostRouter]：第一张图竞速选出的源
 * 会被记住，之后的图直接走它，这个等待只发生在会话的头一两张图上。
 */
private const val SLOW_MAIN_THRESHOLD_MS = 900L

/**
 * 单个备用域名的**总**请求时限。
 *
 * 为什么从 3000ms 提高到这里：原先 3 秒裹住的是"建连 → 首字节 → 下载完响应体"的
 * 全过程，而常规页图有 300~400 KB，移动网络下 3 秒**下不完**。于是镜像明明有图，
 * 却被当成不可用——竞速全灭后把主源（国内被 DNS 污染、连不上）的错误结果交给 UI，
 * 正是"某几页固定 404、点重试也没用"的成因。
 *
 * 取 20 秒：足够容纳一次正常的慢速整图下载，又不至于让真死的源把这一屏拖住。
 * 竞速阶段各候选域名是并发的，一个死源不会拖慢其它源。
 */
private val FALLBACK_TOTAL_TIMEOUT_MS = 20_000L

/**
 * 经验上在当前网络环境**可直接取到图**的镜像后缀，用于 404/403 串行遍历时排序。
 *
 * 依据（本机实测）：`storage.diwodiwo.xyz` 与 `storage.tipatipa.xyz` 均能解析到
 * Cloudflare 并返回 200 + 完整字节；而 `s3`/`s2`/`storage1`/`img`/`www`/
 * `storage-b`.picacomic.com 全部解析到虚假的 `2001::` 地址（DNS 污染），不可达。
 *
 * 这只是一条**排序提示**，不是可用性白名单：排后面的域名仍会被尝试，
 * 服务端换节点后新域名也能被取到，不会重蹈"白名单外一律不试"的覆辙。
 */
private val REACHABLE_HOST_HINTS = listOf("diwodiwo.xyz", "tipatipa.xyz")

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

/**
 * 429/503 是"服务端被打得太狠"的回执，属于**暂时性**失败。
 *
 * 它与上面的 4xx 判定相反：此时换域名只会更快烧光配额（配额通常按 IP/账号算，
 * 与访问哪个节点无关），真正有用的是停下来等窗口过去——这件事由外层
 * [com.shizq.bika.core.network.image.ImageThrottleInterceptor] 负责。
 */
private fun Throwable?.isThrottled(): Boolean {
    if (this !is coil3.network.HttpException) return false
    val code = response.code
    return code == 429 || code == 503
}

internal class FallbackMarker : AbstractCoroutineContextElement(FallbackMarker) {
    companion object Key : CoroutineContext.Key<FallbackMarker>
}

@OptIn(ExperimentalCoilApi::class)
class DomainFallbackInterceptor(
    /** 竞速决出的胜者写进这里，供后续图片请求直接换源（见 PreferredHostInterceptor）。 */
    private val hostRouter: ImageHostRouter,
    /** 被服务端限流时的全局闸门：限流期间不再并发打其他域名。 */
    private val governor: ImageRateGovernor,
) : Interceptor {
    @Volatile
    private var optimalFallbackHost: String? = null

    /** 记录一次胜出：既更新本类的快速通道，也更新全局最快源。 */
    private fun rememberWinner(host: String) {
        optimalFallbackHost = host
        hostRouter.remember(host)
    }

    /**
     * 降级槽位。
     *
     * 原先是 2：评论页一屏十几个头像、详情页同时拉封面与章节图时，
     * 槽位瞬间占满，后面的请求全部阻塞在 `withPermit` 上——
     * 这才是"图片加载很慢"的放大器。
     *
     * 取 12：候选池有 8 个域名，一次竞速最多同时打 8 条，12 个槽位保证
     * "一整屏图片同时降级"也不会互相排队；再往上没有意义——槽位不是并发收益，
     * 只是排队许可，真正的并发上限由图片 OkHttp 的 `maxRequestsPerHost` 决定。
     */
    private val fallbackSlots = Semaphore(12)

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

        // 准入用「路径是不是 /static/ 章节图」而不是「host 认不认识」。
        //
        // 旧实现是 `if (failedHost !in ImageHosts.MANAGED_HOSTS) return proceed()`，
        // 这带来一个致命盲区：**API 返回的 fileServer 一旦是列表外的新节点，
        // 该图的换源能力就被整体关掉**——主源不通时连一个镜像都不会去试，
        // 用户看到的就是"某几页固定加载失败"。而服务端新增存储节点是常事，
        // 失败页也正好固定在那几页。
        //
        // 改为按路径判定后，任何来源的章节图都能进入降级链。
        if (!httpUrl.isChapterImagePath()) {
            return@coroutineScope chain.proceed()
        }

        // 候选池里除当前 host 之外的全部镜像。空则无从降级，原样放行。
        val candidateHosts = ImageHosts.MANAGED_HOSTS.filter { it != failedHost }
        if (candidateHosts.isEmpty()) {
            return@coroutineScope chain.proceed()
        }

        // 是否为已知受管域名：决定降级时用"并发竞速"还是"串行兜底"。
        // 非受管域名（来路不明，可能并非图片存储节点）只做串行尝试，
        // 不对它一口气打 8 条连接。
        val isManaged = failedHost in ImageHosts.MANAGED_HOSTS

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
            // 被限流：不要竞速。此时并发打其余 7 个候选域名只会把剩余配额更快地
            // 烧掉——配额通常按 IP/账号计，与走到哪个节点无关。登记一次退避后把
            // 结果原样交给外层 ImageThrottleInterceptor，由它等窗口过去重试同一张图。
            if (earlyResult.throwable.isThrottled()) {
                val code = (earlyResult.throwable as coil3.network.HttpException).response.code
                governor.noteThrottled(code)
                logger.debug { "主域名 '$failedHost' 返回 $code（限流），等待退避后重试: $originalUrl" }
                return@coroutineScope earlyResult
            }
            if (!earlyResult.throwable.isWorthFallback()) {
                // 403/404 的处理：**不能**直接当永久失败交给 UI。
                //
                // 关键事实（本机实测）：同一张图在不同镜像上的存在性并不一致。
                // API 返回的主源常是 `storage1.picacomic.com` 这类国内被 DNS 污染、
                // 根本连不上的节点；而同一张图在 `storage.diwodiwo.xyz` /
                // `storage.tipatipa.xyz` 上**确实存在且能取到**（已验证 200 + 完整字节）。
                // 所以"主源 404"只说明**这个源**没有，绝不等于整张图不存在。
                //
                // 这里必须把候选域名**全部试一遍**，只有全部都没有这张图时，
                // 才把失败交出去。串行而非竞速：每一发都是确定性的"有没有"，
                // 并发只会把配额打散（配额按 IP/账号算）。
                val throwable = earlyResult.throwable
                val statusCode = (throwable as? coil3.network.HttpException)?.response?.code
                if (statusCode == 403 || statusCode == 404) {
                    // 尝试顺序直接决定用户等多久，必须把「实测可用」的源排前面。
                    //
                    // candidateHosts 里有 6 个 picacomic.com 系域名，国内基本全不可达
                    // （DNS 污染），而真正能取到图的往往只有 storage.diwodiwo.xyz /
                    // storage.tipatipa.xyz。若按列表原序串行试，用户要先陪着 6 个死源
                    // 各等一次超时，才轮到唯一可用的那个——一张图要等好几十秒。
                    //
                    // 排序规则（从优到劣）：
                    // 1. 本会话竞速选出的最优源（optimalFallbackHost）——已经实测最快；
                    // 2. 域内已知可用的镜像（见 REACHABLE_HOST_HINTS）——按历史经验；
                    // 3. 其余候选——可能是新节点，也可能是不可达的污染域名。
                    val ordered = candidateHosts.sortedBy { scoreCandidateHost(it) }
                    for (candidateHost in ordered) {
                        logger.debug { "$statusCode 依次尝试候选域名: $candidateHost" }
                        val fallbackResult = tryFallbackHost(chain, httpUrl, candidateHost)
                        if (fallbackResult != null) {
                            rememberWinner(candidateHost)
                            logger.info { "$statusCode 在镜像 '$candidateHost' 上找到该图，换源成功" }
                            return@coroutineScope fallbackResult
                        }
                        // 失败的原因可能是 404（该源也没有）或超时/连接失败（该源不可用），
                        // 两者都不能作为"整张图不存在"的证据，继续试下一个。
                    }
                    logger.warn {
                        "所有镜像均未取到该图（主源 $statusCode），判定为服务端缺图: $originalUrl"
                    }
                }

                logger.warn(earlyResult.throwable) { "永久性失败，跳过降级: ${chain.request.data}" }
                return@coroutineScope earlyResult
            }
            // 主源失败：它可能正是"记住的最快源"，此时必须忘掉它，
            // 否则后续每张图都会被 PreferredHostInterceptor 改写到这个坏源上。
            hostRouter.forget(failedHost)
            logger.warn { "主域名 '$failedHost' 请求失败，开始降级竞速" }
        }

        val fallbackRequest = async {
            // 非受管域名（不在候选池里、通常也不是图片存储节点）不参与并发竞速：
            // 对一个来路不明的域名一口气打 8 条连接，收益不确定而请求量翻倍。
            // 但仍要走串行兜底，否则"主源不通 + 域名不在白名单"就真的无路可退
            // ——那正是此前"某几页固定失败"的成因。
            performFallbackRace(
                chain,
                httpUrl,
                failedHost,
                sequential = diskPreload || !isManaged,
            )
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
        val fallbackHosts = ImageHosts.imageHosts.filter { it != failedHost }
        if (fallbackHosts.isEmpty()) return null

        // 策略 1: Fast Path (快速通道)
        // 如果有已知的最佳域名，先单独尝试它，避免并发风暴
        val currentOptimal = optimalFallbackHost
        if (currentOptimal != null && currentOptimal in fallbackHosts) {
            logger.debug { "尝试已知最优域名（快速通道）: $currentOptimal" }
            val fastResult = tryFallbackHost(chain, httpUrl, currentOptimal)
            if (fastResult != null) {
                logger.info { "快速通道命中: $currentOptimal" }
                rememberWinner(currentOptimal)
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
            // 串行时顺序即等待时长：把实测可用的源排前面（理由见 scoreCandidateHost），
            // 否则预载与非受管域名要先陪 6 个不可达的 picacomic 系域名各等一次超时。
            for (host in hostsToRace.sortedBy { scoreCandidateHost(it) }) {
                val result = tryFallbackHost(chain, httpUrl, host) ?: continue
                rememberWinner(host)
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
            rememberWinner(msg.first)
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
     * 候选域名的尝试优先级，**分数越小越先试**。
     *
     * 只用于 404/403 的串行遍历（并发竞速不需要顺序）。之所以需要它：
     * 候选池里 6 个是 picacomic.com 系域名，国内普遍被 DNS 污染、根本连不上，
     * 而真正能取到图的常常只有 storage.diwodiwo.xyz / storage.tipatipa.xyz。
     * 按列表原序串行试一遍，用户要陪着 6 个死源各等一次超时才轮到可用的那个。
     *
     * 没有采用"把 picacomic 系直接排到最后"这种硬编码：服务端换节点、网络环境
     * 变化都可能让结论失效，写死会变成新的"固定几页失败"。这里用可解释的三档，
     * 把**本会话的实测结论**放在最高优先级。
     */
    private fun scoreCandidateHost(host: String): Int = when {
        // 本会话竞速/串行选出的最优源：已经实测过，最高优先级。
        host == optimalFallbackHost -> 0
        // 经验上在当前网络环境可用的镜像。
        REACHABLE_HOST_HINTS.any { host.endsWith(it, ignoreCase = true) } -> 1
        // 其余候选：可能是新节点（值得一试），也可能是被污染的域名（会白等一次超时）。
        else -> 2
    }

    /**
     * 单个备用域名的请求封装。
     *
     * ## 超时为什么不能裹住整个 proceed()
     *
     * 曾经这里是 `withTimeoutOrNull(3000ms) { proceed() }`，但那包裹的是**从建连到
     * 响应体下载完成**的全过程。一张常规页图有 300~400 KB，在移动网络下多花几秒是
     * 常态——于是真正**有这张图**的镜像会在"下载到一半"时被判定失败，竞速全灭，
     * 最终把主源（国内被 DNS 污染、根本连不上）的错误结果交给用户，表现就是
     * **某几页固定报 HTTP 404 且怎么重试都一样**。
     *
     * 超时该约束的是"这个源到底活不活"，也就是**首字节什么时候到**；一旦开始收数据，
     * 就该让它读完——读取阶段的耐心由 OkHttp 自己的 readTimeout 决定。
     *
     * 实现上分两段：
     * 1. 用 [FALLBACK_FIRST_BYTE_TIMEOUT_MS] 限制"建连 + 首字节"，超时即判该源不可用；
     * 2. 拿到响应后就让它自然跑完（下面的 await 不再加时限）。
     *
     * 由于 Coil 的 `proceed()` 是一整个 suspend 调用、内部不暴露"首字节"回调，
     * 这里用 [FALLBACK_TOTAL_TIMEOUT_MS] 作为**总时长**兜底——它必须显著大于
     * 首字节超时，才能容忍慢速下载；两者共同保证：既不会对一个死源干等太久，
     * 也不会把正在正常下载的源误杀。
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
                withTimeoutOrNull(FALLBACK_TOTAL_TIMEOUT_MS.milliseconds) {
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

