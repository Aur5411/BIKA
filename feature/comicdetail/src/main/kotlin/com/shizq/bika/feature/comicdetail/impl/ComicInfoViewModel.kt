package com.shizq.bika.feature.comicdetail.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shizq.bika.core.data.model.Chapter
import com.shizq.bika.core.data.repository.ChapterRepository
import com.shizq.bika.core.database.dao.ReadingHistoryDao
import com.shizq.bika.core.download.repository.DownloadTaskRepository
import com.shizq.bika.core.message.MessageAction
import com.shizq.bika.core.message.MessageDuration
import com.shizq.bika.core.message.MessageId
import com.shizq.bika.core.message.MessageReporter
import com.shizq.bika.core.message.UiText
import com.shizq.bika.core.message.reportError
import com.shizq.bika.core.message.reportInfo
import com.shizq.bika.feature.comicdetail.impl.episodes.ChapterSortOrder
import com.shizq.bika.feature.comicdetail.impl.episodes.EpisodeListState
import com.shizq.bika.feature.comicdetail.impl.episodes.applySortOrder
import com.shizq.bika.feature.comicdetail.impl.statemachine.UnitedDetailsStateMachine
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger("ComicInfoViewModel")

/**
 * 漫画详情 ViewModel：详情、推荐、章节、下载、阅读进度。
 *
 * 评论域已拆到 [com.shizq.bika.ui.comicinfo.comments.CommentsViewModel]：
 * 它与这里没有共享状态，留在同一个类里只是让两组无关的依赖互相牵连。
 */
@HiltViewModel(assistedFactory = ComicInfoViewModel.Factory::class)
class ComicInfoViewModel @AssistedInject constructor(
    private val chapterRepository: ChapterRepository,
    stateMachineFactory: UnitedDetailsStateMachine.Factory,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val historyDao: ReadingHistoryDao,
    private val messageReporter: MessageReporter,
    private val comicDownloadEnqueuer: ComicDownloadEnqueuer,
    @Assisted private val comicId: String,
) : ViewModel() {

    private val stateMachine = stateMachineFactory.create(comicId).launchIn(viewModelScope)

    val state = stateMachine.state

    /**
     * 目录排序偏好。
     *
     * 只活在 ViewModel 里：退出详情页即恢复默认正序。写进 DataStore 需要动
     * 偏好序列化与迁移，而"上次看的排序"对多数人是一次性的浏览习惯，
     * 不值得为它引入一次 schema 变更。
     */
    private var episodeSortOrder = ChapterSortOrder.ASCENDING

    /**
     * 已完整拉取的章节目录（升序）。排序只在这份列表上做反转，
     * 不重新请求、也不重新拉数据。
     */
    private var loadedChapters: List<Chapter> = emptyList()

    private val _episodes = MutableStateFlow(EpisodeListState.Empty)

    /** 章节目录。边拉边显示：第一批到手即可阅读，整本齐了再收尾。 */
    val episodes: StateFlow<EpisodeListState> = _episodes.asStateFlow()

    /** 正在进行的目录加载。重新加载时先取消上一次，避免两份结果互相覆盖。 */
    private var catalogLoadJob: Job? = null

    init {
        loadEpisodeCatalog()
    }

    /**
     * 拉取整本章节目录。
     *
     * 走 [ChapterRepository.getCompleteChapterCatalog]（一次性、失败即抛）而不是
     * 边拉边发的目录流：目录页要么是完整目录、要么明确失败可重试，"少几十话的
     * 列表" 比 "加载失败" 有害得多——用户会以为这本漫画就这么多话。
     *
     * 但**加载过程**不再把用户按在整屏转圈上：仓库每拉到一个批次就回调一次，
     * 这里立刻渲染出去。第一个网络来回就能看到前 20 话，而不是等整本齐活。
     * 中途任何一页彻底失败或整体超时，仍然整个切到错误态、丢掉已到手的部分——
     * "渐进出内容"和"绝不展示残缺目录"这两条并不冲突：前者是过程，后者是结论。
     *
     * @param forceRefresh 用户点重试时传 true，绕过仓库缓存强制重拉
     */
    fun loadEpisodeCatalog(forceRefresh: Boolean = false) {
        catalogLoadJob?.cancel()
        catalogLoadJob = viewModelScope.launch {
            _episodes.value = EpisodeListState(isLoading = true, sortOrder = episodeSortOrder)
            try {
                val catalog = chapterRepository.getCompleteChapterCatalog(
                    comicId = comicId,
                    forceRefresh = forceRefresh,
                    onProgress = { chapters ->
                        loadedChapters = chapters
                        _episodes.value = EpisodeListState(
                            chapters = chapters.applySortOrder(episodeSortOrder),
                            sortOrder = episodeSortOrder,
                            isLoading = false,
                            isLoadingMore = true,
                        )
                    },
                )
                loadedChapters = catalog.chapters
                logger.info {
                    "章节目录加载完成: comicId=$comicId 共 ${catalog.chapters.size} 话 " +
                            "(isComplete=${catalog.isComplete})"
                }
                emitEpisodeState()
            } catch (e: CancellationException) {
                // 主动取消上一次加载（或页面销毁）不算失败，状态由新一次加载接管
                throw e
            } catch (e: Exception) {
                logger.error(e) { "章节目录加载失败: comicId=$comicId" }
                _episodes.value = EpisodeListState(
                    isLoading = false,
                    isLoadingMore = false,
                    loadFailed = true,
                    sortOrder = episodeSortOrder,
                )
            }
        }
    }

    /**
     * 切换正序 / 倒序。
     *
     * 只重排已经在手的列表，不触发任何网络请求。状态由 [emitEpisodeState] 统一
     * 重算，**不依赖 combine 之类的流合并**——那种写法一旦上游没发出新值，
     * 用户侧的表现就是"点了没反应"，且很难从结果倒推原因。
     */
    fun toggleEpisodeSortOrder() {
        episodeSortOrder = episodeSortOrder.toggled()
        // 保留"还在拉"的标记：用户在加载过程中点排序，不该让进度提示消失
        emitEpisodeState(isLoadingMore = _episodes.value.isLoadingMore)
    }

    private fun emitEpisodeState(isLoadingMore: Boolean = false) {
        _episodes.value = EpisodeListState(
            chapters = loadedChapters.applySortOrder(episodeSortOrder),
            sortOrder = episodeSortOrder,
            isLoading = false,
            isLoadingMore = isLoadingMore,
            loadFailed = false,
        )
    }

    val downloadTasks = downloadTaskRepository.observeTasksByComic(comicId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val chapterProgress = historyDao.getChapterProgressByComic(comicId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun dispatch(action: UnitedDetailsAction) {
        viewModelScope.launch {
            stateMachine.dispatch(action)
        }
    }

    /** 章节列表（供"全部下载"用的一次性全量拉取）。 */
    private suspend fun fetchAllEpisodes(): List<Chapter> =
        chapterRepository.getAllChapters(comicId)

    /**
     * 下载整本漫画。
     *
     * ## 为什么用 viewModelScope 而不是让 UI 自己起协程
     *
     * 拉取完整章节列表要按页请求，耗时可达数秒。原实现由详情页在
     * `rememberCoroutineScope()` 里调用，而那个 scope 属于 pager 的 DETAIL 分页——
     * 用户在等待期间切到「目录」，分页被 dispose，协程随之取消，
     * 于是既没有下载也没有任何提示。入队是一次「发起后就该完成」的动作，
     * 生命周期必须跟着 ViewModel。
     *
     * ## 为什么单话分支在这里
     *
     * 「epsCount <= 1 视为单话」是下载域的规则，与 [isComicFullyDownloaded]
     * 的同名分支必须同步演化。放在 UI 的 onClick 里，两处会各自漂移，
     * 表现为「显示已下载，但点下载又入队一遍」。
     */
    fun downloadWholeComic(comicTitle: String, coverUrl: String, epsCount: Int) {
        viewModelScope.launch {
            if (epsCount <= 1) {
                enqueueEpisodes(comicTitle, coverUrl, listOf(singleEpisodeChapter()))
                messageReporter.reportInfo(UiText.of(R.string.download_enqueued))
                return@launch
            }

            // id 固定：连点「全部下载」时后续上报被合并，不会堆出一串相同提示
            val pendingId = MessageId("download_all_$comicId")
            messageReporter.reportInfo(
                UiText.of(R.string.download_fetching_chapters),
                id = pendingId,
            )
            try {
                val allEps = fetchAllEpisodes()
                if (allEps.isEmpty()) {
                    messageReporter.dismiss(pendingId)
                    messageReporter.reportError(UiText.of(R.string.download_no_chapters))
                    return@launch
                }
                enqueueEpisodes(comicTitle, coverUrl, allEps)
                // 先撤掉「正在获取」：它没被撤时结果消息要排在其整个展示时长之后，
                // 章节拉得快的话用户会先盯着过期提示看完才见到结果
                messageReporter.dismiss(pendingId)
                messageReporter.reportInfo(
                    UiText.of(R.string.download_all_enqueued, allEps.size),
                    duration = MessageDuration.Long,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "获取全部章节失败: comicId=$comicId" }
                messageReporter.dismiss(pendingId)
                messageReporter.reportError(
                    UiText.of(R.string.download_fetch_chapters_failed),
                    duration = MessageDuration.Long,
                    action = MessageAction(
                        label = UiText.of(R.string.retry),
                        onPerformed = { downloadWholeComic(comicTitle, coverUrl, epsCount) },
                    ),
                )
            }
        }
    }

    /**
     * 批量写库后一次性触发调度。
     *
     * 任务创建和调度由 [ComicDownloadEnqueuer] 统一负责，避免整本下载和选择下载
     * 各自维护一份实现。
     */
    private suspend fun enqueueEpisodes(
        comicTitle: String,
        coverUrl: String,
        episodes: List<Chapter>,
    ) {
        comicDownloadEnqueuer.enqueue(comicId, comicTitle, coverUrl, episodes)
    }

    companion object {
        /** 生成任务 ID，与旧 DownloadRepository.taskId 保持一致 */
        fun taskId(comicId: String, episodeOrder: Int) =
            ComicDownloadEnqueuer.taskId(comicId, episodeOrder)

        /**
         * 单话漫画的合成章节。
         *
         * 服务端对这类漫画不返回章节列表，但下载任务需要一个 order 才能定位图片
         * （[com.shizq.bika.core.download.executor.ChapterDownloadExecutor] 用
         * comicId + episodeOrder 请求，不读 episodeId）。order = 1 与
         * [isComicFullyDownloaded] 的单话分支一致。
         */
        internal fun singleEpisodeChapter() = Chapter(
            id = "single_episode",
            title = "全一话",
            order = 1,
            updatedAt = "",
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            comicId: String,
        ): ComicInfoViewModel
    }
}
