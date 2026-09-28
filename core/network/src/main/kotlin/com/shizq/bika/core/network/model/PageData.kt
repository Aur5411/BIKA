package com.shizq.bika.core.network.model

import com.shizq.bika.core.network.utils.LenientIntSerializer
import kotlinx.serialization.Serializable

@Serializable
data class PageData<T>(
    @Serializable(with = LenientIntSerializer::class) val total: Int,
    @Serializable(with = LenientIntSerializer::class) val limit: Int,
    @Serializable(with = LenientIntSerializer::class) val page: Int,
    @Serializable(with = LenientIntSerializer::class) val pages: Int,
    val docs: List<T>,
)

/**
 * 单向分页（`prevKey` 恒为 null）的下一页 key。
 *
 * 两个终止条件都必需：
 * - `requestedPage >= pages`：正常的末页判断。
 * - [docs] 为空：本类所有 Int 字段都套了 [LenientIntSerializer]（服务端偶发
 *   返回非整数），不保证 `pages` 与 `docs` 自洽。只看 `pages` 时，一旦它虚高，
 *   Paging 会对着空页一路 append 到 `pages`，用户侧表现为"滚到底一直转圈
 *   但没有新内容"。
 *
 * 取 [requestedPage] 而不是响应里的 [page]：服务端对超范围请求会做 clamp，
 * 用被 clamp 回来的值算下一页会原地打转。
 *
 * 这条规则原先在 10 个 PagingSource 里各写一遍，且已经出现 `page < pages` 与
 * `page >= pages` 两种写法；收到这里是为了只有一处定义、也才有可测性。
 */
fun PageData<*>.nextPageKey(requestedPage: Int): Int? = when {
    docs.isEmpty() -> null
    requestedPage >= pages -> null
    else -> requestedPage + 1
}

/**
 * 章节列表（`comics/{id}/eps`）的下一页 key。
 *
 * ## 为什么不再用 `pages` / `total` 决定是否收工
 *
 * 这两个字段都被证明过会报小：100 话以上的漫画，服务端可能只报 1~2 页，
 * 也可能报一个小于真实章节数的 `total`。一旦把它们当成停止条件，目录就会
 * 被截断在中间——而且因为服务端按"最新话在前"分页，截断掉的恰好是**最老的
 * 那几话**，用户看到的是"第 1~120 话里缺了第 1~33 话"这种很像数据错的症状。
 *
 * 现在只有三类信号能结束翻页：
 *
 * 1. **服务端返回空页**（[docs] 为空）——唯一不可伪造的边界，本函数负责；
 * 2. **本页内容与上一页完全相同**——服务端对越界页做 clamp 时会这样，
 *    再翻就是原地打转。这需要跨页状态，由调用方（遍历逻辑）判断；
 * 3. **翻到页数硬上限**——兜底，防止服务端无限返回非空页时打转。
 *
 * `total` 与 `pages` 仍然读，但只用于日志与"这次到底拉全没有"的核对，
 * 不再参与终止判断。
 *
 * @param requestedPage 本次请求的页码（用请求值而不是响应里的 `page`：
 *   服务端对超范围请求会 clamp，用被 clamp 回来的值算下一页会原地打转）
 */
fun PageData<*>.nextChapterPageKey(requestedPage: Int): Int? = when {
    docs.isEmpty() -> null
    requestedPage < 1 -> null
    requestedPage >= MAX_CHAPTER_LIST_PAGES -> null
    else -> requestedPage + 1
}

/**
 * 章节列表翻页的页数硬上限。
 *
 * 按每页 20 条算已经覆盖 1200 话，正常漫画远到不了；它的存在只是为了在
 * 服务端异常（每页都返回非空且内容不重复）时给循环一个确定的终点。
 */
const val MAX_CHAPTER_LIST_PAGES: Int = 60

/**
 * 按第一页响应估算"整本要拉到第几页"，用于决定首批并发发多少请求。
 *
 * 这个值**不参与终止判断**，只影响批次的宽度：
 * - 估小了不影响正确性——后面每拉完一批就往前扩一档，只要还收到非空页就继续；
 * - 估大了最多多花一批请求，遇到空页立刻收工。
 *
 * 所以这里可以放心地取 `total` 与 `pages` 算出来的**较大值**：两者都出现过报小，
 * 取大的那个至少不会把批次规划得太窄。两者都不可用时退回"只探一页"，
 * 让一档一档往前推自己收敛。
 *
 * 结果末尾 +1 是**探测页**：末页比 `limit` 短只是经验规律，不是硬边界
 * （去重、服务端删条目都会让中间页变短），只有空页或重复页才算真的到底。
 *
 * @return 至少 2——第 1 页已经在手，至少要探一下第 2 页
 */
fun PageData<*>.estimateLastChapterPage(): Int {
    val perPage = if (limit > 0) limit else docs.size
    if (perPage <= 0) return MIN_ESTIMATED_LAST_PAGE

    val byTotal = if (total > 0) (total + perPage - 1) / perPage else 0
    val byPages = if (pages > 0) pages else 0
    val estimated = maxOf(byTotal, byPages)
    if (estimated <= 0) return MIN_ESTIMATED_LAST_PAGE

    return (estimated + PROBE_PAGE_COUNT)
        .coerceAtLeast(MIN_ESTIMATED_LAST_PAGE)
        .coerceAtMost(MAX_CHAPTER_LIST_PAGES)
}

/** 多探一页，用来确认真的到底了。 */
private const val PROBE_PAGE_COUNT = 1

private const val MIN_ESTIMATED_LAST_PAGE = 2
