package com.shizq.bika.core.data.paging

import com.shizq.bika.core.network.model.Image
import com.shizq.bika.core.network.model.Media
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 单张服务端图片 → [ChapterPage] 的映射规则。
 *
 * 锁定的缺陷：
 * **URL 畸形的图必须被丢弃**（`fileServer`/`path` 缺失，或 scheme 不是 http(s)）。
 * 这类图会拼出一个必然失败的死链，穿过整条图片链路也不会有任何一层拦下它，
 * 最终表现为"某几页固定加载失败、重试无效"——正是用户反复报告的现象。
 * 宁可少一张并留日志，也不让它进入列表被当作"一张加载不出来的图"。
 */
class MergeChapterPagesTest {

    private fun image(
        id: String,
        path: String = "$id.jpg",
        fileServer: String = "https://img.example.com",
    ) = Image(
        imageId = id,
        media = Media(path = path, fileServer = fileServer),
    )

    @Test
    fun `正常图片映射为完整 URL`() {
        val page = toChapterPageOrNull(image("a"))

        assertNotNull(page)
        assertEquals("a", page.id)
        assertEquals("https://img.example.com/static/a.jpg", page.url)
    }

    @Test
    fun `路径被规范化为完整地址`() {
        val page = toChapterPageOrNull(image("a", path = "/nested/a.jpg"))

        assertNotNull(page)
        assertEquals("https://img.example.com/static/nested/a.jpg", page.url)
    }

    @Test
    fun `fileServer 缺失时丢弃`() {
        assertNull(toChapterPageOrNull(image("bad", fileServer = "")))
    }

    @Test
    fun `path 缺失时丢弃`() {
        assertNull(toChapterPageOrNull(image("bad", path = "")))
    }

    @Test
    fun `fileServer 只有空白时丢弃`() {
        assertNull(toChapterPageOrNull(image("bad", fileServer = "   ")))
    }

    @Test
    fun `scheme 非法时丢弃`() {
        // 缺少 scheme 的字符串会被 OkHttp 当成相对路径，最终请求到 API 域名上，必然 404
        assertNull(toChapterPageOrNull(image("bad", fileServer = "img.example.com")))
    }

    @Test
    fun `fileServer 结尾多余斜杠不会产生双斜杠`() {
        val page = toChapterPageOrNull(image("a", fileServer = "https://img.example.com/"))

        assertNotNull(page)
        assertEquals("https://img.example.com/static/a.jpg", page.url)
    }

    @Test
    fun `path 开头斜杠不会产生双斜杠`() {
        val page = toChapterPageOrNull(image("a", path = "/a.jpg"))

        assertNotNull(page)
        assertEquals("https://img.example.com/static/a.jpg", page.url)
    }
}
