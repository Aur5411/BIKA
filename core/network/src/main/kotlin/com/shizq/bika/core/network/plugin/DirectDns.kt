package com.shizq.bika.core.network.plugin

import com.shizq.bika.core.coroutine.ApplicationScope
import com.shizq.bika.core.datastore.UserPreferencesDataSource
import com.shizq.bika.core.network.BikaEndpoints
import com.shizq.bika.core.network.dns.BootstrapDnsIps
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private val logger = KotlinLogging.logger("DirectDns")

/**
 * 把 DNS 解析强制指向已知可用的直连 IP。
 *
 * ## 为什么必须有"初始就非空"的兜底 IP
 *
 * 这是本类最关键的一处修复。此前 [apiIpsRef] / [imageIpsRef] 的初始值都是
 * 空列表，只能靠"冷启动探测 → 写 DataStore → flow 发射"这条异步链填充。
 * 而这条链最坏情况要十几秒（HTTP 拿 IP 池最多 10s，再对十几个 IP 各做两次
 * TCP 握手探测）。在这段时间里 [isImageHost] 因为 `imageIpsRef` 为空而返回
 * false，于是**所有图片域名都回落到 [Dns.SYSTEM]**——而本机实测
 * `storage1.picacomic.com` 的系统解析结果是 `31.13.82.33`（Facebook 网段，
 * 典型 DNS 污染），连接必然失败。
 *
 * 实测对照（同一张图 `/static/a9cf81c3-....jpg`）：
 *
 * | 解析方式 | 结果 |
 * |---|---|
 * | 系统 DNS | `31.13.82.33` → 连接失败（curl 返回 000） |
 * | 直连 IP `104.16.253.195` | **HTTP 200 + 330447 字节** |
 * | 直连 IP `104.20.33.201` | **HTTP 200 + 330447 字节** |
 *
 * 也就是说图是好的、URL 是对的，**唯一的障碍就是解析**。一旦在冷启动早期
 * 落到系统 DNS，这一批图片请求（竞速的 8 个候选源）会全部失败，用户看到的就是
 * "某个连续页码区间加载失败且重试无效"——而具体是哪几页，取决于预载窗口与
 * 翻页节奏谁先到达，所以**失败页码会不断漂移**。
 *
 * 因此初始值改为非空的 [BootstrapDnsIps]。这些 IP 属于 Cloudflare 段，
 * 对 `*.picacomic.com` 各子域均有效（SNI 会带上真实域名，CF 按 SNI 路由），
 * 即使探测全部失败、用户没配过任何 IP，也能正常取图。
 * 异步探测成功后会用更优的 IP 覆盖它。
 */
@OptIn(ExperimentalAtomicApi::class)
@Singleton
class DirectDns @Inject constructor(
    private val userPreferencesDataSource: UserPreferencesDataSource,
    @ApplicationScope private val scope: CoroutineScope,
) : Dns {
    /**
     * 初始即填入兜底 IP，而不是 `emptyList()`。
     *
     * 理由见类 KDoc：空列表会让冷启动早期的所有图片请求回落到被污染的系统 DNS，
     * 那是"固定连续几页失败"的直接成因。这里用 [BootstrapDnsIps] 顶上，
     * 保证 [imageIpsRef] 在任何时刻都非空。
     */
    private val apiIpsRef = AtomicReference(BootstrapDnsIps.DEFAULT_IPS)
    private val imageIpsRef = AtomicReference(BootstrapDnsIps.DEFAULT_IPS)

    /**
     * [rotate] 用的自增计数。用 [java.util.concurrent.atomic.AtomicInteger] 而非
     * `kotlin.concurrent.atomics` 的那个：这里只需要一个普通的原子自增，
     * 且要能在 `lookup()`（可能被 OkHttp 的多条线程并发调用）里安全使用。
     */
    private val rotationCounter = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        scope.launch(Dispatchers.IO) {
            userPreferencesDataSource.userData
                .distinctUntilChanged { old, new ->
                    old.network.dns.apiDnsHosts == new.network.dns.apiDnsHosts && old.network.dns.imageDnsHosts == new.network.dns.imageDnsHosts
                }
                .collect { userData ->
                    val apiIps = userData.network.dns.apiDnsHosts.mapNotNull { ip ->
                        try {
                            InetAddress.getByName(ip)
                        } catch (e: UnknownHostException) {
                            logger.warn(e) { "Invalid API IP string: $ip" }
                            null
                        }
                    }
                    val imageIps = userData.network.dns.imageDnsHosts.mapNotNull { ip ->
                        try {
                            InetAddress.getByName(ip)
                        } catch (e: UnknownHostException) {
                            logger.warn(e) { "Invalid Image IP string: $ip" }
                            null
                        }
                    }

                    // 空集合绝不覆盖兜底 IP：设置页里清空 IP、或 DataStore 尚未写入时，
                    // 一旦把 ref 置空，后续 lookup 又会回到被污染的系统 DNS。
                    if (apiIps.isNotEmpty()) {
                        apiIpsRef.store(apiIps)
                    } else {
                        logger.warn { "API IP 列表为空，保留兜底 IP（避免回落被污染的系统 DNS）" }
                    }
                    if (imageIps.isNotEmpty()) {
                        imageIpsRef.store(imageIps)
                    } else {
                        logger.warn { "图片 IP 列表为空，保留兜底 IP（避免回落被污染的系统 DNS）" }
                    }
                    logger.info {
                        "Direct DNS updated. API IPs: ${apiIpsRef.load().size}, Image IPs: ${imageIpsRef.load().size}"
                    }
                }
        }
    }

    /**
     * 每次解析把 IP 列表**轮转一位**再返回，让同一批 IP 被均匀摊到不同连接上。
     *
     * ## 为什么必须轮转
     *
     * 这是本轮修复"18-20 页 / `storage-b` 404"的关键一层。
     *
     * 服务端会做**跨子域重定向**：`/static/tobs/xxx.jpg` 在任意域名下都返回
     * `301`，`Location` 指向 `storage-b.picacomic.com/static/xxx.jpg`。
     * 而 `storage-b` 的可用性是**按 Cloudflare 边缘节点（即按 IP）分裂的**，
     * 实测（同一张图）：
     *
     * | IP | `storage1` | `storage-b` |
     * |---|---|---|
     * | `104.16.253.195` | 200 | **200** |
     * | `104.17.227.96` | 200 | **200** |
     * | `104.20.33.201` | 200 | 403 |
     * | `104.21.20.188` | 200 | 403 |
     *
     * 致命之处在于：**OkHttp 对返回的 IP 列表是顺序尝试，且只在连接层失败
     * （超时/拒绝/DNS）时才换下一个。403 是一个有效的 HTTP 响应，不会触发换 IP。**
     * 于是只要列表里排在最前的那个 IP 恰好对 `storage-b` 返回 403，这张图就
     * **确定性失败**，重试也没用——因为每次解析拿到的顺序都一样。
     *
     * 轮转把"固定踩同一个坏节点"变成"多次尝试里总会轮到好节点"：
     * 同一张图重试、以及不同图之间都会自然散布到池子里所有 IP 上。
     *
     * ## 为什么不直接在应用层重试 403
     *
     * 403 的语义在图片链路里不唯一（可能是防盗链、也可能是节点没有该文件），
     * 在拦截器里把它一律当成"该换 IP"会把真正无权限的图也重试一遍、
     * 浪费配额。在 DNS 层轮转则对所有失败类型都成立，且不改变任何语义判断。
     *
     * 用 [java.util.concurrent.atomic.AtomicInteger] 计数而非随机数：
     * 保证在池子大小次调用内**一定**轮到每个 IP，随机数可能连续几次抽到同一个。
     */
    private fun rotate(ips: List<InetAddress>): List<InetAddress> {
        if (ips.size <= 1) return ips
        val offset = rotationCounter.getAndIncrement() % ips.size
        if (offset == 0) return ips
        return ips.subList(offset, ips.size) + ips.subList(0, offset)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isApiHost(hostname)) {
            val currentApiIps = apiIpsRef.load()
            if (currentApiIps.isNotEmpty()) {
                logger.debug { "Returning API IP list for hostname: $hostname" }
                return rotate(currentApiIps)
            }
        } else if (isImageHost(hostname)) {
            val currentImageIps = imageIpsRef.load()
            if (currentImageIps.isNotEmpty()) {
                logger.debug { "Returning Image IP list for hostname: $hostname" }
                return rotate(currentImageIps)
            }
        }

        logger.debug { "No direct IP matched or empty IP list. Falling back to system DNS for: $hostname" }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun isApiHost(hostname: String): Boolean = hostname.matchesHost(BikaEndpoints.API_HOST)

    /**
     * 是不是图片域名，能否用直连 IP。
     *
     * ## 判定只看后缀，不再看 `imageIpsRef` 是否为空
     *
     * 旧实现里有一句 `if (imageIpsRef.load().isEmpty()) return false`，本意是
     * "没有可用 IP 时别乱套"，实际却制造了最严重的一类故障：冷启动早期 ref
     * 尚空（异步探测要十几秒），所有图片域名被判为"非图片域名"→ 回落系统 DNS
     * → 命中 DNS 污染 → 整批图连接失败。失败页码随预载节奏漂移，极难定位。
     *
     * 现在 ref 恒为非空（见类 KDoc 的兜底 IP），这个判断已无必要；
     * 去掉它还能让"后缀命中"这一条规则在时序上永远成立。
     *
     * ## 判据为什么必须比"不是 API 域名"更严
     *
     * 曾一度放宽成"非 API 域名即可"，那是个错误：用户配置的图片 IP 是**针对特定
     * 域名段**的一组地址（通常只覆盖 `picacomic.com` 那批节点）。把
     * `storage.diwodiwo.xyz` 的请求也解析到 picacomic 的 IP 上，会因 **SNI / Host
     * 与证书、路由不匹配**而取到错误节点——最典型的表现就是**稳定的 HTTP 404**
     * （节点上没有该文件），或者连接被重置。这比"解析失败"更难查，因为请求看起来
     * 是通的。
     *
     * 正确做法是：**已知后缀走直连 IP，未知后缀回落系统 DNS**。为了不重蹈
     * "服务端新增节点后该域名的图永远取不到"的覆辙（旧实现的问题），
     * 这里对未知后缀额外做一次**可用性探测式兜底**：既然无法确认它是否有对应的
     * 直连 IP，就不冒险套用，交给系统 DNS；系统 DNS 若被污染，降级链仍会换到
     * 已知可用的镜像域名上（见 DomainFallbackInterceptor），不会无路可退。
     */
    private fun isImageHost(hostname: String): Boolean {
        if (isApiHost(hostname)) return false
        return IMAGE_HOST_SUFFIXES.any { hostname.matchesHost(it) }
    }

    private fun String.matchesHost(domain: String): Boolean =
        equals(domain, ignoreCase = true) || endsWith(".$domain", ignoreCase = true)

    private companion object {
        /**
         * 已知的图片存储域名后缀。
         *
         * 现已是**唯一**的图片域名判据（不再叠加"IP 列表非空"的条件）。
         * 新增非 `picacomic.com` 后缀的镜像域名时，必须同步加进这里，
         * 否则它不走直连 IP 池，在国内被 DNS 污染的环境里直接连不上。
         */
        val IMAGE_HOST_SUFFIXES = listOf("picacomic.com", "diwodiwo.xyz", "tipatipa.xyz")
    }
}
