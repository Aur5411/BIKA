package com.shizq.bika.core.network.dns

import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Clock

/**
 * 通过对 443 端口做 TCP 握手来测量延迟的 [HostLatencyProbe] 实现。
 *
 * 测两次取最小值：单次握手很容易被进程刚唤醒、路由/ARP 缓存冷、同网段其他
 * 应用抢带宽等一次性因素抬高，从而把一条其实很快的线路判成慢的。
 * 取最小值更接近这条线路的真实下限，代价是失败时也要多耗一次超时。
 */
@Singleton
internal class SocketHostLatencyProbe @Inject constructor() : HostLatencyProbe {

    override suspend fun measureLatency(ip: String): Long = withContext(Dispatchers.IO) {
        var best = HostLatencyProbe.UNREACHABLE
        repeat(ATTEMPTS) {
            val startedAtMs = Clock.System.now().toEpochMilliseconds()
            val latency = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, HTTPS_PORT), CONNECT_TIMEOUT_MS)
                }
                Clock.System.now().toEpochMilliseconds() - startedAtMs
            } catch (e: Exception) {
                HostLatencyProbe.UNREACHABLE
            }
            if (latency < best) best = latency
        }
        best
    }

    private companion object {
        const val HTTPS_PORT = 443
        const val CONNECT_TIMEOUT_MS = 2000
        const val ATTEMPTS = 2
    }
}
