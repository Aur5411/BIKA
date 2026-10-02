package com.shizq.bika.core.data.paging

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 章节目录翻页终止判定的纯函数测试。
 *
 * 重点覆盖 v1.11.36 修复的两处回归：
 * 1. 「非空但零新章节」也算终止信号（服务端 clamp 到任意已见页）；
 * 2. 探测中收到新章节即重置预算（空页与数据页交替不再耗尽预算）。
 */
class CatalogWalkDecisionTest {

    @Test
    fun `正常数据页继续翻页`() {
        val d = chapterCatalogWalkDecision(
            isEmptyPage = false, pageRepeated = false, pageHasNewChapters = true,
            loaded = 20, expectedTotal = 160, probesLeft = CATALOG_TERMINAL_PROBE_PAGES,
        )
        assertFalse(d.isTerminal)
        assertFalse(d.isSuspicious)
        assertEquals(CATALOG_TERMINAL_PROBE_PAGES, d.probesLeft)
    }

    @Test
    fun `空页且已拉够则结束`() {
        val d = chapterCatalogWalkDecision(
            isEmptyPage = true, pageRepeated = false, pageHasNewChapters = false,
            loaded = 160, expectedTotal = 160, probesLeft = CATALOG_TERMINAL_PROBE_PAGES,
        )
        assertTrue(d.isTerminal)
        assertFalse(d.isSuspicious)
    }

    @Test
    fun `空页但没拉够则进入探测且预算减一`() {
        val d = chapterCatalogWalkDecision(
            isEmptyPage = true, pageRepeated = false, pageHasNewChapters = false,
            loaded = 80, expectedTotal = 160, probesLeft = 3,
        )
        assertFalse(d.isTerminal)
        assertTrue(d.isSuspicious)
        assertEquals(2, d.probesLeft)
    }

    @Test
    fun `探测中收到新章节则预算重置回满额`() {
        // 空页后紧跟的数据页：预算从 1 重置回 3——空页与数据页交替时不再耗尽预算
        val d = chapterCatalogWalkDecision(
            isEmptyPage = false, pageRepeated = false, pageHasNewChapters = true,
            loaded = 100, expectedTotal = 160, probesLeft = 1,
        )
        assertFalse(d.isTerminal)
        assertFalse(d.isSuspicious)
        assertEquals(CATALOG_TERMINAL_PROBE_PAGES, d.probesLeft)
    }

    @Test
    fun `空页与数据页交替时预算永不耗尽`() {
        var probesLeft = CATALOG_TERMINAL_PROBE_PAGES
        // 模拟：空页、数据页、空页、数据页……各消耗/重置一次
        repeat(5) {
            val empty = chapterCatalogWalkDecision(
                isEmptyPage = true, pageRepeated = false, pageHasNewChapters = false,
                loaded = 80, expectedTotal = 160, probesLeft = probesLeft,
            )
            assertTrue(empty.isSuspicious, "第 ${it + 1} 次空页应继续探测")
            probesLeft = empty.probesLeft

            val data = chapterCatalogWalkDecision(
                isEmptyPage = false, pageRepeated = false, pageHasNewChapters = true,
                loaded = 90, expectedTotal = 160, probesLeft = probesLeft,
            )
            assertFalse(data.isTerminal)
            probesLeft = data.probesLeft
            assertEquals(CATALOG_TERMINAL_PROBE_PAGES, probesLeft, "数据页应重置预算")
        }
    }

    @Test
    fun `连续无新数据耗尽预算后终止`() {
        var probesLeft = CATALOG_TERMINAL_PROBE_PAGES
        // 预算 3 的语义：出现终止信号后还能再探 3 页；第 4 个连续信号页才认输
        repeat(CATALOG_TERMINAL_PROBE_PAGES) {
            val d = chapterCatalogWalkDecision(
                isEmptyPage = false, pageRepeated = false, pageHasNewChapters = false,
                loaded = 80, expectedTotal = 160, probesLeft = probesLeft,
            )
            assertTrue(d.isSuspicious, "预算内（剩余 $probesLeft）应继续探测")
            probesLeft = d.probesLeft
        }
        assertEquals(0, probesLeft)
        val d = chapterCatalogWalkDecision(
            isEmptyPage = false, pageRepeated = false, pageHasNewChapters = false,
            loaded = 80, expectedTotal = 160, probesLeft = probesLeft,
        )
        assertTrue(d.isTerminal, "预算耗尽后应终止")
    }

    @Test
    fun `非空但零新章节也是终止信号`() {
        // 服务端把越界页 clamp 到非紧邻的已见页：不与上一页相同、也不是空页，
        // 但没有任何新内容——旧实现认不出这一形态，会对着同样的数据翻到页数上限
        val d = chapterCatalogWalkDecision(
            isEmptyPage = false, pageRepeated = false, pageHasNewChapters = false,
            loaded = 80, expectedTotal = 160, probesLeft = 3,
        )
        assertTrue(d.isSuspicious)
        assertEquals(2, d.probesLeft)

        // 预算耗尽后必须终止，而不是一路翻到 60 页上限
        val exhausted = chapterCatalogWalkDecision(
            isEmptyPage = false, pageRepeated = false, pageHasNewChapters = false,
            loaded = 80, expectedTotal = 160, probesLeft = 0,
        )
        assertTrue(exhausted.isTerminal)
    }

    @Test
    fun `clamp 回上一页在没拉够时继续探测`() {
        val d = chapterCatalogWalkDecision(
            isEmptyPage = false, pageRepeated = true, pageHasNewChapters = false,
            loaded = 80, expectedTotal = 160, probesLeft = 3,
        )
        assertTrue(d.isSuspicious)
        assertEquals(2, d.probesLeft)
    }

    @Test
    fun `服务端没报总数时空页直接结束`() {
        // expectedTotal = 0（未知）时无从判断"还差多少"，维持保守终止
        val d = chapterCatalogWalkDecision(
            isEmptyPage = true, pageRepeated = false, pageHasNewChapters = false,
            loaded = 20, expectedTotal = 0, probesLeft = 3,
        )
        assertTrue(d.isTerminal)
    }
}
