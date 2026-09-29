package com.shizq.bika.core.data.paging

import com.shizq.bika.core.network.model.ChapterInfo
import com.shizq.bika.core.network.model.ChapterPagesData
import com.shizq.bika.core.network.model.Image
import com.shizq.bika.core.network.model.Media
import com.shizq.bika.core.network.model.PageData
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 整章各 API 页拼接为单一有序列表的行为。
 *
 * 锁定的缺陷：
 * - **顺序必须等于页号顺序**。并发拉取（`awaitAll`）只保证提交顺序，而服务端会
 *   对超范围请求做 clamp，返回的 `page` 不一定等于请求值；不显式排序会让图片
 *   顺序与章节错位。
 * - **URL 畸形的图必须被丢弃**。`fileServer`/`path` 缺失时拼出的地址会穿过整条
 *   图片链路而不被拦下，最终表现为"某几页固定加载失败、重试无效"。
 *
 * 这批用例也顺带锁住"全量单页"这一新架构的前提：一次 load 就是整章，
 * 因此不存在 append 边界，14-20 页之类的中段缺失不再是结构性问题。
 */
class MergeChapterPagesTest {

    private fun image(id: String, path: String = "$id.jpg") = Image(
        imageId = id,
        media = Media(path = path, fileServer = "https://img.example.com"),
    )

    private fun page(
        pageNumber: Int,
        docs: List<Image>,
        total: Int = 100,
        pages: Int = 5,
    ) = ChapterPagesData(
        imagePages = PageData(
            total = total,
            limit = 20,
            page = pageNumber,
            pages = pages,
            docs = docs,
        ),
        chapterInfo = ChapterInfo(chapterId = "ep1", title = "第一章"),
    )

    @Test
    fun `按服务端页号排序而非输入顺序`() {
        // 故意乱序传入，模拟并发返回顺序与页号不一致。
        val result = mergeChapterPages(
            listOf(
                page(3, listOf(image("p3a"), image("p3b"))),
                page(1, listOf(image("p1a"), image("p1b"))),
                page(2, listOf(image("p2a"))),
            )
        )

        assertEquals(
            listOf("p1a", "p1b", "p2a", "p3a", "p3b"),
            result.map { it.id },
        )
    }

    @Test
    fun `页内保持服务端给出的顺序`() {
        val result = mergeChapterPages(
            listOf(page(1, listOf(image("a"), image("b"), image("c"))))
        )

        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun `URL 畸形的图被丢弃且不影响其余页`() {
        val brokenServer = Image(
            imageId = "bad-server",
            media = Media(path = "x.jpg", fileServer = ""),
        )
        val brokenPath = Image(
            imageId = "bad-path",
            media = Media(path = "", fileServer = "https://img.example.com"),
        )

        val result = mergeChapterPages(
            listOf(
                page(1, listOf(image("good1"), brokenServer)),
                page(2, listOf(brokenPath, image("good2"))),
            )
        )

        assertEquals(listOf("good1", "good2"), result.map { it.id })
    }

    @Test
    fun `全部为空时返回空列表而不是抛异常`() {
        assertEquals(emptyList(), mergeChapterPages(emptyList()))
    }

    @Test
    fun `URL 被规范化为完整地址`() {
        val result = mergeChapterPages(
            listOf(page(1, listOf(image("a", path = "/nested/a.jpg"))))
        )

        assertEquals("https://img.example.com/static/nested/a.jpg", result.single().url)
    }
}
