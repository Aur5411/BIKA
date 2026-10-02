package com.shizq.bika.core.model

/**
 * 标签屏蔽的匹配辅助。
 *
 * 服务端返回的标签与用户录入的屏蔽词之间**不能假设逐字一致**：同一位作者会写
 * `Nakadashi` 也会写 `nakadashi`，个别标签还带首尾空格。屏蔽列表用精确 `in`
 * 比较时这些变体全部漏网——用户看到的正是"有的屏蔽了还在"。
 *
 * 统一按「去首尾空格 + 忽略大小写」匹配。标签都是短词，归一化不会引入误伤：
 * 两个不同标签几乎不可能只差大小写或首尾空格。
 */
fun String.normalizedTag(): String = trim().lowercase()

/**
 * 该漫画是否命中屏蔽列表。
 *
 * **必须同时看 [ComicSummary.tags] 和 [ComicSummary.categories]**：
 * 详情页把两者合并成一片标签云展示（`detail.tags + detail.categories`），
 * 也允许长按其中任意一个加入屏蔽列表。所以用户从详情页屏蔽"百合""校园"
 * 这类词时，屏蔽的其实是 [categories] 里的值——只匹配 [tags] 会让这些屏蔽
 * 条目永远不命中，表现就是"屏蔽了还在列表里"。
 */
fun ComicSummary.matchesBlockedTags(blockedTags: Set<String>): Boolean {
    if (blockedTags.isEmpty()) return false
    val blocked = blockedTags.map { it.normalizedTag() }.toSet()
    if (blocked.isEmpty()) return false
    return tags.any { it.normalizedTag() in blocked } ||
            categories.any { it.normalizedTag() in blocked }
}
