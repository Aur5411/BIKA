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
 * 到 `31.13.82.33`（Facebook 网段），连接必然失败——这正是"某几页固定加载失败"
 * 的根因。填入本常量后该窗口被彻底关闭。
 *
 * ## 这些 IP 是怎么来的
 *
 * 全部来自 Bika 自己的 HTTP DNS 服务
 * （`https://macapi1.com/app/picacomic/dns/resolve?domain=picacomic.com`）
 * 返回的 Cloudflare 段，覆盖 telecom / mobile / overseas 三条线路。
 *
 * ## 本机实测（同一张图 `/static/a9cf81c3-73b5-42e8-bf24-f03a5080780c.jpg`）
 *
 * | IP | 结果 |
 * |---|---|
 * | `104.16.253.195` | 200，330447 字节 |
 * | `104.20.33.201` | 200，330447 字节 |
 * | `172.66.168.88` | 200，330447 字节 |
 * | `104.17.227.96` | 200，330447 字节 |
 * | 系统 DNS → `31.13.82.33` | 连接失败（000） |
 *
 * ## 为什么多个 IP 一起给 OkHttp
 *
 * OkHttp 拿到 `List<InetAddress>` 后会自动尝试下一个（`RouteSelector`）。
 * 给一组比给一个稳：单个 CF 边缘节点偶发不可用时不必换源重来。
 *
 * ## 注意：这不是"白名单"
 *
 * 它只是**冷启动的起点**。探测成功后会被更优的 IP 覆盖（见 DirectDns），
 * 服务端换节点、CF 换段也只需更新这里，不构成"只认这几个 IP"的限制。
 */
internal object BootstrapDnsIps {

    /**
     * 兜底 IP 字符串。解析失败（极罕见的 JVM/网络栈问题）时静默跳过，
     * 不会因为一个坏字符串把整个列表清空。
     */
    val BOOTSTRAP_IP_STRINGS: List<String> = listOf(
        // telecom 线路
        "104.20.33.201",
        "104.25.248.203",
        // mobile 线路
        "104.16.253.195",
        "104.17.167.224",
        // overseas 线路
        "104.17.188.39",
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
