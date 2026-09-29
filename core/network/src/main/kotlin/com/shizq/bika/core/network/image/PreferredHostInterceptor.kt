package com.shizq.bika.core.network.image

import coil3.intercept.Interceptor
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
            chain.withRequest(rewritten).proceed()
        } catch (e: Exception) {
            // 最快源失效了：清掉记录，本张图立刻回退到原域名，
            // 由后面的降级拦截器决定要不要重新竞速。
            router.forget(preferred)
            logger.warn(e) { "最快源 $preferred 失效，回退 ${originalUrl.host}" }
            chain.proceed()
        }
    }
}
