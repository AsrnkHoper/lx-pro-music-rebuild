package com.lxpro.core.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistNamesTest {

    @Test
    fun `归一化去首尾空白并压缩内部空白`() {
        assertEquals("我的歌单", PlaylistNames.normalize("  我的歌单  "))
        assertEquals("我的 歌单", PlaylistNames.normalize("我的   歌单"))
    }

    @Test
    fun `空白名返回 null 而不是空歌单名`() {
        assertNull(PlaylistNames.normalize(""))
        assertNull(PlaylistNames.normalize("   "))
        assertNull(PlaylistNames.normalize("\t\n "))
    }

    @Test
    fun `超长名截断到上限`() {
        val long = "长".repeat(60)
        assertEquals(PlaylistNames.MAX_LENGTH, PlaylistNames.normalize(long)!!.length)
    }

    @Test
    fun `默认名跳过已被占用的编号`() {
        assertEquals("新歌单 1", PlaylistNames.nextDefaultName(emptyList()))
        assertEquals("新歌单 3", PlaylistNames.nextDefaultName(listOf("新歌单 1", "新歌单 2")))
        // 别的名字不影响编号选择
        assertEquals("新歌单 1", PlaylistNames.nextDefaultName(listOf("我的歌单")))
    }
}