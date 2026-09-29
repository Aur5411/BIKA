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
}
