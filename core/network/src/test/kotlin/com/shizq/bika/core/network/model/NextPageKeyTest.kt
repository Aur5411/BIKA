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

    // ───────────────────────── estimateLastChapterPage ─────────────────────────
    // 只决定"首批并发发多少页"，不参与终止判断。因此这些用例钉的是
    // "宁可多估一页、也不要估窄"，而不是精确值。

    private fun estimatePage(
        total: Int,
        pages: Int,
        limit: Int = 20,
        docCount: Int = 20,
    ) = PageData(
        total = total,
        limit = limit,
        page = 1,
        pages = pages,
        docs = List(docCount) { "ch$it" },
    )

    @Test
    fun `按 total 规划并多探一页`() {
        // 120 话 / 每页 20 → 6 页，+1 探测页 → 7
        assertEquals(
            7,
            estimatePage(total = 120, pages = 6).estimateLastChapterPage(),
        )
    }

    @Test
    fun `limit 不可用时退回按本页条数估算`() {
        // limit=0 是服务端偶发字段异常，用本页实际条数当每页容量
        assertEquals(
            6,
            estimatePage(total = 100, pages = 5, limit = 0, docCount = 20)
                .estimateLastChapterPage(),
        )
    }

    @Test
    fun `total 与 pages 不一致时取较大值`() {
        // total 报小（40 → 2 页）而 pages 报 6：取大的才不会把批次规划得太窄
        assertEquals(
            7,
            estimatePage(total = 40, pages = 6).estimateLastChapterPage(),
        )
        // 反过来 pages 报小也一样
        assertEquals(
            7,
            estimatePage(total = 120, pages = 1).estimateLastChapterPage(),
        )
    }

    @Test
    fun `两者都不可用时只探一页`() {
        // 规划不出来时退回最小批次，让批次自己一档一档往前推
        assertEquals(
            2,
            estimatePage(total = 0, pages = 0).estimateLastChapterPage(),
        )
    }

    @Test
    fun `页码信息完全缺失时也能给出最小规划`() {
        // total/pages/limit 全为 0 且本页为空：不能返回 0 页，
        // 否则并发批次会退化成"一页都不发"而直接空手结束
        assertEquals(
            2,
            estimatePage(total = 0, pages = 0, limit = 0, docCount = 0)
                .estimateLastChapterPage(),
        )
    }

    @Test
    fun `估算结果不超过页数硬上限`() {
        // total 虚高时不能据此发出上百个请求
        assertEquals(
            MAX_CHAPTER_LIST_PAGES,
            estimatePage(total = 1_000_000, pages = 1).estimateLastChapterPage(),
        )
    }

    // ───────────────────── declaredChapterTotal ─────────────────────
    // 自报总数只认"返回了条目"的页：空页里的 total 是垃圾值，
    // 拿它当真会凭空推出一个不存在的下一章（详见函数文档里的日志片段）。

    private fun totalPage(total: Int, docCount: Int) = PageData(
        total = total,
        limit = 40,
        page = 1,
        pages = 1,
        docs = List(docCount) { "ch$it" },
    )

    @Test
    fun `有数据的页照常抬高自报总数`() {
        assertEquals(72, declaredChapterTotal(current = 0, page = totalPage(72, 40)))
        assertEquals(76, declaredChapterTotal(current = 72, page = totalPage(76, 36)))
    }

    @Test
    fun `空页的 total 被忽略（回归：9 话被算成 10 话）`() {
        // 用户日志里的真实形状：首页 total=9，越界空页却报 total=10
        val afterFirstPage = declaredChapterTotal(current = 0, page = totalPage(9, 9))
        assertEquals(9, afterFirstPage)

        val afterEmptyPage = declaredChapterTotal(current = afterFirstPage, page = totalPage(10, 0))
        assertEquals(9, afterEmptyPage, "空页的 total 不能抬高自报总数")
    }

    @Test
    fun `空页不会把自报总数从零凭空变成有值`() {
        assertEquals(0, declaredChapterTotal(current = 0, page = totalPage(10, 0)))
    }
}
