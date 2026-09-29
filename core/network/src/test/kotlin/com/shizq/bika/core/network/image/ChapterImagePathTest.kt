package com.shizq.bika.core.network.image

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "这张图该不该参与换源"的判据。
 *
 * 锁定的缺陷：旧实现用 `host in ImageHosts.MANAGED_HOSTS` 做准入。于是 API 返回
 * 一个候选池之外的新 fileServer 时，这张图**既不会被改写到已知可用源、也不会
 * 进入降级链**——彻底失去换源能力。服务端新增存储节点是常事，表现就是
 * "固定几页图永远加载不出来"。
 *
 * 新判据改用路径：章节图恒为 `/static/{path}`，而头像 CDN、本地文件、base64
 * 等的路径名不是。这样既保住了"不该碰的图不碰"的原意，又让任何来源的章节图
 * 都能被换源。
 */
class ChapterImagePathTest {

    private fun url(s: String) = s.toHttpUrl()

    @Test
    fun `标准章节图路径被识别`() {
        assertTrue(url("https://s3.picacomic.com/static/abc/1.jpg").isChapterImagePath())
    }

    @Test
    fun `白名单之外的域名同样被识别`() {
        // 这是本次修复的核心：域名认不认识，与该不该换源无关。
        assertTrue(url("https://brand-new-node.example.org/static/abc/1.jpg").isChapterImagePath())
    }

    @Test
    fun `路径带查询参数不影响判定`() {
        assertTrue(url("https://x.com/static/a.jpg?token=abc").isChapterImagePath())
    }

    @Test
    fun `头像路径不参与换源`() {
        assertFalse(url("https://avatars.example.com/avatar/u1.png").isChapterImagePath())
    }

    @Test
    fun `根路径不参与换源`() {
        assertFalse(url("https://example.com/").isChapterImagePath())
    }

    @Test
    fun `前缀相似的路径不被误判`() {
        // /staticfoo 不是 /static/，不能因为前缀像就放行。
        assertFalse(url("https://example.com/staticfoo/1.jpg").isChapterImagePath())
    }
}
