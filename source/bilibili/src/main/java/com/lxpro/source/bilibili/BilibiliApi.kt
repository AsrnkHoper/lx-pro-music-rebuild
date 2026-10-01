package com.lxpro.source.bilibili

import java.security.MessageDigest
import java.util.Locale

/**
 * B 站音源算法规格（依据 04 §5，从前作 `LX_Pro_BiliBili` 的 JS 实现提炼，**不是抄代码**）。
 */
object BilibiliApi {

    const val BASE = "https://api.bilibili.com"
    const val HOME = "https://www.bilibili.com/"
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 播放/接口必须带视频页 Referer，否则 CDN 403 */
    fun videoReferer(bvid: String) = "https://www.bilibili.com/video/$bvid"

    /** WBI 签名重排表（64 项） */
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52,
    )

    /** 用重排表把 imgKey+subKey 压成 32 位 mixinKey（04 §5.8 第 2 步） */
    fun getMixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in 0 until 32) {
            val index = MIXIN_KEY_ENC_TAB[i]
            if (index < raw.length) sb.append(raw[index])
        }
        return sb.toString()
    }

    /**
     * 按 WBI 规则给参数签名（04 §5.8 第 3–6 步）。
     * ⚠️ 值需 encodeURIComponent 语义，并**移除 `!'()*`**；key 按字典序排序。
     */
    fun sign(params: Map<String, String>, mixinKey: String, wts: Long = System.currentTimeMillis() / 1000): String {
        val withWts = params.toMutableMap().apply { put("wts", wts.toString()) }
        val query = withWts.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${encodeUriComponent(v)}" }
        return "$query&w_rid=${md5(query + mixinKey)}"
    }

    /** B 站搜索关键词高亮标签形如 `<em class="keyword">xx</em>`，必须剥掉 */
    fun stripTags(input: String?): String =
        input.orEmpty().replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()

    /** 补全协议头：`//x` → `https://x`，`http://` → `https://` */
    fun normalizeUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
    }

    /** B 站 duration 形如 "4:03" 或 "1:02:03" */
    fun parseDuration(raw: Any?): Long? {
        val text = when (raw) {
            is Number -> return raw.toLong()
            is String -> raw
            else -> return null
        }
        val parts = text.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) return null
        var seconds = 0L
        for (part in parts) seconds = seconds * 60 + part
        return seconds
    }

    /**
     * `encodeURIComponent` 的等价实现。
     *
     * ⚠️ 两个必须对齐 JS 的细节（否则 w_rid 与浏览器端不一致、签名校验失败）：
     * 1. JS 的 encodeURIComponent **不转义** `!'()*`，因此这里也要原样保留（随后再删掉）；
     * 2. 只能用 **ASCII** 判断是否免转义 —— 若拿 `byte.toInt().toChar()` 直接判字母，
     *    高位字节（如 0xC3 → 'Ã'）会被误判成字母，UTF-8 中文会被原样吐出。
     */
    fun encodeUriComponent(value: String): String = buildString {
        for (raw in value.toByteArray(Charsets.UTF_8)) {
            val b = raw.toInt() and 0xFF
            val ch = b.toChar()
            val unreserved = b in 0x61..0x7A || b in 0x41..0x5A || b in 0x30..0x39 ||
                ch in "-_.~" || ch in "!'()*"
            if (unreserved) {
                append(ch)
            } else {
                append('%').append(String.format(Locale.ROOT, "%02X", b))
            }
        }
    }.replace(Regex("[!'()*]"), "")

    fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 从 Set-Cookie 头里挑出风控所需字段，拼成 Cookie 请求头（04 §5.3） */
    fun buildCookieHeader(setCookieHeaders: List<String>): String {
        val wanted = listOf("buvid3", "buvid4", "b_nut", "_uuid")
        val jar = LinkedHashMap<String, String>()
        setCookieHeaders.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name in wanted && value.isNotEmpty()) jar[name] = value
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}