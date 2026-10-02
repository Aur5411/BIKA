package com.shizq.bika.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.shizq.bika.core.coroutine.ApplicationScope
import com.shizq.bika.core.data.model.Chapter
import com.shizq.bika.core.data.model.ChapterCatalog
import com.shizq.bika.core.data.model.asExternalModel
import com.shizq.bika.core.data.model.fillMissingLeadingOrders
import com.shizq.bika.core.data.paging.CATALOG_TERMINAL_PROBE_PAGES
import com.shizq.bika.core.data.paging.ChapterListPagingSource
import com.shizq.bika.core.data.paging.chapterCatalogWalkDecision
import com.shizq.bika.core.data.paging.ChapterMeta
import com.shizq.bika.core.data.paging.ChapterPagesPagingSource
import com.shizq.bika.core.data.paging.CrossPageDeduplicator
import com.shizq.bika.core.network.BikaDataSource
import com.shizq.bika.core.network.model.Episode
import com.shizq.bika.core.network.model.MAX_CHAPTER_LIST_PAGES
import com.shizq.bika.core.network.model.PageData
import com.shizq.bika.core.network.model.declaredChapterTotal
import com.shizq.bika.core.network.model.estimateLastChapterPage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock

private const val CHAPTER_LIST_PAGE_SIZE = 20
private const val CHAPTER_PAGES_PAGE_SIZE = 40

/**
 * 目录并发翻页的批次宽度。
 *
 * 取值与 OkHttp 对同一域名的并发上限对齐（见 NetworkModule 的 `maxRequestsPerHost`）：
 * 再大的话多出来的请求只会在 OkHttp 的队列里排队，并不会更快，反而把同 host 的
 * 配额占满、拖慢同时发出的其它请求。
 */
private const val EPISODE_PAGE_CONCURRENCY = 5

/** 单页章节请求的最大尝试次数（含首次）。 */
private const val EPISODE_PAGE_MAX_ATTEMPTS = 3

/** 章节单页重试的基准间隔，按尝试次数线性放大，另加随机抖动。 */
private const val EPISODE_PAGE_RETRY_DELAY_MS = 300L

/** 重试间隔的随机抖动上限：同批请求同时失败时，错开重发时刻。 */
private const val EPISODE_PAGE_RETRY_JITTER_MS = 250L

/**
 * 整份目录的取数时限。
 *
 * 并发之后正常情况几个网络来回就够（120 话约 3 个来回）。超过这个时间说明网络
 * 或服务端确实有问题，继续等只是让用户对着转圈；宁可失败让他点重试。
 *
 * 取 30 秒（v1.11.36 从 15 秒放宽）：单页失败重试是 3 次、每次都按 3 秒建连超时
 * 加退避抖动算，最坏一批就要 10 秒上下；慢网络下两批就能把 15 秒耗光，
 * 而目录页对"结论"的要求只是**最终判负**——过程里每个批次都已经在渐进渲染，
 * 放宽超时只是让慢网络下的用户等得到结论，而不是中途被判死。
 */
private const val COMPLETE_CATALOG_TIMEOUT_MS = 30_000L

/**
 * 目录流整次拉取失败后的最大重试次数（不含首次）。
 *
 * shareIn 的 upstream 正常完成后永不重启，所以"零页成功"必须在 upstream 内部
 * 自愈——否则 Empty 会被 replay 永久缓存，上下章按钮直到杀进程都是灰的。
 */
private const val CATALOG_FLOW_MAX_ATTEMPTS = 3

/** 目录流重试的基准退避间隔，按尝试次数线性放大（2s → 4s → 6s）。 */
private const val CATALOG_FLOW_RETRY_BASE_DELAY_MS = 2_000L

/**
 * 完整目录的内存缓存有效期。
 *
 * 章节列表在几分钟内基本不会变，而用户"看详情 → 进阅读器 → 返回详情"是很常见的
 * 来回；没有这层缓存的话每次返回都要把整本目录重拉一遍。用户主动重试走
 * [ChapterRepository.getCompleteChapterCatalog] 的 `forceRefresh`，不受这个值影响。
 */
private const val COMPLETE_CATALOG_CACHE_TTL_MS = 5 * 60 * 1000L

private val logger = KotlinLogging.logger("ChapterRepository")

class ChapterRepositoryImpl @Inject constructor(
    private val chapterListPagingSourceFactory: ChapterListPagingSource.Factory,
    private val chapterPagesPagingSourceFactory: ChapterPagesPagingSource.Factory,
    private val network: BikaDataSource,
    @ApplicationScope private val scope: CoroutineScope,
) : ChapterRepository {
    private val catalogCache = ConcurrentHashMap<String, Flow<ChapterCatalog>>()

    /**
     * 完整目录快照缓存。
     *
     * 放在 repository（单例）而不是 ViewModel：ViewModel 随导航栈销毁重建，
     * 放那儿等于没有缓存——"进阅读器看完一章再返回"就会重拉整本目录。
     */
    private val completeCatalogCache = ConcurrentHashMap<String, CatalogCacheEntry>()

    /**
     * 串行化目录拉取。
     *
     * 详情页与阅读器的上下章导航会同时要同一本书的目录；不串行的话两边各拉一遍
     * 整本（页数越多越痛），而且都占满同 host 的并发配额。锁内拿到缓存直接返回，
     * 后到的调用者白等一小会儿，但省下一整轮请求。
     */
    private val catalogFetchMutex = Mutex()

    private class CatalogCacheEntry(
        val catalog: ChapterCatalog,
        val storedAtMs: Long,
    ) {
        fun isExpired(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
            nowMs - storedAtMs > COMPLETE_CATALOG_CACHE_TTL_MS
    }

    override fun getChapterList(comicId: String): Flow<PagingData<Chapter>> =
        Pager(PagingConfig(pageSize = CHAPTER_LIST_PAGE_SIZE)) {
            chapterListPagingSourceFactory.create(comicId)
        }.flow

    override fun getChapterCatalog(comicId: String): Flow<ChapterCatalog> =
        catalogCache.getOrPut(comicId) {
            flow {
                // 详情页刚拉过完整目录时立即先发快照：阅读器的上下章按钮零等待可用，
                // 不必等目录流重新走一遍网络。快照过期与否都发——过期的也比 Empty 强，
                // 后续的完整拉取会立刻覆盖它。
                completeCatalogCache[comicId]?.let { emit(it.catalog) }

                var attempts = 0
                while (true) {
                    val collected = mutableListOf<Chapter>()
                    var emittedAny = false

                    walkEpisodePages(
                        comicId = comicId,
                        onPage = { chapters, isLastPage, declaredTotal ->
                            collected += chapters
                            emittedAny = true
                            emit(
                                ChapterCatalog(
                                    chapters = collected.sortedBy { it.order },
                                    isComplete = isLastPage,
                                    declaredTotal = declaredTotal,
                                )
                                    .fillMissingLeadingOrders(declaredTotal)
                            )
                        },
                        onPageError = { page, e ->
                            logger.warn(e) { "章节目录第 $page 页失败，保留已拉取部分 comic=$comicId" }
                        },
                    )

                    // 拉到了数据（哪怕部分）就结束：部分目录的导航也可用，
                    // 无限重试反而让"已知范围"迟迟不出现。
                    if (emittedAny) break

                    // 一页都没成功——这里曾直接 emit(Empty) 结束 upstream，是个
                    // 严重缺陷：shareIn 的 upstream **正常完成后永不重启**
                    // （WhileSubscribed 只会重启"被取消"的上游，不会重启"已完成"的），
                    // 于是 Empty 被 replay=1 永久缓存，此后每次进这部漫画的阅读器，
                    // 上下章按钮都一直是灰的，直到杀进程。现在改为内部退避重试，
                    // 重试耗尽才 emit Empty。
                    attempts++
                    if (attempts >= CATALOG_FLOW_MAX_ATTEMPTS) {
                        logger.warn { "章节目录 comic=$comicId 连续 $attempts 次拉取全部失败，放弃本次" }
                        emit(ChapterCatalog.Empty)
                        break
                    }
                    val delayMs = CATALOG_FLOW_RETRY_BASE_DELAY_MS * attempts
                    logger.info { "章节目录 comic=$comicId 拉取失败（第 $attempts 次），${delayMs}ms 后重试" }
                    delay(delayMs)
                }
            }
                .shareIn(scope, SharingStarted.WhileSubscribed(30_000), replay = 1)
        }

    /**
     * 一次性拉全整本章节目录，超时或失败即抛。
     *
     * 与 [getChapterCatalog] 的区别是**失败不降级**：目录页要么呈现完整目录、
     * 要么明确报错让用户重试，绝不展示一个"看起来齐了、其实少了几十话"的列表。
     * 中途某页彻底失败（重试也没救回来）时直接抛出，
     * 而不是沿用"保留已拉取部分"的策略——那个策略是给上下章导航用的，
     * 导航"知道一部分"总比什么都不知道强，但目录页拿它当完整列表展示就是骗人。
     *
     * 三重约束保证它不会把界面拖住：
     * 1. **并发翻页**——总耗时从"Σ 单页延迟"降到"⌈页数/并发度⌉ × 单页延迟"；
     * 2. **整体超时** [COMPLETE_CATALOG_TIMEOUT_MS]——网络或服务端出问题时快速失败，
     *    不让用户对着转圈无限等；
     * 3. **内存缓存** [COMPLETE_CATALOG_CACHE_TTL_MS]——"看详情 → 进阅读器 → 返回"
     *    这种来回不再重拉整本。
     *
     * @param forceRefresh 绕过缓存强制重拉。用户点"重试"时必须传 true，
     *   否则会命中上一次失败前写入的旧快照、看起来像"重试没反应"
     * @param onProgress 每拉到一个批次就回调一次当前已到手的章节（升序）。
     *   目录页据此边拉边渲染，第一个网络来回就能把前 20 话交出去
     */
    override suspend fun getCompleteChapterCatalog(
        comicId: String,
        forceRefresh: Boolean,
        onProgress: (List<Chapter>) -> Unit,
    ): ChapterCatalog = catalogFetchMutex.withLock {
        val cached = completeCatalogCache[comicId]
        if (!forceRefresh && cached != null && !cached.isExpired()) {
            logger.info {
                "章节目录命中缓存 comic=$comicId 共 ${cached.catalog.chapters.size} 话"
            }
            return@withLock cached.catalog
        }

        val collected = mutableListOf<Chapter>()
        var complete = false
        var expectedTotal = 0
        val startedAtMs = Clock.System.now().toEpochMilliseconds()

        try {
            expectedTotal = withTimeout(COMPLETE_CATALOG_TIMEOUT_MS) {
                walkEpisodePages(
                    comicId = comicId,
                    onPage = { chapters, isLastPage, _ ->
                        collected += chapters
                        complete = isLastPage
                        onProgress(collected.sortedBy { it.order })
                    },
                    onPageError = { _, e -> throw e },
                )
            }
        } catch (e: CancellationException) {
            // withTimeout 的超时本身也是 CancellationException。直接往上抛的话
            // 上层会把它当成"调用方取消了这次加载"而静默收场，界面就永远停在
            // 加载中——必须换成一个明确的失败，让 UI 切到可重试的错误态。
            if (e is TimeoutCancellationException) {
                logger.warn {
                    "章节目录拉取超时（${COMPLETE_CATALOG_TIMEOUT_MS}ms），" +
                            "已收到 ${collected.size} 话，comic=$comicId"
                }
                throw IllegalStateException(
                    "章节目录拉取超时（已收到 ${collected.size} 话）",
                    e,
                )
            }
            throw e
        }

        val fetched = ChapterCatalog(
            chapters = collected.sortedBy { it.order },
            isComplete = complete,
            declaredTotal = expectedTotal,
        )
        // 服务端把长目录截断时（自报 160 话却只给 90 条），按 order 把缺失的前置章节补出来。
        // 只在推断无歧义时才补，理由见 fillMissingLeadingOrders 的文档
        val catalog = fetched.fillMissingLeadingOrders(expectedTotal)
        val filled = catalog.chapters.size - fetched.chapters.size
        if (filled > 0) {
            logger.warn {
                "章节目录 comic=$comicId 服务端只给到 ${fetched.chapters.size} 条" +
                        "（自报 total=$expectedTotal），已按 order 补齐前置 $filled 条的占位条目"
            }
        }

        completeCatalogCache[comicId] = CatalogCacheEntry(
            catalog = catalog,
            storedAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        logger.info {
            "章节目录加载完成 comic=$comicId 共 ${catalog.chapters.size} 话，" +
                    "耗时 ${Clock.System.now().toEpochMilliseconds() - startedAtMs}ms"
        }
        return catalog
    }

    override suspend fun getAllChapters(comicId: String): List<Chapter> {
        val collected = mutableListOf<Chapter>()

        walkEpisodePages(
            comicId = comicId,
            onPage = { chapters, _, _ -> collected += chapters },
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
     * 它们只用于日志，以及 [estimateLastChapterPage] 决定批次宽度。
     *
     * `MAX_CHAPTER_LIST_PAGES` 是第三条兜底边界，防的是服务端一直返回非空且互不
     * 重复的页。
     *
     * ## 并发翻页
     *
     * 原先一页一页串行拉：120 话要 7 个网络来回，每页都在干等，总耗时是各页
     * 延迟之和。翻页之间没有任何数据依赖（页码互不相关，去重只需要按页序处理
     * 结果），所以改成**按批并发**——把总耗时从"Σ 单页延迟"压成"⌈页数/并发度⌉ × 单页延迟"。
     *
     * 注意这是 IO 等待而非 CPU 计算，**加线程池没有意义**：线程多也只是都在等回包。
     * 真正的天花板是 OkHttp 对同一域名的并发上限（见 NetworkModule 里的
     * `maxRequestsPerHost`），并发度取值必须和它对齐，超出的请求只会在队列里排队。
     *
     * 并发只改变"什么时候发出请求"，不改变**处理顺序**：结果仍按页码顺序消费，
     * 因为 [CrossPageDeduplicator] 是"先到先得"，页序错乱会让去重保留错的那一份。
     *
     * ## 单页失败会重试
     *
     * 每页最多重试 [EPISODE_PAGE_MAX_ATTEMPTS] 次，仍失败才交给 [onPageError]。
     * 重试间隔带随机抖动：同一批请求往往同时失败，固定间隔会让它们同时重发，
     * 对着已经不稳的服务端再打一波同步流量。
     *
     * @param onPage 第二个参数表示本页是否为末页；第三个参数是服务端自报的章节总数
     *   （0 = 未知），上下章导航靠它判断"还有没有下一话"，因此它在第一页就可用、
     *   不必等整本拉完。因页数上限或单页彻底失败而提前终止时，最后一次回调收到的是 false
     * @return 服务端自报的章节总数（各页 `total` 里的最大值）。0 表示服务端没给，
     *   调用方据此判断"还差多少"——[getCompleteChapterCatalog] 用它决定要不要补占位条目
     *
     * @param onPageError 单页重试耗尽后的处置。目录流选择"记日志并停下、保留已拉部分"，
     *   目录页选择"抛出去让用户重试"；抛出即终止整个遍历。
     *   类型是 [Throwable] 而不是 [Exception]：并发版把每页结果装进 [Result]，
     *   而 `Result` 的失败分支只保证 `Throwable`
     */
    private suspend fun walkEpisodePages(
        comicId: String,
        onPage: suspend (chapters: List<Chapter>, isLastPage: Boolean, declaredTotal: Int) -> Unit,
        onPageError: (page: Int, e: Throwable) -> Unit,
    ): Int {
        val deduplicator = CrossPageDeduplicator<Chapter> { it.id }
        var loaded = 0
        var previousPageIds: Set<String>? = null

        // 服务端自报的章节总数（取各页里最大的那个）。**不用于判断"够了"**，
        // 只用于判断"还差"——这是它能安全派上用场的唯一方向：
        // total 报小不会让它提前收工，但看到 loaded < total 就能确定没拉完。
        var expectedTotal = 0

        // 空页/重复页出现后还允许往前探几页。服务端偶发会把中间某页返回成空，
        // 或者把越界页 clamp 回上一页；一次就认输会直接丢掉后面所有章节
        var terminalProbesLeft = CATALOG_TERMINAL_PROBE_PAGES

        var nextPage = 1
        // 计划拉到第几页。第一轮只发第 1 页——拿到 limit/total 才谈得上规划批宽，
        // 也顺带让"第一页先回来"成为最早的一次进度回调
        var plannedLastPage = 1

        while (nextPage <= MAX_CHAPTER_LIST_PAGES) {
            val batchLast = minOf(
                nextPage + EPISODE_PAGE_CONCURRENCY - 1,
                plannedLastPage,
                MAX_CHAPTER_LIST_PAGES,
            )
            val batch = nextPage..batchLast

            // supervisorScope：一页失败不该牵连同批次里已经成功的页。用 coroutineScope
            // 的话，任何一页超时都会取消整批，其余页白等一轮还得重发
            val outcomes = supervisorScope {
                batch.map { page ->
                    async {
                        page to try {
                            Result.success(fetchEpisodePageWithRetry(comicId, page))
                        } catch (e: CancellationException) {
                            // runCatching 会把取消也吞掉，于是"协程被取消"会退化成
                            // "这一页失败了"，进而触发无意义的重试
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }.awaitAll()
            }

            // 按页码顺序消费：并发只影响发出时刻，去重的先后必须仍是页序
            for ((page, outcome) in outcomes) {
                val eps = outcome.getOrElse { e ->
                    onPageError(page, e)
                    return expectedTotal
                }
                // 空页里的 total 是垃圾值，不能拿它抬自报总数——否则会把
                // "9 话的漫画"算成 10 话，凭空推出一个不存在的下一章
                expectedTotal = declaredChapterTotal(expectedTotal, eps)

                val currentPageIds = eps.docs.mapTo(LinkedHashSet()) { it.id }
                // 与上一页逐字相同 = 服务端把越界页 clamp 回了上一页
                val pageRepeated =
                    previousPageIds != null && currentPageIds.isNotEmpty() && currentPageIds == previousPageIds
                val isEmptyPage = eps.docs.isEmpty()

                val chapters = deduplicator.retainUnseen(page, eps.docs.map { it.asExternalModel() })
                loaded += chapters.size

                // 终止判定收敛到纯函数（见 chapterCatalogWalkDecision 的 KDoc）。
                // v1.11.36 两处修复都在这个函数里：
                // 1) "非空但零新章节"也算终止信号——服务端把越界页 clamp 到任意
                //    已见页（不一定是紧邻上一页）时，旧判定认不出来，
                //    遍历会对着同样的数据一路翻到页数上限，目录停在一半；
                // 2) 探测中收到新章节就把预算重置回满额——空页与数据页交替时
                //    旧实现会把 3 次预算逐步耗尽，照样提前终止丢话。
                val decision = chapterCatalogWalkDecision(
                    isEmptyPage = isEmptyPage,
                    pageRepeated = pageRepeated,
                    pageHasNewChapters = chapters.isNotEmpty(),
                    loaded = loaded,
                    expectedTotal = expectedTotal,
                    probesLeft = terminalProbesLeft,
                )
                terminalProbesLeft = decision.probesLeft

                logger.info {
                    "章节目录 comic=$comicId 第 $page 页：收到 ${eps.docs.size} 条，累计 $loaded 条" +
                            "（服务端返回 page=${eps.page} total=${eps.total} pages=${eps.pages}" +
                            " limit=${eps.limit}，首条 order=${eps.docs.firstOrNull()?.order}）" +
                            when {
                                decision.isSuspicious -> " → 判为可疑终止信号，继续往后探"
                                decision.isTerminal -> " → 结束"
                                else -> ""
                            }
                }

                if (decision.isSuspicious) {
                    val reason = when {
                        isEmptyPage -> "空页"
                        pageRepeated -> "与上一页完全相同的页"
                        else -> "不含任何新章节的页"
                    }
                    logger.warn {
                        "章节目录 comic=$comicId 第 $page 页返回$reason，但服务端自报 total=$expectedTotal" +
                                "、目前只收到 $loaded 条，判定为「还没拉完」，跳过继续探" +
                                "（剩余探测次数 $terminalProbesLeft）"
                    }
                    // 只把非空页记进 previousPageIds：空页会把基准清空，
                    // 使得后面真正被 clamp 的页无法再被识别出来
                    if (currentPageIds.isNotEmpty()) previousPageIds = currentPageIds
                    onPage(chapters, false, expectedTotal)
                    continue
                }

                if (eps.total > 0 && loaded >= eps.total && !decision.isTerminal) {
                    // total 说自己齐了但接口还在给数据：以接口为准继续拉，
                    // 只把这种不自洽记下来，便于事后定位服务端问题
                    logger.warn {
                        "章节目录 comic=$comicId 已达 total=${eps.total} 但仍有后续页，" +
                                "继续拉取以接口为准"
                    }
                }

                if (decision.isTerminal) {
                    // 已经结束，但要区分"确信到底"和"探不动了"：跟服务端自报的总数对不上时
                    // 说明后面大概率还有章节，此时不能把 isComplete 报成 true——
                    // 阅读器的上下章导航就是靠这个标记知道边界不可信的
                    val confidentEnd = expectedTotal <= 0 || loaded >= expectedTotal
                    if (confidentEnd) {
                        logger.info { "章节目录 comic=$comicId 拉取结束，共 $loaded 条" }
                    } else {
                        logger.warn {
                            "章节目录 comic=$comicId 在 $loaded 条处结束，但服务端自报 total=$expectedTotal：" +
                                    "剩余 ${expectedTotal - loaded} 条未取到，" +
                                    "疑似服务端在无效页码上返回了无新数据的页"
                        }
                    }
                    onPage(chapters, confidentEnd, expectedTotal)
                    return expectedTotal
                }

                onPage(chapters, false, expectedTotal)
                previousPageIds = currentPageIds
            }

            plannedLastPage = when {
                // 第一页到手：按 total/limit 规划整本
                batch.first() == 1 -> outcomes.first().second.fold(
                    onSuccess = { it.estimateLastChapterPage() },
                    // 第一页失败的话上面已经 return 了，这里只是不让编译器为难
                    onFailure = { nextPage + EPISODE_PAGE_CONCURRENCY },
                )
                // 计划用完还没见到空页 → total/pages 报小了，往前再扩一档
                batchLast >= plannedLastPage ->
                    minOf(plannedLastPage + EPISODE_PAGE_CONCURRENCY, MAX_CHAPTER_LIST_PAGES)
                else -> plannedLastPage
            }
            nextPage = batchLast + 1
        }

        logger.warn {
            "章节拉取到达 $MAX_CHAPTER_LIST_PAGES 页上限（已累计 $loaded 条），comic=$comicId"
        }
        return expectedTotal
    }

    /**
     * 拉取单页章节，失败重试。
     *
     * 章节接口偶发超时/被挡，重试成本远低于"整份目录少一截"的代价。
     * 间隔带随机抖动：并发翻页时同批请求常同时失败，固定间隔会让它们同时重发，
     * 对着本来就不稳的服务端再打一波同步流量。
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
                    delay(
                        EPISODE_PAGE_RETRY_DELAY_MS * (attempt + 1) +
                                Random.nextLong(EPISODE_PAGE_RETRY_JITTER_MS)
                    )
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

        // 从"用户上次读到第几张图"换算成服务端 API 页码（1-based）。
        //
        // 这里用的 CHAPTER_PAGES_PAGE_SIZE 是**客户端常量**，服务端真实 limit 可能不同。
        // 换算不准的后果有限：它只决定"刷新后从哪一页开始重新加载"，
        // 后续页由 Paging 按服务端返回的 respPage 自然续接（nextKey = respPage + 1），
        // 不会因为起点猜偏而丢页——丢页只可能来自 append 失败，
        // 而那条路径已由 ChapterPagesPagingSource 的原地重试 + UI 侧续拉兜住。
        val initialApiPage = (startPageIndex.coerceAtLeast(0) / CHAPTER_PAGES_PAGE_SIZE) + 1

        val pages = Pager(
            config = PagingConfig(
                pageSize = CHAPTER_PAGES_PAGE_SIZE,
                enablePlaceholders = true,
            ),
        ) {
            chapterPagesPagingSourceFactory.create(comicId, order, initialApiPage, metadata)
        }.flow

        return ChapterPagesResult(
            pages = pages,
            meta = metadata.filterNotNull()
        )
    }
}
