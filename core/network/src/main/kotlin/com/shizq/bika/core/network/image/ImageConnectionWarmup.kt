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
 * 1. **提前选路**：并发探一遍候选源，先把最快的那个记进 [ImageHostRouter]，
 *    第一张图就直接打它，不必再从慢主源起步、熬到 900ms 阈值才触发竞速；
 * 2. **提前握手**：对胜出的源摊好几条连接塞进连接池 idle 5 分钟，用户真正
 *    要图时直接复用。
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
        val target = router.preferredHost() ?: electFastestHost()
        if (target == null) return
        if (target == warmedHost) return
        warmedHost = target

        withContext(Dispatchers.IO) {
            coroutineScope {
                repeat(connections) { launch { connect(target) } }
            }
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
