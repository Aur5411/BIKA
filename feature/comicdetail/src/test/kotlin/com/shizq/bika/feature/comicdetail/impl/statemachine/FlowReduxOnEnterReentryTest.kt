package com.shizq.bika.feature.comicdetail.impl.statemachine

import com.freeletics.flowredux2.FlowReduxStateMachineFactory
import com.freeletics.flowredux2.initializeWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 锁定 flowredux2 的一条语义：**在 `onEnter` 里 `mutate` 不会重入同一个状态块。**
 *
 * ## 为什么要专门测这个
 *
 * `UnitedDetailsStateMachine` 的"详情先出、推荐后补"依赖这条语义：
 * 进入 `Content` 时在 `onEnter` 里发起推荐请求，拿到后
 * `mutate { copy(recommendations = …) }` 把推荐补上。
 * 如果 `mutate` 会重入 `Content` 的 `onEnter`，就会变成**无限循环的推荐请求**，
 * 且是静默的，只表现为"详情页一直在发请求"。
 *
 * 这条语义依赖第三方库的实现细节与版本（当前 2.1.1），所以用探针测试钉住：
 * 库升级后若语义变化，这里会先失败，而不是等到线上疯转。
 *
 * ## 为什么用 runBlocking 而不是 runTest
 *
 * 试过 `runTest` + `advanceUntilIdle()`：状态机的内部循环不跑在测试调度器的
 * 虚拟时间里，`onEnter` 一次都没执行（计数为 0），断言失败但原因与语义无关。
 * 这里改用真实调度器 + 轮询等待，避免把"虚拟时间没驱动"误读成"语义不成立"。
 */
private sealed interface ProbeState {
    data object Init : ProbeState
    data class Content(val items: List<String> = emptyList()) : ProbeState
}

private sealed interface ProbeAction

private class ProbeMachine : FlowReduxStateMachineFactory<ProbeState, ProbeAction>() {

    /** `Content` 的 `onEnter` 被执行了几次。期望恒为 1。 */
    @Volatile
    var contentEnterCount = 0
        private set

    init {
        initializeWith { ProbeState.Init }
        spec {
            inState<ProbeState.Init> {
                onEnter { override { ProbeState.Content() } }
            }
            inState<ProbeState.Content> {
                onEnter {
                    contentEnterCount++
                    mutate { copy(items = listOf("late")) }
                }
            }
        }
    }
}

class FlowReduxOnEnterReentryTest {

    @Test
    fun `在 onEnter 里 mutate 不会重入同一状态块`() {
        val factory = ProbeMachine()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = Collections.synchronizedList(mutableListOf<ProbeState>())
        try {
            val handle = factory.launchIn(scope)
            scope.launch { handle.state.collect { seen += it } }

            runBlocking {
                withTimeoutOrNull(WAIT_TIMEOUT_MS) {
                    while (true) {
                        val reached = seen.any { it is ProbeState.Content && it.items == listOf("late") }
                        if (reached) break
                        delay(POLL_INTERVAL_MS)
                    }
                }
            }

            val snapshot = seen.toList()
            assertTrue(
                snapshot.any { it is ProbeState.Content },
                "状态机没有进入 Content，观测到的状态=$snapshot " +
                        "（若为空，说明状态机根本没启动，本测试结论无效）",
            )
            assertTrue(
                snapshot.any { it is ProbeState.Content && it.items == listOf("late") },
                "mutate 的结果没有生效，观测到的状态=$snapshot",
            )
            assertEquals(
                1,
                factory.contentEnterCount,
                "mutate 重入了 Content 的 onEnter（实际进入 ${factory.contentEnterCount} 次）——" +
                        "该版本库里必须改用 collectWhileInState，否则详情页会无限重发推荐请求",
            )
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
