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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * 取 3：抖动是瞬时的，两次重试足够覆盖；再多只是把真正的故障拖长。
 */
private const val PAGE_LOAD_MAX_ATTEMPTS = 3

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

    /**
     * 一次性把整个章节拉完，返回单页全量结果。
     *
     * ## 为什么不再按需 append
     *
     * 旧实现每次只拉一个 API 页，靠 Paging 的 append 逐页续接。这条路有三个致命
     * 耦合，且都被真实用户撞到（"第 14-20 页之后图出不来、重试也没用"）：
     *
     * 1. **每页多少张图只能猜**。`initialApiPage = startPageIndex / 40 + 1` 用的是
     *    客户端常量 40，而服务端真实的 `limit` 从未参与换算。一旦真实 limit 不是 40，
     *    恢复进度时请求的 API 页就是错的，且这个错会随服务端调整而漂移——这正是
     *    用户报告的失败区间从"17-20 页"漂到"14-16 页"的原因。
     * 2. **append 是串行且脆弱的**。任何一次 append 失败（哪怕数据层已重试 3 次），
     *    Paging 就停止续拉，后面所有页再也不会被请求；用户看到的就是"从某页起全断"。
     * 3. **placeholder 连续性校验会丢页**。`itemsBefore/itemsAfter` 用 limit 和 docsSize
     *    分别计算，短页时与下一页不连续，Paging3 校验不过会直接丢弃该页。
     *
     * 章节图片总数是有界的（通常几十到几百张，服务端 `pages` 字段给出总页数），
     * 一次性拉全的代价完全可接受，却把上面三个问题连同它们的漂移一起消掉：
     * 不再需要猜 limit，不存在 append 边界，单页全量的 placeholder 天然连续。
     *
     * 并发受 [LOAD_CONCURRENCY] 约束，与 OkHttp 同 host 配额对齐，避免把域名配额打满。
     */
    override suspend fun load(
        params: LoadParams<Int>
    ): LoadResult<Int, ChapterPage> {
        return try {
            val all = fetchAllPages()
            if (all.isEmpty()) {
                // 章节确实没有图（下架/空章）：正常的空结果，不是错误。
                LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null,
                    itemsBefore = 0,
                    itemsAfter = 0,
                )
            } else {
                LoadResult.Page(
                    data = all,
                    // 全量单页：既无前驱也无后继，Paging 不会再去 append/prepend。
                    prevKey = null,
                    nextKey = null,
                    // 全量单页的绝对位置是确定的：前面 0 张、后面 0 张。
                    // 必须显式给出（而不是留 COUNT_UNDEFINED），否则 enablePlaceholders=true
                    // 下 Paging 会把整份数据当作"位置未知"的占位区，索引换算随之失去锚点。
                    itemsBefore = 0,
                    itemsAfter = 0,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "章节全量拉取失败: comic=$id order=$order" }
            LoadResult.Error(e)
        }
    }

    /**
     * 拉取整章：先取第 1 页拿到真实的 total/limit/pages，再并发补齐其余各页。
     *
     * 结果按服务端页码顺序拼接，保证图片顺序与章节一致——顺序错乱比加载慢严重得多。
     * 任何一页最终失败都会让整次 load 失败并交给 Paging 的 retry 链路重来，
     * 绝不会返回一个"中间缺一页"的残缺列表（那会让页码与内容整体错位）。
     */
    private suspend fun fetchAllPages(): List<ChapterPage> {
        val first = fetchWithPageRetry(fetch = { loadRaw(1) }, onRetry = retryLogger(1))

        val imagePages = first.imagePages
        val total = imagePages.total
        val pageCount = imagePages.pages.coerceAtLeast(1)

        metadata.value = ChapterMeta(title = first.chapterInfo.title, totalImages = total)

        if (pageCount <= 1) {
            // 单页章节：直接走同一套映射（含 URL 畸形过滤），保证行为一致。
            return mergeChapterPages(listOf(first))
        }

        // 并发补齐第 2..pageCount 页。Semaphore 限流，避免瞬间打满图片域名的配额。
        val semaphore = Semaphore(LOAD_CONCURRENCY)
        val restPages = coroutineScope {
            (2..pageCount).map { page ->
                async {
                    semaphore.withPermit {
                        fetchWithPageRetry(fetch = { loadRaw(page) }, onRetry = retryLogger(page))
                    }
                }
            }.awaitAll()
        }

        // 按服务端页号排序后再拼接：awaitAll 保持提交顺序，但页号不一定连续
        // （服务端可能 clamp），显式排序才稳妥。
        val all = mergeChapterPages(listOf(first) + restPages)

        logger.info {
            "章节全量加载完成: comic=$id order=$order 服务端页数=$pageCount " +
                    "总图数=$total 实际取回=${all.size}"
        }
        return all
    }

    private fun retryLogger(apiPage: Int): (Int, Throwable) -> Unit = { attempt, error ->
        logger.warn(error) {
            "章节分页第 $attempt/$PAGE_LOAD_MAX_ATTEMPTS 次失败，comic=$id order=$order 请求页=$apiPage"
        }
    }

    /** 原始拉取：只负责发请求，不吞异常（由 [fetchWithPageRetry] 决定重试）。 */
    private suspend fun loadRaw(apiPage: Int) = dataSource.getChapterPages(id, order, apiPage)

    /**
     * 全量单页没有可跳转空间，refresh 直接回到第 1 页由 [load] 重新全量拉取。
     *
     * 旧实现靠 `lastKnownLimit` 反推页码（远跳分支），在 limit 与常量不一致时
     * 会漂移；全量模式下这个不变量已经被消除。
     */
    override fun getRefreshKey(state: PagingState<Int, ChapterPage>): Int? = null

    @AssistedFactory
    interface Factory {
        fun create(
            id: String,
            @Assisted("order") order: Int,
            @Assisted("initialApiPage") initialApiPage: Int,
            metadata: MutableStateFlow<ChapterMeta?>,
        ): ChapterPagesPagingSource
    }

    private companion object {
        /**
         * 全量拉取时的并发页数。
         *
         * 与 OkHttp 对同一域名的并发上限对齐（见 NetworkModule）：再大只会排进
         * OkHttp 队列，不会更快；并发过高还会触发服务端限流，反而拖垮首屏。
         */
        const val LOAD_CONCURRENCY = 5
    }
}

data class ChapterMeta(
    val title: String,
    val totalImages: Int
)

data class ChapterPage(
    val id: String,
    val url: String,
)

/**
 * 把整章各 API 页的响应按**服务端页号**排序后拼成一条有序列表。
 *
 * 抽成顶层纯函数是为了可单测——[ChapterPagesPagingSource] 依赖 final 的
 * [BikaDataSource]，无法在单测中构造。
 *
 * 两条不变量：
 * 1. **顺序即页号顺序**。并发拉取时 `awaitAll` 只保证提交顺序，而服务端可能
 *    对超范围请求做 clamp、导致返回的 `page` 与请求值不一致；按 `page` 显式
 *    排序才能保证图片顺序与章节一致。顺序错乱比加载慢严重得多。
 * 2. **URL 畸形的图被丢弃**（[com.shizq.bika.core.network.model.Media.safeImageUrl]
 *    为 null）。这类图会产出一个必然失败的死链，在 UI 上表现为"某几页怎么重试
 *    都出不来"；宁可少一张并留日志，也不让它进入列表。
 */
internal fun mergeChapterPages(
    pages: List<com.shizq.bika.core.network.model.ChapterPagesData>,
): List<ChapterPage> {
    return pages
        .sortedBy { it.imagePages.page }
        .flatMap { data ->
            data.imagePages.docs.mapNotNull { image ->
                val url = image.media.safeImageUrl
                if (url == null) {
                    logger.warn {
                        "丢弃 URL 畸形的图片: imageId=${image.imageId} " +
                                "fileServer=${image.media.fileServer} path=${image.media.path}"
                    }
                    null
                } else {
                    ChapterPage(id = image.imageId, url = url)
                }
            }
        }
}