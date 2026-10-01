package com.shizq.bika.core.network.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 候选图片源池的准入底线。
 *
 * 锁定的缺陷：池子里曾放过 `img.picacomic.com` 与 `www.picacomic.com`——
 * 它们是 Web / API 主机，`/static/` 路径下**一切皆 404**（连 `/static/` 本身都 404），
 * 只有根路径返回 200。
 *
 * 危害不是"多失败两次"这么简单：
 *
 * 1. 它们 DNS 能解析、TCP 能连上、还会给出**真实 HTTP 响应**，所以
 *    [ImageHostHealth] 只隔离"连接层失败"的机制**永远不会隔离它们**——
 *    每次换源遍历都要为它们各付出一发，在预算有限的串行兜底里把可用源挤到队尾；
 * 2. 它们返回的 **404** 会顶掉合法存储节点给出的 **403**，让界面把
 *    "路径写错"报成"图片不存在"——一个**方向完全相反**的结论，
 *    直接误导排查（本项目就因此追错了好几轮）。
 *
 * 这些用例是"事后钉住"，不是"事前证明"：池子里每个域名都必须逐个实测过
 * `/static/<真实图>`、`/static/`、根路径三类地址才允许加入。
 */
class ImageHostsTest {

    /**
     * 实测 `/static/` 路径全部 404 的域名，永远不许再进池子。
     *
     * 实测方式：`curl --resolve <host>:443:<ip> https://<host>/static/<真实图>`
     * 以及 `https://<host>/static/`，两者均 404，而根路径 200。
     */
    private val knownDeadImageHosts = setOf(
        "img.picacomic.com",
        "www.picacomic.com",
    )

    @Test
    fun `已知不服图片的域名不在候选池里`() {
        val offenders = ImageHosts.imageHosts.filter { it in knownDeadImageHosts }
        assertTrue(
            offenders.isEmpty(),
            "以下域名在 /static/ 下恒返回 404，会污染失败状态码并白占换源名额: $offenders",
        )
    }

    @Test
    fun `候选池里只剩实测可取到图的域名`() {
        // 实测（每个域名 × 6 个边缘 IP，路径 sub_storage_1/1b/d3/<真实 uuid>.jpg）：
        // s3 / s2 / storage1 / storage-b / storage.diwodiwo.xyz / storage.tipatipa.xyz
        // 全部 200；img / www 全部 404。
        assertEquals(
            listOf(
                "s3.picacomic.com",
                "s2.picacomic.com",
                "storage1.picacomic.com",
                "storage.diwodiwo.xyz",
                "storage-b.picacomic.com",
                "storage.tipatipa.xyz",
            ),
            ImageHosts.imageHosts,
        )
    }

    @Test
    fun `候选池与派生集合保持一致`() {
        assertEquals(ImageHosts.imageDomains.map { it.removePrefix("https://") }, ImageHosts.imageHosts)
        assertEquals(ImageHosts.imageHosts.toSet(), ImageHosts.MANAGED_HOSTS)
        assertEquals(ImageHosts.imageHosts.size, ImageHosts.MANAGED_HOSTS.size, "不应有重复域名")
    }

    @Test
    fun `每个候选都是带 scheme 的 https 地址`() {
        ImageHosts.imageDomains.forEach {
            assertTrue(it.startsWith("https://"), "候选必须是 https: $it")
            assertTrue('/' !in it.removePrefix("https://"), "候选不应带路径: $it")
        }
    }

    @Test
    fun `连接预热用的默认源在候选池里`() {
        // 预热若指向池外的域名，预热出来的连接永远不会被复用，纯属浪费。
        assertTrue(
            ImageHosts.DEFAULT_HOST in ImageHosts.MANAGED_HOSTS,
            "DEFAULT_HOST=${ImageHosts.DEFAULT_HOST} 不在候选池里",
        )
    }
}
