package com.shizq.bika.core.network.dns

import com.shizq.bika.core.coroutine.ApplicationScope
import com.shizq.bika.core.datastore.UserPreferencesDataSource
import com.shizq.bika.core.model.preferences.DnsPreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

private val logger = KotlinLogging.logger("DnsAutoSelector")

/** 并发探测数。参考实现用 6，保持同量级以免把手机网络打满。 */
private const val PROBE_CONCURRENCY = 6

/** 失效 IP 的封禁时长。 */
private const val BLACKLIST_DURATION_MS = 10 * 60 * 1000L

/**
 * 冷启动自动选取最低延迟的分流线路。
 *
 * ## 与设置页「应用最低延迟」的关系
 *
 * 设置页那个按钮是**手动**触发、且只写单个最快的 IP。这里做的是**自动**版本，
 * 并且注入一组 IP（见 [selectInjectionPool]）而不是一个：冷启动时用户没有
 * 机会手动挑，注入单点一旦失效就是整条链路不可用。
 *
 * ## 为什么放在冷启动
 *
 * 分流 IP 会随运营商路由、CF 边缘节点调度变化而漂移，上一次会话的最优 IP
 * 未必还是这次的最优。冷启动是唯一"用户还没开始用、可以安静做十几秒网络探测"
 * 的时机，因此每次进程启动都重新测一轮。
 *
 * ## 安全边界（宁可不优化，也不能改坏）
 *
 * - 一个候选都探不通（典型是刚开机还没连上网络）→ 原样保留现有配置，不做任何写入；
 * - 只探通 API 或只探通图片其中之一 → 不写入，避免留下半套配置；
 * - 探测只在后台进行，不阻塞启动路径，探测期间沿用现有 IP。
 *
 * 已知局限：探测用的是 TCP 443 握手，只能反映"链路是否通、握手多快"，
 * 无法识别连得上但被服务端拒（如 CF 1034）。因此把当前已生效的 IP 也纳入
 * 候选，让它有机会凭延迟胜出，而不是被新候选无条件顶掉。
 */
@Singleton
class DnsAutoSelector @Inject internal constructor(
    private val dnsHostResolver: DnsHostResolver,
    private val hostLatencyProbe: HostLatencyProbe,
    private val userPreferencesDataSource: UserPreferencesDataSource,
    private val stateStore: DnsIpStateStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /**
     * 触发冷启动优化。非阻塞，立即返回。
     *
     * 用 [started] 兜一道：本类在进程内是单例，重复调用（例如 Application
     * 重建、或后续在别处也调用）不应该重复消耗一轮网络探测。
     */
    fun optimizeOnColdStart() {
        if (!started.compareAndSet(false, true)) {
            logger.debug { "冷启动分流优化已执行过，跳过" }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                optimize()
            } catch (e: Exception) {
                // 分流优化是纯增益动作，任何失败都不该冒泡影响启动
                logger.warn(e) { "冷启动分流优化失败" }
            }
        }
    }

    internal suspend fun optimize() {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val stored = stateStore.read()
        val blacklisted = stored.activeBlacklistIps(nowMs)
        val current = userPreferencesDataSource.userData.first().network.dns

        // 候选来源三路合并，顺序即优先级：线路 > 上轮快照 > 当前已生效 > 出厂兜底。
        // distinctBy 保留先出现的那条，所以顺序决定了同名 IP 记在哪条线路下。
        val fromLines = dnsHostResolver.resolveHosts()
        val fromSnapshot = if (stored.snapshot.isEmpty) emptyList() else stored.snapshot.asResolvedHosts()
        val fromCurrent = current.asResolvedHosts()
        val fromFallback = factoryFallbackHosts()

        val candidates = (fromLines + fromSnapshot + fromCurrent + fromFallback)
            .filter { it.ip !in blacklisted }
            .distinctBy { it.ip to it.domain }

        if (candidates.isEmpty()) {
            logger.warn { "分流候选为空（线路与快照均无可用 IP），保留现有配置" }
            return
        }
        logger.info {
            "分流候选 ${candidates.size} 个（线路 ${fromLines.size} / 快照 ${fromSnapshot.size} / " +
                    "当前 ${fromCurrent.size} / 兜底 ${fromFallback.size}，已剔除黑名单 ${blacklisted.size}）"
        }

        val probed = probeAll(candidates)
        val reachable = probed.filter { it.isReachable }
        val unreachable = probed.filterNot { it.isReachable }

        if (reachable.isEmpty()) {
            // 全军覆没几乎一定是本机网络问题（没网 / 飞行模式），不是这些 IP 的问题。
            // 此时拉黑会把好不容易攒下的好 IP 全废掉，所以只记日志。
            logger.warn { "${probed.size} 个候选全部不可达，判为本机网络问题，保留现有配置" }
            return
        }

        val apiHosts = selectInjectionPool(reachable.filter { it.domain == BikaDnsDomains.API })
        val imageHosts = selectInjectionPool(reachable.filter { it.domain == BikaDnsDomains.IMAGE })

        if (apiHosts.isEmpty() || imageHosts.isEmpty()) {
            logger.warn {
                "只探通了其中一类域名（API ${apiHosts.size} / 图片 ${imageHosts.size}），保留现有配置"
            }
            return
        }

        val line = selectActiveLine(apiHosts, imageHosts) ?: current.activeLine

        userPreferencesDataSource.updateDnsSettings(
            apiDns = apiHosts.map { it.ip }.toSet(),
            imageDns = imageHosts.map { it.ip }.toSet(),
            activeDnsLine = line,
        )

        // 写回快照与黑名单：快照供下次冷启动兜底，黑名单避免下一轮再浪费探测配额
        stateStore.write(
            stored
                .copy(
                    snapshot = DnsPoolSnapshot(
                        savedAtEpochMs = nowMs,
                        activeLine = line,
                        apiHosts = apiHosts.map { it.ip },
                        imageHosts = imageHosts.map { it.ip },
                    )
                )
                .withBlacklisted(unreachable.map { it.ip }, nowMs, BLACKLIST_DURATION_MS)
        )

        logger.info {
            "分流已切换：线路=$line API=${apiHosts.size} 个（最快 ${apiHosts.first().latencyMs}ms） " +
                    "图片=${imageHosts.size} 个（最快 ${imageHosts.first().latencyMs}ms） " +
                    "拉黑 ${unreachable.size} 个"
        }
    }

    /** 并发探测全部候选，结果与输入一一对应。 */
    private suspend fun probeAll(candidates: List<ResolvedDnsHost>): List<ProbedDnsHost> =
        coroutineScope {
            val semaphore = Semaphore(PROBE_CONCURRENCY)
            candidates
                .map { host ->
                    async {
                        semaphore.withPermit {
                            ProbedDnsHost(
                                ip = host.ip,
                                lineName = host.lineName,
                                domain = host.domain,
                                latencyMs = hostLatencyProbe.measureLatency(host.ip),
                            )
                        }
                    }
                }
                .map { it.await() }
        }

    private fun factoryFallbackHosts(): List<ResolvedDnsHost> {
        val ip = DnsPreferences.DEFAULT_DNS_IP
        return listOf(
            ResolvedDnsHost(ip, DnsPreferences.DEFAULT_DNS_LINE, BikaDnsDomains.API),
            ResolvedDnsHost(ip, DnsPreferences.DEFAULT_DNS_LINE, BikaDnsDomains.IMAGE),
        )
    }
}

private fun DnsPreferences.asResolvedHosts(): List<ResolvedDnsHost> =
    apiDnsHosts.map { ResolvedDnsHost(it, activeLine, BikaDnsDomains.API) } +
            imageDnsHosts.map { ResolvedDnsHost(it, activeLine, BikaDnsDomains.IMAGE) }

/** [selectInjectionSet] 在本类语境下的别名，调用处读起来更贴合"注入池"。 */
private fun selectInjectionPool(candidates: List<ProbedDnsHost>): List<ProbedDnsHost> =
    selectInjectionSet(candidates)
