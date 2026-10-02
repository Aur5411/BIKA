package com.shizq.bika.core.data.paging

/**
 * 出现空页 / 重复页后，还允许往前探几页。
 *
 * 正常服务端在末页之后返回空页，一次就能确认到底。但对长目录（100 话以上）
 * 实测会出现"中间某页返回空、后面还有数据"和"越界页被 clamp 回上一页"两种情况，
 * 一次就认输会让目录稳定丢掉最老的那几十话。这里给一个很小的探测预算：
 * 真的到底时最多多花几页请求，代价可忽略；探测中一旦收到新数据即重置回满额
 * （见 [chapterCatalogWalkDecision]），只有**连续**无新数据才耗尽预算。
 */
internal const val CATALOG_TERMINAL_PROBE_PAGES = 3

/**
 * 章节目录翻页的单页终止决策（纯函数，见 [chapterCatalogWalkDecision]）。
 *
 * @property isTerminal true = 到此为止，结束整个遍历
 * @property isSuspicious true = 本页命中了终止信号、但判定为"可疑"而继续探测
 *   （服务端偶发把中间页返回成空页/重复页，而自报总数说明后面还有数据）
 * @property probesLeft 探测后的剩余探测次数
 */
internal data class ChapterCatalogWalkDecision(
    val isTerminal: Boolean,
    val isSuspicious: Boolean,
    val probesLeft: Int,
)

/**
 * 决定章节目录翻到某页后"继续 / 探测 / 结束"。
 *
 * ## 终止信号（v1.11.36 修复后共三类）
 *
 * 1. **空页**：服务端对越界页返回空 docs；
 * 2. **与紧邻上一页完全相同**：服务端把越界页 clamp 回上一页的形态；
 * 3. **非空但没有任何新章节**（本次修复新增）：服务端把越界页 clamp 到
 *    **任意已见页**（不一定是紧邻上一页），或在新旧章节间抖动时回吐已见内容。
 *    旧实现只认形态 2，这种 clamp 会让遍历对着同样的数据一路翻到页数上限、
 *    或先撞上整体超时——目录"又不能全部加载"的回归即来源于此。
 *
 * ## 探测预算与重置（本次修复）
 *
 * 服务端偶发"空一页、下一页又正常"甚至空页与数据页**交替**出现。一次就认输
 * 会稳定丢掉最老的那几十话，所以给了探测预算；但旧实现预算只减不补——
 * 交替场景下 3 次预算被空页逐步耗尽，照样提前终止。
 * 现在改为：**探测中只要某一页真的带来了新章节，就把预算重置回满额**。
 * 只有**连续** [ChapterRepositoryImpl.CATALOG_TERMINAL_PROBE_PAGES] 页没有任何
 * 新数据时才认输——那才真的是"服务端拿不出更多了"。
 *
 * @param isEmptyPage 本页 docs 为空
 * @param pageRepeated 本页与紧邻上一页逐字相同（clamp 回上一页）
 * @param pageHasNewChapters 去重后本页带来了新章节（false = 空页或全部已见）
 * @param loaded 到本页为止累计的章节数
 * @param expectedTotal 服务端自报总数（0 = 未知）
 * @param probesLeft 进入本页前的剩余探测次数
 */
internal fun chapterCatalogWalkDecision(
    isEmptyPage: Boolean,
    pageRepeated: Boolean,
    pageHasNewChapters: Boolean,
    loaded: Int,
    expectedTotal: Int,
    probesLeft: Int,
): ChapterCatalogWalkDecision {
    val terminalSignal = isEmptyPage || pageRepeated || !pageHasNewChapters
    val shortOfExpected = expectedTotal > 0 && loaded < expectedTotal
    val canProbe = terminalSignal && shortOfExpected && probesLeft > 0

    return when {
        canProbe -> ChapterCatalogWalkDecision(
            isTerminal = false,
            isSuspicious = true,
            probesLeft = if (pageHasNewChapters) CATALOG_TERMINAL_PROBE_PAGES else probesLeft - 1,
        )
        terminalSignal -> ChapterCatalogWalkDecision(
            isTerminal = true,
            isSuspicious = false,
            probesLeft = probesLeft,
        )
        // 正常数据页：预算同样重置回满额。预算的语义是「连续多少页没有新数据」，
        // 任何带来新章节的页都应把这段连续计数清零——否则"空页与数据页交替"
        // 的服务端行为照样能把预算耗尽（v1.11.36 首版实现就漏了这一支）。
        else -> ChapterCatalogWalkDecision(
            isTerminal = false,
            isSuspicious = false,
            probesLeft = CATALOG_TERMINAL_PROBE_PAGES,
        )
    }
}
