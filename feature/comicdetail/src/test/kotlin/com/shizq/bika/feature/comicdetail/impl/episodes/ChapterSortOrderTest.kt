package com.shizq.bika.feature.comicdetail.impl.episodes

import com.shizq.bika.core.data.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 章节目录的排序切换。
 *
 * 「点正序/倒序没反应」是本次真实遇到的问题，而它有两种完全不同的成因：
 * 状态没变、或者状态变了但展示顺序没变。把排序抽成纯函数后，
 * 后者可以直接钉死，剩下的可能性就只剩状态传递那一段。
 */
class ChapterSortOrderTest {

    private fun chapter(order: Int) = Chapter(
        id = "ch$order",
        title = "第 $order 话",
        order = order,
        updatedAt = "",
    )

    private val ascending = List(5) { chapter(it + 1) }

    @Test
    fun `正序保持原样`() {
        assertSame(ascending, ascending.applySortOrder(ChapterSortOrder.ASCENDING))
    }

    @Test
    fun `倒序把列表反过来`() {
        val reversed = ascending.applySortOrder(ChapterSortOrder.DESCENDING)

        assertEquals(listOf(5, 4, 3, 2, 1), reversed.map { it.order })
    }

    @Test
    fun `倒序不修改原列表`() {
        ascending.applySortOrder(ChapterSortOrder.DESCENDING)

        assertEquals(listOf(1, 2, 3, 4, 5), ascending.map { it.order })
    }

    @Test
    fun `切换两次回到原顺序`() {
        val order = ChapterSortOrder.ASCENDING.toggled()

        assertEquals(ChapterSortOrder.DESCENDING, order)
        assertEquals(ChapterSortOrder.ASCENDING, order.toggled())
    }

    @Test
    fun `排序只改顺序不改内容`() {
        val reversed = ascending.applySortOrder(ChapterSortOrder.DESCENDING)

        assertEquals(ascending.toSet(), reversed.toSet())
        assertEquals(ascending.size, reversed.size)
    }

    @Test
    fun `空列表与单元素列表安全`() {
        assertTrue(emptyList<Chapter>().applySortOrder(ChapterSortOrder.DESCENDING).isEmpty())
        assertEquals(
            listOf(1),
            listOf(chapter(1)).applySortOrder(ChapterSortOrder.DESCENDING).map { it.order },
        )
    }
}
