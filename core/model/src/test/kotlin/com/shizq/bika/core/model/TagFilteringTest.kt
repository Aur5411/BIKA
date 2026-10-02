package com.shizq.bika.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 屏蔽标签匹配的回归测试。
 *
 * 最重要的一条是 [屏蔽词命中 categories 时也必须被拦下]：
 * 详情页把 `tags + categories` 合并成一片标签云展示，也允许长按其中任意一个
 * 加入屏蔽列表。用户在详情页屏蔽"百合""校园"这类词时，屏蔽的其实是
 * `categories` 里的值——如果匹配时只看 `tags`，这些屏蔽条目永远不命中，
 * 表现就是"屏蔽了还在列表里"。
 */
class TagFilteringTest {

    private fun comic(
        tags: List<String> = emptyList(),
        categories: List<String> = emptyList(),
    ) = ComicSummary(
        id = "c1",
        title = "标题",
        author = "作者",
        tags = tags,
        categories = categories,
        image = RemoteImage(),
    )

    @Test
    fun `屏蔽列表为空时不拦任何条目`() {
        assertFalse(comic(tags = listOf("冒险")).matchesBlockedTags(emptySet()))
    }

    @Test
    fun `屏蔽词命中 tags 时拦下`() {
        assertTrue(comic(tags = listOf("冒险", "校园")).matchesBlockedTags(setOf("冒险")))
    }

    @Test
    fun `屏蔽词命中 categories 时也必须被拦下`() {
        // 这条就是"屏蔽了还在"的根因：只匹配 tags 的话下面必然 false
        assertTrue(comic(categories = listOf("百合", "校园")).matchesBlockedTags(setOf("百合")))
    }

    @Test
    fun `tags 与 categories 任一命中即拦下`() {
        val subject = comic(tags = listOf("热血"), categories = listOf("校园"))
        assertTrue(subject.matchesBlockedTags(setOf("热血")))
        assertTrue(subject.matchesBlockedTags(setOf("校园")))
        assertFalse(subject.matchesBlockedTags(setOf("冒险")))
    }

    @Test
    fun `大小写差异不漏网`() {
        assertTrue(comic(tags = listOf("Nakadashi")).matchesBlockedTags(setOf("nakadashi")))
        assertTrue(comic(tags = listOf("nakadashi")).matchesBlockedTags(setOf("Nakadashi")))
    }

    @Test
    fun `首尾空格不漏网`() {
        assertTrue(comic(categories = listOf(" 百合 ")).matchesBlockedTags(setOf("百合")))
        assertTrue(comic(categories = listOf("百合")).matchesBlockedTags(setOf(" 百合 ")))
    }

    @Test
    fun `屏蔽词自身的大小写与空格不影响命中`() {
        // 屏蔽词录成 " NAKADASHI "，标签是 "nakadashi"，仍要命中
        assertTrue(comic(tags = listOf("nakadashi")).matchesBlockedTags(setOf(" NAKADASHI ")))
    }

    @Test
    fun `语言不同的屏蔽词不误伤`() {
        // "lily" 与中文分类"百合"是同一个词，但服务端就是分开写的，
        // 这里只保证归一化不做跨语言转换，不制造误伤也不假装能匹配。
        assertFalse(comic(categories = listOf("百合")).matchesBlockedTags(setOf("lily")))
    }

    @Test
    fun `只有空白字符的屏蔽词视为未屏蔽`() {
        // 空串归一化后是 ""，不能让它变成"匹配一切"的万能筛
        assertFalse(comic(categories = listOf("百合")).matchesBlockedTags(setOf("   ")))
    }

    @Test
    fun `不相关标签不拦`() {
        assertFalse(
            comic(tags = listOf("热血"), categories = listOf("校园"))
                .matchesBlockedTags(setOf("冒险", "百合"))
        )
    }

    @Test
    fun `normalizedTag 去空格并转小写`() {
        assertTrue("  Nakadashi  ".normalizedTag() == "nakadashi")
    }
}
