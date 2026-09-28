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

/** 图片磁盘缓存上限：长漫画的原图能堆到几个 GB，必须封顶。 */
private const val MAX_IMAGE_DISK_CACHE_BYTES = 512L * 1024 * 1024

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
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .dns(directDns)
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
