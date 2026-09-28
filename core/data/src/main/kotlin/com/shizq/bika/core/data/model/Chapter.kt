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
 * @param declaredTotal 服务端自报的章节总数，0 表示未知。
 *
 *   **上下章导航靠它而不是靠"目录里恰好有没有这一条"来判断边界。** 目录可能是
 *   「还在加载」或「服务端把长目录截断了」，此时目录里缺的那一条并不代表它不存在；
 *   只有"当前 order 已经到总数上限"才说明真的没有下一章了。
 */
data class ChapterCatalog(
    val chapters: List<Chapter>,
    val isComplete: Boolean,
    val declaredTotal: Int = 0,
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
 *
 * @param isResolved 目录是否已拉全。为 false 时 [prev] / [next] 里可能是按 order
 *   推导出来的条目（服务端自报总数说明它存在，但目录里还没拿到），仅表示
 *   “按顺序应该还有这一话”，不能据此断定它是目录中的真实条目。
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

/**
 * 求当前章节的上下章。
 *
 * ## 为什么缺的那一条也要给出来
 *
 * 原先只在"目录里恰好有 order 更大的那一条"时才认为有下一章。但目录有两种情况
 * 会缺条目：**还在加载**，或者**服务端把长目录截断了**（实测 160 话只给 90 话）。
 * 这两种情况下 `next` 都是 null，阅读器据此把「下一章」按钮置灰——明明按顺序
 * 还有下一话，却点不动；自动衔接章节也跟着失效。
 *
 * 章节 order 是连续整数，"下一章"就是 `order + 1`，这件事**不需要等目录**：
 * 只要服务端自报的章节总数 [ChapterCatalog.declaredTotal] 说明这一话确实存在，
 * 就按 order 把它补出来。
 *
 * ## 首尾边界
 *
 * 边界一律由 `order` 与总数决定，不依赖目录里有没有条目：
 * - 第一话（`order == 1`）没有上一章：`order - 1 == 0`，直接给 null；
 * - 最后一话（`order == declaredTotal`）没有下一章：`order + 1` 超出自报总数，给 null。
 *
 * ## 目录拉全后不再推导
 *
 * [ChapterCatalog.isComplete] 为 true 时目录本身就是权威，此时**一律不推导**，
 * 相邻章节只从目录里查。否则服务端 `total` 虚高（实测会发生）时会凭空多出一话
 * 并不存在的"下一章"，用户点进去只会看到加载失败。
 *
 * 服务端连总数都没给时（`declaredTotal <= 0`）同样退回原行为，不影响下载模式等场景。
 */
fun ChapterCatalog.navigationAt(order: Int): ChapterNavigation = ChapterNavigation(
    prev = chapters.lastOrNull { it.order < order } ?: synthesizedNeighbor(order - 1),
    next = chapters.firstOrNull { it.order > order } ?: synthesizedNeighbor(order + 1),
    isResolved = isComplete,
)

/**
 * 按 order 推一个相邻章节。
 *
 * 三重前提缺一不可：目录**没拉全**（否则目录说了算）、服务端**给了总数**、
 * 而且目标 order 确实落在 1..总数 之内（首尾边界就是靠最后这条判掉的）。
 */
private fun ChapterCatalog.synthesizedNeighbor(candidateOrder: Int): Chapter? {
    if (isComplete) return null
    if (candidateOrder < 1) return null
    if (declaredTotal <= 0 || candidateOrder > declaredTotal) return null
    return syntheticChapter(candidateOrder)
}

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
 * **不会改动 [ChapterCatalog.isComplete]**：补齐只是把 order 区间补完整，不代表
 * 服务端的目录已经拉全（边加载边补齐时尤其如此），这个标记留给调用方按实际情况给。
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

    val synthesized = (1 until minOrder).map { order -> syntheticChapter(order) }

    return copy(chapters = synthesized + chapters)
}

/**
 * 构造一个"按 order 补出来"的占位章节。
 *
 * 服务端没给标题，只能本地生成——数据层拿不到 Context / 字符串资源，
 * 这是唯一一处硬编码的用户可见文案，而且只在兜底路径（目录被截断、或目录还没加载完
 * 但需要给出上下章）上出现。阅读器定位章节靠的是 `order`，所以补出的条目能直接点开读。
 */
private fun syntheticChapter(order: Int) = Chapter(
    id = "$SYNTHETIC_CHAPTER_ID_PREFIX$order",
    order = order,
    title = "第 $order 話",
    updatedAt = "",
)
