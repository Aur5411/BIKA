package com.shizq.bika.core.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import com.shizq.bika.core.network.BikaDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.random.Random

private val logger = KotlinLogging.logger("ChapterPagesPagingSource")

/**
 * 章节图片分页在 [load] 内部的即时重试次数。
 *
 * append 失败会让 Paging 停止续拉：[ChapterAppendRetryEffect] 虽会退避重试，但一旦
 * 用户此刻正停在那个边界上（章节约第 20 页之后就是最常见的落点），他看到的就是
 * 一整屏"加载失败"，要等好几秒才可能自愈。而这一类失败绝大多数只是一次网络抖动，
 * 在数据层原地重试几乎立刻就能过，用户完全感知不到。
 *
 * 取 5：抖动是瞬时的，但章节接口偶发会被限流/挡，多给两次余量能显著压低
 * "边界那一屏永久停在错误态"的概率。再多只是把真正的故障拖长。
 */
internal const val PAGE_LOAD_MAX_ATTEMPTS = 5

private const val PAGE_LOAD_RETRY_BASE_DELAY_MS = 350L
private const val PAGE_LOAD_RETRY_JITTER_MS = 250L

/**
 * 是否值得在 [load] 内部原地重试。
 *
 * 4xx（除 429）是语义明确的永久失败（参数不对、无权限、资源不存在），重试只是
 * 浪费请求；429、5xx，以及超时/连接被重置/DNS 失败这类传输层异常都是一次性的，
 * 值得重试。
 */
private fun Throwable?.isRetryablePageLoad(): Boolean {
    if (this == null) return true
    if (this is CancellationException) return false
    val message = message.orEmpty()
    // 从异常文本里提取 HTTP 状态码：本模块不依赖图片链路，拿不到带响应的异常类型，
    // 而 Ktor 与 OkHttp 抛出的异常文本里都带得状态码。
    val code = HTTP_STATUS_PATTERN.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (code != null) return code == 429 || code >= 500
    return true
}

private val HTTP_STATUS_PATTERN = Regex("""\b([1-5]\d\d)\b""")

/**
 * 带原地重试的取数。抽成独立函数是为了让它可单测——[ChapterPagesPagingSource]
 * 依赖 final 的 [BikaDataSource]，在单测里没法替换。
 *
 * @param fetch 真正的那一次网络请求
 * @param onRetry 每次**确实还会再试一次**时回调，参数是刚刚失败的是第几次尝试。
 *   用尽次数后的最后一次失败不再回调——那时没有"重试"，只有终止。
 * @param sleep 重试前的等待，注入以便测试用虚拟时间跳过
 */
internal suspend fun <T> fetchWithPageRetry(
    maxAttempts: Int = PAGE_LOAD_MAX_ATTEMPTS,
    fetch: suspend () -> T,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    sleep: suspend (millis: Long) -> Unit = { delay(it) },
): T {
    var lastError: Throwable? = null
    for (attempt in 0 until maxAttempts) {
        try {
            return fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            if (!e.isRetryablePageLoad()) throw e
            val remaining = maxAttempts - attempt - 1
            if (remaining > 0) {
                onRetry(attempt + 1, e)
                sleep(
                    PAGE_LOAD_RETRY_BASE_DELAY_MS * (attempt + 1) +
                            Random.nextLong(PAGE_LOAD_RETRY_JITTER_MS)
                )
            }
        }
    }
    throw lastError ?: IllegalStateException("请求失败")
}

class ChapterPagesPagingSource @AssistedInject constructor(
    @Assisted private val id: String,
    @Assisted("order") private val order: Int,
    @Assisted("initialApiPage") private val initialApiPage: Int,
    @Assisted private val metadata: MutableStateFlow<ChapterMeta?>,
    private val dataSource: BikaDataSource
) : PagingSource<Int, ChapterPage>() {

    override val jumpingSupported: Boolean = true

    @Volatile
    private var lastKnownLimit: Int? = null

    @Volatile
    private var lastKnownPages: Int? = null

    /**
     * 按需拉取一个 API 页，由 Paging 自己 append 续接。
     *
     * ## 为什么不一次性全量拉完
     *
     * 曾试过"一次拉完整章再作为单页全量返回"，目的是绕开 append 边界。但代价
     * 明显大于收益：章节几百张图时，用户要等**全部**数据请求回来才看到第一张，
     * 首屏是长时间空白。用户真正在意的只有眼前那一屏，逐页 append 天然流式——
     * 第 1 页回来即可渲染，后续页在滑动中补齐。
     *
     * 而且**图片加载速度与数据分几页拉无关**：真正决定快慢的是下载链路
     * （预载窗口 + 并发 + 换源 + 重试），数据层逐页拉不牺牲任何一点图片性能。
     */
    override suspend fun load(
        params: LoadParams<Int>
    ): LoadResult<Int, ChapterPage> {
        // initialApiPage 只在 REFRESH 且尚无 key 时生效（即 initial load）；
        // append/prepend 沿用 Paging 传入的 key，不受它影响。
        val requestedApiPage = params.key ?: initialApiPage

        // 数据层原地重试：失败一次就交给 UI 会让"正停在分页边界上的那一屏"永久停在
        // 错误态（见 PAGE_LOAD_MAX_ATTEMPTS 的说明）。重试逻辑见 fetchWithPageRetry。
        return try {
            fetchWithPageRetry(
                fetch = { loadOnce(requestedApiPage) },
                onRetry = { attempt, error ->
                    logger.warn(error) {
                        "章节分页第 $attempt/$PAGE_LOAD_MAX_ATTEMPTS 次失败，" +
                                "comic=$id order=$order 请求页=$requestedApiPage"
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "章节分页重试 $PAGE_LOAD_MAX_ATTEMPTS 次仍失败: comic=$id order=$order " +
                        "请求页=$requestedApiPage"
            }
            LoadResult.Error(e)
        }
    }

    private suspend fun loadOnce(
        requestedApiPage: Int,
    ): LoadResult<Int, ChapterPage> {
        return try {
            val response = dataSource.getChapterPages(id, order, requestedApiPage)
            val imagePages = response.imagePages
            val respPage = imagePages.page
            val limit = imagePages.limit
            val total = imagePages.total
            val docsSize = imagePages.docs.size

            if (respPage != requestedApiPage) {
                logger.warn {
                    "服务端返回页与请求页不一致: 请求=$requestedApiPage 实际=$respPage " +
                            "(可能是章节已缩水，或服务端对超范围请求做了 clamp)"
                }
            }

            // 中间出现空页但按 total 计算后面还应该有数据：数据源自相矛盾。
            //
            // 这不是网络抖动，重试同一页只会拿到同样的空响应，因此在 loadOnce 里
            // 直接以返回值的形态交出去（不抛异常 → 不被外层重试循环吞掉）。
            if (docsSize == 0 && limit > 0 && total > respPage * limit) {
                logger.error {
                    "章节分页返回空页但 total 显示后面仍有数据: page=$respPage total=$total limit=$limit"
                }
                return LoadResult.Error(
                    IllegalStateException(
                        "章节分页返回空页但 total 显示后面仍有数据: page=$respPage total=$total limit=$limit"
                    )
                )
            }

            metadata.value = ChapterMeta(title = response.chapterInfo.title, totalImages = total)
            lastKnownLimit = limit.takeIf { it > 0 }
            lastKnownPages = imagePages.pages.takeIf { it > 0 }

            val (itemsBefore, itemsAfter) = computePlaceholderCounts(
                respPage = respPage,
                limit = limit,
                total = total,
                docsSize = docsSize,
            )

            LoadResult.Page(
                // URL 畸形的图片（fileServer/path 缺失或 scheme 不对）会被丢弃：
                // 它们必然产出死链，在 UI 上表现为"某几页怎么重试都出不来"。
                // 详见 Media.safeImageUrl。
                data = imagePages.docs.mapNotNull { toChapterPageOrNull(it) },
                prevKey = if (respPage > 1) respPage - 1 else null,
                nextKey = if (respPage < imagePages.pages) respPage + 1 else null,
                itemsBefore = itemsBefore,
                itemsAfter = itemsAfter,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不再在这里吞掉异常：交给调用方决定是否原地重试。
            throw e
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ChapterPage>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null

        // 分支 A：锚点落在某个已加载页自身的绝对范围内——用该页自己的 key，
        // 对 limit 是否漂移天然免疫，是普通滚动触发 refresh 的正常路径。
        val itemsBefore = anchorPage.itemsBefore
        if (itemsBefore != LoadResult.Page.COUNT_UNDEFINED) {
            val rangeEnd = itemsBefore + anchorPage.data.size
            if (anchorPosition in itemsBefore until rangeEnd) {
                return anchorPage.currentApiPage()
            }
        }

        // 分支 B：远跳（恢复进度、拖动进度条），锚点在已加载窗口之外，只能靠 limit 反推。
        val limit = lastKnownLimit
        if (limit == null || limit <= 0) {
            return anchorPage.currentApiPage()
        }

        val computedPage = anchorPosition / limit + 1
        val upperBound = lastKnownPages ?: computedPage
        return computedPage.coerceIn(1, upperBound)
    }

    private fun LoadResult.Page<Int, ChapterPage>.currentApiPage(): Int? =
        prevKey?.plus(1) ?: nextKey?.minus(1)

    @AssistedFactory
    interface Factory {
        fun create(
            id: String,
            @Assisted("order") order: Int,
            @Assisted("initialApiPage") initialApiPage: Int,
            metadata: MutableStateFlow<ChapterMeta?>,
        ): ChapterPagesPagingSource
    }
}

/**
 * itemsBefore/itemsAfter 换算结果。任一字段为 [LoadResult.Page.COUNT_UNDEFINED]
 * 时应成对退化——不允许一个可信、另一个不可信，否则 itemCount 在该页前后会不一致。
 */
internal data class PlaceholderCounts(val itemsBefore: Int, val itemsAfter: Int)

/**
 * 根据单次响应自身的 respPage/limit/total/docsSize 计算 itemsBefore/itemsAfter。
 *
 * 所有入参均来自 [com.shizq.bika.core.network.model.PageData]，其字段套了
 * [com.shizq.bika.core.network.utils.LenientIntSerializer]（服务端偶发返回非整数），
 * 不可假设它们互相自洽，因此每一步都做边界钳制，绝不向 Paging3 传负数
 * （负数会直接抛 IllegalArgumentException，比"placeholder 数量不准"严重得多）。
 */
internal fun computePlaceholderCounts(
    respPage: Int,
    limit: Int,
    total: Int,
    docsSize: Int,
): PlaceholderCounts {
    if (limit <= 0 || total <= 0 || respPage <= 0) {
        return PlaceholderCounts(LoadResult.Page.COUNT_UNDEFINED, LoadResult.Page.COUNT_UNDEFINED)
    }

    val itemsBeforeRaw = (respPage - 1) * limit
    if (itemsBeforeRaw < 0) {
        logger.warn { "itemsBefore 计算为负: respPage=$respPage limit=$limit，字段不可信" }
        return PlaceholderCounts(LoadResult.Page.COUNT_UNDEFINED, LoadResult.Page.COUNT_UNDEFINED)
    }

    val itemsAfterRaw = total - itemsBeforeRaw - docsSize
    val itemsAfter = if (itemsAfterRaw < 0) {
        logger.warn { "itemsAfter 计算为负: total=$total itemsBefore=$itemsBeforeRaw docsSize=$docsSize" }
        0
    } else itemsAfterRaw

    return PlaceholderCounts(itemsBeforeRaw, itemsAfter)
}

/**
 * 把一张服务端图片映射成 [ChapterPage]；URL 畸形（fileServer/path 缺失或 scheme 不对）
 * 时返回 null。
 *
 * 抽成顶层纯函数是为了可单测——[ChapterPagesPagingSource] 依赖 final 的
 * [BikaDataSource]，无法在单测中构造。
 */
internal fun toChapterPageOrNull(
    image: com.shizq.bika.core.network.model.Image,
): ChapterPage? {
    val url = image.media.safeImageUrl
    if (url == null) {
        logger.warn {
            "丢弃 URL 畸形的图片: imageId=${image.imageId} " +
                    "fileServer=${image.media.fileServer} path=${image.media.path}"
        }
        return null
    }
    return ChapterPage(id = image.imageId, url = url)
}

data class ChapterMeta(
    val title: String,
    val totalImages: Int
)

data class ChapterPage(
    val id: String,
    val url: String,
)
