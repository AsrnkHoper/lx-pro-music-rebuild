package com.lxpro.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParserTest {

    @Test
    fun `解析标准时间戳并按时间升序`() {
        val lrc = """
            [00:12.50]第二行
            [00:05.00]第一行
        """.trimIndent()

        val lines = LyricParser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals(5_000L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        assertEquals(12_500L, lines[1].timeMs)
    }

    @Test
    fun `支持冒号分隔的小数部分与三位毫秒`() {
        val lines = LyricParser.parse(
            "[00:01:20]冒号版\n[00:02.123]三位毫秒版",
        )

        assertEquals(1_200L, lines[0].timeMs)
        assertEquals(2_123L, lines[1].timeMs)
    }

    @Test
    fun `一行多时间戳展开为多行`() {
        val lines = LyricParser.parse("[00:10.00][01:20.00]副歌")

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(80_000L, lines[1].timeMs)
        assertTrue(lines.all { it.text == "副歌" })
    }

    @Test
    fun `元信息标签被忽略`() {
        val lines = LyricParser.parse(
            "[ti:歌名]\n[ar:歌手]\n[offset:500]\n[by:某人]\n[00:01.00]正文",
        )

        assertEquals(1, lines.size)
        assertEquals("正文", lines[0].text)
    }

    @Test
    fun `翻译按时间点合并到同一行`() {
        val lyric = "[00:05.00]你好\n[00:10.00]再见"
        val translation = "[00:05.00]Hello\n[00:10.00]Bye"

        val lines = LyricParser.parse(lyric, translation)

        assertEquals("Hello", lines[0].translation)
        assertEquals("Bye", lines[1].translation)
    }

    @Test
    fun `没有翻译时 translation 为空`() {
        val lines = LyricParser.parse("[00:05.00]你好")
        assertEquals(null, lines[0].translation)
    }

    @Test
    fun `空输入与纯标签输入返回空列表`() {
        assertTrue(LyricParser.parse("").isEmpty())
        assertTrue(LyricParser.parse("   ").isEmpty())
        assertTrue(LyricParser.parse("[ti:只有标签]").isEmpty())
        assertTrue(LyricParser.parse("[00:05.00]").isEmpty())
    }

    @Test
    fun `indexAt 处理前奏_命中_行间三种情况`() {
        val lines = LyricParser.parse("[00:10.00]A\n[00:20.00]B\n[00:30.00]C")

        assertEquals(-1, LyricParser.indexAt(lines, 0))
        assertEquals(-1, LyricParser.indexAt(lines, 9_999))
        assertEquals(0, LyricParser.indexAt(lines, 10_000))
        assertEquals(1, LyricParser.indexAt(lines, 25_000))
        assertEquals(2, LyricParser.indexAt(lines, 999_999))
        assertEquals(-1, LyricParser.indexAt(emptyList(), 10_000))
    }
}