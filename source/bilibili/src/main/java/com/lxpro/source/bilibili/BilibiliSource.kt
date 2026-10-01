package com.lxpro.source.bilibili

import com.lxpro.core.model.LyricInfo
import com.lxpro.core.model.MusicUrl
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.source.api.SearchResult
import com.lxpro.source.api.SourceAction
import com.lxpro.source.api.SourceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置 B 站音源（唯一内置音源；04 §5 规格）。
 *
 * 取流**优先 durl 完整 MP4**，失败降级 DASH 纯音频 —— 保持前作验证过的优先级。
 */
@Singleton
class BilibiliSource @Inject constructor(
    private val client: OkHttpClient,
) : SourceApi {

    override val id: SourceId = SourceId("bi")
    override val name: String = "小哔音乐"
    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC)
    override val qualities: List<Quality> = listOf(Quality.K128, Quality.K320)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var mixinKey: String? = null

    private val cidCache = ConcurrentHashMap<String, Long>()

    // ── 搜索 ───────────────────────────────────────────────────────────────

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0, false, id)
        ensureCookie()

        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to limit.toString(),
        )

        // 优先走 WBI 签名接口（现行要求），失败回落旧路径
        val mixin = ensureMixinKey()
        var root = if (mixin != null) {
            getRawQuery("/x/web-interface/wbi/search/type", BilibiliApi.sign(params, mixin), BilibiliApi.HOME)
        } else {
            null
        }
        if (root.code() != 0) {
            root = getParams("/x/web-interface/search/type", params, BilibiliApi.HOME)
        }

        val data = root["data"]
        val items = data["result"].asArrayOrNull().orEmpty()
        val songs = items.mapNotNull { item ->
            val bvid = item["bvid"].asStringOrNull() ?: return@mapNotNull null
            val type = item["type"].asStringOrNull()
            if (type != null && type != "video") return@mapNotNull null
            Song(
                id = bvid,
                source = id,
                name = BilibiliApi.stripTags(item["title"].asStringOrNull()),
                singer = BilibiliApi.stripTags(item["author"].asStringOrNull()),
                picUrl = BilibiliApi.normalizeUrl(item["pic"].asStringOrNull()),
                interval = BilibiliApi.parseDuration(item["duration"].asStringOrNull()),
                raw = mapOf("bvid" to bvid),
            )
        }
        return SearchResult(
            list = songs,
            total = data["numResults"].asIntOrNull(),
            hasMore = songs.size >= limit,
            source = id,
        )
    }

    // ── 取流 ───────────────────────────────────────────────────────────────

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val bvid = song.id
        val cid = resolveCid(bvid) ?: error("B 站取流失败：拿不到 cid（bvid=$bvid）")
        val referer = BilibiliApi.videoReferer(bvid)

        // 1) 优先 durl 完整 MP4
        val durlRoot = getParams(
            "/x/player/playurl",
            mapOf("fnval" to "0", "bvid" to bvid, "cid" to cid.toString()),
            referer,
        )
        val durlUrl = durlRoot["data"]["durl"].asArrayOrNull()
            ?.firstOrNull()?.get("url").asStringOrNull()
        BilibiliApi.normalizeUrl(durlUrl)?.let { return MusicUrl(it, quality) }

        // 2) 降级 DASH 纯音频
        val dashRoot = getParams(
            "/x/player/playurl",
            mapOf("fnval" to "16", "fnver" to "0", "fourk" to "1", "bvid" to bvid, "cid" to cid.toString()),
            referer,
        )
        val data = dashRoot["data"]
        val candidates = mutableListOf<Pair<Long, String>>()

        data["flac"]["audio"].audioCandidate()?.let { candidates += it }
        data["dolby"]["audio"].asArrayOrNull().orEmpty().forEach { item ->
            item.audioCandidate()?.let { candidates += it }
        }
        data["audio"].asArrayOrNull().orEmpty().forEach { item ->
            item.audioCandidate()?.let { candidates += it }
        }

        val picked = candidates.maxByOrNull { it.first }
            ?: error("B 站取流失败：durl 与 DASH 均无可用音轨（bvid=$bvid）")
        return MusicUrl(BilibiliApi.normalizeUrl(picked.second)!!, quality)
    }

    private fun JsonElement?.audioCandidate(): Pair<Long, String>? {
        if (this == null) return null
        val url = this["baseUrl"].asStringOrNull()
            ?: this["base_url"].asStringOrNull()
            ?: this["url"].asStringOrNull()
            ?: return null
        val bandwidth = this["bandwidth"].asLongOrNull() ?: 0L
        return bandwidth to url
    }

    private suspend fun resolveCid(bvid: String): Long? {
        cidCache[bvid]?.let { return it }
        val root = getParams("/x/web-interface/view", mapOf("bvid" to bvid), BilibiliApi.videoReferer(bvid))
        val cid = root["data"]["pages"].asArrayOrNull()?.firstOrNull()?.get("cid").asLongOrNull()
        if (cid != null) cidCache[bvid] = cid
        return cid
    }

    // ── 歌词（CC 字幕 → LRC）─────────────────────────────────────────────

    override suspend fun getLyric(song: Song): LyricInfo {
        val bvid = song.id
        val cid = resolveCid(bvid) ?: return LyricInfo(NO_LYRIC)
        val root = getParams(
            "/x/player/v2",
            mapOf("bvid" to bvid, "cid" to cid.toString()),
            BilibiliApi.videoReferer(bvid),
        )
        val subtitleUrl = root["data"]["subtitle"]["subtitles"]
            .asArrayOrNull()
            ?.firstOrNull()?.get("subtitle_url").asStringOrNull()
            ?: return LyricInfo(NO_LYRIC)
        val normalized = BilibiliApi.normalizeUrl(subtitleUrl) ?: return LyricInfo(NO_LYRIC)

        val body = runCatching {
            val request = Request.Builder().url(normalized)
                .header("User-Agent", BilibiliApi.UA)
                .header("Referer", BilibiliApi.videoReferer(bvid))
                .build()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { it.body?.string().orEmpty() }
            }
        }.getOrDefault("")
        if (body.isBlank()) return LyricInfo(NO_LYRIC)

        val cues = runCatching { json.parseToJsonElement(body)["body"].asArrayOrNull().orEmpty() }
            .getOrDefault(emptyList())
        val lrc = buildString {
            cues.forEach { cue ->
                val from = cue["from"].asDoubleOrNull() ?: return@forEach
                val content = cue["content"].asStringOrNull().orEmpty()
                append(toLrcLine(from)).append(content).append('\n')
            }
        }.ifBlank { NO_LYRIC }
        return LyricInfo(lrc)
    }

    private fun toLrcLine(seconds: Double): String {
        val totalSeconds = seconds.toInt()
        val minutes = totalSeconds / 60
        val secs = totalSeconds % 60
        val centis = Math.round((seconds - totalSeconds) * 100).toInt().coerceIn(0, 99)
        return "[%02d:%02d.%02d]".format(minutes, secs, centis)
    }

    override suspend fun getPic(song: Song): String? = song.picUrl

    override fun getDetailPageUrl(song: Song): String = "https://www.bilibili.com/video/${song.id}"

    // ── 风控 Cookie 与 WBI key ────────────────────────────────────────────

    private suspend fun ensureCookie() {
        if (!cookie.isNullOrEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(BilibiliApi.HOME)
                    .header("User-Agent", BilibiliApi.UA)
                    .build()
                client.newCall(request).execute().use { response ->
                    cookie = BilibiliApi.buildCookieHeader(response.headers.values("Set-Cookie"))
                }
            }
        }
    }

    private suspend fun ensureMixinKey(): String? {
        mixinKey?.let { return it }
        val root = runCatching { get("/x/web-interface/nav", BilibiliApi.HOME) }.getOrNull() ?: return null
        val imgUrl = root["data"]["wbi_img"]["img_url"].asStringOrNull() ?: return null
        val subUrl = root["data"]["wbi_img"]["sub_url"].asStringOrNull() ?: return null
        val imgKey = imgUrl.substringAfterLast('/').substringBeforeLast('.')
        val subKey = subUrl.substringAfterLast('/').substringBeforeLast('.')
        return BilibiliApi.getMixinKey(imgKey, subKey).also { mixinKey = it }
    }

    // ── HTTP 基座 ─────────────────────────────────────────────────────────

    private suspend fun getParams(path: String, params: Map<String, String>, referer: String): JsonElement {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${BilibiliApi.encodeUriComponent(v)}"
        }
        return getRawQuery(path, query, referer)
    }

    private suspend fun get(path: String, referer: String): JsonElement = getRawQuery(path, "", referer)

    private suspend fun getRawQuery(path: String, rawQuery: String, referer: String): JsonElement =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(BilibiliApi.BASE).append(path)
                if (rawQuery.isNotEmpty()) append('?').append(rawQuery)
            }
            val builder = Request.Builder().url(url)
                .header("User-Agent", BilibiliApi.UA)
                .header("Referer", referer)
            cookie?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("B 站接口返回空响应：$path (HTTP ${response.code})")
                json.parseToJsonElement(body)
            }
        }

    private fun JsonElement?.code(): Int? = this["code"].asIntOrNull()

    companion object {
        const val NO_LYRIC = "[00:00.00]暂无歌词"
    }
}

// ── JsonElement 安全访问（B 站字段随版本变动，用动态解析比 data class 稳）──

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.asIntOrNull(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonElement?.asLongOrNull(): Long? = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

internal fun JsonElement?.asDoubleOrNull(): Double? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray