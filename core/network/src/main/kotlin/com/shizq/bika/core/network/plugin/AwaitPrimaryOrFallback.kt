package com.shizq.bika.core.network.plugin

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select

/**
 * 在主请求与降级请求之间做选择，**只有成功结果才能赢**。
 *
 * ## 为什么"失败"不能自动获胜
 *
 * 这里曾写成 `fallback.onAwait { result -> result ?: primary.await() }`，
 * 且 `fallback` 的结果**没经过 [isSuccess] 判定**。两处都可能导致一个失败结果
 * 被当成最终答案，在"主源国内被 DNS 污染"这个真实场景下产生极具误导性的症状：
 *
 * 1. API 给的 `fileServer` 是 `storage1.picacomic.com`（污染、连不上）；
 * 2. 主请求卡在 TCP 超时上，或某些中间设备直接回一个 404 页面；
 * 3. 降级因为别的缺陷（例如镜像下载超时）也没成功；
 * 4. 于是 `primary.await()` 的失败结果被当作最终答案 → **用户看到"HTTP 404"**，
 *    而那张图在 `storage.diwodiwo.xyz` 上其实是**存在且能取到的**（已实测 200）。
 *
 * 本函数的契约：
 * - 任意一侧拿到 [isSuccess] 判真的结果 → 立刻采用；
 * - 一侧成功、另一侧失败 → 用成功的（两个 `onAwait` 分支都保证这点）；
 * - 两侧都没成功 → 返回**主请求的失败结果**。
 *
 * 最后一条是有意为之：调用方（[DomainFallbackInterceptor]）在进入降级前已把所有
 * 候选镜像试过一遍，能把失败交到这里，就意味着"所有源都没有这张图"，
 * 此时主请求的异常（带原始 URL 与状态码）信息量最大，最适合呈现给用户。
 */
internal suspend fun <T : Any> awaitPrimaryOrFallback(
    primary: Deferred<T>,
    fallback: Deferred<T?>,
    isSuccess: (T) -> Boolean,
): T = try {
    select {
        primary.onAwait { result ->
            if (isSuccess(result)) {
                result
            } else {
                // 主请求失败：降级成功就用降级的；降级也没成功（null）才用主请求的失败。
                fallback.await() ?: result
            }
        }

        fallback.onAwait { result ->
            // 降级返回的是 T?，必须自己判一次成功，不能因为"非 null"就直接采用
            // ——旧实现漏了这一步，一个 ErrorResult 会被当成成功结果直接返回。
            if (result != null && isSuccess(result)) {
                result
            } else {
                // 降级没给出成功结果：等主请求的最终结论（成功或失败）。
                primary.await()
            }
        }
    }
} finally {
    // 无论走哪条路，都要把另一侧停掉，避免它在后台继续占着名额和连接。
    primary.cancel()
    fallback.cancel()
}
