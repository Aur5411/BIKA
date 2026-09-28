package com.shizq.bika.core.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [fillMissingLeadingOrders] 的契约。
 *
 * 这条规则是"服务端把长目录截断时，客户端能否补出缺失的最老那几十话"的全部依据，
 * 而它只在**推断无歧义**时才允许动手：补错的条目会让用户点进去看到加载失败，
 * 比"少几个条目"更糟。所以这里重点钉的是三个"不该补"的负例。
 */
class FillMissingLeadingOrdersTest {

    private fun chapter(order: Int) = Chapter(
        id = "id-$order",
        order = order,
        title = "第 $order 話",
        updatedAt = "",
    )

    /** 服务端截断的真实形状：自报 160 话，只给到最新的 90 条（order 71..160）。 */
    private fun truncatedCatalog(fromOrder: Int = 71, toOrder: Int = 160) = ChapterCatalog(
        chapters = (fromOrder..toOrder).map { chapter(it) },
        isComplete = false,
    )

    @Test
    fun `尾部被截断时补齐前置章节`() {
        val filled = truncatedCatalog().fillMissingLeadingOrders(expectedTotal = 160)

        assertEquals(160, filled.chapters.size)
        assertEquals((1..160).toList(), filled.chapters.map { it.order })
        assertTrue(filled.isComplete, "补齐后 order 覆盖了服务端声称的全部范围")
    }

    @Test
    fun `补齐的是前置条目而不是追加在后面`() {
        val filled = truncatedCatalog(fromOrder = 3, toOrder = 5)
            .fillMissingLeadingOrders(expectedTotal = 5)

        assertEquals(listOf(1, 2, 3, 4, 5), filled.chapters.map { it.order })
        assertEquals("synthetic-order-1", filled.chapters.first().id)
    }

    @Test
    fun `缺的条数与前置 order 数对不上就不补`() {
        // 90 条但 total 报 200：缺 110 条，而前面只有 70 个 order 位，
        // 说明缺的不止在开头，推断不成立
        val catalog = truncatedCatalog().fillMissingLeadingOrders(expectedTotal = 200)

        assertEquals(90, catalog.chapters.size)
        assertFalse(catalog.isComplete)
    }

    @Test
    fun `order 有空洞就不补`() {
        // 去掉中间一条，造成 71..160 里缺一个：无法断定缺的全在开头
        val holed = ChapterCatalog(
            chapters = (71..160).map { chapter(it) }.filter { it.order != 100 },
            isComplete = false,
        )

        val catalog = holed.fillMissingLeadingOrders(expectedTotal = 160)

        assertEquals(89, catalog.chapters.size)
        assertFalse(catalog.isComplete)
    }

    @Test
    fun `已到手数量不少于自报总数就原样返回`() {
        val full = ChapterCatalog((1..160).map { chapter(it) }, isComplete = true)

        assertEquals(full, full.fillMissingLeadingOrders(expectedTotal = 160))
    }

    @Test
    fun `自报总数为未知时不补`() {
        // total 不可用（0 或负数）时没有依据，宁可少补也不乱补
        val catalog = truncatedCatalog()

        assertEquals(catalog, catalog.fillMissingLeadingOrders(expectedTotal = 0))
        assertEquals(catalog, catalog.fillMissingLeadingOrders(expectedTotal = -1))
    }

    @Test
    fun `空目录不补`() {
        val empty = ChapterCatalog(emptyList(), isComplete = false)

        assertEquals(empty, empty.fillMissingLeadingOrders(expectedTotal = 160))
    }

    @Test
    fun `从头就完整时不产生任何补齐`() {
        // 50 话全在（1..50），自报 total 也是 50：不该冒出 synthetic 条目
        val catalog = ChapterCatalog((1..50).map { chapter(it) }, isComplete = false)

        val filled = catalog.fillMissingLeadingOrders(expectedTotal = 50)

        assertEquals(catalog, filled)
        assertTrue(filled.chapters.none { it.id.startsWith("synthetic-order-") })
    }
}
