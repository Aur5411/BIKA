package com.shizq.bika.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 点赞 / 收藏类接口的响应。
 *
 * Bika 的 `.../like`、`.../favourite` 都是**服务端盲翻转**：接口不带目标状态，
 * 服务端按自己当前的记录翻一次，再用 `action` 告诉调用方翻转后的结果。
 * 因此 [action] 是唯一权威值，客户端不应凭本地的乐观翻转去推断结果——
 * 本地状态一旦与服务端脱节（上一次请求超时、多端同时操作），推断出的方向
 * 就与服务端相反，表现为"点了没反应/点了又弹回去"。
 */
@Serializable
data class ActionData(
    @SerialName("action")
    val action: String,
) {
    /**
     * `action` 是否表示"已激活"（点赞 / 收藏）。
     *
     * 用 [Action.isActive] 映射而不是直接比较字符串：接口对"激活"与"取消"
     * 各有独立字面量（`like`/`favourite` 与 `unlike`/`un_favourite`），
     * 散落的字符串比较一旦漏掉一个分支就会静默取到错误状态。
     */
    val isActive: Boolean? get() = Action.of(action)?.isActive

    /** Bika 在 `action` 里回传的取值。未知取值返回 null，由调用方保留原状态。 */
    enum class Action(val raw: String, val isActive: Boolean) {
        LIKE("like", true),
        UNLIKE("unlike", false),
        FAVOURITE("favourite", true),
        UN_FAVOURITE("un_favourite", false),
        ;

        companion object {
            fun of(raw: String): Action? = entries.firstOrNull { it.raw == raw }
        }
    }

    companion object {
        const val ACTION_LIKE = "like"
        const val ACTION_UNLIKE = "unlike"
        const val ACTION_FAVORITE = "favourite"
        const val ACTION_UN_FAVOURITE = "un_favourite"
    }
}
