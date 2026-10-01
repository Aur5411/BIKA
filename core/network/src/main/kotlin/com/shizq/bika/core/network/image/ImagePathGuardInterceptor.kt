package com.shizq.bika.core.network.image

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val logger = KotlinLogging.logger("ImagePathGuard")

/**
 * 图片链路最外层：把 URL 里不可见的脏字符治掉再放行。
 *
 * ## 它守的是什么
 *
 * 服务端 `path` 字段里会混入不可见字符（典型：目录段本应是 `sub_storage_1`，
 * 实际给的却带一个渲染上看不见的空白）。这种 URL 一路畅通无阻——拼接不报错、
 * 编码不报错、请求发得出去，但 CDN 上那个目录根本不存在，结果是**确定性 403**，
 * 在 UI 上被笼统地报成"图片不存在"。
 *
 * `Media.safeImageUrl` 已经在源头修过，但取图片地址的调用点不止一处
 * （封面/头像走的是保留原样的 `originalImageUrl`）。放在链首就等于不依赖
 * 调用方自觉：只要最终请求的是 `/static/` 图片，进网络前一定被治过。
 *
 * ## 位置
 *
 * 必须是链上**第一个**（`NetworkModule` 里最先 `add`）。放在节流之后的话，
 * 重试日志里打印的仍是脏 URL，排查时会把"地址本来就错"误判成"源不稳定"。
 *
 * ## 只改路径，不改域名
 *
 * 各镜像是同一份存储的入口，`/static/{path}` 在哪个域名下都指向同一张图；
 * 换域名是 [PreferredHostInterceptor] / `DomainFallbackInterceptor` 的职责。
 * 这里只负责让 path 本身是对的——**地址错了，换多少个源都没用**。
 */
internal class ImagePathGuardInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data as? String ?: return chain.proceed()
        if (!data.startsWith("http", ignoreCase = true)) return chain.proceed()

        val url = data.toHttpUrlOrNull() ?: return chain.proceed()
        val fixed = url.sanitizeStaticImagePath() ?: return chain.proceed()

        logger.warn {
            "图片路径含不可见字符，已归一化：" +
                "${url.encodedPath} -> ${fixed.encodedPath}"
        }

        return chain.withRequest(
            chain.request.newBuilder().data(fixed.toString()).build(),
        ).proceed()
    }
}
