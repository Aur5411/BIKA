package com.shizq.bika.core.model.preferences

import com.shizq.bika.core.model.AutoScrollConfig
import com.shizq.bika.core.model.BookSpreadsMode
import com.shizq.bika.core.model.EyeCareConfig
import com.shizq.bika.core.model.reader.ReadingMode
import com.shizq.bika.core.model.reader.ScreenOrientation
import com.shizq.bika.core.model.reader.TapZoneLayout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReaderPreferences(
    val readingMode: ReadingMode = ReadingMode.WEBTOON,
    val screenOrientation: ScreenOrientation = ScreenOrientation.Portrait,
    val tapZoneLayout: TapZoneLayout = TapZoneLayout.Sides,
    @SerialName("volumeKeyNavigation")
    val volumeKeyNavigationEnabled: Boolean = true,
    /**
     * 基准预载张数。
     *
     * 取 8 而不是 2：阅读器是"翻页即出图"的场景，预载 2 页意味着连翻三页就
     * 开始露加载态。8 页的原图约 8~24MB，一次章节阅读完全吃得下；
     * 精读与扫读还会在此基础上自适应调整（见 AdaptivePreloadPolicy）。
     *
     * 改这个只影响新安装/清过数据的默认值，老用户保留自己的设置值。
     */
    val preloadCount: Int = 8,
    val eyeCare: EyeCareConfig = EyeCareConfig(),
    val autoScroll: AutoScrollConfig = AutoScrollConfig(),
    val bookSpreadsMode: BookSpreadsMode = BookSpreadsMode.AUTO,
    val magnifierEnabled: Boolean = true,
    val statusBarCapsuleEnabled: Boolean = true,
)