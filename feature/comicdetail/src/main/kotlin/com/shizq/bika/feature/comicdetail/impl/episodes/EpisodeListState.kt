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
 * 做成一个 [Immutable] 容器而不是拆成多个 StateFlow：拆开之后列表、排序、失败标记
 * 会各自触发一轮重组，长目录下这类重复重组肉眼可见。
 *
 * @param chapters 当前已到手的章节（按 [sortOrder] 排好）。
 *   加载中是**渐进增长**的：第一个网络来回回来就有前 20 话，后面逐批补齐
 * @param sortOrder 当前排序方式
 * @param isLoading 一批都还没到手。界面应显示整屏加载，而不是先说"没有章节"
 * @param isLoadingMore 已有内容、后续批次还在路上。界面在列表末尾提示"正在加载剩余章节"，
 *   而不是把用户按在整屏转圈上等
 * @param loadFailed 加载失败（含单页重试耗尽、整体超时）。此时 [chapters] 为空，
 *   界面应给出重试入口，而不是展示一个残缺列表
 */
@Immutable
data class EpisodeListState(
    val chapters: List<Chapter> = emptyList(),
    val sortOrder: ChapterSortOrder = ChapterSortOrder.ASCENDING,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
) {
    companion object {
        val Empty = EpisodeListState()
    }
}
