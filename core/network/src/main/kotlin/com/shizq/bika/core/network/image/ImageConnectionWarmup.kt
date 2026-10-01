package com.shizq.bika.core.network.image

import com.shizq.bika.core.network.di.ImageClient
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 进入阅读会话前把选路和握手两件事提前做完。
 *
 * ## 为什么要单独做这件事
 *
 * 实测（本机，走代理出口 HK）：
 * - 到一个图片源的 TLS 握手要 **1.0～1.3 秒**；连接复用时首字节约 400ms，
 *   一旦连接被回收，下一张图就要重新付那一秒多的协商成本；
 * - 也就是说"第一张图迟迟不出来"的等待里，有相当一部分根本不在下载上。
 *
 * 于是把两件事提前到 ViewModel 刚起来、还在拉章节表的时候做：
 *
 * 1. **提前握手**：对将要使用的源摊好几条连接塞进连接池 idle 5 分钟，
 *    用户真正要图时直接复用；
 * 2. **提前选路**：并发探一遍候选源，把最快的那个记进 [ImageHostRouter]。
 *
 * ## 这两件事的顺序很重要（v1.11.35 调整）
 *
 * 旧实现是"先选路、后建连"：`target = preferredHost() ?: electFastestHost()`。
 * 冷启动时没有任何结论，于是**必须先等完一轮 7 个候选源的 HEAD 探测**
 * （单项上限 `RACE_TIMEOUT_MS`）才能开始建连——首图等到的连接是凉的。
 *
 * 现在冷启动改为：先用 [ImageHostRouter.startupHostOrNull] 给出的兜底源
 * **立刻建连**，再在后台补一轮选路。首图既不用等探测，又能拿到热连接；
 * 选路结论仍然会被记下来供后续图片使用。
 *
 * ## 预热 downloads 的是状态码，不是流量
 *
 * 用 HEAD 打根路径：服务端返回 403/404 都无所谓，要的是连接走到 keep-alive
 * 状态并被 OkHttp 收进池子里。
 *
 * ## 代价与止损
 *
 * 整个过程在 Dispatchers.IO 上并发进行，且探测客户端单独收紧了超时（见
 * [probeClient]），不会拖住主流程；失败、超时一律忽略——预热是锦上添花，
 * 做不成也只退回原来的"首图自带一次竞速"，不会更差。
 */
@Singleton
class ImageConnectionWarmup @Inject constructor(
    @ImageClient private val client: OkHttpClient,
    private val router: ImageHostRouter,
) {

    @Volatile
    private var warmedHost: String? = null

    /**
     * @param connections 预热连接条数。取 6：够覆盖"首图 + 头几张预载"，再多
     *   就是白占 OkHttp 的同域名请求额度（真正下载要靠那里面的配额）。
     */
    suspend fun warmUp(connections: Int = WARM_CONNECTIONS) {
        val remembered = router.preferredHost()
        val target = remembered
            ?: router.startupHostOrNull()
            ?: electFastestHost()
        if (target == null) return

        if (target != warmedHost) {
            warmedHost = target
            withContext(Dispatchers.IO) {
                coroutineScope {
                    repeat(connections) { launch { connect(target) } }
                }
            }
        }

        // 走兜底源打头阵的那一次，仍然要在后台把"谁最快"选出来。
        //
        // 兜底源只是"已知可用且容错面最宽"，不等于**当前网络下最快**。
        // 不选一次的话，[ImageHostRouter] 会一整场会话都没有结论，
        // 后续每张图都直接用兜底源，失去对网络变化的适应能力。
        //
        // 顺序上放在预热之后：这样 7 个候选源的 HEAD 探测不会和首屏图片
        // 抢同一波带宽与连接。（旧实现是先探测、后建连，首图只能干等探测结束，
        // 那正是"第一次打开慢"的一部分。）
        //
        // `remembered == null` 才选：已经有结论时尊重既有结论；
        // 结论过期时 preferredHost 也返回 null，同样会走到这里重新选，是预期的。
        if (remembered == null) {
            electFastestHost()
        }
    }

    /** 源被弃用或网络切换后调用，让下次重新选路、重新预热。 */
    fun invalidate() {
        warmedHost = null
    }

    /**
     * 并发探一遍候选源，把最快的记进 [ImageHostRouter]。
     *
     * 取延迟最小的那个：不同源在同一网络下主要差的就是握手 + 首字节这一段，
     * 与"下载整张图的快慢"方向一致（实测 diwodiwo 既是 DNS/TLS 最快，也是吞吐最高）。
     *
     * ## 只有"确实取到了 HTTP 响应"才做选举
     *
     * 用 HEAD 打根路径时，200/403/404 都说明**这个源能连上**（此前注释也承认
     * "服务端返回 403/404 都无所谓"）。因此不能拿 `execute()` 是否抛异常当判据——
     * 只要不抛就证明连接可用，可以参与选路。
     *
     * 反过来，**一个都没探通时绝不能 `remember`**：那通常意味着本机网络还没就绪
     * （刚启动、DNS 仍在解析、Wi-Fi 刚连上），此时若把某个源记成"最快"，
     * 后续每张图都会被 [PreferredHostInterceptor] 改写到它上面，形成新的故障。
     * 旧实现在这里返回 null 且不写记录，是正确行为，保留。
     */
    private suspend fun electFastestHost(): String? {
        val winner = supervisorScope {
            ImageHosts.imageHosts
                .map { host -> async { host to measure(host) } }
                .mapNotNull { runCatching { it.await() }.getOrNull() }
                .filter { (_, latencyMs) -> latencyMs != null && latencyMs < RACE_TIMEOUT_MS }
                .minByOrNull { (_, latencyMs) -> latencyMs!! }
                ?.first
        } ?: return null
        router.remember(winner)
        return winner
    }

    /** 一次 HEAD 的端到端耗时（DNS → TCP → TLS → 首字节）；失败或超时返回 null。 */
    private suspend fun measure(host: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://$host/")
            .head()
            .build()
        val startedAtNs = System.nanoTime()
        val ok = runCatching {
            probeClient.newCall(request).execute().use { /* 只要走到这一步，连接已建好 */ Unit }
        }.isSuccess
        if (!ok) return@withContext null
        (System.nanoTime() - startedAtNs) / 1_000_000L
    }

    private suspend fun connect(host: String) {
        val request = Request.Builder()
            .url("https://$host/")
            .head()
            .build()
        runCatching { client.newCall(request).execute().use { Unit } }
    }

    /**
     * 探测专用客户端：单独收紧超时，免得一个不可达的候选源把整场选路拖到十秒。
     * 其余配置（连接池、直连 DNS、请求头）沿用图片链路那一份。
     */
    private val probeClient: OkHttpClient by lazy(LazyThreadSafetyMode.NONE) {
        client.newBuilder()
            .connectTimeout(RACE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private companion object {
        const val WARM_CONNECTIONS = 6
        const val RACE_TIMEOUT_MS = 4_000L
        const val RACE_CONNECT_TIMEOUT_MS = 2_000L
    }
}
