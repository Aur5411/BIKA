package com.shizq.bika.core.network.di

import android.content.Context
import androidx.tracing.trace
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.DebugLogger
import com.shizq.bika.core.datastore.UserCredentialsDataSource
import com.shizq.bika.core.datastore.UserPreferencesDataSource
import com.shizq.bika.core.network.BikaEndpoints
import com.shizq.bika.core.network.BuildConfig
import com.shizq.bika.core.network.auth.SessionExpiryReason
import com.shizq.bika.core.network.auth.SessionManager
import com.shizq.bika.core.network.auth.sessionExpiryPlugin
import com.shizq.bika.core.network.dns.appChannelHeaderFor
import com.shizq.bika.core.network.plugin.ApiEnvelopePlugin
import com.shizq.bika.core.network.plugin.DirectDns
import com.shizq.bika.core.network.plugin.DomainFallbackInterceptor
import com.shizq.bika.core.network.plugin.ImageDownloadCoordinator
import com.shizq.bika.core.network.plugin.bikaAuth
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.ANDROID
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.charsets.Charsets
import jakarta.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.util.concurrent.TimeUnit

/** 图片磁盘缓存的目录名，沿用 Coil 惯例。 */
private const val IMAGE_CACHE_DIR_NAME = "image_cache"

/** 图片磁盘缓存下限：小屏幕设备算出来太小时也要够存几十张原图。 */
private const val MIN_IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024

/**
 * 图片磁盘缓存上限。
 *
 * 这是"回看已读章节要多久"的决定项：命中缓存的页直接读盘，不再走网络。
 * 512MB 对长漫画偏紧——按单页原图 1~3MB 算只装得下几十话。提到 1GB 约翻倍，
 * 代价是占更多存储（目录在 cacheDir，系统空间紧张时会被回收，属可接受的取舍）。
 *
 * 它**不影响首次阅读的速度**：首次翻页快慢由预载窗口 + 网络并发决定，
 * 磁盘缓存只在回看已读页面时才命中。
 */
private const val MAX_IMAGE_DISK_CACHE_BYTES = 1024L * 1024 * 1024

/**
 * API 域名的并发请求上限。
 *
 * OkHttp 默认 `maxRequestsPerHost = 5`。章节目录是并发翻页，批次宽度就受它约束：
 * 调大这里才能让批次继续变宽，否则多出来的请求只会在队列里排队。
 * 取 8 而不是更大：翻页通常只占 5 个，留出余量给同时发出的详情/评论请求，
 * 也不至于对服务端形成过强的瞬时压力（那反而会招来限流、把加载搞失败）。
 */
private const val MAX_REQUESTS_PER_HOST = 8

/** 全局并发上限，保持 OkHttp 默认值，只在同域名上限之外再兜一层。 */
private const val MAX_REQUESTS = 64

/**
 * 图片域名的并发请求上限——**阅读页图的真实天花板**。
 *
 * 章节图全部来自同一批存储域名（storage1.picacomic.com 等），而 OkHttp 按 host
 * 计数，所以这里定多少，同一时刻就最多只有这么多张图在下载。
 *
 * 原先这里**根本没配**，走的是 OkHttp 默认值 5——比浏览器单域名默认（6）还保守。
 * 阅读时"预载"和"当前可见页"共用这 5 个额度，翻页自然总在等。
 *
 * 取 12 的依据：图片是纯大文件下载，不像接口那样有"瞬时并发把服务端打挂"的风险，
 * 单域名十几条连接在 CDN 侧完全正常；但也不宜再大——并发过高会让多张图争抢带宽，
 * 反而拖慢"当前正在看的那一张"，也更容易触发服务端限流。
 */
private const val IMAGE_MAX_REQUESTS_PER_HOST = 12

/** 图片全局并发上限，保持 OkHttp 默认 64。 */
private const val IMAGE_MAX_REQUESTS = 64


@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
    @Provides
    @Singleton
    fun provideConnectionPool(): ConnectionPool = ConnectionPool(
        10,
        5,
        TimeUnit.MINUTES,
    )

    @Provides
    @Singleton
    fun providesHttpClient(
        okHttpClient: OkHttpClient,
        userCredentialsDataSource: UserCredentialsDataSource,
        userPreferencesDataSource: UserPreferencesDataSource,
        sessionManager: SessionManager,
    ): HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        defaultRequest {
            url(BikaEndpoints.API_BASE_URL)
            contentType(ContentType.Application.Json.withCharset(Charsets.UTF_8))
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000L
            requestTimeoutMillis = 30_000L
            socketTimeoutMillis = 30_000L
        }
        // 通路 A：HTTP 401。装在信封插件之前，先于响应体解析拦下鉴权失败
        install(sessionExpiryPlugin(sessionManager))
        install(ApiEnvelopePlugin) {
            // 通路 B：HTTP 200 + 信封内 code=401
            onUnauthorized {
                sessionManager.terminateSession(SessionExpiryReason.TokenRejected)
            }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                }
            )
        }
        // 仅 DEBUG 开启请求日志：LogLevel.ALL 会打印 Authorization token 与签名头，
        // 无条件启用会导致 release 包凭据泄露到 logcat
        if (BuildConfig.DEBUG) {
            Logging {
                logger = Logger.ANDROID
                level = LogLevel.ALL
            }
        }
        bikaAuth {
            channel {
                val activeLine = userPreferencesDataSource.userData.first().network.dns.activeLine
                appChannelHeaderFor(activeLine)
            }
            token {
                userCredentialsDataSource.userData.firstOrNull()?.token
            }
        }
    }

    /**
     * API 链路的 OkHttpClient。
     *
     * 不再挂 `Authenticator`：401 的处理已上移到 Ktor 层的
     * [com.shizq.bika.core.network.auth.sessionExpiryPlugin]。
     * OkHttp 的 `Authenticator` 是同步回调，只能靠 `runBlocking` 桥接
     * suspend 的凭据读取与重登，会阻塞 OkHttp dispatcher 线程；并发 401 时
     * 多个线程互等且重登请求抢不到同 host 的请求配额，形成死锁。
     * Ktor 拦截器天生 suspend，不占请求配额，这类问题不复存在。
     *
     * `Dispatcher` 显式配置是为了把"同域名并发上限"这件事写在台面上：它是并发
     * 翻页的真实天花板（章节目录的批次宽度就按它取值）。默认值 5 会被翻页
     * 独占，同时发出的详情/评论请求只能排队，所以留出余量。
     */
    @Provides
    @Singleton
    fun okHttpCallFactory(
        connectionPool: ConnectionPool,
        directDns: DirectDns,
    ): OkHttpClient = trace("OkHttpClient") {
        OkHttpClient.Builder()
            .connectionPool(connectionPool)
            .dns(directDns)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MAX_REQUESTS
                    maxRequestsPerHost = MAX_REQUESTS_PER_HOST
                }
            )
            .build()
    }

    /**
     * 图片链路专用 OkHttpClient，与 API 链路隔离。
     *
     * Coil 会并发加载几十张图，共用 API 客户端时图片请求会挤占同 host 的
     * 请求配额与连接池，拖慢接口响应。图片 401 通常源于签名或防盗链，
     * 也不该触发会话终止，所以这里刻意不装任何鉴权组件。
     */
    @Provides
    @Singleton
    @ImageClient
    fun imageOkHttpClient(
        directDns: DirectDns,
    ): OkHttpClient = trace("ImageOkHttpClient") {
        OkHttpClient.Builder()
            // 连接池与上面的并发上限匹配：idle 连接数少于同时在飞的请求数时，
            // 多出来的请求每次都要重做握手（DNS→TCP→TLS），叠起来的延迟正是
            // "翻页一顿一顿"的隐性来源。keepAlive 5 分钟覆盖单章阅读时长。
            .connectionPool(ConnectionPool(IMAGE_MAX_REQUESTS_PER_HOST * 2, 5, TimeUnit.MINUTES))
            .dns(directDns)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = IMAGE_MAX_REQUESTS
                    maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
                }
            )
            .build()
    }

    /**
     * 图片加载器。
     *
     * ## 这里必须显式建缓存
     *
     * Coil 3 的 [ImageLoader.Builder] **不会**自动创建磁盘缓存——整个 Coil 产物里
     * 没有任何默认缓存目录名，`diskCache` 默认为 null。不显式配置的话，
     * `NetworkFetcher.readFromDiskCache()` 会因为 `diskCache.value == null` 直接返回 null：
     * 内存缓存之外什么都没有，于是**每次冷启动、每次进新页面，所有封面和章节图都要
     * 从网络重下**。阅读器里那些 `diskCachePolicy(CachePolicy.ENABLED)` 的写法
     * 也会一并变成空操作。用户侧的感受就是"封面加载很慢"。
     *
     * 两处都用 lambda 形式，Coil 会把它包成 lazy：磁盘缓存到第一次真正加载图片时才
     * 建目录、读日志，不在 Application.onCreate 的主线程上做文件 IO。
     */
    @Provides
    @Singleton
    @OptIn(ExperimentalCoilApi::class)
    fun imageLoader(
        @ImageClient okHttpClient: OkHttpClient,
        @ApplicationContext application: Context,
    ): ImageLoader = trace("ImageLoader") {
        ImageLoader.Builder(application)
            .memoryCache {
                MemoryCache.Builder()
                    // 内存缓存吃的是应用可用内存：太小会让列表滚动时反复解码，
                    // 太大则挤压其它组件。20% 是常用取值
                    .maxSizePercent(application, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // 路径与 Coil 惯例一致，避免与旧版本留下的目录分家、白丢缓存
                    .directory(application.cacheDir.resolve(IMAGE_CACHE_DIR_NAME).absolutePath.toPath())
                    // 按可用空间自适应并设上下界：目录里躺的是原图，
                    // 长漫画能堆到几个 GB，必须封顶
                    .maxSizePercent(0.02)
                    .minimumMaxSizeBytes(MIN_IMAGE_DISK_CACHE_BYTES)
                    .maximumMaxSizeBytes(MAX_IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = { okHttpClient },
                    concurrentRequestStrategy = { ImageDownloadCoordinator() },
                ))
                add(DomainFallbackInterceptor())
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }

    /**
     * DNS 解析专用 [HttpClient]：独立于主链路，不带鉴权、不走 DirectDns，
     * 避免"解析直连 IP 却依赖直连 IP"的循环依赖。短超时快速失败。
     */
    @Provides
    @Singleton
    @DnsClient
    fun provideDnsHttpClient(
        connectionPool: ConnectionPool,
    ): HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .build()
        }
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            requestTimeoutMillis = 10_000L
            socketTimeoutMillis = 10_000L
        }
        install(ApiEnvelopePlugin)
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                }
            )
        }
    }
}
