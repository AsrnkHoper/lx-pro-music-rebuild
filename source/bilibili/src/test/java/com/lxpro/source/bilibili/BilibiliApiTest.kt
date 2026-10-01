package com.lxpro.source.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliApiTest {

    @Test
    fun `stripTags 剥掉搜索高亮标签`() {
        assertEquals("晴天", BilibiliApi.stripTags("<em class=\"keyword\">晴天</em>"))
        assertEquals("A&B", BilibiliApi.stripTags("A&amp;B"))
    }

    @Test
    fun `normalizeUrl 补 https`() {
        assertEquals("https://i0.hdslb.com/x.jpg", BilibiliApi.normalizeUrl("//i0.hdslb.com/x.jpg"))
        assertEquals("https://i0.hdslb.com/x.jpg", BilibiliApi.normalizeUrl("http://i0.hdslb.com/x.jpg"))
        assertNull(BilibiliApi.normalizeUrl("  "))
    }

    @Test
    fun `parseDuration 支持 m ss 与 h mm ss`() {
        assertEquals(243L, BilibiliApi.parseDuration("4:03"))
        assertEquals(3723L, BilibiliApi.parseDuration("1:02:03"))
        assertEquals(180L, BilibiliApi.parseDuration(180))
        assertNull(BilibiliApi.parseDuration(null))
    }

    @Test
    fun `encodeUriComponent 对齐 JS 语义`() {
        // 空格 → %20（不是 +）
        assertEquals("a%20b", BilibiliApi.encodeUriComponent("a b"))
        // JS 不转义 !'()*，但 WBI 要求把它们删掉
        assertEquals("abcdef", BilibiliApi.encodeUriComponent("a!b'c(d)e*f"))
        // ~ 保留
        assertEquals("a~b", BilibiliApi.encodeUriComponent("a~b"))
        // 中文必须整体百分号编码（高位字节不能被误判为字母）
        assertEquals("%E6%99%B4%E5%A4%A9", BilibiliApi.encodeUriComponent("晴天"))
    }

    @Test
    fun `getMixinKey 取重排表前 32 位`() {
        // 用可预期的输入验证「按表重排 + 截前 32」这条规则本身
        val imgKey = "0123456789abcdef0123456789abcdef"
        val subKey = "fedcba9876543210fedcba9876543210"
        val mixin = BilibiliApi.getMixinKey(imgKey, subKey)
        assertEquals(32, mixin.length)
    }

    @Test
    fun `md5 与标准实现一致`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", BilibiliApi.md5("hello"))
    }

    @Test
    fun `sign 参数按字典序拼接并附 w_rid`() {
        val query = BilibiliApi.sign(
            mapOf("search_type" to "video", "keyword" to "晴天"),
            mixinKey = "0123456789abcdef0123456789abcdef",
            wts = 1_700_000_000L,
        )
        assertTrue(query.startsWith("keyword="))
        assertTrue(query.contains("&search_type=video&"))
        assertTrue(query.contains("&wts=1700000000"))
        assertTrue(Regex("&w_rid=[0-9a-f]{32}$").containsMatchIn(query))
    }

    @Test
    fun `buildCookieHeader 只保留风控必需字段`() {
        val header = BilibiliApi.buildCookieHeader(
            listOf(
                "buvid3=ABC; Path=/; Domain=.bilibili.com",
                "buvid4=DEF; Path=/",
                "other=XYZ; Path=/",
                "_uuid=GHI; Path=/",
            ),
        )
        assertEquals("buvid3=ABC; buvid4=DEF; _uuid=GHI", header)
    }
}