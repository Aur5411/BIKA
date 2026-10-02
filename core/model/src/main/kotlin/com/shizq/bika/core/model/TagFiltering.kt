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

/** 该漫画的标签里是否有任何一个命中屏蔽列表（归一化后比较）。 */
fun ComicSummary.matchesBlockedTags(blockedTags: Set<String>): Boolean {
    if (blockedTags.isEmpty()) return false
    val blocked = blockedTags.map { it.normalizedTag() }.toSet()
    return tags.any { it.normalizedTag() in blocked }
}
