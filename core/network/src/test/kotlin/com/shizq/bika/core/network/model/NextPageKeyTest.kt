package com.shizq.bika.core.network.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 单向分页的下一页 key。
 *
 * 重点是"空页立即停"：只看 pages 的旧写法在 pages 虚高时会让 Paging
 * 对着空页一路 append，用户侧是滚到底就一直转圈。
 */
class NextPageKeyTest {

    private fun page(
        pages: Int,
        docCount: Int,
        page: Int = 1,
    ) = PageData(
        total = docCount,
        limit = 20,
        page = page,
        pages = pages,
        docs = List(docCount) { "doc$it" },
    )

    @Test
    fun `中间页返回下一页`() {
        assertEquals(2, page(pages = 5, docCount = 20).nextPageKey(requestedPage = 1))
    }

    @Test
    fun `末页返回 null`() {
        assertNull(page(pages = 5, docCount = 20).nextPageKey(requestedPage = 5))
    }

    @Test
    fun `页码超出总页数时返回 null`() {
        // 服务端对超范围请求做 clamp 时会走到这里
        assertNull(page(pages = 5, docCount = 20).nextPageKey(requestedPage = 7))
    }

    @Test
    fun `空页即使未到末页也停止`() {
        // pages 虚高：声明还有 5 页，实际第 2 页就没数据了
        assertNull(page(pages = 5, docCount = 0).nextPageKey(requestedPage = 2))
    }

    @Test
    fun `首页为空时不再请求第二页`() {
        assertNull(page(pages = 3, docCount = 0).nextPageKey(requestedPage = 1))
    }

    @Test
    fun `总页数为零时返回 null`() {
        // 空列表的正常响应：pages=0、docs 为空
        assertNull(page(pages = 0, docCount = 0).nextPageKey(requestedPage = 1))
    }

    @Test
    fun `总页数为零但有数据时仍然停止`() {
        // 字段自相矛盾，按保守处理：不追加请求，已拿到的数据照常展示
        assertNull(page(pages = 0, docCount = 20).nextPageKey(requestedPage = 1))
    }

    @Test
    fun `用请求页而非响应页推算下一页`() {
        // 响应里的 page 被服务端 clamp 回 5，若用它算下一页会永远停在 6、原地打转
        val clamped = page(pages = 5, docCount = 20, page = 5)

        assertNull(clamped.nextPageKey(requestedPage = 9))
    }

    @Test
    fun `未满一页但仍有后续页时继续`() {
        // 去重或服务端删条目会让单页短于 limit，这不等于到了末页
        assertEquals(3, page(pages = 5, docCount = 3).nextPageKey(requestedPage = 2))
    }

    // ───────────────────────── nextChapterPageKey ─────────────────────────
    // 章节列表专用：终止只认"空页"（+ 调用方的重复页判断 + 页数硬上限），
    // total / pages 一律不参与——它们都被证实会报小。

    /** 构造真实形状的章节页响应：total 是全书总话数，不是本页条数。 */
    private fun chapterPage(
        requestedPage: Int,
        total: Int = 120,
        pages: Int = 6,
        docCount: Int = 20,
    ) = PageData(
        total = total,
        limit = 20,
        page = requestedPage,
        pages = pages,
        docs = List(docCount) { "ch$it" },
    )

    @Test
    fun `pages 报小时不再提前收工`() {
        // 回归用例：120 话的漫画服务端只报 1 页。旧逻辑（认 pages）会停在第 1 页，
        // 而因为服务端按"最新话在前"分页，被截掉的恰好是最老的几十话
        assertEquals(
            2,
            chapterPage(requestedPage = 1, total = 120, pages = 1)
                .nextChapterPageKey(requestedPage = 1),
        )
    }

    @Test
    fun `total 报小时不再提前收工`() {
        // 同上，这次是 total 报小。已收条数远超 total 也要继续翻
        assertEquals(
            4,
            chapterPage(requestedPage = 3, total = 40, pages = 2)
                .nextChapterPageKey(requestedPage = 3),
        )
    }

    @Test
    fun `收到末页仍然继续，由空页负责收工`() {
        // pages 说这是最后一页，但接口还没给出空页，继续翻一次成本极低（50ms），
        // 比"少几十话"便宜得多
        assertEquals(
            7,
            chapterPage(requestedPage = 6, total = 120, pages = 6)
                .nextChapterPageKey(requestedPage = 6),
        )
    }

    @Test
    fun `空页立即停止`() {
        assertNull(
            chapterPage(requestedPage = 7, docCount = 0)
                .nextChapterPageKey(requestedPage = 7),
        )
    }

    @Test
    fun `首页为空时不请求第二页`() {
        assertNull(chapterPage(requestedPage = 1, docCount = 0).nextChapterPageKey(1))
    }

    @Test
    fun `页码非法时停止`() {
        assertNull(chapterPage(requestedPage = 0).nextChapterPageKey(0))
    }

    @Test
    fun `翻到页数硬上限即停止`() {
        // 兜底：服务端一直返回互不重复的非空页时，循环也要有确定的终点
        assertNull(
            chapterPage(requestedPage = MAX_CHAPTER_LIST_PAGES, docCount = 20)
                .nextChapterPageKey(MAX_CHAPTER_LIST_PAGES),
        )
        assertEquals(
            MAX_CHAPTER_LIST_PAGES,
            chapterPage(requestedPage = MAX_CHAPTER_LIST_PAGES - 1)
                .nextChapterPageKey(MAX_CHAPTER_LIST_PAGES - 1),
        )
    }
}
