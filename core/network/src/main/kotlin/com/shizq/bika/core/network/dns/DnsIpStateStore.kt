package com.shizq.bika.core.network.dns

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger("DnsIpStateStore")

/**
 * 上一轮验证可用的 IP 组合。
 *
 * 冷启动时智能 DNS 常常还没就绪（或整条线路全挂），此时"上轮能用的 IP"
 * 往往仍然可用，比直接回退出厂 IP 靠谱得多。
 */
@Serializable
internal data class DnsPoolSnapshot(
    val savedAtEpochMs: Long = 0L,
    val activeLine: String = "",
    val apiHosts: List<String> = emptyList(),
    val imageHosts: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = apiHosts.isEmpty() && imageHosts.isEmpty()

    internal fun asResolvedHosts(): List<ResolvedDnsHost> =
        apiHosts.map { ResolvedDnsHost(it, activeLine, BikaDnsDomains.API) } +
                imageHosts.map { ResolvedDnsHost(it, activeLine, BikaDnsDomains.IMAGE) }
}

/** 一条 IP 黑名单记录：在 [untilEpochMs] 之前不再作为候选。 */
@Serializable
internal data class DnsIpBlacklistEntry(
    val ip: String,
    val untilEpochMs: Long,
)

/**
 * 分流状态的整体持久化形态。
 *
 * 快照与黑名单放同一个文件：两者都是"上一次探测留下的结论"，分开存会出现
 * 只写成功一半、重启后互相矛盾的状态。
 */
@Serializable
internal data class DnsPoolState(
    val snapshot: DnsPoolSnapshot = DnsPoolSnapshot(),
    val blacklist: List<DnsIpBlacklistEntry> = emptyList(),
) {
    /** 当前仍生效的黑名单 IP。 */
    fun activeBlacklistIps(nowEpochMs: Long): Set<String> =
        blacklist.filter { it.untilEpochMs > nowEpochMs }.mapTo(mutableSetOf()) { it.ip }

    /**
     * 记入黑名单并顺手清理过期项。
     *
     * 同一 IP 重复拉黑时取更晚的到期时间，避免"先拉 10 分钟、后拉 1 分钟"
     * 把前一条的封禁期缩短。
     */
    fun withBlacklisted(ips: Collection<String>, nowEpochMs: Long, durationMs: Long): DnsPoolState {
        if (ips.isEmpty()) return copy(blacklist = blacklist.filter { it.untilEpochMs > nowEpochMs })
        val until = nowEpochMs + durationMs
        val merged = blacklist
            .filter { it.untilEpochMs > nowEpochMs }
            .associateBy { it.ip }
            .toMutableMap()
        ips.forEach { ip ->
            val existing = merged[ip]
            merged[ip] = DnsIpBlacklistEntry(ip, maxOf(existing?.untilEpochMs ?: 0L, until))
        }
        return copy(blacklist = merged.values.toList())
    }
}

/**
 * 分流状态的本地持久化。
 *
 * 读写都走 IO 线程 + 互斥锁：冷启动优化与设置页的手动测速可能同时发生，
 * 并发写同一个文件会写出半截 JSON，之后每次启动都解析失败，
 * 表现为"分流优化莫名不再生效"。
 */
@Singleton
internal class DnsIpStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    suspend fun read(): DnsPoolState = mutex.withLock {
        withContext(Dispatchers.IO) { readLocked() }
    }

    suspend fun write(state: DnsPoolState) = mutex.withLock {
        withContext(Dispatchers.IO) { writeLocked(state) }
    }

    private fun readLocked(): DnsPoolState {
        val target = file
        if (!target.exists()) return DnsPoolState()
        return try {
            json.decodeFromString(DnsPoolState.serializer(), target.readText())
        } catch (e: Exception) {
            // 解析失败说明文件损坏（如上次写盘恰好被杀进程），删掉重来而不是
            // 每次启动都重复踩同一个坑
            logger.warn(e) { "分流状态解析失败，已重置" }
            target.delete()
            DnsPoolState()
        }
    }

    private fun writeLocked(state: DnsPoolState) {
        try {
            val target = file
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            // 先写临时文件再原子替换，避免写到一半被杀留下半截 JSON
            tmp.writeText(json.encodeToString(DnsPoolState.serializer(), state))
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.warn(e) { "分流状态写入失败" }
        }
    }

    private companion object {
        const val FILE_NAME = "dns_pool_state.json"
    }
}
