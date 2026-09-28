package com.shizq.bika.feature.reader.impl.progress

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlin.time.Duration

private val logger = KotlinLogging.logger("ProgressWriter")

/**
 * 进度写入流水线：唯一的写入者。
 *
 * 旧实现有四个互不排序的写入者（防抖 job、ON_STOP 的 persistNow、onDispose 的
 * persistLastKnownPage、JumpToChapter handler 内的直接 store），共享三个可变字段
 * （persistJob / persistProgress / @Volatile lastKnownPage），且 persistJob 被
 * 生命周期回调协程和跟踪协程同时读写，没有互斥。
 *
 * 这里折叠成一条流：
 * - [submit] 提交「用户现在读到这」，走防抖
 * - [flush] 提交「立刻落库」的信号，显式传入要写的进度
 * - [storeImmediately] 绕过闸门的强制写入（切章）
 *
 * 三路 merge 进单个 collector，写入顺序天然串行。所有可变字段消失。
 *
 * scope 必须是 viewModelScope 级别（比 composition 长寿）：旧实现用
 * rememberCoroutineScope()，组合销毁会掐死尚未触发的防抖 job，这正是
 * persistLastKnownPage 那个同步逃生口存在的唯一理由。
 */
class ReadingProgressWriter(
    private val sink: ChapterProgressSink,
    scope: CoroutineScope,
    private val debounce: Duration,
) {
    /**
     * replay = 1：用于 distinctUntilChanged 比对相邻重复。
     * extraBufferCapacity 给足，配合 tryEmit 让 submit 可以从非挂起上下文调用。
     */
    private val submissions = MutableSharedFlow<ChapterProgress>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    /**
     * 绕过闸门与防抖的直接写入（切章、flush）。仍然走同一个 collector，
     * 因此与防抖写入之间有确定的先后顺序——这是把切章写入「并进」流水线的关键：
     * 若用 scope.launch 直接调 sink，它与 collector 是两个并发写入者，
     * 切章瞬间可能被一条迟到的防抖写入覆盖。
     *
     * replay = 1：collector 是 launchIn 起的，在它真正订阅上之前发出的值
     * 需要一个缓存位才不丢。切章与 ON_STOP 都发生在"构造完立刻调用"的时序里
     * （组合进入即触发），replay = 0 会让这些写入静默消失——正是"退出阅读器后
     * 进度没保存"这类最难复现的丢进度问题。
     */
    private val immediateWrites = MutableSharedFlow<ChapterProgress>(
        replay = 1,
        extraBufferCapacity = 8,
    )

    /** 写入闸门。恢复未确认前保持 Closed，[submit] 直接丢弃。 */
    @Volatile
    var gate: PersistGate = PersistGate.Closed
        private set

    init {
        merge(
            // 防抖到期时二次检查闸门。submit() 只能拦住"新值进入 submissions"，
            // 已经进入的值仍会在防抖到期后无条件发射；而切章（closeGate）恰恰
            // 常发生在防抖窗口之内，那条迟到的旧章节页码必须在这里被拦掉，
            // 否则它会覆盖切章时写入的真实位置。
            //
            // 闸门过滤只加在防抖这一路：immediateWrites 承载的是切章写入，
            // 它按设计就要绕过闸门（旧章节的进度与新章节的恢复状态无关）。
            submissions.sample(debounce).filter { gate == PersistGate.Open },
            immediateWrites,
        )
            // 只对相邻重复去重。注意不能把 immediateWrites 排除在外——切章写入的
            // chapterOrder 与当前章不同，天然不会被去重掉。
            .distinctUntilChanged()
            .onEach { progress ->
                val ok = sink.store(progress)
                if (!ok) {
                    logger.warn { "进度落库失败: $progress" }
                }
            }
            .launchIn(scope)
    }

    /** 恢复确认后放开闸门。 */
    fun openGate() {
        if (gate != PersistGate.Open) {
            logger.debug { "写库闸门打开" }
            gate = PersistGate.Open
        }
    }

    /**
     * 提交一次页码变化，走防抖。闸门关闭时丢弃——你选的策略是
     * 「恢复未确认成功就不允许写库」，丢弃而非缓存，避免闸门打开瞬间
     * 把恢复期间的中间位置补写进去。
     */
    fun submit(progress: ChapterProgress) {
        if (gate != PersistGate.Open) {
            logger.debug { "闸门关闭，丢弃提交: page=${progress.pageIndex}" }
            return
        }
        submissions.tryEmit(progress)
    }

    /**
     * 请求立即落库（ON_STOP、返回、组合销毁）。
     *
     * 改动：不再从 replayCache 取值，调用方显式传入要写的进度。
     * 同步返回、不挂起：调用方可能是 onDispose 这类同步回调。实际写库在
     * [scope]（viewModelScope）里完成，不受组合生命周期影响。
     *
     * 同时压进 [submissions]：flush 的语义是"我现在真实读到这"，必须顶掉防抖
     * 窗口里那个尚未到期的旧值。只走 immediateWrites 的话，几百毫秒前的旧页码
     * 会在 flush 落库之后才到，把进度又写回去——用户侧表现为"退出再进来，
     * 进度倒退了几页"。防抖流那侧有闸门过滤，这里不需要再判断。
     */
    fun flush(progress: ChapterProgress) {
        if (gate != PersistGate.Open) return
        submissions.tryEmit(progress)
        immediateWrites.tryEmit(progress)
    }

    /**
     * 切章前的强制落库：绕过闸门与防抖，直接提交指定进度。
     *
     * 切章是唯一需要绕过闸门的场景——旧章节的进度是已确认的（用户确实读到那），
     * 与新章节的恢复状态无关。JumpToChapter 走这条路，与其余路径共用
     * 同一个 sink，因此与防抖写入之间有明确顺序。
     */
    fun storeImmediately(progress: ChapterProgress) {
        immediateWrites.tryEmit(progress)
    }

    /**
     * 切章时重置：新章节的恢复尚未确认，闸门必须关回去。
     *
     * 旧实现的门闩（`state.first { Restored || RestoreFailed }`）是一次性的，
     * 放开后无法关闭，切章后新章节在恢复期间的中间位置会被直接写库。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun closeGate() {
        gate = PersistGate.Closed
        // 清掉上一章的 replayCache，避免旧章节的值残留。
        submissions.resetReplayCache()
    }
}
