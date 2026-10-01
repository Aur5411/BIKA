package com.shizq.bika.core.network.dns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 兜底 DNS IP 池的不变量。
 *
 * 锁定的是本轮最严重的缺陷：`DirectDns` 的两个 IP 引用初始为空，只能靠
 * 冷启动异步探测填充（最坏十几秒）。这段窗口内所有图片域名都被判为"非图片域名"，
 * 回落到**被污染的系统 DNS**——本机实测 `storage1.picacomic.com` 会解析到
 * `31.13.82.33`（Facebook 网段），连接必然失败。
 *
 * 实测对照（同一张图 `/static/a9cf81c3-....jpg`）：
 * - 系统 DNS → 连接失败（curl 000）
 * - 直连 IP `104.16.253.195` → **200 + 330447 字节**
 *
 * 也就是说图与 URL 都没问题，唯一致命因素是解析。因此"启动早期必须有非空兜底 IP"
 * 这条不变量必须被测试守住——它一旦被改回 `emptyList()`，整个故障会原样复现，
 * 且因为失败页码随预载节奏漂移，非常难再次定位。
 */
class BootstrapDnsIpsTest {

    @Test
    fun `兜底 IP 列表非空`() {
        assertTrue(
            BootstrapDnsIps.BOOTSTRAP_IP_STRINGS.isNotEmpty(),
            "兜底 IP 为空等于把冷启动早期的图片请求交回被污染的系统 DNS",
        )
    }

    @Test
    fun `兜底 IP 全部能解析为 InetAddress`() {
        val expected = BootstrapDnsIps.BOOTSTRAP_IP_STRINGS.size
        assertEquals(
            expected,
            BootstrapDnsIps.DEFAULT_IPS.size,
            "有 IP 字符串解析失败，说明列表里混进了非法地址",
        )
    }

    @Test
    fun `每个兜底 IP 都是字面量地址而非主机名`() {
        BootstrapDnsIps.BOOTSTRAP_IP_STRINGS.forEach { ip ->
            // inet 地址经 getHostAddress 规范化后应与输入一致；若输入是主机名，
            // 这一条会失败——把主机名放进直连 IP 池会触发递归 DNS 解析
            val resolved = BootstrapDnsIps.DEFAULT_IPS
                .firstOrNull { it.hostAddress == ip }
            assertTrue(resolved != null, "『$ip』不是可用的字面量 IP")
        }
    }

    @Test
    fun `兜底 IP 覆盖多个不同网段`() {
        // 全部落在同一 /16 时，一次路由抖动就会同时失效；
        // 实测可用的 CF 段横跨 104.16 / 104.17 / 104.20 / 104.25
        val subnets = BootstrapDnsIps.BOOTSTRAP_IP_STRINGS
            .map { it.substringBeforeLast('.') }
            .toSet()
        assertTrue(
            subnets.size >= 2,
            "兜底 IP 只覆盖 $subnets 一个网段，单点故障风险过高",
        )
    }
}
