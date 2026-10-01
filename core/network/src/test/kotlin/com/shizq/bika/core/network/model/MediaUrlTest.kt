package com.shizq.bika.core.network.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Media.safeImageUrl] 的健壮性。
 *
 * 锁定的缺陷：图片 URL 由服务端返回的 `fileServer` + `path` 直接拼接，任一缺失
 * 都会产出一个必然下载失败的地址，而它**不会被链路上任何一层拦下**——
 * 限流拦截器的 `startsWith("http")` 判假直接放行，换源拦截器的
 * `toHttpUrlOrNull()` 返回 null 也会原样放行，最终在 OkHttp 处失败。
 * 用户看到的是"某几页固定加载不出来、怎么点重试都没用"。
 *
 * 本类保证：字段缺失时返回 null（调用方据此把这一张过滤掉），而不是返回死链。
 */
class MediaUrlTest {

    @Test
    fun `正常字段拼出完整地址`() {
        val media = Media(path = "abc/1.jpg", fileServer = "https://s3.picacomic.com")
        assertEquals("https://s3.picacomic.com/static/abc/1.jpg", media.safeImageUrl)
    }

    @Test
    fun `fileServer 为空时返回 null`() {
        assertNull(Media(path = "abc/1.jpg", fileServer = "").safeImageUrl)
    }

    @Test
    fun `path 为空时返回 null`() {
        assertNull(Media(path = "", fileServer = "https://s3.picacomic.com").safeImageUrl)
    }

    @Test
    fun `缺 scheme 的 fileServer 返回 null`() {
        // 服务端偶发返回裸域名，此时拼出来的地址无效，必须拦截。
        assertNull(Media(path = "1.jpg", fileServer = "s3.picacomic.com").safeImageUrl)
    }

    @Test
    fun `多余斜杠会被规范化`() {
        val media = Media(path = "/abc/1.jpg", fileServer = "https://s3.picacomic.com/")
        assertEquals("https://s3.picacomic.com/static/abc/1.jpg", media.safeImageUrl)
    }

    @Test
    fun `字段两侧空白会被裁掉`() {
        val media = Media(path = " 1.jpg ", fileServer = " https://s3.picacomic.com ")
        assertEquals("https://s3.picacomic.com/static/1.jpg", media.safeImageUrl)
    }

    @Test
    fun `原属性仍然无条件拼接以便对照排查`() {
        // safeImageUrl 是新入口；originalImageUrl 保留原行为，
        // 便于在日志里对照"服务端到底给了什么"。
        val media = Media(path = "", fileServer = "")
        assertEquals("/static/", media.originalImageUrl)
        assertNull(media.safeImageUrl)
    }

    // ---- 以下用例锁定"必然 404"的两种 URL 形态 ----
    //
    // 实测（本机直连图片源）：/static/static/x.jpg 与 /static//x.jpg 都返回 404，
    // 且这两种形态看起来完全正常，排查时极易漏掉。真实用户报告的"某几页固定
    // 提示 HTTP 404"里，有一类就是它们造成的。

    @Test
    fun `path 自带 static 前缀时不会拼出双 static`() {
        val media = Media(path = "static/abc/1.jpg", fileServer = "https://s3.picacomic.com")
        assertEquals("https://s3.picacomic.com/static/abc/1.jpg", media.safeImageUrl)
    }

    @Test
    fun `path 自带斜杠加 static 前缀时同样归一化`() {
        val media = Media(path = "/static/abc/1.jpg", fileServer = "https://s3.picacomic.com")
        assertEquals("https://s3.picacomic.com/static/abc/1.jpg", media.safeImageUrl)
    }

    @Test
    fun `fileServer 与 path 都带斜杠时不会出现双斜杠`() {
        val media = Media(path = "/abc/1.jpg", fileServer = "https://s3.picacomic.com/")
        val url = media.safeImageUrl
        assertEquals("https://s3.picacomic.com/static/abc/1.jpg", url)
        // 双斜杠会被部分 CDN 当成不同路径直接 404，这里显式断言不存在
        assert(!url!!.substringAfter("://").contains("//")) { "URL 中不应出现双斜杠: $url" }
    }

    @Test
    fun `path 只有 static 前缀时被识别为空并返回 null`() {
        // "static/" 剥掉后什么都不剩，拼出来就是 /static/ 根，必然取不到图
        assertNull(Media(path = "static/", fileServer = "https://s3.picacomic.com").safeImageUrl)
    }

    @Test
    fun `path 只由斜杠组成时返回 null`() {
        assertNull(Media(path = "///", fileServer = "https://s3.picacomic.com").safeImageUrl)
    }

    // ---- 以下用例锁定"路径段含空格"这一形态 ----
    //
    // 实测（storage-b，4 个边缘 IP 交叉验证，结论一致）：
    //   /static/sub_storage%201/7f/8b/<uuid>.jpg  -> 403（nginx 原生 403 页，目录不存在）
    //   /static/sub_storage_1/7f/8b/<uuid>.jpg    -> 200 + 128093 字节
    // 服务端 path 里的空格是脏数据，真实字符是下划线。放行的话 OkHttp 会编码成
    // %20 发出去，CDN 侧确定性 403，而 URL"看起来完全正常"，会被误判成"图不存在"。

    @Test
    fun `路径段中的空格被还原为下划线`() {
        val media = Media(
            path = "sub_storage 1/7f/8b/7f8b7261-7509-4aae-88df-9e238ba01e62.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/7f/8b/7f8b7261-7509-4aae-88df-9e238ba01e62.jpg",
            media.safeImageUrl,
        )
    }

    @Test
    fun `含空格的路径不会残留编码空格或裸空格`() {
        val media = Media(
            path = "sub_storage 1/7f/8b/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        val url = media.safeImageUrl!!
        assert(!url.contains(" ")) { "URL 中不应残留裸空格: $url" }
        assert(!url.contains("%20")) { "URL 中不应残留 %20: $url" }
        assert(!url.contains("+")) { "URL 中不应残留 +: $url" }
    }

    @Test
    fun `tobs 前缀加空格目录段时两处都正确`() {
        // 真实形态：path = "tobs/sub_storage 1/7f/8b/xxx.jpg"
        // 服务端对 /static/tobs/... 会 301 到 /static/...，但空格必须先还原，
        // 否则重定向目标也带着 %20，一样 403。
        val media = Media(
            path = "tobs/sub_storage 1/7f/8b/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        assertEquals(
            "https://storage-b.picacomic.com/static/tobs/sub_storage_1/7f/8b/1.jpg",
            media.safeImageUrl,
        )
    }

    @Test
    fun `不含空格的正常路径完全不受影响`() {
        // 回归保护：不能让归一化误伤普通路径。
        val media = Media(path = "abc/def/1.jpg", fileServer = "https://s3.picacomic.com")
        assertEquals("https://s3.picacomic.com/static/abc/def/1.jpg", media.safeImageUrl)
    }

    @Test
    fun `路径段两侧已有空白仍先被裁掉再归一化`() {
        val media = Media(
            path = "  sub_storage 1/1.jpg  ",
            fileServer = " https://storage-b.picacomic.com ",
        )
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/1.jpg",
            media.safeImageUrl,
        )
    }

    // ---- 下列用例锁定"看起来是空格、但不是普通空格"的字符 ----
    //
    // Java 的 Character.isWhitespace('\u00A0') 返回 **false**，但它渲染出来和普通
    // 空格完全一样。只写 .replace(" ", "_") 会在这些字符上**静默失效**，
    // 现象与完全没修一模一样——这是本类要防住的最后一种漏网形态。
    //
    // 实测（storage-b，同一张图）：U+0020 / U+00A0 / U+3000 / U+0009 全部 403，
    // 只有下划线返回 200。所以统一还原是安全的，不存在误伤合法路径的风险。

    @Test
    fun `不间断空格同样被还原为下划线`() {
        val media = Media(
            path = "sub_storage\u00A01/6a/7c/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/6a/7c/1.jpg",
            media.safeImageUrl,
        )
    }

    @Test
    fun `全角空格同样被还原为下划线`() {
        val media = Media(
            path = "sub_storage\u30001/6a/7c/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/6a/7c/1.jpg",
            media.safeImageUrl,
        )
    }

    @Test
    fun `制表符同样被还原为下划线`() {
        val media = Media(
            path = "sub_storage\t1/6a/7c/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/6a/7c/1.jpg",
            media.safeImageUrl,
        )
    }

    @Test
    fun `归一化后不残留任何空白字符`() {
        val media = Media(
            path = "sub_storage\u00A01/6a/7c/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        val url = media.safeImageUrl!!
        val hasBlank = url.any { it == ' ' || it == '\u00A0' || it == '\u3000' || it == '\t' }
        assert(!hasBlank) { "URL 中不应残留空白字符: $url" }
    }

    // ---- normalizedImageUrl：下载链路专用的"永不返回 null"入口 ----

    @Test
    fun `normalizedImageUrl 与 safeImageUrl 的归一化结果一致`() {
        val media = Media(
            path = "sub_storage 1/6a/7c/1.jpg",
            fileServer = "https://storage-b.picacomic.com",
        )
        assertEquals(media.safeImageUrl, media.normalizedImageUrl)
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/6a/7c/1.jpg",
            media.normalizedImageUrl,
        )
    }

    @Test
    fun `normalizedImageUrl 在字段缺失时回退原值而不为 null`() {
        // 下载链路必须拿到一个值：静默丢弃会让分页循环把"这一页全畸形"
        // 误判成"没有更多页"而提前终止，反而丢掉后面正常的页。
        val media = Media(path = "abc/1.jpg", fileServer = "")
        assertNull(media.safeImageUrl)
        assertEquals(media.originalImageUrl, media.normalizedImageUrl)
        assert(media.normalizedImageUrl.isNotEmpty())
    }
}
