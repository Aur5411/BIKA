package com.shizq.bika.feature.reader.impl.util.preload

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps only the current reading window, in nearest-page-first order.
 * All calls and [scope] must use the same single-threaded dispatcher (the UI dispatcher).
 * [execute] 返回 true 表示预载成功；返回 false 表示失败。失败任务在当前窗口
 * 内不会被立刻忙重试，但**冷却一段时间后允许再试**（见 [FAILED_KEY_COOLDOWN_MS]）——
 * 原实现是"在这个窗口里永久拉黑"，于是一次瞬时 429 就让某几张图在用户停留期间
 * 再也不可能被预载成功，用户滑到那里就只能看到失败态。
 *
 * [maxConcurrent] 刻意不设默认值：它原先默认 2，而生产调用点没显式传参，
 * 于是"预载只有 2 并发"这件事在任何地方都读不出来，只能翻源码才发现——
 * 阅读页图慢有它一份。改成必填后，每个调用点都得写明自己要多少并发。
 */
internal class PreloadQueue<K : Any, T : Any>(
    private val scope: CoroutineScope,
    private val maxConcurrent: Int,
    private val keyOf: (T) -> K,
    private val execute: suspend (T) -> Boolean,
) {
    init {
        require(maxConcurrent > 0)
    }

    private var wanted = linkedMapOf<K, T>()
    private val running = mutableMapOf<K, Job>()
    private val completed = mutableSetOf<K>()

    /** 失败任务的"拉黑截止时刻"（epoch ms）。到期后自动解除，允许重新预载。 */
    private val failedUntilMs = mutableMapOf<K, Long>()
    private var closed = false

    /** 最近一次 [update] 的时间，用于判断拉黑是否过期（[update] 由 UI 线程驱动，频率足够）。 */
    private var lastTickMs = System.currentTimeMillis()

    /**
     * 预载窗口里原本有任务、本次 [update] 后却一个都没有的次数。
     *
     * 这不是失败，而是一个**结构性问题**：预载窗口的每一条都取不到数据
     * （典型情形是 Paging 的 itemCount 还没长到窗口覆盖的范围）。此时队列会
     * 一直空着，直到下一次视口事件或数据刷新才重新尝试；用户在这一段时间里
     * 已经翻到了那些页，但没人去准备它们。
     *
     * 真实场景就发生在分页边界：章节每 40 张图一个 API 页，预载窗口一旦越过
     * 已加载范围末尾（约第 20 页之后是最常见的落点），`getItem` 全部返回 null，
     * 窗口直接饿死。
     */
    @Volatile
    var starvationCount: Int = 0
        private set

    fun update(items: List<T>, retainRunning: Set<K> = emptySet()) {
        if (closed) return
        val previousSize = wanted.size
        wanted = items.associateByTo(linkedMapOf(), keyOf)
        completed.retainAll(wanted.keys)
        failedUntilMs.keys.retainAll(wanted.keys)

        // 拉动时间轴：把已过期的拉黑记录清掉，让它们重新参与调度。
        lastTickMs = System.currentTimeMillis()
        if (failedUntilMs.isNotEmpty()) {
            failedUntilMs.entries.removeAll { it.value <= lastTickMs }
        }

        if (wanted.isEmpty() && previousSize > 0) {
            starvationCount++
        }

        // A prefetched page that has just become visible should finish its download.
        // Its foreground request can then read the same disk entry without restarting.
        running.filterKeys { it !in wanted && it !in retainRunning }.values.forEach { it.cancel() }
        drain()
    }

    fun close() {
        closed = true
        wanted.clear()
        completed.clear()
        failedUntilMs.clear()
        running.values.toList().forEach { it.cancel() }
    }

    private fun drain() {
        if (closed || !scope.isActive) return
        val nowMs = System.currentTimeMillis()
        // Snapshot the window: a synchronous cache hit may complete during job.start().
        for ((key, item) in wanted.toList()) {
            if (running.size >= maxConcurrent) break
            if (key in running || key in completed) continue
            // 冷却未到期则跳过；到期即视为可重试。
            val blockedUntil = failedUntilMs[key]
            if (blockedUntil != null && blockedUntil > nowMs) continue
            var succeeded = false
            val job = scope.launch(start = CoroutineStart.LAZY) {
                succeeded = execute(item)
            }
            running[key] = job
            // A rapid swipe can cancel a dispatched job before its body ever starts.
            // Completion handlers still run in that case, unlike a finally in the body.
            job.invokeOnCompletion { cause ->
                running.remove(key)
                if (cause == null && key in wanted) {
                    if (succeeded) {
                        completed.add(key)
                        failedUntilMs.remove(key)
                    } else {
                        // 失败：拉黑一小段时间，避免对持续失败的任务做忙重试；
                        // 到期后自动解除，下一轮 drain（通常由滚动事件触发）会再试一次。
                        failedUntilMs[key] = System.currentTimeMillis() + FAILED_KEY_COOLDOWN_MS
                    }
                }
                drain()
            }
            job.start()
        }
    }

    private companion object {
        /**
         * 失败任务的重试冷却。
         *
         * 取 4s：足够躲开一次瞬时 429（冷却上限 12s 里最外层的窗口通常 1~3s），
         * 又短到用户在当前窗口停留期间还能等到一次自动重试。
         */
        const val FAILED_KEY_COOLDOWN_MS = 4_000L
    }
}
