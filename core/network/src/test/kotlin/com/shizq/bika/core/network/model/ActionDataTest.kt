package com.shizq.bika.core.network.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `action` 字段到布尔状态的映射。
 *
 * 这层存在的理由：`.../like`、`.../favourite` 是服务端盲翻转，客户端只能靠
 * `action` 得知翻转后的真实结果。映射一旦漏分支就会静默取到错误状态，
 * 而错误状态不会报错、只会让用户觉得"点了没用"。
 */
class ActionDataTest {

    @Test
    fun `点赞与取消点赞分别映射为 true 和 false`() {
        assertTrue(ActionData(ActionData.ACTION_LIKE).isActive!!)
        assertFalse(ActionData(ActionData.ACTION_UNLIKE).isActive!!)
    }

    @Test
    fun `收藏与取消收藏分别映射为 true 和 false`() {
        assertTrue(ActionData(ActionData.ACTION_FAVORITE).isActive!!)
        assertFalse(ActionData(ActionData.ACTION_UN_FAVOURITE).isActive!!)
    }

    @Test
    fun `未知 action 返回 null 而不是猜一个方向`() {
        // 失败语义：调用方拿到 null 会保留乐观值。若这里退化成 false，
        // 服务端新增动作时会静默把用户已点赞的评论显示成未点赞。
        assertNull(ActionData("whatever").isActive)
        assertNull(ActionData("").isActive)
    }

    @Test
    fun `常量字面量与 Bika 接口一致`() {
        // 这些字符串直接上线路，改动会被服务端当作未知动作
        assertEquals("like", ActionData.ACTION_LIKE)
        assertEquals("unlike", ActionData.ACTION_UNLIKE)
        assertEquals("favourite", ActionData.ACTION_FAVORITE)
        assertEquals("un_favourite", ActionData.ACTION_UN_FAVOURITE)
    }
}
