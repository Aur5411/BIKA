package com.shizq.bika.core.data.paging

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 章节图片分页的**即时重试**行为。
 *
 * 锁的是一个真实发生过的缺陷：分页请求失败一次就把 LoadResult.Error 交给 Paging，
 * 于是失败那一刻正停在边界上的那一屏就永久停在错误态。用户最容易撞上的边界，
 * 正是章节每 40 张图一个 API 页所对应的"约第 20 页之后"。
 *
 * 重试逻辑本身抽在 [fetchWithPageRetry] 里，这里直接测它——
 * [ChapterPagesPagingSource] 依赖 final 的 BikaDataSource，无法在单测中替身。
 */
class ChapterPagesRetryTest {

    /** 用虚拟时间跳过退避等待，测试不必真的等几百毫秒。 */
    private val noSleep: suspend (Long) -> Unit = { }

    @Test
    fun `单次网络抖动会被原地重试并成功`() = runTest {
        var calls = 0

        val result = fetchWithPageRetry(
            fetch = {
                calls++
                if (calls == 1) throw IOException("boom")
                "page-1"
            },
            sleep = noSleep,
        )

        assertEquals("page-1", result)
        assertEquals(2, calls, "第一次失败后应重试一次")
    }

    @Test
    fun `连续失败超过上限后抛出最后一次异常`() = runTest {
        var calls = 0

        assertFailsWith<IOException> {
            fetchWithPageRetry(
                fetch = {
                    calls++
                    throw IOException("boom")
                },
                sleep = noSleep,
            )
        }
        assertEquals(3, calls, "重试有上限，不应无限重试")
    }

    @Test
    fun `永久性 404 不重试且立即抛出`() = runTest {
        var calls = 0

        assertFailsWith<IOException> {
            fetchWithPageRetry(
                fetch = {
                    calls++
                    throw IOException("HTTP 404 Not Found")
                },
                sleep = noSleep,
            )
        }
        assertEquals(1, calls, "4xx 是永久失败，重试没有收益")
    }

    @Test
    fun `429 限流会重试`() = runTest {
        var calls = 0

        assertFailsWith<IOException> {
            fetchWithPageRetry(
                fetch = {
                    calls++
                    throw IOException("HTTP 429 Too Many Requests")
                },
                sleep = noSleep,
            )
        }
        assertEquals(3, calls, "429 是暂时性失败，值得重试")
    }

    @Test
    fun `正常返回不触发重试`() = runTest {
        var calls = 0

        val result = fetchWithPageRetry(
            fetch = {
                calls++
                "page-1"
            },
            sleep = noSleep,
        )

        assertEquals("page-1", result)
        assertEquals(1, calls)
    }

    @Test
    fun `重试会按次数回调以便记录日志`() = runTest {
        val failedAttempts = mutableListOf<Int>()

        runCatching {
            fetchWithPageRetry(
                fetch = { throw IOException("boom") },
                onRetry = { attempt, _ -> failedAttempts += attempt },
                sleep = noSleep,
            )
        }

        // 三次尝试、前两次失败各回调一次，参数是"第几次尝试失败了"。
        assertEquals(listOf(1, 2), failedAttempts)
    }
}
