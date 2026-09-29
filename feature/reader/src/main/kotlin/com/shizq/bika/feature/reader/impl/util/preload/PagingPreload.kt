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
        // 这是一个"用户明明还没滑到、我们却应该提前拉"的缺口：预载窗口的页如果
        // 还不在 Paging 的 itemCount 里，预载器一条请求都发不出来（取不到 item），
        // 只能干等。用户滑到那里时才是第一次请求——那正是"翻过 20 页之后开始
        // 一张一张等"的来源。这里在检测到窗口落空时把续拉推起来，让预载重新
        // 有东西可下。
        //
        // 分页边界（章节约 40 张图一个 API 页）是它最主要的触发点。
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
