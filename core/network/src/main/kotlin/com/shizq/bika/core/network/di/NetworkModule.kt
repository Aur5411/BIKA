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
import com.shizq.bika.core.network.image.ImageHostRouter
import com.shizq.bika.core.network.image.ImageRateGovernor
import com.shizq.bika.core.network.image.ImageThrottleInterceptor
import com.shizq.bika.core.network.image.PreferredHostInterceptor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
 *
 * 取 2GB：按单页原图 1~3MB 算能装下几百话，长漫画回看几乎不再走网络。
 * 目录在 `cacheDir`，系统空间紧张时会被整体回收，代价可控。
 */
private const val MAX_IMAGE_DISK_CACHE_BYTES = 2048L * 1024 * 1024

/**
 * API 域名的并发请求上限。
 *
 * OkHttp 默认 `maxRequestsPerHost = 5`。章节目录是并发翻页，批次宽度就受它约束：
 * 调大这里才能让批次继续变宽，否则多出来的请求只会在队列里排队。
 *
 * 取 16：翻页批次本身要 8~10 条，再加上同时发出的详情/评论/榜单请求，
 * 8 个额度会被翻页独占，其余请求只能排队。接口是小 JSON，多几条连接不会
 * 把服务端打挂，也不构成需要担心的瞬时压力。
 */
private const val MAX_REQUESTS_PER_HOST = 16

/** 全局并发上限，在同域名上限之外再兜一层。 */
private const val MAX_REQUESTS = 128

/**
 * 图片域名的并发请求上限——**阅读页图的真实天花板**。
 *
 * 章节图全部来自同一批存储域名（storage1.picacomic.com 等），而 OkHttp 按 host
 * 计数，所以这里定多少，同一时刻就最多只有这么多张图在下载。
 *
 * 原先这里**根本没配**，走的是 OkHttp 默认值 5——比浏览器单域名默认（6）还保守。
 * 阅读时"预载"和"当前可见页"共用这 5 个额度，翻页自然总在等。
 *
 * 取 32 的依据：图片是纯大文件下载，不像接口那样有"瞬时并发把服务端打挂"的风险，
 * 单域名几十条连接在 CDN 侧完全正常。而阅读器的真实占用是
 * 「预载窗口 12 并发 + 当前可见页 + 进度条预览 + 封面列表」同时存在，
 * 16 个额度在这些请求之间不够分，翻页时仍会看到加载态。
 *
 * 32 已经接近收益拐点：单张页图的下载速度只由带宽和 RTT 决定，与同时在跑多少张图
 * 无关——并发数决定的是"同时能准备几张"，不是"一张能多快"。再往上加，
 * 多出来的连接只是把同一份带宽切得更碎（各自的 TCP 慢启动还要重来一遍），
 * 同时放大服务端限流与超时重试的概率。要继续提升阅读手感该动的是**预载窗口**
 * （见 AdaptivePreloadPolicy）和**最快源选路**（见 ImageHostRouter）。
 */
private const val IMAGE_MAX_REQUESTS_PER_HOST = 32

/** 图片全局并发上限：所有图片域名加起来也足够铺满预载窗口。 */
private const val IMAGE_MAX_REQUESTS = 128

/**
 * 图片解码的并行上限。
 *
 * 解码是 CPU 密集，并行数超过核心数只会让线程互相抢 CPU、拖累 UI 线程掉帧，
 * 所以按核心数取、并夹在 4~8 之间：低端机 4 条仍有并行红利，高端机不超过 8 条。
 *
 * 这里特意用 `Dispatchers.IO.limitedParallelism` 而不是自建线程池：取流/解码的协程体
 * 内部还会再派生子协程（降级竞速一次要并发打若干个候选域名），自建固定大小线程池时
 * 子协程排在同一批线程后面，父协程又在等子协程——这正是教科书级的线程饥饿。
 * `limitedParallelism` 只是给 Dispatchers.IO 加一道并发闸门，底层仍是可扩容的线程池，
 * 不会出现"子协程抢不到线程、父协程干等"的局面。
 */
private val IMAGE_DECODER_THREADS: Int =
    maxOf(4, minOf(8, Runtime.getRuntime().availableProcessors()))

/**
 * 图片取流的并行上限。
 *
 * 取流主要是等网络，不是 CPU 密集；给一个明显大于解码的上限，让"还在等字节的图"
 * 不占用解码的额度。真正的在飞请求数由
 * [com.shizq.bika.core.network.image.ImageRateGovernor] 另行卡在更低的水平，
 * 这里只负责保证并发协程自身的调度开销不会反过来拖住它。
 */
private const val IMAGE_FETCHER_THREADS = 64

/**
 * 图片链路的请求头。
 *
 * Cloudflare 前面对"没有浏览器 UA"的请求会直接 403——实测
 * `storage.tipatipa.xyz` 在默认 OkHttp UA 下返回 403，补上 UA 与 Referer 后 200。
 * 403 会被降级逻辑当成"主源失败"，于是每一张图都要白跑一轮换域名竞速，
 * 这比源本身慢更影响体感。图片链路不参与签名，加这些头没有副作用。
 */
private const val IMAGE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private const val IMAGE_REFERER = "https://www.picacomic.com/"


@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
    @Provides
    @Singleton
    fun provideConnectionPool(): ConnectionPool = ConnectionPool(
        // 连接数要盖住同域名并发上限（MAX_REQUESTS_PER_HOST），否则多出来的请求
        // 每次都要重做 DNS→TCP→TLS，握手延迟直接叠在接口响应上
        MAX_REQUESTS_PER_HOST * 2,
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
     *
     * ## 为什么强制 HTTP/1.1（本链路最大的一处提速）
     *
     * OkHttp 默认 `protocols` 是 `[HTTP/2, HTTP/1.1]`，而这个 CDN 镜像实测支持 h2，
     * 于是整条图片链路会协商成 HTTP/2。听起来是好事，实际是个大坑：
     * HTTP/2 下 OkHttp 对同一 host **只建一条 TCP 连接**，所有并发在这个 CDN 上
     * 被压到几十 KB/s 的量级——本机实测同一份文件、同样的并发数：
     *
     * | 并发 | HTTP/2（单连接多路复用） | HTTP/1.1（多连接） |
     * |------|------------------------|-------------------|
     * | 8    | 104 KB/s               | **1784 KB/s**     |
     * | 16   | 44 KB/s                | **3007 KB/s**     |
     * | 32   | 115 KB/s               | **2520 KB/s**     |
     *
     * 差 20～60 倍。也就是说调再大的 `maxRequestsPerHost` 都没用——HTTP/2 下这些
     * 请求挤在同一条被限速的连接里，`Dispatcher` 的额度根本换不成带宽。
     * 强制走 HTTP/1.1 后，并发数就等于连接数，上面那条 perHost 额度才真正兑现成
     * 吞吐（16 并发约 3 MB/s，且到 16 为止仍近似线性）。
     *
     * 副作用是每条连接都要单独做 TLS 握手（实测 1.0～1.3 秒），所以配套的
     * [ImageConnectionWarmup] 与 5 分钟 keep-alive 的连接池缺一不可。
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
            // 详见本方法的 KDoc：这一行是"图片能跑满带宽"的前提，去掉它整条链路
            // 会回到 HTTP/2 单连接、几十 KB/s 的量级。
            .protocols(listOf(Protocol.HTTP_1_1))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = IMAGE_MAX_REQUESTS
                    maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
                }
            )
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", IMAGE_USER_AGENT)
                    .header("Referer", IMAGE_REFERER)
                    .build()
                chain.proceed(request)
            }
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
        hostRouter: ImageHostRouter,
        governor: ImageRateGovernor,
    ): ImageLoader = trace("ImageLoader") {
        ImageLoader.Builder(application)
            .memoryCache {
                MemoryCache.Builder()
                    // 内存缓存吃的是应用可用内存：太小会让列表滚动时反复解码，
                    // 太大则挤压其它组件。图片是这个应用的主要内存占用，
                    // 从常用的 20% 提到 25%，翻回去看上一页时不必重新解码
                    .maxSizePercent(application, 0.25)
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
            // 取流/解码各自限一道并行闸门：默认共享 Dispatchers.IO，会和下载、数据库
            // 挤到队尾就直接表现为"图出来了但半天不显示"。
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(IMAGE_FETCHER_THREADS))
            .decoderCoroutineContext(Dispatchers.IO.limitedParallelism(IMAGE_DECODER_THREADS))
            .components {
                add(OkHttpNetworkFetcherFactory(
                    callFactory = { okHttpClient },
                    concurrentRequestStrategy = { ImageDownloadCoordinator() },
                ))
                // 顺序即执行顺序：最外层先过节流闸门（它能看到整条链路的最终成败，
                // 负责退避重试），再把请求改写到已知最快的源，最后才走降级竞速。
                add(ImageThrottleInterceptor(governor))
                add(PreferredHostInterceptor(hostRouter))
                add(DomainFallbackInterceptor(hostRouter, governor))
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
