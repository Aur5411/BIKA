package com.shizq.bika.core.network.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ImageHostRouter] 的选路记账，重点是 v1.11.35 新增的冷启动兜底源。
 *
 * 这段逻辑要保证的是"第一次打开不慢"，而它只在**进程刚起来**那一小段时间里
 * 起作用——正是最难复现、也最容易在后续改动里被无声破坏的窗口，
 * 所以用测试把边界钉住。
 */
class ImageHostRouterTest {

    private val default = ImageHosts.DEFAULT_HOST
    private val other = ImageHosts.imageHosts.first { it != default }

    @Test
    fun `没有结论时给出兜底源`() {
        val router = ImageHostRouter()
        assertNull(router.preferredHost(nowMs = 1_000L), "冷启动不该有竞速结论")
        assertEquals(default, router.startupHostOrNull(nowMs = 1_000L))
    }

    @Test
    fun `已有结论时不越权返回兜底源`() {
        val router = ImageHostRouter()
        router.remember(other, nowMs = 1_000L)
        // 竞速已经选出了别的源，兜底源就不该再插一脚
        assertNull(router.startupHostOrNull(nowMs = 1_001L))
        assertEquals(other, router.preferredHost(nowMs = 1_001L))
    }

    @Test
    fun `结论过期后重新允许兜底源打头阵`() {
        val router = ImageHostRouter()
        router.remember(other, nowMs = 1_000L)
        // 10 分钟保鲜期过后，preferredHost 失效，兜底源重新顶上
        val afterTtl = 1_000L + 10 * 60 * 1000L + 1
        assertNull(router.preferredHost(nowMs = afterTtl))
        assertEquals(default, router.startupHostOrNull(nowMs = afterTtl))
    }

    @Test
    fun `兜底源失败后进入冷却不再打头阵`() {
        val router = ImageHostRouter()
        // forget 内部记的是真实时钟，这里也统一用真实时钟，
        // 避免把"虚拟时间"和"真实时间"混在一个断言里。
        router.forget(default)

        // 关键：此时 preferred 从未被设置过（走的就是 startupHostOrNull 直接打头阵
        // 那条路径），`preferred == host` 不成立——这正是必须单独记账的原因。
        assertNull(
            router.startupHostOrNull(),
            "兜底源刚失败过，不该再让每张图都先陪它失败一次",
        )
    }

    @Test
    fun `冷却结束后兜底源重新参与`() {
        val router = ImageHostRouter()
        router.forget(default)

        val afterCooldown = System.currentTimeMillis() + 5 * 60 * 1000L + 1
        assertEquals(
            default,
            router.startupHostOrNull(nowMs = afterCooldown),
            "网络环境会变，永久拉黑会变成新的固定失败",
        )
    }

    @Test
    fun `失败只清掉与传入 host 相符的记录`() {
        val router = ImageHostRouter()
        router.remember(other, nowMs = 1_000L)

        router.forget("some-other-host.example")
        assertEquals(other, router.preferredHost(nowMs = 1_001L), "不该误清新学到的源")

        router.forget(other)
        assertNull(router.preferredHost(nowMs = 1_002L))
    }

    @Test
    fun `兜底源就是候选池里那个容错面最宽的源`() {
        assertEquals(DEFAULT_HOST_IN_POOL, default)
    }

    private companion object {
        /** [ImageHosts.DEFAULT_HOST] 的期望值：唯一能直接吃下无 `tobs/` 路径的节点。 */
        const val DEFAULT_HOST_IN_POOL = "storage-b.diwodiwo.xyz"
    }
}
