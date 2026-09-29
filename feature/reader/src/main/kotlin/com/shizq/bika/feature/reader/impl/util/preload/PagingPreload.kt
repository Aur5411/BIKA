package com.shizq.bika.feature.reader.impl.util.preload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.paging.ItemSnapshotList
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.combine

@Composable
fun <T : Any> PagingPreload(
    pagingItems: LazyPagingItems<T>,
    scrollStateProvider: ScrollStateProvider,
    modelProvider: PreloadModelProvider<T>,
    preloadCount: Int,
) {
    val context = LocalContext.current
    val currentPreloadCount by rememberUpdatedState(preloadCount)

    // scrollStateProvider 是 key：它随章节重建（rememberReaderContext 里包了
    // key(chapterOrder)），据此重建预载会话，避免跨章节复用旧的方向和请求窗口。
    // 不能仅以 pagingItems 为 key：章节切换时它的引用可能不变。
    LaunchedEffect(context, pagingItems, scrollStateProvider, modelProvider) {
        val enqueuer = CoilPreloadRequestEnqueuer(context, this)
        val session = ReaderPreloadSession(
            scope = this,
            dataProvider = PagingPreloadDataProvider(pagingItems),
            modelProvider = modelProvider,
            enqueuer = enqueuer,
            closeEnqueuer = enqueuer::close,
        )
        // 预载窗口越过 Paging 已加载范围末尾时，主动催一次续拉。
        //
        // 历史背景：旧实现按 API 页 append 续拉，预载窗口一旦越过已加载末尾，
        // 窗口内的页全部取不到 item，预载器只能干等；用户滑过去才是第一次请求，
        // 表现为"翻过几十页后开始一张一张等"。
        //
        // 现状：ChapterPagesPagingSource 已改为**一次性拉取整章**并作为单页全量
        // 返回，不存在"后续页还没加载"的状态，这个回调自然不再触发（append 恒为
        // NotLoading，且 starvationCount 不会增长）。保留它是为了不破坏
        // ReaderPreloadSession 的接口契约，也作为将来若改回增量加载的兜底。
        session.onStarvation = {
            // 已经在加载中就不要重复催：Paging 的 append 是串行的，重复调用没有增益。
            if (pagingItems.loadState.append !is LoadState.Loading) {
                pagingItems.retry()
            }
        }
        try {
            val viewportEvents = scrollStateProvider.viewportEvents
            var previousSnapshot: ItemSnapshotList<T>? = null
            var previousSnapshotInitialized = false
            combine(
                viewportEvents,
                snapshotFlow { pagingItems.itemSnapshotList },
                snapshotFlow { currentPreloadCount },
            ) { viewport, snapshot, count -> Triple(viewport, snapshot, count) }
                .collect { (viewport, snapshot, count) ->
                    val dataChanged = previousSnapshotInitialized && snapshot != previousSnapshot
                    previousSnapshot = snapshot
                    previousSnapshotInitialized = true
                    val effectiveViewport = if (dataChanged) {
                        viewport.copy(cause = ViewportChangeCause.DataRefresh)
                    } else {
                        viewport
                    }
                    session.submitViewport(effectiveViewport, count)
                }
        } finally {
            // Closing the session cancels the worker and releases pending downloads.
            session.close()
        }

    }
}

private class PagingPreloadDataProvider<T : Any>(
    private val pagingItems: LazyPagingItems<T>
) : PreloadDataProvider<T> {
    override val itemCount: Int get() = pagingItems.itemCount
    override fun getItem(index: Int): T? = pagingItems.peek(index)
}
