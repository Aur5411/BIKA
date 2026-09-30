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
}
