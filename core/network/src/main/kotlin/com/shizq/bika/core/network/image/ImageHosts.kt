package com.shizq.bika.core.network.image

/**
 * 图片源（存储域名）候选池，单一事实来源。
 *
 * 原先这份列表是 [com.shizq.bika.core.network.plugin.DomainFallbackInterceptor] 里的
 * 私有常量，只有降级竞速能用到它。现在"最快源选路"也要读同一份列表，
 * 两边各写一份必然会在某次新增镜像后走偏，所以提到这里共享。
 *
 * ## ⚠️ 入池前必须逐个实测，不能凭"看起来像图片域名"就加
 *
 * 池子里放过两个**根本不是图片存储节点**的域名，代价很实在：
 *
 * | 域名 | `/static/` 路径实测 | 根路径 |
 * |---|---|---|
 * | `img.picacomic.com` | **全部 404**（连 `/static/` 本身都 404） | 200 |
 * | `www.picacomic.com` | **全部 404** | 200 |
 *
 * 它们是 Web / API 主机，根路径有正常页面，所以 DNS 能解析、TCP 能连上、
 * 还会给出**真实的 HTTP 响应**——于是 [ImageHostHealth] 永远不会把它们隔离
 * （该组件只隔离"连接层失败"，拿到状态码就认为源是活的）。
 *
 * 结果是这两个域名**每次换源遍历都白占两发**，在遍历预算有限的串行兜底里
 * 直接把可用源挤到队尾；更糟的是它们返回的 **404** 会顶掉合法存储节点给出的
 * 403，让界面把"路径写错"报成"图片不存在"——一个**方向完全相反**的结论。
 *
 * 所以新增域名前必须实测三类路径（`/static/<真实图>`、`/static/`、根路径）：
 * 只有 `/static/` 下能返回 200/301 的才入池。仅凭"域名里带 storage/img、
 * 能 ping 通"就加进来，等于给每一次换源都加一次必败请求。
 *
 * ## 路径里的 `tobs/` 是**后端选择段**，不是普通目录
 *
 * 这一条决定了"换源能不能救回一张图"，是最容易被误判的一层。
 *
 * 实测（本机直连，路径 `sub_storage_1/7f/8b/<真实 uuid>.jpg`，683 KB 级大图）：
 *
 * | 域名 | `/static/tobs/sub_storage_1/…` | `/static/sub_storage_1/…`（无 `tobs/`） |
 * |---|---|---|
 * | `storage.diwodiwo.xyz` | 301 → `storage-b.diwodiwo.xyz/static/sub_storage_1/…` → **200** | **404** |
 * | `storage.tipatipa.xyz` | 301 → 同上 → **200** | **404** |
 * | `storage-b.diwodiwo.xyz` | 301（自我剥掉 `tobs/`）→ **200** | **200** |
 *
 * 也就是说 `tobs/` 的含义是"这份文件在 storage-b 后端"，靠它做后端路由；
 * **一旦 URL 里没有 `tobs/`，池子里那些"路由器"域名全部 404**。
 *
 * 为什么这条很致命：[DomainFallbackInterceptor] 换源时**只改 host、保留 path**。
 * 若服务端给的 `path` 恰好不带 `tobs/` 前缀，那么无论换到池子里哪一个路由器域名，
 * 拿到的都是 404 —— 遍历一圈全灭，最后被判定成"服务端缺图"，
 * 界面报 **"图片不存在（HTTP 404）"**，而这张图其实好好地躺在源站上。
 * 这正是"某几页怎么重试都出不来"在 `sub_storage*` 分桶目录上的成因。
 *
 * 所以池子里必须至少保留一个**能直接吃下无 `tobs/` 路径**的节点。
 * `storage-b.diwodiwo.xyz` 就是这样的节点：上表 8 种路径形态（有/无 `tobs/` ×
 * 3 个真实 uuid + 1 个普通图）**全部 200**，是当前容错面最宽的候选，故排首位。
 *
 * ## 顺序的含义
 *
 * - 竞速（race）是并发的，顺序只决定同速时的胜出者；
 * - 404/403 是串行的，顺序即尝试次序，因此把命中率高的排前面。
 *
 * ## 新增域名前必须确认的两件事
 *
 * 1. 域名真实存在且能取到 `/static/{path}`——见上，不实测就会引入必败源；
 * 2. 若域名后缀不是 `picacomic.com`，还要把它加进
 *    [com.shizq.bika.core.network.plugin.DirectDns] 的 `IMAGE_HOST_SUFFIXES`，
 *    否则它不走直连 IP 池，在国内被 DNS 污染的环境里直接连不上。
 */
internal object ImageHosts {

    /** 主源（API 返回的 `fileServer`）之外的全部候选镜像。 */
    val imageDomains = listOf(
        // 容错面最宽：有/无 `tobs/` 前缀都能直接 200（本身就在 storage-b 后端上），
        // 无需跳转。换源只改 host 不改 path，所以它必须排第一——
        // 它是池子里唯一能接住"path 不带 tobs/"的节点。
        "https://storage-b.diwodiwo.xyz",
        "https://s3.picacomic.com",
        "https://s2.picacomic.com",
        "https://storage1.picacomic.com",
        "https://storage.diwodiwo.xyz",
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
     *
     * 选 `storage-b.diwodiwo.xyz` 而不是 `storage.diwodiwo.xyz`：前者就在
     * storage-b 后端上，`/static/{path}` 直接 200，**省掉一次跨域 `301`**；
     * 后者是只做后端路由的前端，任何请求都要先跳一次。
     */
    const val DEFAULT_HOST = "storage-b.diwodiwo.xyz"
}
