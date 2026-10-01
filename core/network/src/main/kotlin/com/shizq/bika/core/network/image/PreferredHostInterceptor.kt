package com.shizq.bika.core.network.image

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val logger = KotlinLogging.logger("PreferredHost")

/**
 * 是不是章节页图（`/static/...`）。
 *
 * 这是 [PreferredHostInterceptor] 区分"该换源的图"与"不该碰的图"的判据。
 * 用路径而不是域名白名单，理由见 intercept 内的说明。
 */
internal fun HttpUrl.isChapterImagePath(): Boolean =
    encodedPath.startsWith("/static/")

/**
 * 把图片请求改写到已知最快的源。
 *
 * 挂在 [com.shizq.bika.core.network.plugin.DomainFallbackInterceptor] **之前**：
 * 先换成最快源，再走"主源失败才竞速"的降级逻辑。两张图之间学到的选路结论
 * 由 [ImageHostRouter] 保存，这里只负责应用。
 *
 * 只改 host，path 与 query 原样保留——各镜像是同一份存储的入口，
 * `/static/{path}` 在哪个域名下都指向同一张图。
 */
internal class PreferredHostInterceptor(
    private val router: ImageHostRouter,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val originalUrl = (chain.request.data as? String)?.toHttpUrlOrNull()
            ?: return chain.proceed()

        // 用「路径长什么样」而不是「host 认不认识」做准入。
        //
        // 旧实现是 `if (host !in MANAGED_HOSTS) return proceed()`，于是 API 返回
        // 候选池之外的新 fileServer 时，这张图既不会被改写到已知可用源、也不会
        // 进入降级链——彻底失去换源能力。而服务端新增存储节点恰恰是常事，
        // 表现就是固定几页图永远加载不出来。
        //
        // 改用路径判定后，既保住了"头像 CDN / 本地文件 / base64 不碰"的原意
        // （它们的路径名不是 /static/），又让任何来源的章节图都能被换源。
        // 改动只换 host、path 与 query 原样保留——各镜像是同一份存储的入口，
        // /static/{path} 在哪个域名下都指向同一张图。
        if (!originalUrl.isChapterImagePath()) return chain.proceed()

        // 优先用竞速选出的最快源；**本进程还没有结论时**退回兜底源
        // （见 ImageHostRouter.startupHostOrNull）。
        //
        // 这一条专门针对"第一次打开特别慢"：冷启动首图的 fileServer 常常是
        // 连不上的污染节点，原样打过去要等满 SLOW_MAIN_THRESHOLD_MS（900ms）
        // 才会触发竞速。直接改成打已知可用的兜底源，这 900ms 就省掉了。
        val preferred = router.preferredHost()
            ?: router.startupHostOrNull()
            ?: return chain.proceed()
        if (preferred == originalUrl.host) return chain.proceed()
        // 只会是候选池里的域名（只有竞速/串行胜者才会 remember），
        // 这里再挡一道防御性检查，避免将来有别的写入路径把非法值塞进来。
        if (preferred !in ImageHosts.MANAGED_HOSTS) return chain.proceed()

        val rewritten = chain.request.newBuilder()
            .data(originalUrl.newBuilder().host(preferred).build().toString())
            .build()

        return try {
            logger.debug { "改用最快源: ${originalUrl.host} -> $preferred" }
            val result = chain.withRequest(rewritten).proceed()
            if (result is ErrorResult) {
                // Coil 将 HTTP 失败作为 ErrorResult 返回，不会抛异常。
                // 之前这里只 catch Exception，导致坏源返回 403/404 后仍被记为最快源，
                // 后续每页和手动重试都会继续命中同一个坏源。
                router.forget(preferred)
                val status = (result.throwable as? HttpException)?.response?.code
                logger.warn { "最快源 $preferred 返回失败${status?.let { " HTTP $it" } ?: ""}，清除选路记录" }
                // 不能把这个失败直接交给 UI：本次请求还没尝试原始 fileServer
                // 和其它镜像。沿原始 URL 再走一次后置降级链，确保点击重试前就有
                // 机会换到可用源。
                return chain.proceed()
            }
            result
        } catch (e: Exception) {
            // 网络异常同样清掉记录；DomainFallbackInterceptor 会接管换源竞速。
            router.forget(preferred)
            logger.warn(e) { "最快源 $preferred 失效，清除选路记录" }
            chain.proceed()
        }
    }
}
