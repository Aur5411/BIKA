package com.shizq.bika.ui.leaderboard

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shizq.bika.core.coroutine.FlowRestarter
import com.shizq.bika.core.coroutine.restartable
import com.shizq.bika.core.data.model.User
import com.shizq.bika.core.data.repository.LeaderboardRepository
import com.shizq.bika.core.database.dao.ReadingHistoryDao
import com.shizq.bika.core.datastore.UserPreferencesDataSource
import com.shizq.bika.core.model.ComicSummary
import com.shizq.bika.core.result.Result
import com.shizq.bika.core.result.asResult
import com.shizq.bika.util.injectLocalStatusFrom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val leaderboardRepository: LeaderboardRepository,
    private val historyDao: ReadingHistoryDao,
    private val userPreferencesDataSource: UserPreferencesDataSource,
) : ViewModel() {
    private val leaderboardRestarter = FlowRestarter()

    val scrollStates = List(4) { LazyListState() }

    /**
     * 榜单数据。
     *
     * 走 [LeaderboardRepository] 而不是直接打接口，是为了拿到缓存：
     * 榜单数据以小时为粒度变化，用户却是"看几眼 → 进漫画 → 返回"的用法，
     * 每次都重新请求四个接口，返回时就要再转一次圈。
     *
     * 这里不传 forceRefresh：命中缓存时零请求返回、缓存过期时自动重拉，
     * 只有用户主动刷新（[refresh]）才强制绕过缓存。
     */
    private val rawLeaderboardFlow = flow {
        emit(leaderboardRepository.getLeaderboards())
    }

    val leaderboardUiState = combine(
        rawLeaderboardFlow,
        // DB Flow：收藏/阅读进度任何变化都会触发此流发射新值，驱动 UI 实时更新
        historyDao.getDetailedHistories(),
        userPreferencesDataSource.userData,
    ) { allData, histories, prefs ->
        val daily = allData.dailyComics.injectLocalStatusFrom(histories)
        val weekly = allData.weeklyComics.injectLocalStatusFrom(histories)
        val monthly = allData.monthlyComics.injectLocalStatusFrom(histories)

        val filteredDaily =
            if (prefs.filter.globalTopicBlockEnabled && prefs.filter.globalBlockedTopics.isNotEmpty()) {
                daily.filter { comic -> prefs.filter.globalBlockedTopics.none { it in comic.categories } }
            } else daily

        val filteredWeekly =
            if (prefs.filter.globalTopicBlockEnabled && prefs.filter.globalBlockedTopics.isNotEmpty()) {
                weekly.filter { comic -> prefs.filter.globalBlockedTopics.none { it in comic.categories } }
            } else weekly

        val filteredMonthly =
            if (prefs.filter.globalTopicBlockEnabled && prefs.filter.globalBlockedTopics.isNotEmpty()) {
                monthly.filter { comic -> prefs.filter.globalBlockedTopics.none { it in comic.categories } }
            } else monthly

        val finalDaily = if (prefs.filter.blockedTags.isNotEmpty()) {
            filteredDaily.filter { comic -> comic.tags.none { it in prefs.filter.blockedTags } }
        } else filteredDaily

        val finalWeekly = if (prefs.filter.blockedTags.isNotEmpty()) {
            filteredWeekly.filter { comic -> comic.tags.none { it in prefs.filter.blockedTags } }
        } else filteredWeekly

        val finalMonthly = if (prefs.filter.blockedTags.isNotEmpty()) {
            filteredMonthly.filter { comic -> comic.tags.none { it in prefs.filter.blockedTags } }
        } else filteredMonthly

        AllLeaderboards(
            dailyComics = finalDaily,
            weeklyComics = finalWeekly,
            monthlyComics = finalMonthly,
            knightUsers = allData.knightUsers,
        )
    }
        .asResult()
        .restartable(leaderboardRestarter)
        .map { result ->
            when (result) {
                is Result.Error -> LeaderboardUiState.Error(
                    result.exception.message ?: "Unknown Error"
                )

                Result.Loading -> LeaderboardUiState.Loading
                is Result.Success -> {
                    val allData = result.data
                    LeaderboardUiState.Success(
                        dailyList = allData.dailyComics,
                        weeklyList = allData.weeklyComics,
                        monthlyList = allData.monthlyComics,
                        knightList = allData.knightUsers
                    )
                }
            }
        }.stateIn(
            scope = viewModelScope,
            // Lazily 而不是 WhileSubscribed(5s)：后者在用户进入漫画详情、离开本页
            // 超过 5 秒后会取消上游，返回时重新订阅 → 重新走一遍 Loading，
            // 于是"从漫画返回榜单"必然闪一次转圈。这个页面活在导航栈上时
            // 数据本就该留着，也省掉一次无意义的重新请求。
            started = SharingStarted.Lazily,
            initialValue = LeaderboardUiState.Loading
        )

    /**
     * 用户主动刷新（刷新按钮 / 出错重试）。
     *
     * 先让仓库缓存失效，再重启数据流，保证这一次一定打到网络，
     * 而不是又被缓存挡回来。
     */
    fun refresh() {
        viewModelScope.launch {
            leaderboardRepository.invalidate()
            leaderboardRestarter.restart()
        }
    }

    private data class AllLeaderboards(
        val dailyComics: List<ComicSummary>,
        val weeklyComics: List<ComicSummary>,
        val monthlyComics: List<ComicSummary>,
        val knightUsers: List<User>
    )
}

sealed interface LeaderboardUiState {
    data class Success(
        val dailyList: List<ComicSummary>,
        val weeklyList: List<ComicSummary>,
        val monthlyList: List<ComicSummary>,
        val knightList: List<User>
    ) : LeaderboardUiState

    data class Error(val message: String) : LeaderboardUiState
    data object Loading : LeaderboardUiState
}
