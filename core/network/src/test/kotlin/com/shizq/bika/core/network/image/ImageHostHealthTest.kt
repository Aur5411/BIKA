package com.shizq.bika.core.network.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 域名健康度的语义边界。
 *
 * 锁定的缺陷：候选池 8 个域名里有 6 个（picacomic.com 系）在本网络环境**永久不可达**
 * （DNS 污染 → 连接层直接失败），而 API 下发的 `fileServer` 恰恰常是其中一个。
 * 每张图都要为这 6 个死源各烧一次 `connectTimeout`（3 秒），18 秒白等期间还占着
 * 一个 `fallbackSlots` 名额（共 12 个）。一屏 5~8 张图 + 预载窗口 16 张，
 * 槽位被"正在陪死源干等"的图占满，队尾那几张（第 37/38 页）连尝试机会都没有。
 *
 * 这些用例固化三件必须成立的事：
 * 1. 连续连接失败要进隔离，且**隔离后重排到队尾**；
 * 2. 一次成功（或拿到任何 HTTP 响应）**立即**恢复——好源不能被误杀；
 * 3. 隔离**有期限**，到期自动解除，避免变成新的"固定几页失败"。
 */
class ImageHostHealthTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `首次连接失败不隔离`() {
        val health = ImageHostHealth()
        health.noteUnreachable("s3.picacomic.com", now)
        assertFalse(
            health.isQuarantined("s3.picacomic.com", now),
            "单次失败可能只是网络抖动，立刻隔离会误伤好源",
        )
    }

    @Test
    fun `连续两次连接失败进入隔离`() {
        val health = ImageHostHealth()
        health.noteUnreachable("s3.picacomic.com", now)
        health.noteUnreachable("s3.picacomic.com", now)
        assertTrue(health.isQuarantined("s3.picacomic.com", now))
    }

    @Test
    fun `一次成功立即解除隔离并清零计数`() {
        val health = ImageHostHealth()
        repeat(3) { health.noteUnreachable("storage.diwodiwo.xyz", now) }
        assertTrue(health.isQuarantined("storage.diwodiwo.xyz", now))

        health.noteReachable("storage.diwodiwo.xyz")
        assertFalse(
            health.isQuarantined("storage.diwodiwo.xyz", now),
            "拿到任何 HTTP 响应都说明源可达，必须立即恢复",
        )

        // 计数也被清零：再失败一次不应立刻又进隔离。
        health.noteUnreachable("storage.diwodiwo.xyz", now)
        assertFalse(health.isQuarantined("storage.diwodiwo.xyz", now))
    }

    @Test
    fun `隔离到期自动解除`() {
        val health = ImageHostHealth()
        repeat(2) { health.noteUnreachable("img.picacomic.com", now) }
        assertTrue(health.isQuarantined("img.picacomic.com", now))

        // 3 分钟隔离期过后，网络环境可能已变（换 Wi-Fi、服务端换节点）。
        val later = now + 3 * 60 * 1000L + 1
        assertFalse(
            health.isQuarantined("img.picacomic.com", later),
            "隔离必须有期限，永久拉黑会变成新的『固定几页失败』",
        )
    }

    @Test
    fun `隔离的域名被排到队尾但仍在候选里`() {
        val health = ImageHostHealth()
        val dead = listOf("s3.picacomic.com", "s2.picacomic.com", "storage1.picacomic.com")
        dead.forEach { host -> repeat(2) { health.noteUnreachable(host, now) } }

        val candidates = dead + listOf("storage.diwodiwo.xyz", "storage.tipatipa.xyz")
        val ordered = health.orderCandidates(candidates, now)

        assertEquals(
            listOf("storage.diwodiwo.xyz", "storage.tipatipa.xyz") + dead,
            ordered,
            "可用的源必须排前面；死源排后面但仍保留（服务端换节点后还得能试到）",
        )
        assertEquals(
            candidates.toSet(),
            ordered.toSet(),
            "重排不能丢域名——隔离是排序提示，不是可用性白名单",
        )
    }

    @Test
    fun `未隔离时保持传入顺序`() {
        val health = ImageHostHealth()
        val hosts = listOf("a.com", "b.com", "c.com")
        assertEquals(hosts, health.orderCandidates(hosts, now))
    }

    @Test
    fun `隔离是逐域名的，互不影响`() {
        val health = ImageHostHealth()
        repeat(2) { health.noteUnreachable("s3.picacomic.com", now) }

        assertTrue(health.isQuarantined("s3.picacomic.com", now))
        assertFalse(health.isQuarantined("s2.picacomic.com", now))
        assertFalse(health.isQuarantined("storage.diwodiwo.xyz", now))
    }

    @Test
    fun `quarantinedHosts 只返回当前真正被隔离的域名`() {
        val health = ImageHostHealth()
        repeat(2) { health.noteUnreachable("s3.picacomic.com", now) }
        health.noteUnreachable("img.picacomic.com", now) // 只失败一次，未隔离

        assertEquals(setOf("s3.picacomic.com"), health.quarantinedHosts(now))
    }
}
