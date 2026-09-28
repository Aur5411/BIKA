package com.shizq.bika.core.data.repository

import com.shizq.bika.core.data.model.User
import com.shizq.bika.core.model.ComicSummary

/**
 * 榜单四个榜的数据。
 *
 * 四个榜来自四个接口，但界面上是一个整体（切换 Tab 不重新请求），
 * 所以缓存与请求都以这一组为单位。
 */
data class Leaderboards(
    val dailyComics: List<ComicSummary>,
    val weeklyComics: List<ComicSummary>,
    val monthlyComics: List<ComicSummary>,
    val knightUsers: List<User>,
)

/**
 * 榜单数据仓库。
 *
 * 存在的唯一理由是**缓存**：榜单是"看几眼就走、过一会儿又回来"的页面，
 * 而它的数据变化极慢（小时级）。每次进页面都重新发四个请求，用户看到的就是
 * "从漫画详情返回榜单还要再转一次圈"。
 *
 * 缓存放在仓库而不是 ViewModel：ViewModel 会随导航栈被销毁重建，
 * 放那里等于没缓存；放仓库（单例）才能跨页面重建复用。
 */
interface LeaderboardRepository {

    /**
     * 取榜单数据。
     *
     * 命中未过期缓存时不发任何请求，直接返回上次结果。
     *
     * @param forceRefresh 忽略缓存强制重新拉取（用户主动下拉刷新 / 点重试时用）
     */
    suspend fun getLeaderboards(forceRefresh: Boolean = false): Leaderboards

    /** 让缓存失效；下一次 [getLeaderboards] 会重新请求。 */
    suspend fun invalidate()
}
