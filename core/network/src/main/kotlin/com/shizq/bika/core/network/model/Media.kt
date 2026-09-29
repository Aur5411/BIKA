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
        get() {
            val server = fileServer.trim()
            val p = path.trim()
            if (server.isEmpty() || p.isEmpty()) return null
            if (!server.startsWith("http://") && !server.startsWith("https://")) return null
            return "${server.trimEnd('/')}/static/${p.trimStart('/')}"
        }
}