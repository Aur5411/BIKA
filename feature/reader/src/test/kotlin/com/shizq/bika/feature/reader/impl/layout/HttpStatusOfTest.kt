package com.shizq.bika.feature.reader.impl.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [httpStatusOf] 的行为约束。
 *
 * 这个函数决定 UI 是否把一次失败当成"服务端缺图"（404/403 → 停止重试），
 * 所以"别把连接超时误判成 404"和"真 404 要认得出来"同等重要。
 *
 * 背景：某几页固定报 HTTP 404 的排查里，一度无法确定那是服务端真的缺图，
 * 还是状态码提取把 URL / 异常文本里的数字误当成了状态码——后者会让本可自愈的
 * 网络抖动被永久判死。所以这里把两个方向都钉住。
 */
class HttpStatusOfTest {

    @Test
    fun `普通异常且无状态码时返回 null`() {
        assertNull(httpStatusOf(RuntimeException("connection reset by peer")))
        assertNull(httpStatusOf(null))
        assertNull(httpStatusOf(RuntimeException("")))
    }

    @Test
    fun `识别 HTTP 后跟三位的写法`() {
        assertEquals(404, httpStatusOf(RuntimeException("HTTP 404 Not Found")))
        assertEquals(403, httpStatusOf(RuntimeException("HTTP 403 Forbidden")))
        assertEquals(500, httpStatusOf(RuntimeException("HTTP 500 Internal Server Error")))
    }

    @Test
    fun `识别 code 与 status 的等号冒号写法`() {
        assertEquals(404, httpStatusOf(RuntimeException("code=404")))
        assertEquals(403, httpStatusOf(RuntimeException("code: 403")))
        assertEquals(502, httpStatusOf(RuntimeException("status=502")))
        assertEquals(503, httpStatusOf(RuntimeException("status:503")))
    }

    @Test
    fun `识别中文状态码写法`() {
        assertEquals(404, httpStatusOf(RuntimeException("状态码 404")))
        assertEquals(500, httpStatusOf(RuntimeException("状态码:500")))
    }

    @Test
    fun `识别括号包裹的状态码`() {
        assertEquals(404, httpStatusOf(RuntimeException("加载失败 (404)")))
    }

    /**
     * 这是本函数存在的核心理由：图片 URL 里常带 UUID 或数字段，
     * 裸三位数匹配会把它们误当成状态码，从而把一次可自愈的失败判成"缺图"。
     */
    @Test
    fun `不会把 URL 里的数字段误判为状态码`() {
        assertNull(
            httpStatusOf(
                RuntimeException(
                    "Failed to load " +
                            "https://storage1.picacomic.com/static/404-abc-500-def.jpg"
                )
            )
        )
        assertNull(
            httpStatusOf(
                RuntimeException(
                    "timeout for " +
                            "https://storage.diwodiwo.xyz/static/a9cf81c3-73b5-42e8-bf24-f03a5080780c.jpg"
                )
            )
        )
        assertNull(httpStatusOf(RuntimeException("connect to 10.0.0.1:404 timed out")))
    }

    @Test
    fun `不会把时间戳或端口误判为状态码`() {
        assertNull(httpStatusOf(RuntimeException("socket closed after 1500 ms")))
        assertNull(httpStatusOf(RuntimeException("bound to port 8443")))
    }

    @Test
    fun `超出 1xx-5xx 范围的三位数不被采纳`() {
        // 600 不在 HTTP 状态码范围内，不该被当成状态码返回。
        assertNull(httpStatusOf(RuntimeException("HTTP 600 weird")))
        assertNull(httpStatusOf(RuntimeException("code=999")))
    }

    @Test
    fun `反射分支优先于文本分支`() {
        // 构造一个带 getResponse().getCode() 的假异常：即使 message 里写了别的数字，
        // 也应当以反射读到的真实状态码为准。
        val fake = FakeHttpException(message = "HTTP 500 boom", status = 404)
        assertEquals(404, httpStatusOf(fake))
    }

    @Test
    fun `反射失败时回落到文本解析`() {
        // getResponse 抛异常（模拟实现变化），应回到文本解析而不是返回 null。
        val broken = BrokenReflectionException("HTTP 403 Forbidden")
        assertEquals(403, httpStatusOf(broken))
    }

    private class FakeResponse(val code: Int)

    private class FakeHttpException(
        message: String,
        val status: Int,
    ) : RuntimeException(message) {
        fun getResponse(): FakeResponse = FakeResponse(status)
    }

    private class BrokenReflectionException(message: String) : RuntimeException(message) {
        @Suppress("unused")
        fun getResponse(): Nothing = throw IllegalStateException("no response attached")
    }
}
