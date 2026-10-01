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
     * 与 [safeImageUrl] **相同的归一化**，但永不返回 null。
     *
     * 供下载链路使用。下载的语义是"尽力去抓"：即使字段缺失也要保留一个值，
     * 这样失败时能报出服务端真实给的地址，而不是静默丢弃这一项——
     * 静默丢弃会让分页循环把"这一页全畸形"误判成"没有更多页"而提前终止，
     * 反而丢掉后面本来正常的页。
     *
     * （原先下载用的是 [originalImageUrl]，完全不做归一化，
     * 于是同一批含空白路径的图在阅读时能修好、在离线下载时却必然失败。）
     */
    val normalizedImageUrl: String
        get() = buildStaticUrl(fileServer, path) ?: originalImageUrl

    /**
     * 拼接 `{fileServer}/static/{path}`，并在若干已知会产出**必然 404** 的形态上
     * 做归一化/拒绝。
     *
     * ## 为什么需要归一化，而不只是判空
     *
     * 实测（本机直连图片源）确认了三件与 404 直接相关的事：
     *
     * 1. **`/static/static/...` 一定 404**。若服务端某个字段升级后 `path` 自带了
     *    `static/` 前缀（历史上服务端确实调整过 path 的形态），直接拼接会得到
     *    重复前缀，取到的就是一个不存在的地址——而它看起来完全正常，
     *    排查时极难发现。这里统一把 `path` 开头多余的 `static/` 剥掉。
     * 2. **两个斜杠一定 404**。`fileServer` 以 `/` 结尾或 `path` 以 `/` 开头都会
     *    拼出 `//`，部分 CDN 把 `//` 当成不同路径，直接 404。
     * 3. **路径段里的空格一定 403/404**，真实字符是下划线。见下方专节。
     *
     * 这三条修好之后，剩下仍然 404 的，就是**服务端在那个 path 上确实没有这张图**——
     * 那时任何换源/重试都无效，应尽快停止重试并把地址暴露给用户（见 ComicItem）。
     *
     * ## 空格 → 下划线：一个"看起来最正常"的死链
     *
     * 服务端返回的 `path` 里会出现形如 `sub_storage 1/7f/8b/<uuid>.jpg` 的值——
     * 目录段 `sub_storage 1` 中间是一个**真正的空格**。这个值一路原样穿过整条图片
     * 链路：拼接时不报错，`HttpUrl` 解析时把空格编码成 `%20`，请求正常发出，
     * **没有任何一层会认为它有问题**。
     *
     * 但它在 CDN 上必然取不到图。实测（`storage-b`，4 个边缘 IP 交叉验证，
     * 结论完全一致）：
     *
     * | 路径段 | 状态 | 字节数 |
     * |---|---|---|
     * | `sub_storage%201`（空格） | 403 | 178 |
     * | `sub_storage_1`（下划线） | **200** | **128093** |
     *
     * 403 是 nginx 自带的 `403 Forbidden` 页（`nginx/1.10.0 (Ubuntu)`），
     * 说明**该目录在源站根本不存在**——不是权限问题，是路径写错了。真实字符是
     * 下划线，空格是数据里的脏值。
     *
     * 为什么必须在这一层修：这是唯一知道"这是路径、不是用户输入"的地方。
     * 且在 OkHttp 之前把 `%20` 还原掉，才能让选源/重试/缓存各层看到同一个
     * 正确 URL（`diskCacheKey` 也才稳定）。
     *
     * 替换规则：**只处理路径分隔符 `/` 之间的空格**，不动连续空格以外的内容，
     * 也不引入任何 URL 保留字符。下划线在 URL 路径中是安全字符，无需再编码。
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
            // 路径段内的空白是脏数据，真实字符为下划线（见上方专节）。
            // 放行的话 OkHttp 会把它编码成 %20 / %C2%A0 发出去，CDN 侧确定性 403，
            // 且因为 URL"看起来完全正常"，排查时会误判成"图不存在"。
            .normalizePathBlanks()

        if (cleaned.isEmpty()) return null
        return "${server.trimEnd('/')}/static/$cleaned"
    }
}

/**
 * 把路径里"看起来是空格"的字符统一还原为下划线。
 *
 * ## 为什么逐一列出而不用 `Char.isWhitespace()`
 *
 * `\u00A0`（不间断空格）在 Java 的 `Character.isWhitespace()` 里返回 **false**，
 * 但它渲染出来和普通空格**完全一样**——正是这类脏值最容易漏掉的一种。
 * 只写 `.replace(" ", "_")` 会在这上面栽跟头，而且现象与没修一模一样。
 *
 * ## 为什么统一还原是安全的
 *
 * 实测（同一张图、同一目录段，4 个边缘 IP 交叉验证）：
 *
 * | 目录段 | 状态 |
 * |---|---|
 * | `sub_storage 1`（U+0020） | 403 |
 * | `sub_storage\u00A01`（U+00A0） | 403 |
 * | `sub_storage　1`（U+3000） | 403 |
 * | `sub_storage\t1`（U+0009） | 403 |
 * | `sub_storage_1`（下划线） | **200** |
 *
 * 所有空白变体都取不到图，只有下划线是正确字符，因此不存在"误伤合法路径"的风险。
 */
private val PATH_BLANK_CHARS = charArrayOf(
    ' ',      // U+0020 普通空格
    '\u00A0', // U+00A0 不间断空格（NBSP）
    '\u3000', // U+3000 全角空格
    '\t',     // U+0009 制表符
)

private fun String.normalizePathBlanks(): String = buildString(length) {
    for (c in this@normalizePathBlanks) {
        append(if (c in PATH_BLANK_CHARS) '_' else c)
    }
}