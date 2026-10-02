package com.shizq.bika.feature.comicdetail.impl

import androidx.compose.runtime.Immutable
import com.shizq.bika.core.model.normalizedTag
import com.shizq.bika.core.network.model.ComicData
import com.shizq.bika.core.network.model.ComicData.Comic.Creator
import com.shizq.bika.core.network.model.RecommendComicDto
import com.shizq.bika.core.network.model.RecommendationData

sealed interface UnitedDetailsUiState {
    data object Initialize : UnitedDetailsUiState

    @Immutable
    data class Content(
        val id: String,
        val detail: ComicDetail = ComicDetail(),
        val recommendations: List<ComicSummary> = emptyList(),
    ) : UnitedDetailsUiState

    data class Error(val cause: Throwable) : UnitedDetailsUiState
}

fun ComicData.toComicDetail(): ComicDetail {
    return ComicDetail(
        author = comic.author,
        categories = comic.categories,
        chineseTeam = comic.chineseTeam,
        commentsCount = comic.commentsCount,
        createdAt = comic.createdAt,
        creator = comic.creator,
        description = comic.description,
        epsCount = comic.epsCount,
        finished = comic.finished,
        id = comic.id,
        isFavourited = comic.isFavourite,
        isLiked = comic.isLiked,
        pagesCount = comic.pagesCount,
        tags = comic.tags,
        cover = comic.thumb.originalImageUrl,
        title = comic.title,
        totalLikes = comic.totalLikes,
        totalViews = comic.totalViews,
        updatedAt = comic.updatedAt
    )
}

data class ComicDetail(
    val author: String = "",
    val categories: List<String> = listOf(),
    val chineseTeam: String = "",
    val commentsCount: Int = 0,
    val createdAt: String = "",
    val creator: Creator = Creator(),
    val description: String = "",
    val epsCount: Int = 0,
    val finished: Boolean = false,
    val id: String = "",
    val isFavourited: Boolean = false,
    val isLiked: Boolean = false,
    val pagesCount: Int = 0,
    val tags: List<String> = listOf(),
    val cover: String = "",
    val title: String = "",
    val totalLikes: Int = 0,
    val totalViews: Int = 0,
    val updatedAt: String = "",
)

data class ComicSummary(
    val id: String,
    val title: String,
    val coverUrl: String,
    val author: String,
    // 推荐接口（RecommendComicDto）只返回 categories、不返回 tags。
    // 之前这里不接 categories，导致详情页推荐位在结构上无法按屏蔽词过滤——
    // 只能改标题/作者来匹配，屏蔽词几乎永远命中不了。
    val categories: List<String> = emptyList(),
)

fun RecommendComicDto.toComicSummary(): ComicSummary {
    return ComicSummary(
        id = id,
        title = title,
        coverUrl = thumb.originalImageUrl,
        author = author,
        categories = categories,
    )
}

fun RecommendationData.toComicSummaryList(): List<ComicSummary> {
    return this.comics.map { it.toComicSummary() }
}

/**
 * 推荐位是否命中屏蔽列表。
 *
 * 推荐接口只返回 categories（见 [RecommendComicDto]），所以这里只看 categories。
 * 归一化规则与 `core.model` 里列表页用的完全一致（去首尾空格 + 忽略大小写），
 * 否则同一个屏蔽词在列表页生效、在推荐页不生效，用户会以为"屏蔽时灵时不灵"。
 */
fun ComicSummary.matchesBlockedTags(blockedTags: Set<String>): Boolean {
    if (blockedTags.isEmpty()) return false
    val blocked = blockedTags.map { it.normalizedTag() }.toSet()
    if (blocked.isEmpty()) return false
    return categories.any { it.normalizedTag() in blocked }
}