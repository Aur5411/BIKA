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
 *
 * ## 第二条不变量：不能收录"对 storage-b 返回 403"的 IP
 *
 * 服务端会把 `/static/tobs/xxx.jpg` 在任意域名下 `301` 重定向到
 * `storage-b.picacomic.com`，而 `storage-b` 的可用性按 CF 边缘节点（IP）分裂。
 * OkHttp 只在**连接失败**时才换 IP，**403 不触发切换**，所以列表里排最前的
 * 那个 IP 一旦对 `storage-b` 是 403，图片就确定性失败、重试无效。
 * 这条属于"数据正确性"，无法靠单测的静态断言完全覆盖（需要联网），
 * 因此在 [BootstrapDnsIps] 的 KDoc 里记录了完整的实测矩阵；
 * 这里只守结构与规模。
 */
class BootstrapDnsIpsTest {

    /** 已知对 `storage-b.picacomic.com` 返回 403 的 IP，绝不能进兜底池。 */
    private val storageBHostileIps = setOf(
        "104.21.20.188",
        "104.20.33.201",
        "104.25.248.203",
        "172.66.168.88",
        "172.66.208.43",
    )

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
        // 实测可用的 CF 段横跨 104.16 / 104.17 / 104.19
        val subnets = BootstrapDnsIps.BOOTSTRAP_IP_STRINGS
            .map { it.substringBeforeLast('.') }
            .toSet()
        assertTrue(
            subnets.size >= 2,
            "兜底 IP 只覆盖 $subnets 一个网段，单点故障风险过高",
        )
    }

    @Test
    fun `兜底池不得包含对 storage-b 返回 403 的 IP`() {
        // 见类 KDoc：服务端会 301 到 storage-b，而 OkHttp 不会因 403 换 IP，
        // 所以这类 IP 排在最前会造成"某些图稳定失败、重试无效"。
        val offending = BootstrapDnsIps.BOOTSTRAP_IP_STRINGS
            .filter { it in storageBHostileIps }
        assertTrue(
            offending.isEmpty(),
            "兜底池里混入了对 storage-b 返回 403 的 IP: $offending —— " +
                    "服务端会 301 重定向到 storage-b，这类 IP 排在最前会导致图片确定性失败",
        )
    }

    @Test
    fun `模型层默认 IP 池与网络层兜底池保持一致方向`() {
        // 两处各有一份（模块依赖决定无法共用），最容易出现的漂移是
        // "改了 core:network 忘了改 core:model"。
        // 这里只要求两者都不含 storage-b 敌对 IP，避免任一处先被改坏。
        val modelDefaults = com.shizq.bika.core.model.preferences.DnsPreferences.DEFAULT_DNS_IPS
        val offending = modelDefaults.filter { it in storageBHostileIps }
        assertTrue(
            offending.isEmpty(),
            "DnsPreferences.DEFAULT_DNS_IPS 含 storage-b 敌对 IP: $offending",
        )
    }
}
