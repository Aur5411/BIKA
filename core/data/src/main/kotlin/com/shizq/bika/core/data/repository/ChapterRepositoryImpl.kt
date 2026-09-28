package com.shizq.bika.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.shizq.bika.core.coroutine.ApplicationScope
import com.shizq.bika.core.data.model.Chapter
import com.shizq.bika.core.data.model.ChapterCatalog
import com.shizq.bika.core.data.model.asExternalModel
import com.shizq.bika.core.data.paging.ChapterListPagingSource
import com.shizq.bika.core.data.paging.ChapterMeta
import com.shizq.bika.core.data.paging.ChapterPagesPagingSource
import com.shizq.bika.core.data.paging.CrossPageDeduplicator
import com.shizq.bika.core.network.BikaDataSource
import com.shizq.bika.core.network.model.Episode
import com.shizq.bika.core.network.model.PageData
import com.shizq.bika.core.network.model.MAX_CHAPTER_LIST_PAGES
import com.shizq.bika.core.network.model.nextChapterPageKey
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap

private const val CHAPTER_LIST_PAGE_SIZE = 20
private const val CHAPTER_PAGES_PAGE_SIZE = 40

/** 单页章节请求的最大尝试次数（含首次）。 */
private const val EPISODE_PAGE_MAX_ATTEMPTS = 3

/** 章节单页重试的基准间隔，按尝试次数线性放大。 */
private const val EPISODE_PAGE_RETRY_DELAY_MS = 300L

private val logger = KotlinLogging.logger("ChapterRepository")

class ChapterRepositoryImpl @Inject constructor(
    private val chapterListPagingSourceFactory: ChapterListPagingSource.Factory,
    private val chapterPagesPagingSourceFactory: ChapterPagesPagingSource.Factory,
    private val network: BikaDataSource,
    @ApplicationScope private val scope: CoroutineScope,
) : ChapterRepository {
    private val catalogCache = ConcurrentHashMap<String, Flow<ChapterCatalog>>()
    override fun getChapterList(comicId: String): Flow<PagingData<Chapter>> =
        Pager(PagingConfig(pageSize = CHAPTER_LIST_PAGE_SIZE)) {
            chapterListPagingSourceFactory.create(comicId)
        }.flow

    override fun getChapterCatalog(comicId: String): Flow<ChapterCatalog> =
        catalogCache.getOrPut(comicId) {
            flow {
                val collected = mutableListOf<Chapter>()
                var emittedAny = false

                walkEpisodePages(
                    comicId = comicId,
                    onPage = { chapters, isLastPage ->
                        collected += chapters
                        emittedAny = true
                        // isComplete 直接取"是否末页"：因页数上限或中途失败停下时
                        // 这个值是 false，导航据此知道边界不可信
                        emit(
                            ChapterCatalog(
                                chapters = collected.sortedBy { it.order },
                                isComplete = isLastPage,
                            )
                        )
                    },
                    // 中途失败保留已拉到的部分：若由下游 catch 统一 emit(Empty)，
                    // 第 3 页失败会把前 2 页已经交出去的目录清空，
                    // 上下章导航从"知道一部分"退化成"什么都不知道"
                    onPageError = { page, e ->
                        logger.warn(e) { "章节目录第 $page 页失败，保留已拉取部分 comic=$comicId" }
                    },
                )

                // 连第一页都没成功：一条都没发过，下游会一直等在初始值上
                if (!emittedAny) {
                    emit(ChapterCatalog.Empty)
                }
            }
                .shareIn(scope, SharingStarted.WhileSubscribed(30_000), replay = 1)
        }

    /**
     * 一次性拉全整本章节目录。
     *
     * 与 [getChapterCatalog] 的区别是**失败不降级**：目录页要么呈现完整目录，
     * 要么明确报错让用户重试，绝不展示一个"看起来齐了、其实少了几十话"的列表。
     * 中途某页彻底失败（重试也没救回来）时直接抛出，
     * 而不是沿用"保留已拉取部分"的策略——那个策略是给上下章导航用的，
     * 导航"知道一部分"总比什么都不知道强，但目录页拿它当完整列表展示就是骗人。
     */
    override suspend fun getCompleteChapterCatalog(comicId: String): ChapterCatalog {
        val collected = mutableListOf<Chapter>()
        var complete = false

        walkEpisodePages(
            comicId = comicId,
            onPage = { chapters, isLastPage ->
                collected += chapters
                complete = isLastPage
            },
            onPageError = { _, e -> throw e },
        )

        return ChapterCatalog(
            chapters = collected.sortedBy { it.order },
            isComplete = complete,
        )
    }

    override suspend fun getAllChapters(comicId: String): List<Chapter> {
        val collected = mutableListOf<Chapter>()

        walkEpisodePages(
            comicId = comicId,
            onPage = { chapters, _ -> collected += chapters },
            // 不吞异常：下载选择面板拿到残缺列表，用户会以为章节就这么多
            onPageError = { _, e -> throw e },
        )

        return collected.sortedBy { it.order }
    }

    /**
     * 逐页走完 `comics/{id}/eps`，每页交给 [onPage]。
     *
     * 抽出来是因为"全量拉章节"有三个交付形态（目录流、一次性列表、一次性目录），
     * 但翻页规则、页数上限、空页终止这些约束必须完全一致。
     *
     * ## 终止条件（这是本方法最要紧的部分）
     *
     * 只认两类可信信号：
     * 1. 服务端返回**空页**；
     * 2. 本页内容与上一页**完全相同**——服务端对越界页做 clamp 时会重复上一页，
     *    此时再翻就是原地打转。
     *
     * `total` / `pages` **一律不参与终止判断**。两者都被证实会报小（100 话以上的
     * 漫画报成 1~2 页是常态），把它们当边界会让目录被截断；而因为服务端按
     * "最新话在前"分页，截断掉的恰好是最老的那几话，长得很像数据错。
     * 它们只用于日志，方便核对"这次到底拉全没有"。
     *
     * `MAX_CHAPTER_LIST_PAGES` 是第三条兜底边界，防的是服务端一直返回非空且互不
     * 重复的页。
     *
     * ## 单页失败会重试
     *
     * 原先单页失败直接 `onPageError` + 终止，配合调用方"保留已拉取部分"的策略，
     * 表现就是**目录静默少一截**（用户看到"缺第 1~33 话"却没任何提示）。
     * 现在每页最多重试 [EPISODE_PAGE_MAX_ATTEMPTS] 次，仍失败才交给 [onPageError]。
     *
     * @param onPage 第二个参数表示本页是否为末页。因页数上限或单页彻底失败而
     *   提前终止时，最后一次回调收到的是 false
     * @param onPageError 单页重试耗尽后的处置。目录流选择"记日志并停下、保留已拉部分"，
     *   目录页选择"抛出去让用户重试"；抛出即终止整个遍历
     */
    private suspend fun walkEpisodePages(
        comicId: String,
        onPage: suspend (chapters: List<Chapter>, isLastPage: Boolean) -> Unit,
        onPageError: (page: Int, e: Exception) -> Unit,
    ) {
        var page: Int? = 1
        var pagesFetched = 0
        val deduplicator = CrossPageDeduplicator<Chapter> { it.id }
        var loaded = 0
        var previousPageIds: Set<String>? = null

        while (page != null) {
            if (pagesFetched >= MAX_CHAPTER_LIST_PAGES) {
                logger.warn {
                    "章节拉取到达 $MAX_CHAPTER_LIST_PAGES 页上限（已累计 $loaded 条），comic=$comicId"
                }
                return
            }
            val eps = try {
                fetchEpisodePageWithRetry(comicId, page)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onPageError(page, e)
                return
            }
            pagesFetched++

            val currentPageIds = eps.docs.mapTo(LinkedHashSet()) { it.id }
            // 与上一页逐字相同 = 服务端把越界页 clamp 回了上一页，再翻没有意义
            val pageRepeated =
                previousPageIds != null && currentPageIds.isNotEmpty() && currentPageIds == previousPageIds

            val chapters = deduplicator.retainUnseen(page, eps.docs.map { it.asExternalModel() })
            loaded += chapters.size

            val nextPage = if (pageRepeated) null else eps.nextChapterPageKey(page)
            logger.info {
                "章节目录 comic=$comicId 第 $page 页：收到 ${eps.docs.size} 条，累计 $loaded 条" +
                        "（服务端 total=${eps.total} pages=${eps.pages}）" +
                        if (nextPage == null) " → 结束" else " → 继续第 $nextPage 页"
            }
            if (eps.total > 0 && loaded >= eps.total && nextPage != null) {
                // total 说自己齐了但接口还说有下一页：以接口为准继续拉，
                // 只把这种不自洽记下来，便于事后定位服务端问题
                logger.warn {
                    "章节目录 comic=$comicId 已达 total=${eps.total} 但接口仍有下一页 $nextPage，" +
                            "继续拉取以接口为准"
                }
            }

            onPage(chapters, nextPage == null)
            previousPageIds = currentPageIds
            page = nextPage
        }
    }

    /**
     * 拉取单页章节，失败重试。
     *
     * 章节接口偶发超时/被挡，重试成本远低于"整份目录少一截"的代价。
     */
    private suspend fun fetchEpisodePageWithRetry(comicId: String, page: Int): PageData<Episode> {
        var lastError: Exception? = null
        repeat(EPISODE_PAGE_MAX_ATTEMPTS) { attempt ->
            try {
                return network.getComicEpisodes(comicId, page).eps
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                logger.warn(e) {
                    "章节目录第 $page 页请求失败（第 ${attempt + 1}/$EPISODE_PAGE_MAX_ATTEMPTS 次），comic=$comicId"
                }
                if (attempt < EPISODE_PAGE_MAX_ATTEMPTS - 1) {
                    delay(EPISODE_PAGE_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("章节第 $page 页请求失败")
    }

    override fun getChapterPages(
        comicId: String,
        order: Int,
        startPageIndex: Int
    ): ChapterPagesResult {
        // 局部变量：仅归属于这一次调用，不同章节/不同调用互不影响，避免共享状态污染
        val metadata = MutableStateFlow<ChapterMeta?>(null)

        // 索引 -> API 页的换算依赖服务端每页数量，但首次请求前这个值还不知道，
        // 只能用 CHAPTER_PAGES_PAGE_SIZE 作为猜测（与 PagingConfig.pageSize 保持一致）。
        // 猜错的后果是首次加载没有精确落在目标索引所在页，getRefreshKey 会在数据到位后
        // 用真实 limit 重新算一次，不会导致崩溃或死循环，只是首屏多一次纠偏。
        val initialApiPage = (startPageIndex / CHAPTER_PAGES_PAGE_SIZE) + 1

        val pages = Pager(
            config = PagingConfig(
                pageSize = CHAPTER_PAGES_PAGE_SIZE,
                enablePlaceholders = true,
            ),
            initialKey = initialApiPage.takeIf { it > 1 },
        ) {
            chapterPagesPagingSourceFactory.create(comicId, order, initialApiPage, metadata)
        }.flow

        return ChapterPagesResult(
            pages = pages,
            meta = metadata.filterNotNull()
        )
    }
}
