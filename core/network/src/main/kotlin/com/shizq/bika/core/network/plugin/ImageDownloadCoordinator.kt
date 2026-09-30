package com.shizq.bika.core.network.plugin

import coil3.annotation.ExperimentalCoilApi
import coil3.fetch.FetchResult
import coil3.network.ConcurrentRequestStrategy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes fetches for the same cache key. After a preload writes the image to disk,
 * the visible request rechecks that entry instead of downloading the same bytes again.
 * Mirror races must remain independent so a stalled primary cannot block its own fallback.
 *
 * ## 等待可被打断
 *
 * 这里刻意**不使用不响应取消的 `Mutex.withLock` 语义**去排队：一个同 key 的请求
 * 在飞时，后来者（典型是用户点的"重试"）必须能等到前一个结束就立刻开跑，
 * 而不是排在一个可能已经卡死的锁后面。原先的实现问题不大（`Mutex` 本身响应取消），
 * 但配合上层 45s 的重试预算后，一个卡住的同 key 请求仍能把重试拖到超时，
 * 表现为"点了重试也没用"。这里改为**带超时的等待**：等待超过 [MAX_WAIT_MS]
 * 就放弃排队、直接自己发一次请求——重复下载一张图的成本，远小于让用户
 * 对着永远不动的加载圈。
 */
@OptIn(ExperimentalCoilApi::class)
internal class ImageDownloadCoordinator : ConcurrentRequestStrategy {
    private val requests = KeyedRequestCoordinator()

    override suspend fun apply(key: String, block: suspend () -> FetchResult): FetchResult =
        if (currentCoroutineContext()[FallbackMarker] != null) block()
        else requests.withKey(key, block)
}

/** Entries include waiting callers, and are released even when cancelled before acquiring. */
internal class KeyedRequestCoordinator {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            // 带超时的抢锁：等不到就自己跑，绝不把请求无限期挂在别人后面。
            val acquired = withTimeoutOrNull(MAX_WAIT_MS) { entry.mutex.lock() }
            return if (acquired != null) {
                try {
                    block()
                } finally {
                    entry.mutex.unlock()
                }
            } else {
                block()
            }
        } finally {
            synchronized(entries) {
                if (--entry.users == 0) entries.remove(key)
            }
        }
    }

    private companion object {
        /**
         * 同 key 排队的等待上限。
         *
         * 取 5s：正常一次图片下载在几百毫秒到 2s 之间。等满 5s 说明前一个请求
         * 已经不正常了，此时重复下载一次远比继续等划算。
         */
        const val MAX_WAIT_MS = 5_000L
    }
}
