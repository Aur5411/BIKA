package com.shizq.bika.feature.reader.impl.util.preload

/**
 * 根据用户最近的翻页速率，动态调整预载张数：
 * 快速扫读时多预载，慢速精读时少预载，避免不必要的带宽/内存开销。
 *
 * @property sampleWindowSize 参与计算平均速率的最近翻页间隔样本数
 * @property minValidInterval 有效翻页间隔下限（毫秒），小于此值视为异常（如连续快速点击）
 * @property maxValidInterval 有效翻页间隔上限（毫秒），大于此值视为异常（如跳章、长时间停留后才翻页）
 * @property fastReadingThreshold 平均间隔小于此值（毫秒）判定为快速扫读
 * @property slowReadingThreshold 平均间隔大于此值（毫秒）判定为慢速精读
 * @property fastReadingPreloadCount 快速扫读时的预载张数
 * @property slowReadingPreloadCount 慢速精读时的预载张数
 */
data class AdaptivePreloadPolicy(
    val sampleWindowSize: Int = 3,
    val minValidInterval: Long = 100,
    val maxValidInterval: Long = 10_000,
    val fastReadingThreshold: Long = 1_500,
    val slowReadingThreshold: Long = 3_200,
    /**
     * 快速扫读时的预载张数。
     *
     * 这是**最能改善"翻页即出图"体验的旋钮**，比提高网络并发更直接：并发只决定
     * 同时能准备几张，窗口决定的是"提前准备了多远"。窗口 16 页意味着快速连翻
     * 十六页都不用等；而窗口 6 页时，翻到第 7 页就开始露加载态。
     *
     * 代价可控：窗口变大不会线性增加请求量——预载队列在窗口移动时会取消离开
     * 窗口的任务（见 PreloadQueue.update），快速滚动下真正发出的请求由实际
     * 走过的页码数决定。预载并发（PRELOAD_CONCURRENCY）与图片域名并发
     * （IMAGE_MAX_REQUESTS_PER_HOST）都已按这个窗口配好，不会自相排队。
     */
    val fastReadingPreloadCount: Int = 16,
    /**
     * 慢速精读时的预载张数。
     *
     * 精读时一页停留很久，准备太远没有意义反而占带宽；取 6 而不是 3，是因为
     * 一话通常有二三十页，突发来一次"连翻几页找剧情"在精读时同样会发生，
     * 6 页的余量足够覆盖，代价只有几 MB。
     */
    val slowReadingPreloadCount: Int = 6,
) {
    /**
     * 有效翻页间隔的取值范围，超出范围的间隔（如跳章、长时间停留）会被 [isValidInterval] 剔除。
     */
    val validIntervalRange: LongRange get() = minValidInterval..maxValidInterval

    fun isValidInterval(intervalMillis: Long): Boolean = intervalMillis in validIntervalRange

    /**
     * 根据最近若干次翻页间隔的平均值，计算建议的预载张数。
     *
     * @param recentIntervals 最近的翻页间隔样本（毫秒），通常取最后 [sampleWindowSize] 个
     * @param baselineCount 用户在设置中配置的基准预载张数；为 0 表示用户关闭了预载，
     *   此时不做自适应调整，始终返回 0
     * @return 建议的预载张数；样本数不足 [sampleWindowSize] 时返回 [baselineCount]（保持现状）
     */
    fun resolvePreloadCount(recentIntervals: List<Long>, baselineCount: Int): Int {
        if (baselineCount == 0) return 0
        if (recentIntervals.size < sampleWindowSize) return baselineCount

        val average = recentIntervals.average()
        return when {
            average < fastReadingThreshold -> fastReadingPreloadCount
            average > slowReadingThreshold -> slowReadingPreloadCount
            else -> baselineCount
        }
    }
}
