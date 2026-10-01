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
        /**
         * 历史默认 IP。**已不再作为首选**。
         *
         * 实测它对 `storage1.picacomic.com` 正常，但对 `storage-b.picacomic.com`
         * **返回 403**——而服务端会把 `/static/tobs/xxx.jpg` 301 重定向到
         * `storage-b`，导致走重定向的图稳定失败。因为 OkHttp 只在**连接失败**时
         * 才换下一个 IP、403 不触发切换，把它放在列表首位会直接造成故障。
         *
         * 保留常量仅供识别/迁移用，不再写入默认池。
         */
        const val DEFAULT_DNS_IP = "104.21.20.188"
        const val DEFAULT_DNS_LINE = "telecom"

        /**
         * 出厂默认 IP 池。
         *
         * 选取硬标准：必须对 `storage1` **与** `storage-b` 双双返回 200
         * （理由见 [com.shizq.bika.core.network.dns.BootstrapDnsIps] 的说明——
         * 服务端会跨子域 301，最终的图是从 `storage-b` 取的）。
         *
         * 用 `LinkedHashSet` 保持顺序：OkHttp 顺序尝试，排前面的先被用。
         */
        val DEFAULT_DNS_IPS: Set<String> = linkedSetOf(
            "104.16.253.195",
            "104.17.227.96",
            "104.17.188.39",
            "104.17.167.224",
            "104.17.216.104",
            "104.19.145.134",
        )
    }
}
