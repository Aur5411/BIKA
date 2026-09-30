package com.shizq.bika.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class Media(
    val originalName: String = "",
    val path: String = "",
    val fileServer: String = "",
) {
    val originalImageUrl: String
        get() = "${fileServer}/static/${path}"

    /**
     * 与 [originalImageUrl] 相同，但当字段缺失/畸形时返回 null 而不是拼出一个
     * 必然下载失败的字符串。
     *
     * 为什么需要：`fileServer` 与 `path` 都来自服务端响应，任一为空时
     * [originalImageUrl] 会产出 `"/static/xxx"` 或 `"https://host/static/"` 这类
     * 相对/残缺地址。这种 URL 会一路穿过整条图片链路而不被任何一层拦下——
     * `ImageThrottleInterceptor` 的 `startsWith("http")` 判假会直接放行，
     * 换源拦截器的 `toHttpUrlOrNull()` 返回 null 也会原样放行，最后在 OkHttp
     * 那里必然失败。表现就是**某几张固定的图无论怎么重试都出不来**。
     * 在数据入口处识别出来，才能把它当作"这一页确实没有可用的图"处理。
     */
    val safeImageUrl: String?
        get() = buildStaticUrl(fileServer, path)

    /**
     * 拼接 `{fileServer}/static/{path}`，并在若干已知会产出**必然 404** 的形态上
     * 做归一化/拒绝。
     *
     * ## 为什么需要归一化，而不只是判空
     *
     * 实测（本机直连图片源）确认了两件与 404 直接相关的事：
     *
     * 1. **`/static/static/...` 一定 404**。若服务端某个字段升级后 `path` 自带了
     *    `static/` 前缀（历史上服务端确实调整过 path 的形态），直接拼接会得到
     *    重复前缀，取到的就是一个不存在的地址——而它看起来完全正常，
     *    排查时极难发现。这里统一把 `path` 开头多余的 `static/` 剥掉。
     * 2. **两个斜杠一定 404**。`fileServer` 以 `/` 结尾或 `path` 以 `/` 开头都会
     *    拼出 `//`，部分 CDN 把 `//` 当成不同路径，直接 404。
     *
     * 这两条修好之后，剩下仍然 404 的，就是**服务端在那个 path 上确实没有这张图**——
     * 那时任何换源/重试都无效，应尽快停止重试并把地址暴露给用户（见 ComicItem）。
     */
    private fun buildStaticUrl(fileServer: String, path: String): String? {
        val server = fileServer.trim()
        if (server.isEmpty()) return null
        if (!server.startsWith("http://") && !server.startsWith("https://")) return null

        val cleaned = path.trim()
            .trimStart('/')
            // 服务端若已带 static/ 前缀，剥掉，避免 /static/static/ 这种必然 404 的地址
            .removePrefix("static/")
            .trimStart('/')

        if (cleaned.isEmpty()) return null
        return "${server.trimEnd('/')}/static/$cleaned"
    }
}