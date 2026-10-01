package com.shizq.bika.core.model.preferences

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 分流直连 IP 配置。
 *
 * `apiDns` / `imageDns` 默认给**一组** Cloudflare 段 IP，而不是单个
 * [DEFAULT_DNS_IP]：单个 IP 一旦在当前网络不可达（CF 边缘节点会按地域调度，
 * 换网络后未必仍可达），而冷启动自动优选又因探测失败"保留现有配置"，
 * 用户就会一直卡在一个连不上的 IP 上——表现为所有网络请求一起失败。
 * 给一组时 OkHttp 会自动尝试下一个（`RouteSelector`），可用性显著更高。
 *
 * 这组 IP 与 `core:network` 的 `BootstrapDnsIps` 同源（都取自 Bika HTTP DNS
 * 返回的 CF 段）。两处都保留一份是因为本模块只依赖 `core:model`、
 * 不依赖 `core:network`，无法直接引用后者。
 */
@Serializable
data class DnsPreferences(
    @SerialName("apiDns")
    val apiDnsHosts: Set<String> = DEFAULT_DNS_IPS,
    @SerialName("imageDns")
    val imageDnsHosts: Set<String> = DEFAULT_DNS_IPS,
    val activeLine: String = DEFAULT_DNS_LINE,
) {
    companion object {
        /** 保留为"首选 IP"语义：仍是最先尝试的那个。 */
        const val DEFAULT_DNS_IP = "104.21.20.188"
        const val DEFAULT_DNS_LINE = "telecom"

        /**
         * 出厂默认 IP 池。首个是 [DEFAULT_DNS_IP]（历史验证可用），
         * 其余取自 Bika HTTP DNS 的 telecom / mobile / overseas 线路。
         *
         * 用 `LinkedHashSet` 保持顺序，让 [DEFAULT_DNS_IP] 排在首位。
         */
        val DEFAULT_DNS_IPS: Set<String> = linkedSetOf(
            DEFAULT_DNS_IP,
            "104.20.33.201",
            "104.25.248.203",
            "104.16.253.195",
            "104.17.167.224",
            "104.17.188.39",
        )
    }
}
