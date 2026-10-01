package com.shizq.bika.feature.reader.impl.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [describeSuspiciousPathChars] —— 报错界面上的"服务端原始数据"诊断。
 *
 * 锁定的困境：图片 404 报告反复出现，而 `url` 是归一化后的结果、看起来永远干净，
 * 光凭它无法判断"是我们拼错还是服务端数据脏"。把原始 path 里不可见字符的码位
 * 直接显示在失败卡片上，用户截一张图就等于提供了完整现场。
 *
 * 这里只测"能不能把不可见字符准确地指出来"，不测它的展示位置。
 */
class SuspiciousPathCharsTest {

    @Test
    fun `干净的路径不产生任何诊断输出`() {
        val path = "tobs/sub_storage_1/7f/8b/7f8b7261-7509-4aae-88df-9e238ba01e62.jpg"
        assertEquals("", describeSuspiciousPathChars(path))
    }

    @Test
    fun `空路径不产生输出`() {
        assertEquals("", describeSuspiciousPathChars(""))
    }

    @Test
    fun `普通空格被指出并带上位置与码位`() {
        val out = describeSuspiciousPathChars("sub_storage 1/1.jpg")
        assertTrue(out.contains("U+0020"), "应指出 U+0020，实际: $out")
        assertTrue(out.contains("[11]"), "应指出下标 11，实际: $out")
        assertTrue(out.contains("SPACE"), "应带上字符名，实际: $out")
    }

    @Test
    fun `不间断空格同样被指出`() {
        val out = describeSuspiciousPathChars("sub_storage\u00A01/1.jpg")
        assertTrue(out.contains("U+00A0"), "应指出 U+00A0，实际: $out")
    }

    @Test
    fun `零宽字符同样被指出`() {
        val out = describeSuspiciousPathChars("sub_storage\u200B1/1.jpg")
        assertTrue(out.contains("U+200B"), "应指出 U+200B，实际: $out")
    }

    @Test
    fun `制表符与换行也被指出`() {
        val out = describeSuspiciousPathChars("a\tb\nc")
        assertTrue(out.contains("U+0009") && out.contains("U+000A"), "实际: $out")
    }

    @Test
    fun `多个可疑字符按顺序全部列出`() {
        val out = describeSuspiciousPathChars("a b\u00A0c")
        assertTrue(out.indexOf("U+0020") < out.indexOf("U+00A0"), "应按出现顺序，实际: $out")
        assertEquals(2, out.split("U+").size - 1, "应恰好列出两个字符，实际: $out")
    }

    @Test
    fun `可打印 ASCII 全谱都不被判为可疑`() {
        // 反向保护：判定过宽会把正常路径也刷成一片噪声，诊断反而失效。
        val printable = (0x21..0x7E).map { it.toChar() }.joinToString("")
        assertEquals("", describeSuspiciousPathChars(printable))
    }
}
