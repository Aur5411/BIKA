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
     * 是不是图片域名。
     *
     * 判据是**「不是 API 域名，且用户配置了图片直连 IP」**，而不是"后缀在已知列表里"。
     *
     * 旧实现用后缀白名单（picacomic.com / diwodiwo.xyz / tipatipa.xyz），
     * 于是服务端一旦启用新的存储节点（例如启用某个新 CDN 域名），那个域名的图
     * 就会**回落到系统 DNS**：在 DNS 被污染或不可达的网络里直接解析失败，
     * 表现为"某几页图固定加载不出来"，而其它域名的图都正常——极难定位。
     *
     * 图片链路只会请求图片存储域名（章节图 /static/、头像等），API 域名已在上面
     * 单独分支处理，所以"非 API 域名 + 有配置 IP"这个判据足够安全：
     * 用户没配置图片 IP 时（imageIps 为空）仍然回落系统 DNS，行为不变。
     */
    private fun isImageHost(hostname: String): Boolean {
        if (isApiHost(hostname)) return false
        if (imageIpsRef.load().isEmpty()) return false
        // 已知后缀直接放行；未知后缀同样放行，让新存储节点也能走直连 IP。
        return true
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