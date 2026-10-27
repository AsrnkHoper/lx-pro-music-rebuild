package com.lxpro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 分享协议的核心纯逻辑测试：文本提取 + 字段解析。 */
class SongLinkTest {

    /** 用户真实粘贴的内容：整句邀请语 + 末尾链接（含中文标点） */
    private val fullInvite =
        "我在阿玉音乐听《晴天-周杰伦》，复制前往阿玉音乐聆听！" +
            "lxpro://share?title=%E6%99%B4%E5%A4%A9&artist=%E5%91%A8%E6%9D%B0%E4%BC%A6&t=84916&tag=test"

    @Test
    fun `从整句邀请语里抠出链接`() {
        val link = SongLink.extractLinkFromText(fullInvite)
        assertNotNull(link)
        assertEquals(
            "lxpro://share?title=%E6%99%B4%E5%A4%A9&artist=%E5%91%A8%E6%9D%B0%E4%BC%A6&t=84916&tag=test",
            link,
        )
    }

    @Test
    fun `纯链接也能提取`() {
        val raw = "lxpro://share?title=Hello&t=1000"
        assertEquals(raw, SongLink.extractLinkFromText(raw))
    }

    @Test
    fun `普通文字不含链接时返回 null`() {
        assertNull(SongLink.extractLinkFromText("晴天 周杰伦"))
        assertNull(SongLink.extractLinkFromText("lxpro"))
    }

    @Test
    fun `解析分享协议各字段`() {
        val payload = SongLink.decodeShare(
            "lxpro://share?title=%E6%99%B4%E5%A4%A9&artist=%E5%91%A8%E6%9D%B0%E4%BC%A6&t=84916&tag=%E4%BD%A0%E5%90%AC",
        )
        assertNotNull(payload)
        assertEquals("晴天", payload!!.title)
        assertEquals("周杰伦", payload.artist)
        assertEquals(84916L, payload.timestampMs)
        assertEquals("你听", payload.tag)
    }

    @Test
    fun `缺少 title 时不认作分享链接`() {
        assertNull(SongLink.decodeShare("lxpro://share?artist=Someone&t=1000"))
        assertNull(SongLink.decodeShare("lxpro://song/abcdef"))
    }

    @Test
    fun `无时间戳与标签时回落默认值`() {
        val payload = SongLink.decodeShare("lxpro://share?title=Hello&artist=World")
        assertNotNull(payload)
        assertEquals(0L, payload!!.timestampMs)
        assertEquals("", payload.tag)
    }

    @Test
    fun `encodeShare 与 decodeShare 往返一致`() {
        val original = SongLink.SharePayload(
            title = "晴天",
            artist = "周杰伦",
            timestampMs = 84916L,
            tag = "颳風這天我試過握著你手",
        )
        val decoded = SongLink.decodeShare(SongLink.encodeShare(original))
        assertEquals(original, decoded)
    }
}
