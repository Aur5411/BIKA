package com.shizq.bika.feature.comicdetail.impl.episodes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shizq.bika.core.data.model.Chapter
import com.shizq.bika.core.database.model.ChapterProgressEntity
import com.shizq.bika.core.ui.ErrorState
import com.shizq.bika.core.ui.LoadingState
import com.shizq.bika.feature.comicdetail.impl.R
import kotlinx.coroutines.launch

/** 距离列表两端多少行之内就不再显示对应的跳转按钮。 */
private const val SCROLL_BUTTON_THRESHOLD_ITEMS = 8

private const val EPISODE_CONTENT_TYPE = "episode"
private const val HEADER_CONTENT_TYPE = "header"

@Composable
fun EpisodesPage(
    state: EpisodeListState,
    modifier: Modifier = Modifier,
    chapterProgress: List<ChapterProgressEntity> = emptyList(),
    onEpisodeClick: (Chapter) -> Unit = {},
    onToggleSortOrder: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onDownloadSelectionClick: () -> Unit = {},
) {
    // 整屏转圈只在"一批都还没到手"时出现。一旦有内容就立刻铺出去，
    // 后面几批用列表末尾的一行提示带过——把用户按在整屏转圈上等整本齐活，
    // 正是"100 多话的漫画显得加载不出来"的直接原因
    if (state.isLoading && state.chapters.isEmpty()) {
        LoadingState(modifier = modifier.fillMaxSize())
        return
    }

    if (state.loadFailed) {
        // 不用"残缺列表 + 一行小字"糊过去：目录页要么完整、要么明确失败可重试
        ErrorState(
            onRetry = onRetryLoad,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    // 键是 ChapterProgressEntity.chapterId，而它写入时存的就是章节 order
    // （见 ReadingProgressStore），因此按 chapterId 建表、用实体的 order 取值是正确的。
    val progressByOrder = remember(chapterProgress) {
        chapterProgress.associateBy { it.chapterId }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // derivedStateOf：让"是否显示回到顶部/底部"只在跨过阈值时翻转一次，
    // 而不是滚动过程中每一帧都驱动整个 EpisodesPage 重组
    val showScrollToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex >= SCROLL_BUTTON_THRESHOLD_ITEMS }
    }
    // 与"回到顶部"用同一个阈值，两个按钮的出现时机才对称：
    // 顶端只有「到底」，中段两个都在，底部只剩「到顶」
    val showScrollToBottom by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            lastVisibleIndex <= info.totalItemsCount - 1 - SCROLL_BUTTON_THRESHOLD_ITEMS
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                // 底部留出操作区的高度，否则最后一行会被压住。
                // 边到边：16(外边距) + 4×56(按钮) + 3×12(间距) = 276，再留 8 呼吸位。
                // 按"四个都在"算：按钮由 Column 底部对齐，隐藏上面的按钮不会让下面的
                // 按钮下移，所以按最多的情况预留一定够，且不会随显隐抖动
                bottom = 284.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(
                span = { GridItemSpan(maxLineSpan) },
                key = "episodeCountHeader",
                contentType = HEADER_CONTENT_TYPE,
            ) {
                // 把"到底加载了多少话"摆在明面上：目录不完整时用户一眼能发现，
                // 而不是滑到中段才隐约觉得少了。加载中写"已加载"而不是"共"——
                // "共 20 话"在只拉到第一批时是假话
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                if (state.isLoadingMore) {
                                    R.string.episode_loaded_count
                                } else {
                                    R.string.episode_total_count
                                },
                                state.chapters.size
                            )
                        )
                        append(" · ")
                        append(
                            stringResource(
                                if (state.sortOrder == ChapterSortOrder.DESCENDING) {
                                    R.string.episode_order_descending
                                } else {
                                    R.string.episode_order_ascending
                                }
                            )
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }

            items(
                items = state.chapters,
                // key 只用 id：遍历侧已做跨页去重，id 在列表内唯一
                key = { chapter -> chapter.id },
                // 全部 item 同一类型，Compose 可以跨位复用，避免长目录下反复新建组合
                contentType = { EPISODE_CONTENT_TYPE }
            ) { chapter ->
                EpisodeItem(
                    text = chapter.title,
                    progress = progressByOrder[chapter.order],
                    onClick = onEpisodeClick,
                    chapter = chapter
                )
            }

            if (state.isLoadingMore) {
                item(
                    span = { GridItemSpan(maxLineSpan) },
                    key = "episodeLoadingMore",
                    contentType = HEADER_CONTENT_TYPE,
                ) {
                    Text(
                        text = stringResource(R.string.episode_loading_more),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }

        // 右下角操作区，自上而下：回到顶部 / 回到底部 / 正序倒序 / 下载。
        //
        // 四个按钮严格同款：同尺寸（默认 56dp）、同容器色（primaryContainer）、同间距、
        // 图标统一取 Rounded 家族 —— 不同家族的图标放在一起笔画轻重不一致，
        // 竖排时"哪个更粗"会显得其中一个像另一个的强调态，视觉上很吵。
        //
        // 两个滚动按钮相邻并成对出现：它们是一组同类操作（跳到列表两端），
        // 中间插进排序按钮会让人以为排序和"到底"有什么关系。
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // 16dp 是 Material 对悬浮按钮的安全边距；原先 24dp 会让整列
                // 无谓地往里缩，也更容易压住列表内容
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerticalAlignTop,
                        contentDescription = stringResource(R.string.episode_scroll_to_top)
                    )
                }
            }

            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            // 目录是渐进加载的，追加新批次会让总条目数变大；
                            // 这里每次点击都重新取一次总数，避免滚到"上一批的末尾"
                            val lastIndex = gridState.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) gridState.animateScrollToItem(lastIndex)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerticalAlignBottom,
                        contentDescription = stringResource(R.string.episode_scroll_to_bottom)
                    )
                }
            }

            SortOrderButton(
                sortOrder = state.sortOrder,
                onClick = {
                    onToggleSortOrder()
                    // 重新排序后停留在原索引没有意义，直接回到顶部
                    scope.launch { gridState.scrollToItem(0) }
                }
            )

            FloatingActionButton(
                onClick = onDownloadSelectionClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.download_select_action)
                )
            }
        }
    }
}

/**
 * 正序 / 倒序切换。
 *
 * 纯图标 FAB，与下面的下载按钮同款同尺寸，三个悬浮按钮排成一列不显得突兀。
 *
 * 图标用「双向箭头」（[Icons.Rounded.SwapVert]）而不是单向上/下箭头：后者一眼看过去
 * 会被读成"向上滚 / 向下滚"，正好和上面的「回到顶部」（`VerticalAlignTop`，竖线+上箭头）
 * 撞在一起，让人以为是"一键到顶 / 一键到底"。双向箭头表达的是"把顺序翻过来"，
 * 只说这件事，不会被当成滚动操作。
 *
 * 点击后的反馈来自列表本身——整份目录立刻从「第 1 话在最上」翻成「最新话在最上」，
 * 这是比图标变化更明确的信号。图标不表达当前状态，因此 [contentDescription] 必须
 * 写明当前顺序：它是读屏与自动化测试唯一能拿到状态的出口。
 */
@Composable
private fun SortOrderButton(
    sortOrder: ChapterSortOrder,
    onClick: () -> Unit,
) {
    val isDescending = sortOrder == ChapterSortOrder.DESCENDING

    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Icon(
            imageVector = Icons.Rounded.SwapVert,
            contentDescription = stringResource(
                if (isDescending) {
                    R.string.episode_sort_descending
                } else {
                    R.string.episode_sort_ascending
                }
            )
        )
    }
}

@Preview(showBackground = true, name = "Episodes Page Preview")
@Composable
private fun EpisodesPagePreview() {
    val fakeChapters = List(20) { i ->
        Chapter(
            id = i.toString(),
            title = "第 ${i + 1} 话",
            order = i + 1,
            updatedAt = ""
        )
    }

    MaterialTheme {
        EpisodesPage(
            state = EpisodeListState(chapters = fakeChapters, isLoading = false)
        )
    }
}

@Preview(showBackground = true, name = "Episodes Page Descending Preview")
@Composable
private fun EpisodesPageDescendingPreview() {
    val fakeChapters = List(120) { i ->
        Chapter(
            id = i.toString(),
            title = "第 ${i + 1} 话",
            order = i + 1,
            updatedAt = ""
        )
    }

    MaterialTheme {
        EpisodesPage(
            state = EpisodeListState(
                chapters = fakeChapters.applySortOrder(ChapterSortOrder.DESCENDING),
                sortOrder = ChapterSortOrder.DESCENDING,
                isLoading = false,
            )
        )
    }
}
