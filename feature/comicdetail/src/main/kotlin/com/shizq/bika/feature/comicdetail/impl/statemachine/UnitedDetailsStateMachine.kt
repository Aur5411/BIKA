@file:OptIn(ExperimentalCoroutinesApi::class)

package com.shizq.bika.feature.comicdetail.impl.statemachine

import android.util.Log
import com.freeletics.flowredux2.FlowReduxStateMachineFactory
import com.freeletics.flowredux2.initializeWith
import com.shizq.bika.core.database.dao.ReadingHistoryDao
import com.shizq.bika.core.database.model.ReadingHistoryEntity
import com.shizq.bika.core.network.BikaDataSource
import com.shizq.bika.core.network.model.ActionData
import com.shizq.bika.core.network.runCatchingApi
import com.shizq.bika.feature.comicdetail.impl.ComicDetail
import com.shizq.bika.feature.comicdetail.impl.ComicSummary
import com.shizq.bika.feature.comicdetail.impl.UnitedDetailsAction
import com.shizq.bika.feature.comicdetail.impl.UnitedDetailsUiState
import com.shizq.bika.feature.comicdetail.impl.toComicDetail
import com.shizq.bika.feature.comicdetail.impl.toComicSummaryList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

class UnitedDetailsStateMachine @AssistedInject constructor(
    private val network: BikaDataSource,
    private val historyDao: ReadingHistoryDao,
    // 原名 id：与"评论 id"在状态机和 ViewModel 里同名同类型，传错编译器不拦
    @Assisted private val comicId: String,
) : FlowReduxStateMachineFactory<UnitedDetailsUiState, UnitedDetailsAction>() {

    init {
        initializeWith { UnitedDetailsUiState.Initialize }
        spec {
            inState<UnitedDetailsUiState.Initialize> {
                onEnter {
                    // 只等详情接口，**不等推荐**。
                    //
                    // 曾经这里是 `detailDeferred.await() to recommendationsDeferred.await()`：
                    // 两个请求并发发出，但状态推进必须等两者都回来。于是推荐慢或超时
                    // （上限 RECOMMENDATIONS_TIMEOUT_MS）会把整个详情页按在转圈上
                    // ——详情其实早就到手了。
                    //
                    // 现在推荐挪到 Content 之后再补（见下方单独的 inState<Content> 块）：
                    // 首屏只取决于详情一个请求。
                    runCatchingApi { network.getComicDetails(comicId).toComicDetail() }.fold(
                        onSuccess = { detail ->
                            // 历史写入放在这里而不是 Content 的 onEnter：
                            // 后者在每次重新进入 Content（例如 Error→Retry→成功）时都会跑，
                            // 而"加载成功"恰好发生一次，语义明确且与原先的意图等价。
                            historyDao.upsertHistory(detail.toHistoryEntity(comicId))
                            Log.d(
                                TAG,
                                "Upsert history for '${detail.title}' with full comic details."
                            )
                            override {
                                UnitedDetailsUiState.Content(
                                    id = comicId,
                                    detail = detail,
                                    // 推荐是页面底部的附加内容，先留空，到了再由
                                    // Content 的 onEnter 补上。
                                    recommendations = emptyList(),
                                )
                            }
                        },
                        onFailure = { override { UnitedDetailsUiState.Error(it) } }
                    )
                }
            }

            inState<UnitedDetailsUiState.Content> {
                // 第一屏已经在屏幕上了，推荐晚到多久都不影响它。
                //
                // 拿不到就保持空列表：UI 侧本来就是
                // `if (recommendations.isNotEmpty())` 才渲染这一段，空列表等于不显示，
                // 不需要额外的"加载中/失败"状态，也不会打断阅读。
                //
                // 依赖 flowredux2 的一条语义：在这里 `mutate` 不会重入本 onEnter。
                // 该语义由 FlowReduxOnEnterReentryTest 钉住（否则会变成无限重发推荐请求）。
                onEnter {
                    val recommendations = loadRecommendations()
                    // 必须显式给出 else 分支：onEnter 的返回值是 ChangedState<…>，
                    // 只写 `if (...) mutate {}` 会让类型推断成 Unit 而编译不过。
                    // 拿不到推荐属于正常情况（服务端慢/没有推荐位），noChange() 即可。
                    if (recommendations.isNotEmpty()) {
                        mutate { copy(recommendations = recommendations) }
                    } else {
                        noChange()
                    }
                }

                on<UnitedDetailsAction.ToggleLike> {
                    val currentDetail = snapshot.detail

                    runCatchingApi { network.toggleComicLike(snapshot.id) }.fold(
                        onSuccess = { r ->
                            val isLiked = when (r.action) {
                                ACTION_LIKE -> true
                                ACTION_UNLIKE -> false
                                else -> currentDetail.isLiked
                            }
                            mutate {
                                copy(detail = currentDetail.copy(isLiked = isLiked))
                            }
                        },
                        onFailure = { e ->
                            Log.e(TAG, "ToggleLike: ", e)
                            noChange()
                        }
                    )
                }
                on<UnitedDetailsAction.ToggleFavorite> {
                    val currentDetail = snapshot.detail

                    runCatchingApi { network.toggleComicFavourite(snapshot.id) }.fold(
                        onSuccess = { r ->
                            val isFavourited = when (r.action) {
                                ACTION_FAVORITE -> true
                                ACTION_UN_FAVORITE -> false
                                else -> currentDetail.isFavourited
                            }
                            // Room 的 suspend 方法自带调度，不需要外层再包 Dispatchers.IO
                            historyDao.updateIsFavourited(snapshot.id, isFavourited)
                            Log.d(
                                TAG,
                                "Sync isFavourited for '${snapshot.id}' to local database: $isFavourited"
                            )
                            mutate {
                                copy(detail = currentDetail.copy(isFavourited = isFavourited))
                            }
                        },
                        onFailure = { e ->
                            Log.e(TAG, "ToggleFavorite: ", e)
                            noChange()
                        }
                    )
                }
            }

            inState<UnitedDetailsUiState.Error> {
                on<UnitedDetailsAction.Retry> {
                    override { UnitedDetailsUiState.Initialize }
                }
            }
        }
    }

    /**
     * 拉推荐列表；任何失败/超时都退回空列表。
     *
     * 这个值只影响页面底部的推荐位，**不参与首屏判定**，所以：
     * - 超时可以给得比过去宽松（过去 1.5s 是为了把对首屏的拖累压到最小，
     *   现在它不拖累任何人，太短反而会让慢一点点的推荐白丢）；
     * - 失败不上报、不抛给状态机，页面保持"没有推荐"即可。
     */
    private suspend fun loadRecommendations(): List<ComicSummary> = try {
        withTimeoutOrNull(RECOMMENDATIONS_TIMEOUT_MS) {
            network.getRecommendations(comicId).toComicSummaryList()
        }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Recommendations unavailable; detail page stays visible without them", e)
        emptyList()
    }

    @AssistedFactory
    interface Factory {
        fun create(comicId: String): UnitedDetailsStateMachine
    }

    private companion object {
        /**
         * 推荐请求的等待上限。
         *
         * 从 1.5s 放宽到 8s：这个上限原先存在的唯一理由是"别把首屏拖住"，
         * 而推荐已经不再参与首屏判定（见 Content 的 onEnter），
         * 卡在 1.5s 只会让稍慢的推荐白丢。
         */
        private const val RECOMMENDATIONS_TIMEOUT_MS = 8_000L
        const val ACTION_LIKE = ActionData.ACTION_LIKE
        const val ACTION_UNLIKE = ActionData.ACTION_UNLIKE
        const val ACTION_FAVORITE = ActionData.ACTION_FAVORITE
        const val ACTION_UN_FAVORITE = ActionData.ACTION_UN_FAVOURITE
        private const val TAG = "UnitedDetailsStateMachine"
    }
}

private fun ComicDetail.toHistoryEntity(comicId: String) = ReadingHistoryEntity(
    id = comicId,
    title = title,
    author = author,
    coverUrl = cover,
    lastInteractionAt = Clock.System.now(),
    categories = categories,
    pagesCount = pagesCount,
    epsCount = epsCount,
    finished = finished,
    totalLikes = totalLikes,
    isFavourited = isFavourited
)