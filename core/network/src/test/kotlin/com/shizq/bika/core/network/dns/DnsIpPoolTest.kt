package com.shizq.bika.core.network.dns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分流 IP 池的选取规则。
 *
 * 这层决定「哪几个 IP 会被注入 DNS」，一旦选错，用户侧的表现是
 * 网络时快时慢且难以复现，因此规则本身要能被测试钉住。
 */
class DnsIpPoolTest {

    private fun host(
        ip: String,
        latency: Long,
        domain: String = BikaDnsDomains.API,
        line: String = "telecom",
    ) = ProbedDnsHost(ip = ip, domain = domain, lineName = line, latencyMs = latency)

    // ───────────────────────── subnetKeyOf ─────────────────────────

    @Test
    fun `取 IPv4 前三段作为网段`() {
        assertEquals("104.20.42", subnetKeyOf("104.20.42.106"))
    }

    @Test
    fun `非 IPv4 原样返回`() {
        // 不能把 IPv6 / 垃圾数据截断成看似合法的网段，否则会把互不相关的
        // 候选错误地归并成同一段，冗余度凭空下降
        assertEquals("2606:4700::1", subnetKeyOf("2606:4700::1"))
        assertEquals("", subnetKeyOf(""))
    }

    // ───────────────────── selectInjectionSet ─────────────────────

    @Test
    fun `按延迟升序取满上限`() {
        val picked = selectInjectionSet(
            listOf(host("1.1.1.1", 300), host("2.2.2.2", 100), host("3.3.3.3", 200)),
            maxIps = 2,
            perSubnetCap = 10,
        )
        assertEquals(listOf("2.2.2.2", "3.3.3.3"), picked.map { it.ip })
    }

    @Test
    fun `同一网段超限时向后取别的网段`() {
        // 四个都很快但都在同一个 /24：必须让位给稍慢但不同网段的候选，
        // 否则注入的一组 IP 实际是同一条路由，等于没有冗余
        val candidates = listOf(
            host("104.20.42.1", 10),
            host("104.20.42.2", 11),
            host("104.20.42.3", 12),
            host("104.20.42.4", 13),
            host("172.66.173.1", 50),
        )
        val picked = selectInjectionSet(candidates, maxIps = 3, perSubnetCap = 2)
        assertEquals(listOf("104.20.42.1", "104.20.42.2", "172.66.173.1"), picked.map { it.ip })
    }

    @Test
    fun `不可达的候选一律不入选`() {
        val picked = selectInjectionSet(
            listOf(
                host("1.1.1.1", 100),
                host("2.2.2.2", HostLatencyProbe.UNREACHABLE),
                host("3.3.3.3", 200),
            ),
            perSubnetCap = 10,
        )
        assertEquals(listOf("1.1.1.1", "3.3.3.3"), picked.map { it.ip })
    }

    @Test
    fun `全部不可达时返回空`() {
        assertTrue(
            selectInjectionSet(
                listOf(host("1.1.1.1", HostLatencyProbe.UNREACHABLE)),
            ).isEmpty()
        )
    }

    // ─────────────────────── selectActiveLine ──────────────────────

    @Test
    fun `优先选 API 与图片都能连上的线路`() {
        // 电信的 API 更快，但图片域名只有联通能连上；分开选会让服务端的
        // app-channel 分流自相矛盾，因此整体应该走联通
        val api = listOf(host("1.1.1.1", 50, line = "telecom"), host("2.2.2.2", 80, line = "unicom"))
        val image = listOf(host("3.3.3.3", 90, line = "unicom"))

        assertEquals("unicom", selectActiveLine(api, image))
    }

    @Test
    fun `没有成对线路时退回最快的那一侧`() {
        val api = listOf(host("1.1.1.1", 50, line = "telecom"))
        val image = listOf(host("3.3.3.3", 30, line = "mobile"))

        assertEquals("telecom", selectActiveLine(api, image))
    }

    @Test
    fun `两组都为空时返回 null`() {
        assertNull(selectActiveLine(emptyList(), emptyList()))
    }

    // ───────────────────────── DnsPoolState ───────────────────────

    @Test
    fun `黑名单到期后自动失效`() {
        val now = 1_000_000L
        val state = DnsPoolState().withBlacklisted(listOf("1.1.1.1"), now, durationMs = 60_000)

        assertEquals(setOf("1.1.1.1"), state.activeBlacklistIps(now + 1))
        assertTrue(state.activeBlacklistIps(now + 60_001).isEmpty())
    }

    @Test
    fun `重复拉黑取更晚的到期时间`() {
        // 先按 10 分钟拉黑，再按 1 分钟写一次，不能让封禁期被缩短
        val now = 1_000_000L
        val state = DnsPoolState()
            .withBlacklisted(listOf("1.1.1.1"), now, durationMs = 600_000)
            .withBlacklisted(listOf("1.1.1.1"), now + 1_000, durationMs = 60_000)

        assertEquals(setOf("1.1.1.1"), state.activeBlacklistIps(now + 500_000))
    }

    @Test
    fun `写回黑名单时顺带清理过期项`() {
        val now = 1_000_000L
        val state = DnsPoolState()
            .withBlacklisted(listOf("1.1.1.1"), now, durationMs = 1_000)
            .withBlacklisted(listOf("2.2.2.2"), now + 5_000, durationMs = 60_000)

        val ips = state.blacklist.map { it.ip }
        assertEquals(listOf("2.2.2.2"), ips)
    }

    @Test
    fun `快照按域名展开为候选项`() {
        val snapshot = DnsPoolSnapshot(
            savedAtEpochMs = 1L,
            activeLine = "unicom",
            apiHosts = listOf("1.1.1.1"),
            imageHosts = listOf("2.2.2.2"),
        )
        val hosts = snapshot.asResolvedHosts()

        assertEquals(2, hosts.size)
        assertEquals(BikaDnsDomains.API, hosts[0].domain)
        assertEquals(BikaDnsDomains.IMAGE, hosts[1].domain)
        assertEquals("unicom", hosts[0].lineName)
    }

    @Test
    fun `空快照不产生候选项`() {
        assertTrue(DnsPoolSnapshot().isEmpty)
        assertTrue(DnsPoolSnapshot().asResolvedHosts().isEmpty())
    }
}
