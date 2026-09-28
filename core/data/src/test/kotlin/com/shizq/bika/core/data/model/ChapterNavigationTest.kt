package com.shizq.bika.core.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [navigationAt] 的上下章判定。
 *
 * 两条铁律，各自都有反面用例守着：
 *
 * 1. **上一章可以按 order 推导**：目录还在加载、或服务端把长目录截断了（截掉的
 *    正是最老的那几十话）时，`order - 1` 那一话确实存在，不能让「上一章」置灰；
 * 2. **下一章只能来自目录里真实存在的条目**：服务端按"最新话在前"分页，目录的
 *    最大 order 就是最新一话，不存在更靠后的章节。曾经按 `order + 1 <= total`
 *    推导，结果在一本服务端自报 total 偏大的漫画上，给最后一话点亮了指向
 *    不存在章节的「下一章」。
 */
class ChapterNavigationTest {

    private fun chapter(order: Int) = Chapter(
        id = "id-$order",
        order = order,
        title = "第 $order 話",
        updatedAt = "",
    )

    private fun fullCatalog(to: Int) = ChapterCatalog(
        chapters = (1..to).map { chapter(it) },
        isComplete = true,
        declaredTotal = to,
    )

    /** 服务端截断的真实形状：自报 160 话，目录只给到 71..160。 */
    private fun truncatedCatalog(declaredTotal: Int = 160) = ChapterCatalog(
        chapters = (71..160).map { chapter(it) },
        isComplete = false,
        declaredTotal = declaredTotal,
    )

    @Test
    fun `完整目录给出真实的相邻章节`() {
        val nav = fullCatalog(to = 10).navigationAt(order = 5)

        assertEquals("id-4", nav.prev?.id)
        assertEquals("id-6", nav.next?.id)
        assertTrue(nav.isResolved)
    }

    @Test
    fun `第一话没有上一章最后一话没有下一章`() {
        val catalog = fullCatalog(to = 10)

        assertNull(catalog.navigationAt(order = 1).prev)
        assertNull(catalog.navigationAt(order = 10).next)
    }

    @Test
    fun `目录开头缺失时按 order 推导出上一章`() {
        // 自报总数 5，目录只给到 3..5：读第 3 话时按顺序前面还有 2
        val catalog = ChapterCatalog(
            chapters = (3..5).map { chapter(it) },
            isComplete = false,
            declaredTotal = 5,
        )

        val nav = catalog.navigationAt(order = 3)
        assertEquals(2, nav.prev?.order)
        assertEquals("synthetic-order-2", nav.prev?.id)
    }

    /**
     * 回归用例，形状直接取自用户日志：
     *
     * ```
     * 第 1 页：收到 9 条（total=9，首条 order=9）    ← 真实数据
     * 第 2 页：收到 0 条（total=10，首条 order=null） ← 空页谎报 total
     * 加载完成 共 9 话 (isComplete=false)
     * ```
     *
     * 自报总数被空页抬到 10，旧实现据此认为"第 9 话的下一话存在"，推出幻影
     * 第 10 话并点亮「下一章」。
     */
    @Test
    fun `空页谎报总数时不会推出幻影下一章`() {
        val polluted = ChapterCatalog(
            chapters = (1..9).map { chapter(it) },
            isComplete = false,
            declaredTotal = 10,
        )

        assertNull(
            polluted.navigationAt(order = 9).next,
            "目录最大 order 就是最新话，不该推导出第 10 话",
        )
        // 更老的那一侧照常：第 1 话依然没有上一章
        assertNull(polluted.navigationAt(order = 1).prev)
    }

    @Test
    fun `下一章永远来自目录已存在的条目`() {
        // 目录只拉到 131..160，读第 120 话（不在目录里）
        val partial = ChapterCatalog(
            chapters = (131..160).map { chapter(it) },
            isComplete = false,
            declaredTotal = 160,
        )

        // 给出的是目录里最近的一条真实章节，而不是按 order+1 补出来的合成条目
        val next = partial.navigationAt(order = 120).next
        assertEquals(131, next?.order)
        assertEquals("id-131", next?.id)

        // 目录里有的就正常给
        assertEquals(132, partial.navigationAt(order = 131).next?.order)

        // 目录最大 order 之后再无下一章可推
        assertNull(partial.navigationAt(order = 160).next)
    }

    @Test
    fun `目录还在加载时上一章也能立刻算出来`() {
        // 第一页刚回来只有最新的 30 话，补齐成 1..160 之后读第 5 话，上一章是 4
        val loading = ChapterCatalog(
            chapters = (131..160).map { chapter(it) },
            isComplete = false,
            declaredTotal = 160,
        ).fillMissingLeadingOrders(expectedTotal = 160)

        assertEquals(4, loading.navigationAt(order = 5).prev?.order)
    }

    @Test
    fun `服务端没给总数时退回原行为`() {
        // declaredTotal = 0 表示未知：不能凭空推导相邻章节（下载模式等场景靠这个）
        val unknown = ChapterCatalog(
            chapters = listOf(chapter(3), chapter(4)),
            isComplete = false,
            declaredTotal = 0,
        )

        assertNull(unknown.navigationAt(order = 4).next)
        assertNull(unknown.navigationAt(order = 3).prev)
    }

    @Test
    fun `isResolved 仍然只反映目录是否拉全`() {
        assertFalse(truncatedCatalog(declaredTotal = 200).navigationAt(order = 160).isResolved)
        assertTrue(fullCatalog(to = 10).navigationAt(order = 5).isResolved)
    }

    // ───────────────────────── 首尾边界 ─────────────────────────

    @Test
    fun `目录没拉全时第一话依然没有上一章`() {
        // 目录被截断、总数虚高，都不该让第一话冒出一个第 0 话
        val truncated = truncatedCatalog(declaredTotal = 200)
        assertNull(truncated.navigationAt(order = 1).prev)

        val totalOverReported = ChapterCatalog(
            chapters = listOf(chapter(1)),
            isComplete = false,
            declaredTotal = 200,
        )
        assertNull(totalOverReported.navigationAt(order = 1).prev)
    }

    @Test
    fun `目录没拉全时最后一话依然没有下一章`() {
        assertNull(truncatedCatalog(declaredTotal = 160).navigationAt(order = 160).next)
    }

    @Test
    fun `目录已拉全时完全按目录判边界`() {
        // 服务端 total 虚高（报 20，实际只有 10）：目录说了算，第 10 话没有下一章
        val complete = ChapterCatalog(
            chapters = (1..10).map { chapter(it) },
            isComplete = true,
            declaredTotal = 20,
        )

        assertNull(complete.navigationAt(order = 10).next)
        assertNull(complete.navigationAt(order = 1).prev)
    }

    @Test
    fun `总数未知时首尾边界照常`() {
        val unknown = ChapterCatalog(
            chapters = (1..5).map { chapter(it) },
            isComplete = false,
            declaredTotal = 0,
        )

        assertNull(unknown.navigationAt(order = 1).prev)
        assertNull(unknown.navigationAt(order = 5).next)
        assertEquals("id-4", unknown.navigationAt(order = 5).prev?.id)
    }
}
