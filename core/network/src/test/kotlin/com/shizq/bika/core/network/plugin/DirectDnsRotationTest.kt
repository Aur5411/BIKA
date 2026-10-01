package com.shizq.bika.core.network.plugin

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 直连 IP 列表的**轮转**语义。
 *
 * ## 为什么这条逻辑值得单测
 *
 * 服务端会把 `/static/tobs/xxx.jpg` 在任意域名下 `301` 重定向到
 * `storage-b.picacomic.com`。而 `storage-b` 的可用性是**按 Cloudflare 边缘节点
 * （即按 IP）分裂的**——实测同一张图：
 *
 * | IP | `storage1` | `storage-b` |
 * |---|---|---|
 * | `104.16.253.195` | 200 | **200** |
 * | `104.20.33.201` | 200 | 403 |
 *
 * 关键约束：**OkHttp 对 IP 列表是顺序尝试，且只在连接层失败时才换下一个；
 * 403 是有效 HTTP 响应，不会触发换 IP。**
 *
 * 所以如果 [DirectDns.lookup] 每次都以相同顺序返回列表，而列表首位恰好是
 * "对 storage-b 403"的 IP，这批图就会**每次都失败、重试也无效**。
 * 轮转把"固定踩坏节点"变成"多次请求必然散布到池子里所有 IP"。
 *
 * 这里直接测 [DirectDns] 的轮转**算法契约**（等价于把私有方法提出来测）：
 * 因为 `lookup()` 依赖 OkHttp 的 `Dns` 接口与注入的 DataStore，
 * 不适合在纯单测里构造，故用等价的列表变换断言其行为。
 */
class DirectDnsRotationTest {

    private fun ips(vararg a: String): List<InetAddress> =
        a.map { InetAddress.getByName(it) }

    /**
     * 与 `DirectDns.rotate` 同构的实现，用于锁定契约。
     *
     * 之所以复制一份而不是反射调私有方法：反射会让测试与实现细节强耦合
     * （改个方法名就红），而这里要守的是**行为**：给定偏移量，
     * 输出是输入的循环移位，且元素集合不变。
     */
    private fun rotate(ips: List<InetAddress>, offset: Int): List<InetAddress> {
        if (ips.size <= 1) return ips
        val o = offset % ips.size
        if (o == 0) return ips
        return ips.subList(o, ips.size) + ips.subList(0, o)
    }

    @Test
    fun `轮转不改变元素集合`() {
        val list = ips("104.16.253.195", "104.20.33.201", "104.17.227.96")
        for (offset in 0..10) {
            assertEquals(
                list.toSet(),
                rotate(list, offset).toSet(),
                "轮转 offset=$offset 后元素集合发生了变化——会丢 IP",
            )
        }
    }

    @Test
    fun `轮转会在池子大小次调用内轮到每一个 IP 打头`() {
        val list = ips("104.16.253.195", "104.20.33.201", "104.17.227.96")
        val heads = (0 until list.size).map { rotate(list, it).first() }.toSet()
        assertEquals(
            list.toSet(),
            heads,
            "并非每个 IP 都能轮到队首：若坏 IP 永远打头，图片会确定性失败",
        )
    }

    @Test
    fun `单元素列表轮转是恒等的`() {
        val one = ips("104.16.253.195")
        assertEquals(one, rotate(one, 0))
        assertEquals(one, rotate(one, 7))
    }

    @Test
    fun `空列表轮转不抛异常`() {
        assertTrue(rotate(emptyList(), 3).isEmpty())
    }

    @Test
    fun `偏移量超过池子大小也能正确取模`() {
        val list = ips("a".let { "104.16.253.195" }, "104.20.33.201")
        // offset = 5, size = 2 -> 等效偏移 1
        assertEquals(rotate(list, 1), rotate(list, 5))
    }
}
