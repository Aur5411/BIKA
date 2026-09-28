package com.shizq.bika.feature.comicdetail.impl.episodes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.draw.rotate
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

/** 滚动多少行之后才认为"用户已经离开顶部"，据此显示回到顶部按钮。 */
private const val SCROLL_TO_TOP_THRESHOLD_ITEMS = 8

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
    if (state.isLoading) {
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

    // derivedStateOf：让"是否显示回到顶部"只在跨过阈值时翻转一次，
    // 而不是滚动过程中每一帧都驱动整个 EpisodesPage 重组
    val showScrollToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex >= SCROLL_TO_TOP_THRESHOLD_ITEMS }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                // 底部留出三个悬浮按钮叠加的高度（3×56 + 2×12 + 24 边距），
                // 否则最后一行会被操作区盖住
                bottom = 232.dp
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
                // 而不是滑到中段才隐约觉得少了
                Text(
                    text = stringResource(R.string.episode_total_count, state.chapters.size),
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
        }

        // 右下角操作区：回到顶部 / 正序倒序 / 下载
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
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
                        imageVector = Icons.Default.VerticalAlignTop,
                        contentDescription = stringResource(R.string.episode_scroll_to_top)
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

            // 下载选择：纯图标 FAB，与上面两个同色系，保持原有样式
            FloatingActionButton(
                onClick = onDownloadSelectionClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.download_select_action)
                )
            }
        }
    }
}

/**
 * 正序 / 倒序切换。
 *
 * 用带文字的 ExtendedFAB 而不是纯图标：纯图标下"点完到底生效没有"完全看不出来
 * （图标只是翻了个方向，很容易被当成没反应）。文字直接写明当前顺序，
 * 点一下就能看到「正序」变「倒序」，状态是否生效一目了然。
 * 配色与下载按钮完全一致，仍是同一族的悬浮按钮。
 */
@Composable
private fun SortOrderButton(
    sortOrder: ChapterSortOrder,
    onClick: () -> Unit,
) {
    val isDescending = sortOrder == ChapterSortOrder.DESCENDING
    // 翻转时把"排序"图标转 180°：同一套图标下方向即语义
    val iconRotation by animateFloatAsState(
        targetValue = if (isDescending) 180f else 0f,
        label = "episodeSortIconRotation"
    )

    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                // 文字已经把当前顺序说清楚了，图标不重复念一遍
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(iconRotation)
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (isDescending) {
                        R.string.episode_sort_descending
                    } else {
                        R.string.episode_sort_ascending
                    }
                ),
                style = MaterialTheme.typography.labelLarge
            )
        },
    )
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
