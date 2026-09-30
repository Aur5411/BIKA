package com.shizq.bika.core.network.plugin

import com.shizq.bika.core.coroutine.ApplicationScope
import com.shizq.bika.core.datastore.UserPreferencesDataSource
import com.shizq.bika.core.network.BikaEndpoints
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

@OptIn(ExperimentalAtomicApi::class)
@Singleton
class DirectDns @Inject constructor(
    private val userPreferencesDataSource: UserPreferencesDataSource,
    @ApplicationScope private val scope: CoroutineScope,
) : Dns {
    private val apiIpsRef = AtomicReference<List<InetAddress>>(emptyList())
    private val imageIpsRef = AtomicReference<List<InetAddress>>(emptyList())

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

                    apiIpsRef.store(apiIps)
                    imageIpsRef.store(imageIps)
                    logger.info { "Direct DNS updated. API IPs: ${apiIps.size}, Image IPs: ${imageIps.size}" }
                }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (isApiHost(hostname)) {
            val currentApiIps = apiIpsRef.load()
            if (currentApiIps.isNotEmpty()) {
                logger.debug { "Returning API IP list for hostname: $hostname" }
                return currentApiIps
            }
        } else if (isImageHost(hostname)) {
            val currentImageIps = imageIpsRef.load()
            if (currentImageIps.isNotEmpty()) {
                logger.debug { "Returning Image IP list for hostname: $hostname" }
                return currentImageIps
            }
        }

        logger.debug { "No direct IP matched or empty IP list. Falling back to system DNS for: $hostname" }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun isApiHost(hostname: String): Boolean = hostname.matchesHost(BikaEndpoints.API_HOST)

    /**
     * 是不是图片域名，能否用用户配置的直连 IP。
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
        if (imageIpsRef.load().isEmpty()) return false
        return IMAGE_HOST_SUFFIXES.any { hostname.matchesHost(it) }
    }

    private fun String.matchesHost(domain: String): Boolean =
        equals(domain, ignoreCase = true) || endsWith(".$domain", ignoreCase = true)

    private companion object {
        /**
         * 保留为文档/日志用途：这些是已知的图片存储域名后缀。
         * 判定不再依赖它（见 [isImageHost]），改后缀列表会在服务端换节点时静默失效。
         */
        val IMAGE_HOST_SUFFIXES = listOf("picacomic.com", "diwodiwo.xyz", "tipatipa.xyz")
    }
}