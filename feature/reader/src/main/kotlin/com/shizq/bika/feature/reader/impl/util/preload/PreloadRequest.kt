package com.shizq.bika.feature.reader.impl.util.preload

import android.content.Context
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

interface PreloadModelProvider<T> {
    fun getPreloadRequest(item: T): ImageRequest?

    /** 阅读会话内的稳定页面身份；不能使用列表下标。 */
    fun getPreloadKey(item: T, request: ImageRequest): String =
        request.diskCacheKey ?: request.data.toString()
}

interface PreloadDataProvider<T> {
    /** 列表中的项目总数。 */
    val itemCount: Int

    /** 获取指定位置的项目，如果该位置不可用，则返回 null。 */
    fun getItem(index: Int): T?
}

/** 预载请求的投递出口。 */
interface PreloadRequestEnqueuer {
    fun updateWindow(
        requests: List<PreloadRequest>,
        visibleRequests: List<PreloadRequest> = emptyList(),
    )

    /**
     * 队列因"窗口内无可用数据"而落空的累计次数。
     *
     * 用于让上层发现"预载饿死"（见 [PreloadQueue.starvationCount]）并主动推动
     * Paging 续拉，而不是被动等用户滑到那里才第一次请求。
     */
    val starvationCount: Int
}

/**
 * 阅读会话内的预载任务。
 *
 * [index] 只用于判断任务是否进入可见区域；[key] 才是队列去重和保留任务的身份。
 */
data class PreloadRequest(
    val index: Int,
    val key: String,
    val request: ImageRequest,
)

/**
 * 预载并发数。
 *
 * 与图片客户端对同一域名的并发上限（见 NetworkModule 的
 * `IMAGE_MAX_REQUESTS_PER_HOST`）留出余量：预载、当前可见页、进度条预览图
 * 共用那一个额度，预载只该占其中一部分，否则会把"用户正在看的那一张"挤到后面。
 *
 * 取 12：扫读时预载窗口是 16 页（见 AdaptivePreloadPolicy），并发低于窗口宽度
 * 时窗口根本喂不满——排在窗口末尾的页要等前面的下完才开始，翻到那里就露加载态。
 * 12 与窗口同量级，同时给可见页、进度条预览留下大部分额度（同域名上限 32）。
 *
 * 原先是 2——且因为 [PreloadQueue] 当时给了默认值、这里没传参，那个 2 是隐形的；
 * 后来提到 5，仍小于窗口宽度 10，是"窗口调大了但没跟着调并发"的残留。
 */
private const val PRELOAD_CONCURRENCY = 12

internal class CoilPreloadRequestEnqueuer(
    context: Context,
    scope: CoroutineScope,
) : PreloadRequestEnqueuer {
    private val logger = KotlinLogging.logger {}
    private val imageLoader = context.imageLoader
    private val queue = PreloadQueue(
        scope = scope,
        maxConcurrent = PRELOAD_CONCURRENCY,
        keyOf = PreloadRequest::key,
        execute = { preload ->
            try {
                when (imageLoader.execute(preload.request)) {
                    is SuccessResult -> true
                    is ErrorResult -> false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn(error) {
                    "预载失败，留给可见页请求重试"
                }
                false
            }
        },
    )

    override fun updateWindow(
        requests: List<PreloadRequest>,
        visibleRequests: List<PreloadRequest>,
    ) {
        queue.update(
            items = requests,
            retainRunning = visibleRequests.mapTo(mutableSetOf(), PreloadRequest::key),
        )
    }

    /**
     * 预载窗口是否因为"窗口内的页还取不到数据"而一次次落空。
     *
     * 见 [PreloadQueue.starvationCount]：这不是预载失败，而是窗口越过了 Paging
     * 已加载范围的边界，此时队列空转、无人去准备用户即将看到的那些页。
     * 上层据此主动催一次续拉。
     */
    override val starvationCount: Int get() = queue.starvationCount

    fun close() = queue.close()
}
