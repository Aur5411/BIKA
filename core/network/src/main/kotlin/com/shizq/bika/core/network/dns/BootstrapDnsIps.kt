package com.shizq.bika.core.network.dns

import java.net.InetAddress

/**
 * 冷启动用的**兜底直连 IP 池**，保证 DNS 解析在任何时刻都不会落到被污染的系统 DNS。
 *
 * ## 为什么需要这个常量
 *
 * [com.shizq.bika.core.network.plugin.DirectDns] 的 IP 引用此前初始为空，
 * 只能靠"冷启动探测 → 写 DataStore → flow 发射"填充（最坏十几秒）。
 * 期间所有图片域名都会回落到系统 DNS，而国内对 `*.picacomic.com` 的解析被污染
 * 到 `31.13.82.33`（Facebook 网段），连接必然失败。
 *
 * ## 选 IP 的硬性标准：必须同时覆盖多个存储子域
 *
 * 这一条是本文件最容易被忽视、却直接决定成败的地方。
 *
 * 服务端会做**跨子域重定向**：`/static/tobs/xxx.jpg` 在任意域名下都返回
 * `301`，`Location` 指向 `storage-b.picacomic.com/static/xxx.jpg`（剥离 `tobs/` 段）。
 * 客户端跟随重定向后，**最终请求落在 `storage-b` 上**，而不是原始域名。
 *
 * 而 `storage-b` 的可用性是**按 Cloudflare 边缘节点（即按 IP）分裂的**——
 * 这是实测数据（同一张图 `ca6a278e-73c6-40ec-aa74-4cb4872d786c.jpg`）：
 *
 * | IP | `storage1` | `storage-b` |
 * |---|---|---|
 * | `104.16.253.195` | 200 | **200** |
 * | `104.17.227.96` | 200 | **200** |
 * | `104.17.188.39` | 200 | **200** |
 * | `104.17.167.224` | 200 | **200** |
 * | `104.20.33.201` | 200 | 403 |
 * | `104.25.248.203` | 200 | 403 |
 * | `172.66.168.88` | 200 | 403 |
 * | `104.21.20.188` | 200 | 403 |
 *
 * 注意最后一行：`104.21.20.188` 是历史默认 IP，它对 `storage1` 完全正常，
 * **但对 `storage-b` 返回 403**。而 OkHttp 对 `List<InetAddress>` 是
 * **顺序尝试、且只在连接层失败时才换下一个**——403 是有效的 HTTP 响应，
 * 不会触发换 IP。所以只要列表里排在最前的是这一批"storage-b 不友好"的 IP，
 * **所有走 301 重定向的图都会稳定失败**，且失败是确定性的、重试无效。
 *
 * 因此本列表**只收录对 `storage1` 与 `storage-b` 双双返回 200 的 IP**。
 * 这不是"性能优选"，是"可用性下限"。
 *
 * ## 为什么多个 IP 一起给 OkHttp
 *
 * OkHttp 拿到 `List<InetAddress>` 后会在**连接失败**时自动尝试下一个。
 * 给一组比给一个稳：某个 CF 边缘节点连不上时不必换源重来。
 *
 * ## 注意：这不是"白名单"
 *
 * 它只是**冷启动的起点**。探测成功后会被更优的 IP 覆盖（见 DirectDns），
 * 服务端换节点也只需更新这里，不构成"只认这几个 IP"的限制。
 */
internal object BootstrapDnsIps {

    /**
     * 兜底 IP 字符串，**按"对全部存储子域可用"筛选**。
     *
     * 全部取自 Bika 自己的 HTTP DNS 服务
     * （`https://macapi1.com/app/picacomic/dns/resolve?domain=picacomic.com`）
     * 返回的 Cloudflare 段。
     *
     * 顺序有意义：OkHttp 顺序尝试，排前面的先被用。这里把实测
     * `storage1` / `storage-b` 双 200 的放在最前。
     */
    val BOOTSTRAP_IP_STRINGS: List<String> = listOf(
        // 实测 storage1 + storage-b 双 200，放在最前
        "104.16.253.195",
        "104.17.227.96",
        "104.17.188.39",
        "104.17.167.224",
        // 以下同样通过双 200 校验，作为冗余
        "104.17.216.104",
        "104.19.145.134",
    )

    /**
     * 已解析的 [BOOTSTRAP_IP_STRINGS]。
     *
     * 用 `InetAddress.getByName(ip)` 而非 DNS 查询：传入字面量 IP 时它只做
     * 就地解析、不发网络请求，因此可以在类初始化阶段安全调用，不阻塞启动。
     */
    val DEFAULT_IPS: List<InetAddress> = BOOTSTRAP_IP_STRINGS.mapNotNull { ip ->
        runCatching { InetAddress.getByName(ip) }.getOrNull()
    }
}
