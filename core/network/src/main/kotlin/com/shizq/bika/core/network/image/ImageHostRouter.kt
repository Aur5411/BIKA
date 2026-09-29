package com.shizq.bika.core.network.image

import jakarta.inject.Inject
import jakarta.inject.Singleton

/** 最快源的保鲜期：超过这个时间重新接受一次竞速结果，防止网络环境切换后一直吊死在旧源。 */
private const val PREFERRED_HOST_TTL_MS = 10 * 60 * 1000L

/**
 * 记住"当前最快的图片源"，让后续每张图直接走它。
 *
 * ## 为什么需要它
 *
 * 只有 [com.shizq.bika.core.network.plugin.DomainFallbackInterceptor] 时，
 * 每张图都是"先打 API 给的 fileServer，慢/失败才竞速"。也就是说：
 * 用户每翻一页，都要先在慢源上耗掉一个竞速窗口（见 `SLOW_MAIN_THRESHOLD_MS`），
 * 竞速学到的"哪个源快"只被用在同一次请求的后续重试里，下一张图又从头再来。
 *
 * 有这个路由后，竞速一旦决出胜者就记下来，之后的图片请求在进 Coil 链之前
 * 就被改写到胜者域名（见 [PreferredHostInterceptor]）——第一张图之后的每一张
 * 都直接打最快的源。
 *
 * ## 缓存不会因此失效
 *
 * 阅读器与下载链路在构造 `ImageRequest` 时都显式写了 `diskCacheKey = 原始 url`
 * （见 `ComicItem` / `ChapterPagePreloadProvider`），换域名不改变缓存键，
 * 因此换源不会导致同一张图重复下载、重复占盘。
 *
 * ## 失效
 *
 * 源挂掉、被限流都会表现为请求失败，调用方用 [forget] 清掉记录，
 * 下一张图重新走"主源 + 竞速"，选路过程重来一遍。
 */
@Singleton
class ImageHostRouter @Inject constructor() {

    @Volatile
    private var preferred: String? = null

    @Volatile
    private var decidedAtEpochMs: Long = 0L

    /**
     * 当前最快源；超过保鲜期或从未选出时返回 null（调用方按原样走主源）。
     */
    fun preferredHost(nowMs: Long = System.currentTimeMillis()): String? {
        val host = preferred ?: return null
        return if (nowMs - decidedAtEpochMs > PREFERRED_HOST_TTL_MS) null else host
    }

    /** 竞速/串行尝试胜出时调用。 */
    fun remember(host: String, nowMs: Long = System.currentTimeMillis()) {
        preferred = host
        decidedAtEpochMs = nowMs
    }

    /** 该源失败时调用；只清掉与传入 host 相符的记录，避免误清新学到的源。 */
    fun forget(host: String) {
        if (preferred == host) {
            preferred = null
            decidedAtEpochMs = 0L
        }
    }
}
