package com.shizq.bika.core.data.model

import com.shizq.bika.core.network.model.Episode

data class Chapter(
    val id: String,
    val order: Int,
    val title: String,
    val updatedAt: String
)

fun Episode.asExternalModel() = Chapter(
    id = id,
    order = order,
    title = title,
    updatedAt = updatedAt
)

/**
 * 漫画的完整章节目录（按 [Chapter.order] 升序排列）。
 *
 * 用于上下章导航的相邻章节查询：目录需要支持随机访问任意 order，
 * 分页窗口（[ChapterListPagingSource]）无法满足这一诉求。
 *
 * @param isComplete 是否已拉取完整目录。为 false 时 [navigationAt] 给出的
 *   相邻章节仅代表“已知范围内”的结果，不能用于判断“是否为第一章/最后一章”。
 */
data class ChapterCatalog(
    val chapters: List<Chapter>,
    val isComplete: Boolean,
) {
    companion object {
        val Empty = ChapterCatalog(chapters = emptyList(), isComplete = false)
    }
}

/**
 * 上下章导航信息：给定当前章节 order，得到最近的更小/更大 order 的章节。
 *
 * 用“最近的更小/更大 order”而非“索引 ±1”，是因为当前章节可能不在目录中
 * （例如仅下载模式下打开了一个未下载完成的章节），此时索引法会直接失效，
 * 而按 order 比较仍能给出正确的相邻项。
 */
data class ChapterNavigation(
    val prev: Chapter?,
    val next: Chapter?,
    val isResolved: Boolean,
) {
    companion object {
        val Unresolved = ChapterNavigation(prev = null, next = null, isResolved = false)
    }
}

fun ChapterCatalog.navigationAt(order: Int): ChapterNavigation = ChapterNavigation(
    prev = chapters.lastOrNull { it.order < order },
    next = chapters.firstOrNull { it.order > order },
    isResolved = isComplete,
)

/** 补齐条目用的 id 前缀。与真实章节 id 分开，便于日志里一眼看出哪些是补出来的。 */
private const val SYNTHETIC_CHAPTER_ID_PREFIX = "synthetic-order-"

/**
 * 服务端把章节目录截断时，为缺失的**前置**章节补出占位条目。
 *
 * ## 为什么需要
 *
 * 章节接口按"最新话在前"分页。实测长目录（100 话以上）会出现：服务端自报
 * `total=160`，但翻到第 4 页就返回空页 / 把越界页 clamp 回上一页，只给到 90 条。
 * 被截掉的正是最老的几十话——用户看到的是"160 话里缺了前 70 话"。
 *
 * 这种情况下目录缺的是**开头**，而 order 是连续的整数，缺失范围可以被唯一确定：
 * 已到手的若是 `71..160` 这样一整段连续 order，且缺的条数恰好等于前置 order 的个数，
 * 就能确定缺的就是 `1..70`。阅读器定位章节靠的是 `order`（图片接口是
 * `/comics/{id}/order/{order}/pages`），所以补出的条目是**能直接点开读的**。
 *
 * ## 只在推断无歧义时才补
 *
 * 三个条件必须同时成立，任一不满足就原样返回：
 * 1. 服务端自报总数 [expectedTotal] 大于已到手条数；
 * 2. 已到手的 order 是**一整段连续区间**（没有中间空洞）——否则"缺的都在最前面"不成立；
 * 3. 缺的条数**正好等于**前置 order 的个数。
 *
 * 宁可少补也不乱补：补错的条目会让用户点进去看到加载失败，比"少了条目"更糟。
 *
 * @param expectedTotal 服务端自报的章节总数；0 或负数表示未知，直接原样返回
 */
fun ChapterCatalog.fillMissingLeadingOrders(expectedTotal: Int): ChapterCatalog {
    if (expectedTotal <= 0 || chapters.isEmpty() || chapters.size >= expectedTotal) return this

    val minOrder = chapters.minOf { it.order }
    val maxOrder = chapters.maxOf { it.order }
    // 条件 2：必须连续。带空洞说明缺的不止在开头，推断不成立
    if (maxOrder - minOrder + 1 != chapters.size) return this
    // 条件 3：缺的条数要正好落在开头那一段
    if (minOrder - 1 != expectedTotal - chapters.size) return this

    val synthesized = (1 until minOrder).map { order ->
        Chapter(
            id = "$SYNTHETIC_CHAPTER_ID_PREFIX$order",
            order = order,
            // 标题服务端没给，只能本地生成。数据层拿不到 Context / 字符串资源，
            // 这里是唯一一处硬编码的用户可见文案，而且只在兜底路径上出现
            title = "第 $order 話",
            updatedAt = "",
        )
    }

    return copy(chapters = synthesized + chapters, isComplete = true)
}
