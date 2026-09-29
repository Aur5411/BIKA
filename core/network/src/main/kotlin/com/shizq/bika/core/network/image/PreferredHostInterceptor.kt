package com.shizq.bika.core.network.image

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val logger = KotlinLogging.logger("PreferredHost")

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

        // 非受管域名（头像 CDN、本地文件、用户头像 base64 等）不碰
        if (originalUrl.host !in ImageHosts.MANAGED_HOSTS) return chain.proceed()

        val preferred = router.preferredHost() ?: return chain.proceed()
        if (preferred == originalUrl.host) return chain.proceed()

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
