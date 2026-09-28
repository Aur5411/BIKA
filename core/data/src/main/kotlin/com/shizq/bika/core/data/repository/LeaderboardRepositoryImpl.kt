package com.shizq.bika.core.data.repository

import com.shizq.bika.core.data.model.asExternalModel
import com.shizq.bika.core.network.BikaDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

private val logger = KotlinLogging.logger("LeaderboardRepository")

/**
 * 榜单缓存有效期。
 *
 * 榜单按 "H24 / D7 / D30" 统计，实际数据以小时为粒度变化，10 分钟足够新鲜；
 * 用户主动刷新始终走 [LeaderboardRepository.getLeaderboards] 的 forceRefresh，
 * 不受这个值影响。
 */
private const val CACHE_TTL_MS = 10 * 60 * 1000L

private const val TIME_H24 = "H24"
private const val TIME_D7 = "D7"
private const val TIME_D30 = "D30"

@Singleton
internal class LeaderboardRepositoryImpl @Inject constructor(
    private val api: BikaDataSource,
) : LeaderboardRepository {

    private val mutex = Mutex()

    @Volatile
    private var cached: Leaderboards? = null

    @Volatile
    private var cachedAtEpochMs: Long = 0L

    override suspend fun getLeaderboards(forceRefresh: Boolean): Leaderboards =
        mutex.withLock {
            val snapshot = cached
            if (!forceRefresh && snapshot != null && !isExpired()) {
                logger.debug { "榜单命中缓存，跳过网络请求" }
                return snapshot
            }

            // 网络请求放在锁内：并发调用会被串行化，后到的调用者直接拿到
            // 前一个刚写回的缓存，不会把同一份数据重复拉四遍
            val fresh = fetchAll()
            cached = fresh
            cachedAtEpochMs = Clock.System.now().toEpochMilliseconds()
            logger.info { "榜单已刷新并写入缓存" }
            return fresh
        }

    override suspend fun invalidate() = mutex.withLock {
        cached = null
        cachedAtEpochMs = 0L
    }

    private fun isExpired(): Boolean =
        Clock.System.now().toEpochMilliseconds() - cachedAtEpochMs > CACHE_TTL_MS

    /** 四个榜并发拉取：串行拉的话总耗时是四段之和，首屏要多等好几秒。 */
    private suspend fun fetchAll(): Leaderboards = coroutineScope {
        val daily = async { api.getLeaderboard(TIME_H24).comics }
        val weekly = async { api.getLeaderboard(TIME_D7).comics }
        val monthly = async { api.getLeaderboard(TIME_D30).comics }
        val knights = async { api.getKnightLeaderboard().users.map { it.asExternalModel() } }

        Leaderboards(
            dailyComics = daily.await(),
            weeklyComics = weekly.await(),
            monthlyComics = monthly.await(),
            knightUsers = knights.await(),
        )
    }
}
