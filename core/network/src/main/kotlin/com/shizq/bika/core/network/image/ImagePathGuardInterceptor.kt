package com.shizq.bika.core.network.image

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val logger = KotlinLogging.logger("ImagePathGuard")

/**
 * 图片链路最外层：把 URL 里不可见的脏字符治掉；治完仍取不到图时，
 * 再退回「服务端原样路径」试一次。
 *
 * ## 它守的是什么
 *
 * 服务端 `path` 字段里会混入不可见字符（实测：目录段本应是 `sub_storage_1`，
 * 服务端给的却带一个 **U+0020 普通空格**，在屏幕上与正常空格无法区分）。
 * 这种 URL 一路畅通无阻——拼接不报错、编码不报错、请求发得出去，
 * 但 CDN 上那个目录根本不存在，于是确定性失败，UI 上被笼统报成"图片不存在"。
 *
 * ## 为什么"归一化"之外还要留一条回退路
 *
 * "把不可见字符换成下划线"是一个**基于实测的强假设**：我们验证过
 * `sub_storage%201` 在多个源、多个边缘节点上全部 403/404，而 `sub_storage_1`
 * 全部 200。但它终究是假设——谁也不能保证源站永远只有下划线那一种拼法。
 *
 * 所以这里不退化成"赌一种拼法"：先按归一化后的形态请求，**只有它返回
 * 403/404 时**，才用服务端原样给的路径再试一次，两条路都走完才判定失败。
 * 这样服务端日后无论改成哪种拼法，应用都能自己走出来，不必再发一版。
 *
 * 额外开销可控：正常情况（归一化后成功）**一次请求都不多**；只有已经失败的图
 * 才各多花一次，且外层 `DomainFallbackInterceptor` 对每次请求都有整体预算，
 * UI 侧重试另有退避，不会指数放大。
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
        // 没有脏字符可修（或不是 /static/ 章节图）→ 原样放行，不做任何多余动作。
        val sanitized = url.sanitizeStaticImagePath() ?: return chain.proceed()

        logger.warn {
            "图片路径含不可见字符，已归一化：" +
                "${url.encodedPath} -> ${sanitized.encodedPath}"
        }

        val sanitizedRequest = chain.request.newBuilder().data(sanitized.toString()).build()
        val result = chain.withRequest(sanitizedRequest).proceed()
        if (result !is ErrorResult) return result

        // 只有 403/404 才值得换拼法重来：其它错误（超时、5xx、限流）与路径无关，
        // 换路径不会让它们变好，只会白花一次请求。
        val code = (result.throwable as? HttpException)?.response?.code
        if (code != HTTP_FORBIDDEN && code != HTTP_NOT_FOUND) return result

        logger.warn {
            "归一化后仍失败（HTTP $code），改用服务端原样路径再试一次: ${url.encodedPath}"
        }
        // chain.proceed() 用的是原始 data —— 即服务端给的、未归一化的那条。
        return chain.proceed()
    }

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
    }
}
