package com.shizq.bika.core.network.image

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [sanitizeStaticImagePath] —— 图片链路的最后一道路径守卫。
 *
 * 锁定的缺陷：`Media.safeImageUrl` 只修好了"从 API 响应拼 URL"这一条路径，
 * 而取图片地址的调用点不止一处（封面/头像走 `originalImageUrl`）。任何一处漏掉，
 * 用户看到的仍然是"图片不存在"，且极难定位——因为 URL 渲染出来"看起来完全正常"。
 *
 * 本类保证：只要最终请求的是 `/static/` 图片，进网络前的路径一定不含不可见字符。
 */
class ImagePathSanitizerTest {

    private fun sanitize(url: String): String? =
        url.toHttpUrlOrNull()!!.sanitizeStaticImagePath()?.toString()

    @Test
    fun `路径段里的空格被还原为下划线`() {
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/7f/8b/1.jpg",
            sanitize("https://storage-b.picacomic.com/static/sub_storage%201/7f/8b/1.jpg"),
        )
    }

    @Test
    fun `不间断空格的编码形态同样被还原`() {
        // %C2%A0 是 U+00A0（NBSP）。它渲染出来和普通空格一样，
        // 但 Character.isWhitespace('\u00A0') == false —— 只按"空白"判会漏。
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/6a/7c/1.jpg",
            sanitize("https://storage-b.picacomic.com/static/sub_storage%C2%A01/6a/7c/1.jpg"),
        )
    }

    @Test
    fun `零宽字符的编码形态同样被还原`() {
        // %E2%80%8B 是 U+200B（零宽空格），属于 Cf 类别，两个常用空白判据都判不出。
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/1b/d3/1.jpg",
            sanitize("https://storage-b.picacomic.com/static/sub_storage%E2%80%8B1/1b/d3/1.jpg"),
        )
    }

    @Test
    fun `归一化结果是幂等的`() {
        // 守卫可能被多条链路依次经过，重复处理必须无副作用。
        val once = sanitize("https://storage-b.picacomic.com/static/sub_storage%201/1.jpg")
        assertNull(sanitize(once!!), "已经干净的地址不应再被改写")
    }

    @Test
    fun `干净的地址返回 null 不做任何改动`() {
        assertNull(sanitize("https://storage-b.picacomic.com/static/sub_storage_1/7f/8b/1.jpg"))
    }

    @Test
    fun `非 static 路径完全不受影响`() {
        // 头像 / 其它 CDN 资源不归这里管，必须原样放行。
        assertNull(sanitize("https://storage-b.picacomic.com/avatar/1 2.png"))
        assertNull(sanitize("https://www.picacomic.com/api/v2/users/profile"))
    }

    @Test
    fun `改写路径时保留查询串`() {
        assertEquals(
            "https://storage-b.picacomic.com/static/sub_storage_1/1.jpg?token=ab%20cd",
            sanitize("https://storage-b.picacomic.com/static/sub_storage%201/1.jpg?token=ab%20cd"),
        )
    }

    @Test
    fun `路径段首尾的不可见字符也被替换为下划线`() {
        // 段内的守卫是"替换"而不是"裁剪"：保留段的位置与长度，
        // 避免把 `a b` 剪成 `a` 之后拼出另一个同样不存在的目录。
        val out = sanitize("https://storage-b.picacomic.com/static/%20sub%20/1.jpg")
        assertEquals("https://storage-b.picacomic.com/static/_sub_/1.jpg", out)
    }

    @Test
    fun `连续多个不可见字符各自替换不会塌缩`() {
        val out = sanitize("https://storage-b.picacomic.com/static/sub%20%20%201/1.jpg")
        assertEquals("https://storage-b.picacomic.com/static/sub___1/1.jpg", out)
    }

    @Test
    fun `重构后的地址仍然可解析且路径段数量不变`() {
        val out = sanitize("https://storage-b.picacomic.com/static/tobs/sub_storage%201/7f/8b/1.jpg")
        assertNotNull(out)
        val reparsed = out.toHttpUrlOrNull()!!
        assertEquals(6, reparsed.pathSegments.size)
        assertEquals("static", reparsed.pathSegments[0])
        assertEquals("tobs", reparsed.pathSegments[1])
        assertEquals("sub_storage_1", reparsed.pathSegments[2])
    }
}
