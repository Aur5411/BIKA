package com.shizq.bika.core.network.dns

/**
 * 一次 IP 探测的结果。
 *
 * @param latencyMs 握手延迟；不可达时为 [HostLatencyProbe.UNREACHABLE]
 */
data class ProbedDnsHost(
    val ip: String,
    val lineName: String,
    val domain: String,
    val latencyMs: Long,
) {
    val isReachable: Boolean get() = latencyMs != HostLatencyProbe.UNREACHABLE
}

/** 注入 DNS 的 IP 个数上限。 */
internal const val MAX_INJECT_IPS = 6

/** 同一个 /24 里最多取几个 IP。 */
internal const val MAX_IPS_PER_SUBNET = 3

/**
 * 取 IPv4 的 /24 前缀，用于把候选 IP 按网段归并。
 *
 * 非 IPv4（IPv6、域名、垃圾数据）原样返回：归并只是"分母段"，把无法归并的
 * 输入各自当成独立一段，比强行截断成错误的网段更安全。
 */
internal fun subnetKeyOf(ip: String): String {
    val parts = ip.split('.')
    return if (parts.size == 4 && parts.all { it.isNotEmpty() }) {
        parts.take(3).joinToString(".")
    } else {
        ip
    }
}

/**
 * 从探测结果里选出要注入 DNS 的 IP 集合。
 *
 * ## 为什么注入多个而不是只注入最快的一个
 *
 * OkHttp 只会在同一 hostname 返回的 IP 列表内部轮换重试。只给一个 IP 时，
 * 那一个被墙、被限速或临时抖动，整条链路就断了，客户端没有任何退路。
 * 给一组 IP，失败的那个会被 OkHttp 自然跳过。
 *
 * ## 为什么要按 /24 归并
 *
 * 同一 /24 的 IP 走同一段路由，同时不可用的概率很高。若直接取"最快的 6 个"，
 * 很可能全部落在同一个 /24 里，冗余度等于零。因此按网段限流后再取，
 * 保证注入集合横跨多个网段。
 *
 * @param candidates 同一域名下的全部探测结果
 * @param maxIps 最多注入几个
 * @param perSubnetCap 每个 /24 最多取几个
 */
internal fun selectInjectionSet(
    candidates: List<ProbedDnsHost>,
    maxIps: Int = MAX_INJECT_IPS,
    perSubnetCap: Int = MAX_IPS_PER_SUBNET,
): List<ProbedDnsHost> {
    val takenPerSubnet = mutableMapOf<String, Int>()
    val picked = ArrayList<ProbedDnsHost>(maxIps)

    // 按延迟升序：排序后不可达项（UNREACHABLE 是 Long.MAX_VALUE）全部落在末尾，
    // 因此遇到第一个不可达项就可以整体收工
    for (candidate in candidates.sortedBy { it.latencyMs }) {
        if (!candidate.isReachable) break

        val subnet = subnetKeyOf(candidate.ip)
        val used = takenPerSubnet[subnet] ?: 0
        if (used >= perSubnetCap) continue

        takenPerSubnet[subnet] = used + 1
        picked += candidate
        if (picked.size >= maxIps) break
    }

    return picked
}

/**
 * 选出要激活的分流线路。
 *
 * 优先取"API 与图片两个域名都能连上"的线路，并按两者延迟之和比较：
 * 两个域名走同一条线路时，服务端的 `app-channel` 分流才是自洽的；
 * 一个走电信、一个走联通属于不得已的降级。
 *
 * @param apiHosts 已选出的 API 域名 IP（延迟升序由调用方保证或此处重排）
 * @param imageHosts 已选出的图片域名 IP
 * @return 线路名；两组都为空时返回 null
 */
internal fun selectActiveLine(
    apiHosts: List<ProbedDnsHost>,
    imageHosts: List<ProbedDnsHost>,
): String? {
    val bestApiByLine = apiHosts.filter { it.isReachable }.minByOrNull { it.latencyMs }
    val bestImageByLine = imageHosts.filter { it.isReachable }.minByOrNull { it.latencyMs }

    val pairedLine = apiHosts.asSequence()
        .filter { it.isReachable }
        .groupBy { it.lineName }
        .mapNotNull { (line, apiItems) ->
            val imageItems = imageHosts.filter { it.lineName == line && it.isReachable }
            val api = apiItems.minByOrNull { it.latencyMs } ?: return@mapNotNull null
            val image = imageItems.minByOrNull { it.latencyMs } ?: return@mapNotNull null
            Triple(line, api.latencyMs + image.latencyMs, api)
        }
        .minByOrNull { it.second }

    return pairedLine?.first
        ?: bestApiByLine?.lineName
        ?: bestImageByLine?.lineName
}
