package com.shizq.bika.core.network.image

/**
 * 图片源（存储域名）候选池，单一事实来源。
 *
 * 原先这份列表是 [com.shizq.bika.core.network.plugin.DomainFallbackInterceptor] 里的
 * 私有常量，只有降级竞速能用到它。现在"最快源选路"也要读同一份列表，
 * 两边各写一份必然会在某次新增镜像后走偏，所以提到这里共享。
 *
 * ## 顺序的含义
 *
 * - 竞速（race）是并发的，顺序只决定同速时的胜出者；
 * - 404 是串行的，顺序即尝试次序，因此把命中率高的排前面。
 *
 * ## 新增域名前必须确认的两件事
 *
 * 1. 域名真实存在且能取到 `/static/{path}`——不存在的域名会白占一个竞速槽位，
 *    真正能用的源反而排不上队；
 * 2. 若域名后缀不是 `picacomic.com`，还要把它加进
 *    [com.shizq.bika.core.network.plugin.DirectDns] 的 `IMAGE_HOST_SUFFIXES`，
 *    否则它不走直连 IP 池，在国内被 DNS 污染的环境里直接连不上。
 */
internal object ImageHosts {

    /** 主源（API 返回的 `fileServer`）之外的全部候选镜像。 */
    val imageDomains = listOf(
        "https://s3.picacomic.com",
        "https://s2.picacomic.com",
        "https://storage1.picacomic.com",
        // img 与上面几个同属 picacomic，走 DirectDns 的图片直连 IP 池；
        // 与 storage1 等不同节点，多一个候选就多一次绕过故障节点的机会。
        "https://img.picacomic.com",
        "https://storage.diwodiwo.xyz",
        "https://www.picacomic.com",
        "https://storage-b.picacomic.com",
        // tipatipa 实测比 diwodiwo 慢一档，且对缺 UA 的请求直接 403，排在末位兜底。
        "https://storage.tipatipa.xyz",
    )

    /** [imageDomains] 的 host 形式，保持顺序。 */
    val imageHosts: List<String> = imageDomains.map { it.removePrefix("https://") }

    /** 由 [imageHosts] 派生，避免调用方各自解析一遍。 */
    val MANAGED_HOSTS: Set<String> = imageHosts.toSet()

    /**
     * 还没有竞速结论时的默认源，用于连接预热。
     *
     * 取本机实测最快的那个：漫画像允许 Range 请求、且同一份文件多线程也能线性
     * 叠加带宽（16 并发约 3 MB/s），而 diwodiwo 是唯一始终可达且最快的节点。
     * 预热猜错了没有代价——坏连接闲置 5 分钟会被连接池清掉，
     * 真正下载时的选路结论仍然来自竞速。
     */
    const val DEFAULT_HOST = "storage.diwodiwo.xyz"
}
