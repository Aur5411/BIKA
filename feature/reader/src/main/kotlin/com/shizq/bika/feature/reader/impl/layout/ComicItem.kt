package com.shizq.bika.feature.reader.impl.layout

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.magnifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.shizq.bika.core.data.paging.ChapterPage
import com.shizq.bika.core.ui.CircularProgressIndicator
import com.shizq.bika.core.ui.backoffDelayMillis
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

private val pagingLogger = KotlinLogging.logger("ReaderPaging")

private val logger = KotlinLogging.logger("ReaderImage")

/**
 * 单张页图的自动重试次数。
 *
 * 传输层（[com.shizq.bika.core.network.image.ImageThrottleInterceptor]）已在
 * 自己内部重试至多 5 次、累计等待 45s；这里再补一层"UI 侧重启"，覆盖两层情形：
 * - 它因预载预算（8s）主动放弃、结果下一轮立刻就能成功；
 * - 它因取消（用户滑走又滑回）中止，而这张图其实只是缺一次干净的重新请求。
 *
 * 取 5：配合下面的间隔，总覆盖窗口约 800+1600+2400+3200+4000 ≈ 12s，
 * 与限流冷却上限（12s）对齐——一次完整的冷却刚好能被这一串自动重试穿越。
 */
private const val IMAGE_AUTO_RETRY_MAX = 5

/** 单张页图自动重试的基准间隔，按次数线性放大。 */
private const val IMAGE_AUTO_RETRY_BASE_MS = 800L

/** 自动重试间隔的随机抖动上限：同屏多张图同时失败时错开重启时刻，别一起撞同一堵墙。 */
private const val IMAGE_AUTO_RETRY_JITTER_MS = 400L

/**
 * HTTP 404 / 403 / 410 之后还允许的换源重试次数。
 *
 * 不能一次就判死：同一张图在各镜像上的存在性不一致，而主源（`storage1.picacomic.com`
 * 之类）在国内被 DNS 污染、常常连不上或返回错误页面。换源链虽然会遍历候选域名，
 * 但单次尝试可能因网络抖动而落空，交给 UI 的往往就是主源那一个 404——
 * 而这张图在 `storage.diwodiwo.xyz` 上其实是好的（已实测 200 + 完整字节）。
 *
 * 取 2（原为 3）：换源链现在有两处硬化，单次尝试的可靠性显著提高，不再需要
 * 那么多次"重来一遍"——
 * - `ImageHostHealth` 会把连续连接失败 2 次的死源隔离 3 分钟，遍历不再陪死源干等；
 * - `NOT_FOUND_SWEEP_BUDGET_MS` 给每次遍历封了 25 秒总预算。
 *
 * 代价核算：最坏情况 2 次重试 → 2 ×（25 秒遍历 + 1.2~2.4 秒间隔）≈ 55 秒。
 * 取 3 会推到约 85 秒，而这段时间这张"确实缺图"的页一直占着一个并发名额，
 * 正是「修好第 19 页、第 37/38 页又失败」中"饿死队尾"的放大器。
 */
private const val IMAGE_NOT_FOUND_RETRY_MAX = 2

/** 404/403 换源重试的基准间隔，按次数线性放大，避免与普通失败的重试节奏重合。 */
private const val IMAGE_NOT_FOUND_RETRY_BASE_MS = 1_200L

/**
 * 从图片加载异常里取出 HTTP 状态码；取不到返回 null。
 *
 * ## 为什么必须先反射、后正则
 *
 * 旧实现一上来就用 `\b([1-5]\d\d)\b` 在 `message` 里找三位数，这会把 URL /
 * 端口 / 时间戳里的数字误当成状态码，进而让 UI 把"连接超时"误报成"HTTP 404"
 * （404 会触发"停止重试"分支，于是本可自愈的失败被永久判死）。
 * Coil 的 `HttpException` 把真实响应存在自身字段里，所以**反射优先**，
 * 只有拿不到响应对象时才退到文本解析。
 *
 * ## 正则为什么收紧成"HTTP 语义"
 *
 * 文本兜底不再匹配裸三位数，只认 `HTTP 404` / `code=404` / `状态码 404`
 * 这类明确写法——它们不会出现在文件名、UUID、端口号里。
 * 拿不到就返回 null，"不确定"比"猜一个错误的状态码"安全得多。
 */
internal fun httpStatusOf(throwable: Throwable?): Int? {
    if (throwable == null) return null

    // 一、优先反射：Coil 的 HttpException 用 getResponse().getCode() 携带真实状态码。
    runCatching {
        val resp = throwable.javaClass.getMethod("getResponse").invoke(throwable)
        val code = resp.javaClass.getMethod("getCode").invoke(resp) as Int
        if (code in 100..599) return code
    }
    // 有些实现把 response 直接挂在属性上（Kotlin 属性 getter）。
    runCatching {
        val resp = throwable.javaClass.getMethod("getResponse").invoke(throwable)
        val code = resp.javaClass.getMethod("getCode").invoke(resp) as? Int
        if (code != null && code in 100..599) return code
    }

    // 二、文本兜底：只认明确带 HTTP 语义的写法，避免误匹配路径中的数字。
    val text = throwable.message.orEmpty()
    HTTP_STATUS_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        if (it in 100..599) return it
    }
    return null
}

/**
 * 只匹配「HTTP 404」「code=404」「code:403」「status 500」「(404)」这类写法。
 *
 * 刻意不匹配裸三位数：`/static/a9cf81c3-...jpg` 这类路径里可能恰好出现
 * `404`/`500` 形状的数字段，误判会把可自愈的失败直接判死。
 */
internal val HTTP_STATUS_PATTERN = Regex(
    """(?i)(?:http[/ ]?\d?\.?\d?\s*|code\s*[=:]\s*|status\s*[=:]?\s*|状态码\s*[=:]?\s*|\(|\s)([1-5]\d\d)(?:\s|$|[),;])"""
)

/**
 * 章节分页失败后的**唯一**退避重试驱动（间隔 2s/4s/8s/16s/30s 封顶）。
 *
 * 必须由宿主调用**一次**，不能放在占位项里。`loadState` 是整个 PagingData 共享的，
 * 而占位项在屏上通常同时存在若干个：之前每个占位项各持一份 autoRetryCount、
 * 各起一个 LaunchedEffect、各调 `pageItems.retry()`，于是
 * - 实际重试间隔变成所有实例中最小的那个，退避形同失效；
 * - 每轮重试的请求数等于当前可见占位项数量；
 * - 计数的 `remember(pageItems)` 在滚动复用时被重建，间隔又被拉回 2s。
 *
 * 失败原因日志也只在这里记一次，不再按可见占位项数量刷屏。
 */
@Composable
fun ChapterAppendRetryEffect(
    pageItems: LazyPagingItems<ChapterPage>,
    onReloadPages: () -> Unit = {},
) {
    // 退避计数活在协程栈上，不是组合状态。
    //
    // 之前是 `remember` 计数 + `LaunchedEffect(error, autoRetryCount)`，即 effect
    // 自己写自己的 key。这里的 `delay` 在 `++` 之前，取消发生在计数写入之前，
    // 所以侥幸能跑完一轮——但形状与 [ComicPageItem] 里那份（先 `++` 后 `delay`，
    // 因此重试一次都不发生）完全同构，正确性全靠这两行的相对顺序，
    // 而这读起来像风格问题、不像正确性问题。换成长活协程后这个类别整体消失。
    //
    // 循环自驱动：每轮主动读一次 loadState，不依赖「新的 LoadState.Error 与旧的
    // 不相等」来推进。每轮必经一次 delay，不会退化成忙循环。
    val currentOnReload by rememberUpdatedState(onReloadPages)
    LaunchedEffect(pageItems) {
        var attempt = 0
        var logged = false
        while (true) {
            // loadState 是快照状态，snapshotFlow 会在它变化时重新求值。
            // refresh 与 append 任一失败都要退避重试。
            val throwable = snapshotFlow {
                val loadState = pageItems.loadState
                ((loadState.refresh as? LoadState.Error)
                    ?: (loadState.append as? LoadState.Error))?.error
            }.first { it != null } ?: continue

            if (!logged) {
                logged = true
            }

            delay(backoffDelayMillis(attempt))
            attempt++
            // 两件事必须一起做：`retry()` 修 Paging 内部的数据副本，
            // [currentOnReload] 让 composition 收到重建后的分页流。
            // 只做前者时重试在表面上看不出任何变化（见 ReaderViewModel 的说明），
            // 这正是"重试也没有用"的来源之一。
            currentOnReload()
            pageItems.retry()
        }
    }
}

/**
 * 分页数据未就绪时的占位组件，**纯 UI**：
 * - 加载中：显示进度条
 * - 分页失败：显示可点击的重试按钮（用户显式操作立即生效，不走退避）
 *
 * 自动退避重试见 [ChapterAppendRetryEffect]。
 */
@Composable
fun ChapterPageLoadStateItem(
    pageItems: LazyPagingItems<ChapterPage>,
    index: Int,
    modifier: Modifier = Modifier,
    onReloadPages: () -> Unit = {},
) {
    val loadState = pageItems.loadState
    val isError = loadState.refresh is LoadState.Error || loadState.append is LoadState.Error

    // clickable 只在错误态挂上，不写成 `clickable(enabled = isError)`。
    //
    // enabled=false 的 clickable 仍然参与 hit test 并消费 down 事件：加载中态
    // 铺满整个占位项，会把点击静默吞掉——在阅读器里表现为占位项所在的那一屏
    // 点击不出菜单、也不翻页，而 Pager 模式下点击是主要的翻页方式。
    // 同样的坑在 core/ui 的 RetryableAsyncImage 里已有注释记录。
    val errorClickable = if (isError) {
        Modifier.clickable {
            // 与 ChapterAppendRetryEffect 同一套动作：重载分页流 + 让 Paging 重拉。
            // 少了前者，点击重试在界面上不会有任何变化。
            onReloadPages()
            pageItems.retry()
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .background(if (isError) Color.LightGray else Color.Gray.copy(alpha = 0.1f))
            .then(errorClickable),
        contentAlignment = Alignment.Center
    ) {
        if (isError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry")
                Text(
                    text = "第 ${index + 1} 页加载失败\n点击重试",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
        }
    }
}

/**
 * 单页渲染。
 *
 * [zoomable] 为 true 时本页自己承接缩放与点击（翻页模式）：每页独立缩放，
 * 翻到下一页时缩放自动复位。条漫模式传 false，由容器整体缩放。
 *
 * [onTap] 收到的坐标换算到 [viewport] 所标记的节点的坐标系，而不是本页的局部
 * 坐标：跨页模式一屏有两页，用页面局部坐标会把右页的左半边当成「屏幕左侧」，
 * 导致点击翻页方向反掉。传 [onTap] 时必须一并传 [viewport]，否则无从换算。
 *
 * [magnifierEnabled] 显式传入而不是整体读 ReaderConfig：这里只用到 ReaderConfig
 * 的这一个字段，若整体读 CompositionLocal，护眼深度、自动滚动速度等任何其他
 * 字段变化都会让每一个可见的 ComicPageItem 一起重组。
 */
@Composable
fun ComicPageItem(
    page: ChapterPage,
    index: Int,
    modifier: Modifier = Modifier,
    zoomable: Boolean = false,
    magnifierEnabled: Boolean = true,
    onTap: ((PageTapContext) -> Unit)? = null,
    viewport: ViewportAnchor? = null,
    onSizeLoaded: ((width: Float, height: Float) -> Unit)? = null
) {
    var magnifierCenter by remember { mutableStateOf(Offset.Unspecified) }

    // 缩放状态不需要按 page.id 做 key：翻页模式下 Pager 的 key 已经包含页码与
    // 图片 id，换页就是换节点，state 随节点一起重建，缩放不会残留到下一页。
    val zoomableState = rememberZoomableState(ZoomSpec(maxZoomFactor = 4f))
    var pageCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)

    val zoomModifier = if (zoomable) {
        Modifier
            // onGloballyPositioned 必须在 zoomable **之前**：放在之后拿到的是
            // 已经过缩放变换的坐标系，换算出来的点击位置会随缩放倍数漂移。
            .onGloballyPositioned { pageCoordinates = it }
            .zoomable(
                state = zoomableState,
                gestures = EnabledZoomGestures.ZoomAndPan,
                onClick = { localOffset ->
                    val handler = currentOnTap ?: return@zoomable
                    val coords = pageCoordinates
                    if (coords == null || !coords.isAttached) return@zoomable
                    // 换算到视口坐标系。视口由布局策略指定（见 ViewportAnchor），
                    // 不用 findRootCoordinates()：那取的是整个窗口，阅读器内容区
                    // 被 inset / scaffold padding 推下去多少，分区边界就偏多少。
                    val viewportCoords = viewport?.coordinates ?: return@zoomable
                    if (!viewportCoords.isAttached) return@zoomable
                    handler(
                        PageTapContext(
                            position = viewportCoords.localPositionOf(coords, localOffset),
                            viewportSize = viewportCoords.size,
                        )
                    )
                }
            )
    } else {
        Modifier
    }

    val magnifierModifier = if (magnifierEnabled) {
        Modifier
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        magnifierCenter = offset
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        magnifierCenter = if (magnifierCenter != Offset.Unspecified) {
                            magnifierCenter + dragAmount
                        } else {
                            magnifierCenter
                        }
                    },
                    onDragEnd = {
                        magnifierCenter = Offset.Unspecified
                    },
                    onDragCancel = {
                        magnifierCenter = Offset.Unspecified
                    }
                )
            }
            .magnifier(
                sourceCenter = { magnifierCenter },
                magnifierCenter = {
                    if (magnifierCenter != Offset.Unspecified) {
                        magnifierCenter - Offset(0f, 150f)
                    } else {
                        Offset.Unspecified
                    }
                },
                zoom = 1.8f
            )
    } else {
        Modifier
    }

    val configuration = LocalConfiguration.current
    val platformContext = LocalPlatformContext.current
    val contentScale = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        ContentScale.Fit
    } else {
        ContentScale.FillWidth
    }
    // 按画布尺寸解码，而不是按文件原始尺寸。
    //
    // AsyncImagePainter 在 `defined.sizeResolver == null` 时会兜底成
    // `SizeResolver.ORIGINAL`（见 coil3/compose/AsyncImagePainter.updateRequest），
    // 于是扫描分辨率是 2000×3000 的页图也会被解成同尺寸的 Bitmap——单张 20MB 起步，
    // 解码时间跟着像素数线性增长。这就是"下载完了图还待一会儿"的那段时间，
    // 也是翻页时内存曲线突然抬高的原因。
    //
    // 采样只砍掉超出画布的冗余像素：常规页图本就在屏幕宽度附近，几乎 1:1 无损；
    // 真正被降采样的只有那些远大于屏幕的扫描件。
    val sizeResolver = rememberConstraintsSizeResolver()
    var imageAspectRatio by remember(page.id) { mutableFloatStateOf(0.75f) }
    val imageRequest = remember(platformContext, page.url, sizeResolver) {
        ImageRequest.Builder(platformContext)
            .data(page.url)
            .size(sizeResolver)
            .crossfade(false)
            .diskCacheKey(page.url)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val painter = rememberAsyncImagePainter(model = imageRequest)

    val state by painter.state.collectAsState()

    // 手动重试信号：递增会重启下面的重试协程，attempt 归零，用户的显式操作
    // 立即生效而不用等当前退避走完。
    var manualRetryNonce by remember(page.id) { mutableIntStateOf(0) }

    // 自动重试 + 手动重试二合一。
    //
    // 背景：Coil 自身没有重试机制，一次 ErrorResult 就是这个请求的结局。此前这里
    // 的自动重试调用被注释、函数实现也已不存在，于是单张页图失败后就**只剩**用户
    // 点击一条路；而用户点 painter.restart() 时若正处在限流冷却期内，
    // ImageThrottleInterceptor 会把这次请求 delay 才判失败——用户看到的就是
    // "点了没反应、重试也没用"。
    //
    // 现在已经双管齐下：
    // - 传输层：冷却上限压到 12s，且可见页 acquire() 恒排队不放弃，保证重试**必然等到**；
    // - UI 层：失败后在协程里按退避自动重试，用户点击立即重启计数归零、马上再试一次。
    //
    // key 用 imageRequest 而非 painter：painter 实例在 model 变化时会被复用，
    // 只按它做 key 会让复用到新页的节点继承上一页的退避计数与已记日志标记。
    LaunchedEffect(imageRequest, manualRetryNonce) {
        var attempt = 0
        // 记录"已经因为 404/403 短暂停过几次"，见下方 notFoundAttempts 的说明。
        var notFoundAttempts = 0
        while (true) {
            val current = painter.state.value
            if (current is AsyncImagePainter.State.Error) {
                val code = httpStatusOf(current.result.throwable)

                // 404 / 403 / 410 不再是"一次就判死"。
                //
                // 关键事实：同一张图在不同镜像上的存在性并不一致，而主源
                // （`storage1.picacomic.com` 之类）在国内被 DNS 污染、常常连不上。
                // 换源链虽然会把候选域名都试一遍，但**每一次尝试都可能因网络抖动、
                // 节点瞬时不健康而失败**——此时交给 UI 的状态码往往就是主源那一个
                // 404，而这张图在 `storage.diwodiwo.xyz` 上其实是好的（已实测 200）。
                //
                // 所以给 404/403 留 [IMAGE_NOT_FOUND_RETRY_MAX] 次重试机会：
                // 每次重试都会重新走一遍换源链，有很大概率落到另一个可用镜像上。
                // 只有连续多次都确认"没有这张图"，才真正停下——那时才大概率是
                // 服务端在该 path 上确实缺图，继续重试只是白占并发名额。
                if (code == 404 || code == 403 || code == 410) {
                    if (notFoundAttempts >= IMAGE_NOT_FOUND_RETRY_MAX) {
                        logger.warn {
                            "第 ${index + 1} 页判定为服务端缺图（HTTP $code，已重试 " +
                                    "$notFoundAttempts 次且所有镜像均无）: url=${page.url}"
                        }
                        break
                    }
                    notFoundAttempts++
                    val delayMs = IMAGE_NOT_FOUND_RETRY_BASE_MS * notFoundAttempts +
                            Random.nextLong(IMAGE_AUTO_RETRY_JITTER_MS)
                    logger.debug {
                        "第 ${index + 1} 页返回 HTTP $code，${delayMs}ms 后第 " +
                                "$notFoundAttempts/$IMAGE_NOT_FOUND_RETRY_MAX 次换源重试: url=${page.url}"
                    }
                    delay(delayMs)
                    if (painter.state.value is AsyncImagePainter.State.Success) break
                    painter.restart()
                    continue
                }

                if (attempt >= IMAGE_AUTO_RETRY_MAX) {
                    logger.warn {
                        "第 ${index + 1} 页自动重试已用尽（${httpStatusOf(current.result.throwable) ?: "非 HTTP"}）: url=${page.url}"
                    }
                    break
                }
                attempt++
                val delayMs = IMAGE_AUTO_RETRY_BASE_MS * attempt +
                        Random.nextLong(IMAGE_AUTO_RETRY_JITTER_MS)
                logger.debug { "第 ${index + 1} 页加载失败，${delayMs}ms 后第 $attempt 次自动重试" }
                delay(delayMs)
                if (painter.state.value is AsyncImagePainter.State.Success) break
                painter.restart()
            } else {
                // 等下一次状态变化：成功/加载中都不需要动作。
                // snapshotFlow 而非 collect：只在状态"变到 Error"时才唤醒重试循环，
                // 避免 Loading→Error→Loading 的抖动让重试计数虚增。
                snapshotFlow { painter.state.value }
                    .first { it is AsyncImagePainter.State.Error || it is AsyncImagePainter.State.Success }
                if (painter.state.value is AsyncImagePainter.State.Success) {
                    break
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(imageAspectRatio)
            .animateContentSize(animationSpec = tween(durationMillis = 200))
            .then(zoomModifier)
            .then(magnifierModifier),
    ) {
        Image(
            painter = painter,
            contentDescription = "Page ${index + 1}",
            contentScale = contentScale,
            // sizeResolver 同时是 LayoutModifier：挂在这里才能读到真实画布约束，
            // 进而算出该用多大的采样率去解这一页。
            modifier = Modifier.fillMaxSize().then(sizeResolver)
        )
        when (state) {
            is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            }

            is AsyncImagePainter.State.Error -> {
                // 退避重试逻辑已提到 when 之外，这里只负责 UI
                //
                // 失败详情直接显示在屏幕上（而不是只写日志）：用户截图就能把
                // 「真实 URL + 域名 + 具体错误」带回来，不必接 adb 抓 logcat。
                // 对"某几页固定加载不出来"这类问题，这三项是定位的全部所需。
                val errorText = remember(state) {
                    val t = (state as? AsyncImagePainter.State.Error)?.result?.throwable
                    val code = httpStatusOf(t)
                    buildString {
                        append(
                            when (code) {
                                404 -> "图片不存在（HTTP 404）"
                                403 -> "无权访问（HTTP 403）"
                                else -> "加载失败"
                            }
                        )
                        append("\n点击重试")
                        append("\n—")
                        append("\n第 ${index + 1} 页")
                        append("\nhost: ${page.url.substringAfter("://").substringBefore("/")}")
                        // 404 时把完整 URL 显示出来：这是唯一能验证"服务端是否真缺图"的凭据。
                        // 原先只显示 host，看到 404 也无从验证。
                        append("\nurl: ${page.url.take(200)}")
                        append("\n${t?.let { it::class.simpleName } ?: "未知"}: ${t?.message?.take(120)}")
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.LightGray)
                        .clickable {
                            // 递增 nonce 重启退避协程，attempt 归零，
                            // 用户的显式操作立即生效而不用等当前退避走完。
                            manualRetryNonce++
                            painter.restart()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry")
                        Text(
                            text = errorText,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            is AsyncImagePainter.State.Success -> {
                val intrinsicSize = state.painter?.intrinsicSize

                // 上报尺寸不能放在 `if (ratio != newRatio)` 里：跨页分组依赖这个
                // 回调判定宽页，而恰好等于当前 ratio 的页会被跳过，宽页永远测不出来。
                LaunchedEffect(intrinsicSize) {
                    if (intrinsicSize != null && intrinsicSize.width > 0 && intrinsicSize.height > 0) {
                        imageAspectRatio = intrinsicSize.width / intrinsicSize.height
                        onSizeLoaded?.invoke(intrinsicSize.width, intrinsicSize.height)
                    }
                }
            }

            else -> {}
        }
    }
}

@Preview(
    name = "单个条目预览 (Light)",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF
)
@Composable
private fun PreviewComicPageItem() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("模拟加载中/失败状态：", modifier = Modifier.padding(bottom = 8.dp))
            ComicPageItem(
                page = ChapterPage(id = "1", url = "http://fake.url"),
                index = 4
            )
        }
    }
}

@Preview(
    name = "列表模拟预览",
    showSystemUi = true
)
@Composable
private fun PreviewComicList() {
    MaterialTheme {
        Surface {
            LazyColumn {
                item {
                    Text(
                        "漫画阅读器示例",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(3) { index ->
                    ComicPageItem(
                        page = ChapterPage(id = "$index", url = "http://fake.url"),
                        index = index
                    )
                }
            }
        }
    }
}