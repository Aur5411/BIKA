package com.shizq.bika.core.network.image

import okhttp3.HttpUrl

/**
 * 图片路径的**不可见字符**治理，全模块单一事实来源。
 *
 * `Media`（拼接 URL 时）与 [ImagePathGuardInterceptor]（拦截器链兜底）都走这里。
 * 两边各写一份判据，必然会在某次修改后走偏——那时故障形态是"阅读修好了、
 * 封面仍然 404"这种极难定位的不一致。
 */

/**
 * 判断字符是否是"路径里的不可见垃圾"。
 *
 * ## 为什么按 Unicode 类别判，而不是枚举字符
 *
 * 这类脏值的麻烦在于**渲染后与普通空格完全无法区分**（连日志里都看不出来），
 * 只能靠码位识别。而"看起来像空格 / 什么都看不见"的字符远不止一种，枚举永远
 * 在追着补——补到第几种才够，没有答案。
 *
 * 更麻烦的是最顺手的那两个判据**互有盲区**：
 *
 * | 判断方式 | 覆盖 | 盲区 |
 * |---|---|---|
 * | `Char.isWhitespace()` | Cc 里的 `\t \n \u000B \f \r`、Zs 大部分 | **漏 U+00A0（NBSP）、U+202F** |
 * | `Character.isSpaceChar()` | 整个 Zs 类别 | 漏 U+2028/U+2029、所有 Cf |
 *
 * 两者取并集仍然漏掉 Cf 类别（零宽字符 U+200B/U+200C/U+200D/U+200E/U+200F、
 * U+2060、U+FEFF、蒙古文元音分隔符 U+180E、软连字符 U+00AD 等）——它们同样
 * 渲染为"什么都没有"，塞进路径后一样会把请求打到不存在的目录。
 *
 * 所以改成**按 Unicode 类别一刀切**：只要字符属于"控制 / 格式 / 空白分隔 /
 * 行分隔 / 段分隔"这五类，就一律视为不可见垃圾。这五类正是"屏幕上不占可见宽度、
 * 却在 URL 里真实存在"的全部来源，一次覆盖，不存在"又发现一种"。
 *
 * 反过来，可打印字符（含 CJK、`%`、`~` 等）一律保留——合法图片路径不会被误伤。
 */
internal fun Char.isInvisiblePathJunk(): Boolean = when (Character.getType(this)) {
    Character.CONTROL.toInt() -> true              // Cc：\t \n \r、U+000B、U+001C–U+001F、U+007F–U+009F
    Character.FORMAT.toInt() -> true               // Cf：零宽 U+200B/200C/200D/200E/200F、U+2060、U+FEFF、U+180E、U+00AD
    Character.SPACE_SEPARATOR.toInt() -> true      // Zs：U+0020、U+00A0、U+2000–U+200A、U+202F、U+205F、U+3000
    Character.LINE_SEPARATOR.toInt() -> true       // Zl：U+2028
    Character.PARAGRAPH_SEPARATOR.toInt() -> true  // Zp：U+2029
    else -> false
}

/**
 * 把路径里不可见的字符替换为下划线。
 *
 * ## 为什么是"下划线"，为什么可以无条件替换
 *
 * 服务端返回的 `path` 里会出现形如 `sub_storage 1/7f/8b/<uuid>.jpg` 的值——
 * 目录段 `sub_storage 1` 中间是一个**不可见字符**。这个值一路原样穿过整条图片链路：
 * 拼接时不报错，`HttpUrl` 解析时把它编码成 `%20` / `%C2%A0`，请求正常发出，
 * **没有任何一层会认为它有问题**。
 *
 * 但它在 CDN 上必然取不到图。实测（`storage-b`，6 个边缘 IP 交叉验证，结论完全一致）：
 *
 * | 路径段 | 状态 | 字节数 |
 * |---|---|---|
 * | `sub_storage%201`（空格） | 403 | 178（nginx 原生 403 页） |
 * | `sub_storage_1`（下划线） | **200** | **626131（真实 JPEG）** |
 *
 * 403 是 nginx 自带页，说明**该目录在源站根本不存在**——不是权限问题，是路径写错了。
 * 真实字符是下划线，那个不可见字符是数据里的脏值。
 *
 * 替换是安全的：真实图片路径只由 `[A-Za-z0-9._/-]` 构成（名字段 + uuid + 扩展名），
 * 不含任何空白或不可见字符，所以不存在误伤。
 */
internal fun String.sanitizePathBlanks(): String = buildString(length) {
    for (c in this@sanitizePathBlanks) {
        append(if (c.isInvisiblePathJunk()) '_' else c)
    }
}

/**
 * 裁掉首尾的不可见字符。
 *
 * 不能直接用 Kotlin 的 `String.trim()`——它内部走 `Char.isWhitespace()`，
 * **判不出 U+00A0（NBSP）**。若字段首尾恰好是 NBSP，`trim()` 不会去掉它，
 * 紧接着 [sanitizePathBlanks] 又会把它替换成下划线，于是拼出 `_sub_storage/...`
 * 这种同样 403 的地址——错得比完全不处理更隐蔽。
 */
internal fun String.trimInvisible(): String = trim { it.isInvisiblePathJunk() }

/**
 * 对 `/static/` 图片地址做最后一道归一化，返回改写后的 URL；
 * 无需改动（或不是章节图）时返回 null。
 *
 * ## 为什么要在拦截器层再兜一次
 *
 * `Media.safeImageUrl` 已经修好了"从 API 响应拼 URL"这一条路径，但应用里
 * 取图片地址的地方不止一处——封面、头像、通知列表等用的是 `originalImageUrl`
 * （刻意保留原样，便于日志对照），历史上还可能有别处自行拼过 URL。
 * 只要漏掉任何一处，现象就退化成"章节图正常、封面固定 404"，而用户看到的
 * 依旧是"图片不存在"。
 *
 * 放在拦截器链**最外层**，就等于给所有取图路径加了一个不依赖调用方自觉的
 * 收口：无论 URL 从哪来，只要它是 `/static/` 图片，进网络前一定被治过。
 *
 * 实现上按 `pathSegments`（已解码）逐段处理，再用 `addPathSegment` 重新编码——
 * 这样 `%20`、`%C2%A0` 这些已经编码过的形态也会先被还原成字符再判定，
 * 不会因为"已经是编码态"而漏网。
 */
internal fun HttpUrl.sanitizeStaticImagePath(): HttpUrl? {
    if (!encodedPath.startsWith("/static/")) return null

    val segments = pathSegments
    var changed = false
    val rebuilt = newBuilder().encodedPath("/")
    for (segment in segments) {
        val fixed = segment.sanitizePathBlanks()
        if (fixed != segment) changed = true
        rebuilt.addPathSegment(fixed)
    }
    return if (changed) rebuilt.build() else null
}
