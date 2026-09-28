package com.shizq.bika.core.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [navigationAt] 的上下章判定。
 *
 * 这里钉的是本次修复的核心：**"还有没有下一话"由 order + 服务端自报总数决定，
 * 不能只看"目录里恰好有没有这一条"**。后者在目录还在加载、或服务端把长目录截断时
 * 会让 `next` 变成 null，阅读器据此把「下一章」按钮置灰——按顺序明明还有下一话。
 *
 * 两条防线配合工作：
 * 1. [fillMissingLeadingOrders] 把中断的 order 区间补完整（覆盖开头缺失的情形）；
 * 2. [navigationAt] 在目录仍然缺那一条时按 order 推导（覆盖尾部缺失的情形）。
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
    fun `目录尾部缺失时按 order 推导出下一章`() {
        // 服务端自报 200 话，目录只拉到 160：读第 160 话时按顺序还有 161
        val nav = truncatedCatalog(declaredTotal = 200).navigationAt(order = 160)

        assertEquals(161, nav.next?.order)
        assertEquals("synthetic-order-161", nav.next?.id)
        assertEquals("第 161 話", nav.next?.title)
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

    @Test
    fun `补齐区间后下一章就是 order 加一`() {
        // 截断目录先补齐 1..70，再求导航：读第 60 话，下一章必须是 61 而不是跳到 71
        val filled = truncatedCatalog().fillMissingLeadingOrders(expectedTotal = 160)

        assertEquals(160, filled.chapters.size)
        assertEquals(61, filled.navigationAt(order = 60).next?.order)
    }

    @Test
    fun `目录还在加载时下一章也能立刻算出来`() {
        // 第一页刚回来只有最新的 30 话，补齐成 1..160 之后读第 5 话，下一章是 6
        val loading = ChapterCatalog(
            chapters = (131..160).map { chapter(it) },
            isComplete = false,
            declaredTotal = 160,
        ).fillMissingLeadingOrders(expectedTotal = 160)

        assertEquals(6, loading.navigationAt(order = 5).next?.order)
    }

    @Test
    fun `order 超出自报总数时不推导`() {
        // 总数不可信地报小（实测会发生）：已无依据，退回"目录里没有就没有"
        assertNull(truncatedCatalog(declaredTotal = 160).navigationAt(order = 200).next)
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
        // 推导出相邻章节不等于目录拉全了，这个标记不能跟着变
        assertFalse(truncatedCatalog(declaredTotal = 200).navigationAt(order = 160).isResolved)
        assertTrue(fullCatalog(to = 10).navigationAt(order = 5).isResolved)
    }

    // ───────────────────────── 首尾边界 ─────────────────────────
    // 边界完全由 order 与自报总数判掉，不依赖目录里有没有那一条。
    // 而且边界判定要在"目录没拉全"的前提下也成立——这正是原先出问题的地方。

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
        // 读到底那一话：order 已达自报总数，不能再推导出第 161 话
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
