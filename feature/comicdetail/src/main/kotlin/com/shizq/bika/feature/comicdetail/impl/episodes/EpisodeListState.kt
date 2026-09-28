package com.shizq.bika.feature.comicdetail.impl.episodes

import androidx.compose.runtime.Immutable
import com.shizq.bika.core.data.model.Chapter

/**
 * 章节目录的排序方式。
 *
 * 只作用于展示顺序，不影响章节身份：跳转阅读器传的是 `Chapter.order`，
 * 因此倒序展示时点第 1 行依然是"最新一话"，上下章导航也不会错位。
 */
enum class ChapterSortOrder {
    /** 正序：第 1 话 → 最新一话 */
    ASCENDING,

    /** 倒序：最新一话 → 第 1 话 */
    DESCENDING;

    fun toggled(): ChapterSortOrder = if (this == ASCENDING) DESCENDING else ASCENDING
}

/**
 * 按 [order] 排列章节。
 *
 * 抽成独立函数是为了能被单测直接钉住——"点排序按钮没反应"这种问题一旦发生，
 * 最难分的恰恰是"状态没变"还是"状态变了但展示顺序没变"。把它变成纯函数之后，
 * 这两件事可以分开验证。
 */
fun List<Chapter>.applySortOrder(order: ChapterSortOrder): List<Chapter> = when (order) {
    // asReversed() 是视图而非拷贝：1000 话的目录翻转是 O(1)，
    // 不产生一次全量列表分配
    ChapterSortOrder.ASCENDING -> this
    ChapterSortOrder.DESCENDING -> asReversed()
}

/**
 * 章节目录页的完整状态。
 *
 * 做成一个 [Immutable] 容器而不是拆成多个 StateFlow：目录是**一次性拉全**的，
 * 状态机只有「加载中 → 成功 / 失败」三种落脚点。拆开之后列表、排序、失败标记
 * 会各自触发一轮重组，长目录下这类重复重组肉眼可见。
 *
 * @param chapters 当前展示顺序下的完整章节列表。**一定保证是完整的**：
 *   不做"先给一部分、边滚边补"的渐进渲染，避免出现"看起来齐了其实少一截"
 * @param sortOrder 当前排序方式
 * @param isLoading 加载尚未结束
 * @param loadFailed 加载失败（含中途某页重试耗尽）。此时 [chapters] 为空，
 *   界面应给出重试入口，而不是展示一个残缺列表
 */
@Immutable
data class EpisodeListState(
    val chapters: List<Chapter> = emptyList(),
    val sortOrder: ChapterSortOrder = ChapterSortOrder.ASCENDING,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
) {
    companion object {
        val Empty = EpisodeListState()
    }
}
