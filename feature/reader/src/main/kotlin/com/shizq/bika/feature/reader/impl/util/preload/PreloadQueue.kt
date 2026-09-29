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
 * 内不会忙重试，离开窗口后清除失败标记，重新进入时可以再次尝试。
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
    private val failedInWindow = mutableSetOf<K>()
    private var closed = false

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
        failedInWindow.retainAll(wanted.keys)

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
        failedInWindow.clear()
        running.values.toList().forEach { it.cancel() }
    }

    private fun drain() {
        if (closed || !scope.isActive) return
        // Snapshot the window: a synchronous cache hit may complete during job.start().
        for ((key, item) in wanted.toList()) {
            if (running.size >= maxConcurrent) break
            if (key in running || key in completed || key in failedInWindow) continue
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
                    } else {
                        // Do not spin on a persistent failure while the same window stays active.
                        // Leaving the window clears this marker, so returning later can retry.
                        failedInWindow.add(key)
                    }
                }
                drain()
            }
            job.start()
        }
    }
}
