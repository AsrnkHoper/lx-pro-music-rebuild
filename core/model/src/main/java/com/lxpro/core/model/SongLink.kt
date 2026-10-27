package com.lxpro.core.model

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * 歌曲软链接：两种协议。
 *
 * 1. `lxpro://song/<Base64Url(歌曲 JSON)>` —— 单曲直接播放（在线歌曲带完整 source+raw，离线粘进搜索框即播）。
 * 2. `lxpro://share?title=...&artist=...&t=毫秒时间戳&tag=...` —— 分享协议（跨音源匹配，
 *    不嵌 source/raw，收方用歌名+艺术家在已启用音源里搜索最接近的结果，然后自动 seek 到 t）。
 *
 * 为什么分享协议只用歌名+艺术家+时间戳+标签：
 * - 分享者用的音源收方不一定启用，嵌 source/raw 反而匹配失败；
 * - 歌名+艺术家 是通用的跨源匹配 key，所有音源都支持。
 */
object SongLink {

    /** 单曲直接播放协议前缀 */
    const val PREFIX = "lxpro://song/"

    /** 分享协议前缀（URL scheme + host，不是 path 前缀） */
    const val SHARE_SCHEME = "lxpro"
    const val SHARE_HOST = "share"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 生成单曲直接播放软链接（URL 安全 Base64、无填充） */
    fun encode(song: Song): String {
        val payload = json.encodeToString(Song.serializer(), song).toByteArray(Charsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        return PREFIX + encoded
    }

    /** 解析单曲直接播放软链接；不是本应用的链接或内容损坏都返回 null */
    fun decode(raw: String): Song? {
        val text = raw.trim()
        if (!text.startsWith(PREFIX)) return null
        val encoded = text.removePrefix(PREFIX).trim()
        if (encoded.isEmpty()) return null
        return runCatching {
            val payload = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            json.decodeFromString(Song.serializer(), payload)
        }.getOrNull()?.takeIf { it.id.isNotBlank() }
    }

    /** 分享链接的数据载荷（歌名+艺术家+时间戳+标签文本） */
    data class SharePayload(
        val title: String,
        val artist: String,
        /** 要播放的时间戳（毫秒），歌词分享时取 LyricLine.timeMs */
        val timestampMs: Long = 0L,
        /** 分享者贴的标签文本（可为空） */
        val tag: String = "",
    )

    /** 生成分享协议链接（lxpro://share?title=...&artist=...&t=...&tag=...） */
    fun encodeShare(payload: SharePayload): String = buildString {
        append(SHARE_SCHEME).append("://").append(SHARE_HOST)
        append("?title=").append(urlEncode(payload.title))
        append("&artist=").append(urlEncode(payload.artist))
        if (payload.timestampMs > 0) append("&t=").append(payload.timestampMs)
        if (payload.tag.isNotBlank()) append("&tag=").append(urlEncode(payload.tag))
    }

    /**
     * 解析分享协议链接；不是 lxpro://share 或缺少 title/artist 返回 null。
     *
     * ⚠️ 不用 `android.net.Uri`：它是框架类，纯 JVM 单测里不可用，且对自定义 scheme
     * 的 authority 解析行为不直观。这里手写 query 解析，逻辑可测、行为确定。
     */
    fun decodeShare(raw: String): SharePayload? {
        val text = raw.trim()
        val prefix = "$SHARE_SCHEME://$SHARE_HOST"
        if (!text.startsWith(prefix)) return null
        val rest = text.substring(prefix.length)
        // 允许 lxpro://share?... 与 lxpro://share/?... 两种写法
        if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/') return null
        val query = rest.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
        val title = params["title"]?.let(::urlDecode)?.trim().orEmpty()
        val artist = params["artist"]?.let(::urlDecode)?.trim().orEmpty()
        if (title.isBlank()) return null
        val t = params["t"]?.toLongOrNull() ?: 0L
        val tag = params["tag"]?.let(::urlDecode).orEmpty()
        return SharePayload(title, artist, t, tag)
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun urlDecode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /** 识别任意软链接字符串——可能是 song 直接播、也可能是 share 分享 */
    fun decodeAny(raw: String): Any? = decode(raw) ?: decodeShare(raw)

    /**
     * 从一段自由文本里提取 `lxpro://` 链接。
     *
     * 分享出去的是一整句邀请语，例如：
     * `我在阿玉音乐听《晴天-周杰伦》，复制前往阿玉音乐聆听！lxpro://share?title=...`
     * 用户把整句粘进搜索框时，必须先把末尾那段链接抠出来，否则整句会被当成关键词去搜。
     *
     * 截断规则：到第一个空白、换行或**中文标点**为止。
     * ⚠️ 绝不能把 `:` `?` `=` `&` `/` 这类 ASCII 标点当断点——它们都是 URL 自身的组成部分
     * （曾经把它们写进断点表，导致 `lxpro://share` 被截成 `lxpro`，整条分享链路失效）。
     */
    fun extractLinkFromText(text: String): String? {
        val start = text.indexOf("$SHARE_SCHEME://")
        if (start < 0) return null
        val rest = text.substring(start)
        val end = rest.indexOfFirst { ch -> ch.isWhitespace() || ch in CN_STOP_CHARS }
        val link = if (end < 0) rest else rest.substring(0, end)
        return link.trimEnd().takeIf { it.length > "$SHARE_SCHEME://".length }
    }

    /** URL 里不会出现的中文标点——用作链接结束的判定 */
    private val CN_STOP_CHARS = charArrayOf(
        '，', '。', '！', '？', '、', '；', '：', '「', '」', '“', '”', '‘', '’',
        '（', '）', '【', '】', '《', '》', '…', '—',
    )
}
