package com.shizq.bika.core.coroutine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 按 key 合流并发请求。1
 *
 * 这个类存在的动机是"同一份响应被两个消费者各取一半、导致同一个 GET 打两遍"，
 * 所以"并发只发一次"和"请求结束后不复用旧结果"这两条是它的核心契约，
 * 各自都有用例覆盖。
 *
 * 用例统一把 [TestScope.backgroundScope] 而不是 `this` 传给 [SingleFlight]：
 * 被传入的 scope 会成为内部 flightScope 的父 Job，而 `runTest` 结束时会等待
 * 测试作用域下所有子 Job 结束。传 `this` 等于给测试挂了一个永不结束的子 Job，
 * 用例只能靠 60 秒超时失败（UncompletedCoroutinesError）；
 * backgroundScope 的子 Job 在测试收尾时统一取消，正是为这种"长期驻留设施"准备的。
 */
class SingleFlightTest {

    @Test
    fun `并发调用同一 key 只执行一次`() = runTest {
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val singleFlight = SingleFlight<String, Int>(backgroundScope)

        val block: suspend () -> Int = {
            calls.incrementAndGet()
            gate.await()
            42
        }

        // 三个调用者同时进入，block 被 gate 挡住，确保它们都处于"在途"状态
        val a = async { singleFlight.run("k", block) }
        val b = async { singleFlight.run("k", block) }
        val c = async { singleFlight.run("k", block) }
        advanceUntilIdle()

        gate.complete(Unit)

        assertEquals(42, a.await())
        assertEquals(42, b.await())
        assertEquals(42, c.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `不同 key 各自执行`() = runTest {
        val calls = AtomicInteger(0)
        val singleFlight = SingleFlight<String, Int>(backgroundScope)

        val a = async { singleFlight.run("k1") { calls.incrementAndGet() } }
        val b = async { singleFlight.run("k2") { calls.incrementAndGet() } }
        advanceUntilIdle()

        a.await()
        b.await()
        assertEquals(2, calls.get())
    }

    @Test
    fun `请求结束后不复用结果`() = runTest {
        val calls = AtomicInteger(0)
        val singleFlight = SingleFlight<String, Int>(backgroundScope)
        val block: suspend () -> Int = { calls.incrementAndGet() }

        // 刻意串行：合流窗口只覆盖"请求在途"这段时间。
        // 评论要求下拉即新，缓存结果会让刷新拿到旧数据
        singleFlight.run("k", block)
        singleFlight.run("k", block)

        assertEquals(2, calls.get())
    }

    @Test
    fun `完成后登记被摘除`() = runTest {
        val singleFlight = SingleFlight<String, Int>(backgroundScope)

        singleFlight.run("k") { 1 }
        advanceUntilIdle()

        // 不摘除的话这张表会随访问过的 key 无界增长
        assertEquals(0, singleFlight.inFlightCount)
    }

    @Test
    fun `失败传播给所有等待者且不留下登记`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val singleFlight = SingleFlight<String, Int>(backgroundScope)
        val block: suspend () -> Int = {
            gate.await()
            throw IllegalStateException("boom")
        }

        // 调用者自己包住异常再 await：async 失败会取消父 Job，直接让 a/b 失败会把
        // 测试作用域一起打掉，runTest 收尾时重新抛出同一个异常，
        // 用例就看不到后面两行断言了
        val a = async { runCatching { singleFlight.run("k", block) } }
        val b = async { runCatching { singleFlight.run("k", block) } }
        advanceUntilIdle()
        gate.complete(Unit)

        assertTrue(a.await().exceptionOrNull() is IllegalStateException)
        assertTrue(b.await().exceptionOrNull() is IllegalStateException)
        advanceUntilIdle()

        // 失败的 Deferred 留在表里会让后来者 await 到同一个异常，
        // 一次网络抖动会变成永久失败
        assertEquals(0, singleFlight.inFlightCount)
    }

    @Test
    fun `失败后下一次调用重新执行`() = runTest {
        val calls = AtomicInteger(0)
        val singleFlight = SingleFlight<String, Int>(backgroundScope)

        assertFailsWith<IllegalStateException> {
            singleFlight.run("k") {
                calls.incrementAndGet()
                throw IllegalStateException("boom")
            }
        }
        advanceUntilIdle()

        val result = singleFlight.run("k") { calls.incrementAndGet() }

        assertEquals(2, calls.get())
        assertEquals(2, result)
    }

    @Test
    fun `一个调用者取消不影响其他调用者`() = runTest {
        // 关键场景：Paging 重建分页源会取消它那边的 load，
        // 而置顶评论仍在等同一个请求。共享请求挂在独立 scope 上才不会被带走
        val singleFlight = SingleFlight<String, Int>(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val block: suspend () -> Int = {
            gate.await()
            7
        }

        val survivor = async { singleFlight.run("k", block) }
        val quitter = launch { singleFlight.run("k", block) }
        advanceUntilIdle()

        quitter.cancelAndJoin()
        gate.complete(Unit)

        assertEquals(7, survivor.await())
    }

    @Test
    fun `全部调用者取消后共享请求仍然跑完并摘除登记`() = runTest {
        // 共享请求挂在独立 scope 上，没人等了它也会跑完并自行摘除登记。
        // 这是刻意的取舍：宁可多跑一次，也不让"取消其中一方"变成另一方的失败
        val singleFlight = SingleFlight<String, Int>(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        val job = launch {
            singleFlight.run("k") {
                started.complete(Unit)
                gate.await()
                99.also { finished.complete(Unit) }
            }
        }

        // 用 await(started) 而不是 advanceUntilIdle 来判断"请求已经开始"：共享请求
        // 跑在 backgroundScope 上，而 advanceUntilIdle 只保证前台任务推进完，
        // 拿它当栅栏会得到一个偶发为假的前提
        started.await()

        job.cancelAndJoin()
        gate.complete(Unit)

        // 等共享请求自己跑完。这里不能靠 advanceUntilIdle：它只保证前台任务推进到底，
        // 跑在 backgroundScope 上的共享请求不会被它驱动，断言会得到假失败。
        // 用 await 才是最直接的"它会跑完"证据——真的没跑完就会卡在 runTest 的超时上。
        finished.await()

        assertTrue(finished.isCompleted, "共享请求不该随调用者一起被取消")
        assertEquals(0, singleFlight.inFlightCount)
    }

    @Test
    fun `请求失败不会取消传入的 scope`() = runTest {
        // async 失败会取消自己的父 Job——异常存进 Deferred 等人 await 是一回事，
        // 取消父 Job 是另一回事，两者同时发生。
        // SingleFlight 内部必须插一层 SupervisorJob 把失败隔断，否则传入
        // viewModelScope 时，一次评论请求失败会让该 ViewModel 后续所有协程停摆。
        val singleFlight = SingleFlight<String, Int>(backgroundScope)
        val outerJob = coroutineContext[Job]

        assertFailsWith<IllegalStateException> {
            singleFlight.run("k") { throw IllegalStateException("boom") }
        }
        advanceUntilIdle()

        // scope 仍然可用：还能正常跑新的请求
        val afterFailure = singleFlight.run("k2") { 5 }

        assertEquals(5, afterFailure)
        assertTrue(outerJob?.isActive == true, "传入的 scope 不该被请求失败拖垮")
    }
}
