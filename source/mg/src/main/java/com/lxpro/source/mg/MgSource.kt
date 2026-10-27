package com.lxpro.source.mg

import com.lxpro.core.model.CommentInfo
import com.lxpro.core.model.CommentResult
import com.lxpro.core.model.LyricInfo
import com.lxpro.core.model.MusicUrl
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.source.api.SearchResult
import com.lxpro.source.api.SourceAction
import com.lxpro.source.api.SourceApi
import com.lxpro.source.api.decodeEntities
import com.lxpro.source.api.get
import com.lxpro.source.api.jArray
import com.lxpro.source.api.jLong
import com.lxpro.source.api.jObj
import com.lxpro.source.api.jStr
import com.lxpro.source.script.ScriptSourceManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置咪咕音源（批十九，移植蓝本：walnut musicSdk/mg）。
 *
 * search / lyric / pic / comment 为内置实现；**取流委托给已导入的 lx 脚本**。
 * 歌词优先走 lrcUrl（纯文本），回退 web API（getLyric?copyrightId=），
 * 避免实现 mrc TEA 解密（逐字级别，后续可补）。
 */
@Singleton
class MgSource @Inject constructor(
    private val client: OkHttpClient,
    private val scriptManager: Lazy<ScriptSourceManager>,
) : SourceApi {

    override val id: SourceId = SourceId("mg")
    override val name: String = "咪咕"
    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC, SourceAction.COMMENT)
    override val qualities: List<Quality> = Quality.entries.toList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 搜索（jadeite.migu.cn，walnut mg/musicSearch.js）──────────────────────

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0, false, id)
        val time = System.currentTimeMillis().toString()
        val (sign, deviceId) = createSignature(time, keyword)
        val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll" +
            "?isCorrect=0&isCopyright=1" +
            "&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0" +
            "%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1" +
            "%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D" +
            "&pageSize=$limit&text=${encodeKeyword(keyword)}&pageNo=$page&sort=0&sid=USS"
        val headers = mapOf(
            "uiVersion" to "A_music_3.6.1",
            "deviceId" to deviceId,
            "timestamp" to time,
            "sign" to sign,
            "channel" to "0146921",
            "User-Agent" to "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30",
        )
        val root = runCatching { getJson(url, headers) }.getOrNull()
            ?: return SearchResult(emptyList(), 0, false, id)
        if (root["code"]?.jStr() != "000000")
            return SearchResult(emptyList(), 0, false, id)

        val songResultData = root["songResultData"]?.jObj()
            ?: return SearchResult(emptyList(), 0, false, id)
        val total = songResultData["totalCount"]?.jLong()?.toInt() ?: 0
        val resultList = songResultData["resultList"]?.jArray().orEmpty()

        // resultList 是数组的数组（walnut filterData: rawData.forEach(item => item.forEach(data => ...)))
        val seen = HashSet<String>()
        val songs = mutableListOf<Song>()
        for (inner in resultList) {
            for (data in inner.jArray().orEmpty()) {
                val songId = data["songId"]?.jStr() ?: continue
                val copyrightId = data["copyrightId"]?.jStr() ?: continue
                if (!seen.add(copyrightId)) continue
                songs += songOf(data)
            }
        }
        return SearchResult(songs, total, page * limit < total, id)
    }

    private fun songOf(data: JsonElement): Song {
        val (qualitys, sizes) = parseAudioFormats(data["audioFormats"]?.jArray().orEmpty())
        var img = data["img3"]?.jStr() ?: data["img2"]?.jStr() ?: data["img1"]?.jStr()
        if (img != null && !img.startsWith("http")) img = "http://d.musicapp.migu.cn$img"
        return Song(
            id = data["songId"]?.jStr().orEmpty(),
            source = id,
            name = decodeEntities(data["name"]?.jStr().orEmpty()),
            singer = formatSinger(data["singerList"]?.jArray().orEmpty()),
            albumName = data["album"]?.jStr()?.let(::decodeEntities),
            albumId = data["albumId"]?.jStr(),
            picUrl = img,
            interval = data["duration"]?.jLong(),
            qualitys = qualitys,
            qualitySizes = sizes,
            raw = mapOf(
                "copyrightId" to (data["copyrightId"]?.jStr().orEmpty()),
                "songId" to (data["songId"]?.jStr().orEmpty()),
                "lrcUrl" to (data["lrcUrl"]?.jStr() ?: ""),
                "mrcUrl" to (data["mrcurl"]?.jStr() ?: ""),
                "trcUrl" to (data["trcUrl"]?.jStr() ?: ""),
            ),
        )
    }

    private fun parseAudioFormats(formats: List<JsonElement>): Pair<List<Quality>, Map<String, Long>> {
        val qualitys = mutableListOf<Quality>()
        val sizes = mutableMapOf<String, Long>()
        for (fmt in formats) {
            val size = fmt["asize"]?.jLong() ?: fmt["isize"]?.jLong() ?: 0L
            val quality = when (fmt["formatType"]?.jStr()) {
                "PQ" -> Quality.K128
                "HQ" -> Quality.K320
                "SQ" -> Quality.FLAC
                "ZQ", "ZQ24" -> Quality.HIRES
                else -> null
            } ?: continue
            if (quality !in qualitys) {
                qualitys += quality
                sizes[quality.value] = size
            }
        }
        qualitys.reverse()
        return qualitys to sizes
    }

    private fun formatSinger(singers: List<JsonElement>): String =
        singers.joinToString("、") { decodeEntities(it["name"]?.jStr().orEmpty()) }

    // ── 取流（委托脚本）──────────────────────────────────────────────────────

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val api = scriptManager.get().apiForSource(id.value)
            ?: throw IllegalStateException("未导入支持${name}的音源脚本")
        return api.getMusicUrl(song, quality)
    }

    // ── 歌词（lrcUrl 纯文本优先，回退 web API，walnut mg/lyric.js）─────────────

    override suspend fun getLyric(song: Song): LyricInfo {
        val lrcUrl = song.raw["lrcUrl"]?.takeIf { it.isNotBlank() }
        val trcUrl = song.raw["trcUrl"]?.takeIf { it.isNotBlank() }
        val copyrightId = song.raw["copyrightId"].orEmpty()

        // 1. lrcUrl → 纯文本歌词
        val lyric = if (lrcUrl != null) {
            runCatching { getString(lrcUrl, mgHeaders) }.getOrNull()?.trim().orEmpty()
        } else {
            // 2. 回退 web API
            val webUrl = "https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=$copyrightId"
            val root = runCatching { getJson(webUrl, mapOf("Referer" to "https://music.migu.cn/v3/music/player/audio?from=migu")) }.getOrNull()
            if (root?.get("returnCode")?.jStr() == "000000") root["lyric"]?.jStr().orEmpty() else ""
        }
        if (lyric.isBlank()) return LyricInfo("")

        // 3. 翻译歌词
        val tlyric = trcUrl?.let { runCatching { getString(it, mgHeaders) }.getOrNull()?.trim() }
        return LyricInfo(lyric, tlyric?.ifBlank { null })
    }

    // ── 封面（music.migu.cn getSongPic，walnut mg/pic.js）─────────────────────

    override suspend fun getPic(song: Song): String? {
        song.picUrl?.takeIf { it.startsWith("http") }?.let { return it }
        val songId = song.raw["songId"]?.takeIf { it.isNotBlank() } ?: return null
        val url = "http://music.migu.cn/v3/api/music/audioPlayer/getSongPic?songId=$songId"
        val root = runCatching {
            getJson(url, mapOf("Referer" to "http://music.migu.cn/v3/music/player/audio?from=migu"))
        }.getOrNull() ?: return null
        if (root["returnCode"]?.jStr() != "000000") return null
        val pic = root["largePic"]?.jStr() ?: root["mediumPic"]?.jStr() ?: root["smallPic"]?.jStr()
        return pic?.let { if (it.startsWith("http")) it else "http:$it" }
    }

    // ── 评论（MIGUM3.0 comment stack，walnut mg/comment.js）───────────────────

    override suspend fun getComment(song: Song, page: Int, limit: Int): CommentResult {
        val songId = song.raw["songId"]?.takeIf { it.isNotBlank() } ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val url = "https://app.c.nf.migu.cn/MIGUM3.0/user/comment/stack/v1.0" +
            "?pageSize=$limit&queryType=1&resourceId=$songId&resourceType=2&commentId="
        val root = runCatching { getJson(url, commentHeaders) }.getOrNull()
            ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["code"]?.jStr() != "000000") return CommentResult(id, emptyList(), 0, page, limit, 1)
        val data = root["data"] ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = data["commentNums"]?.jStr()?.toIntOrNull() ?: 0
        val comments = filterComment(data["comments"]?.jArray().orEmpty())
        return CommentResult(id, comments, total, page, limit, (total + limit - 1) / limit)
    }

    override suspend fun getHotComment(song: Song, page: Int, limit: Int): CommentResult {
        val songId = song.raw["songId"]?.takeIf { it.isNotBlank() } ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val url = "https://app.c.nf.migu.cn/MIGUM3.0/user/comment/stack/v1.0" +
            "?pageSize=$limit&queryType=2&resourceId=$songId&resourceType=2" +
            "&hotCommentStart=${(page - 1) * limit}"
        val root = runCatching { getJson(url, commentHeaders) }.getOrNull()
            ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["code"]?.jStr() != "000000") return CommentResult(id, emptyList(), 0, page, limit, 1)
        val data = root["data"] ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = data["cfgHotCount"]?.jStr()?.toIntOrNull() ?: 0
        val comments = filterComment(data["hotComments"]?.jArray().orEmpty())
        return CommentResult(id, comments, total, page, limit, (total + limit - 1) / limit)
    }

    private fun filterComment(rawList: List<JsonElement>): List<CommentInfo> =
        rawList.map { item ->
            CommentInfo(
                id = item["commentId"]?.jStr().orEmpty(),
                text = item["commentInfo"]?.jStr().orEmpty(),
                time = item["commentTime"]?.jLong(),
                timeStr = item["commentTime"]?.jLong()?.toString(),
                userName = item["user"]?.get("nickName")?.jStr().orEmpty(),
                avatar = item["user"]?.get("middleIcon")?.jStr()
                    ?: item["user"]?.get("bigIcon")?.jStr()
                    ?: item["user"]?.get("smallIcon")?.jStr(),
                userId = item["user"]?.get("userId")?.jStr(),
                likedCount = item["opNumItem"]?.get("thumbNum")?.jLong(),
                replyNum = item["replyTotalCount"]?.jLong(),
                reply = item["replyComments"]?.jArray().orEmpty().map { c ->
                    CommentInfo(
                        id = c["replyId"]?.jStr().orEmpty(),
                        text = c["replyInfo"]?.jStr().orEmpty(),
                        time = c["replyTime"]?.jLong(),
                        timeStr = c["replyTime"]?.jLong()?.toString(),
                        userName = c["user"]?.get("nickName")?.jStr().orEmpty(),
                        avatar = c["user"]?.get("middleIcon")?.jStr(),
                        userId = c["user"]?.get("userId")?.jStr(),
                    )
                },
            )
        }

    override fun getDetailPageUrl(song: Song): String? =
        "http://music.migu.cn/v3/music/song/${song.raw["copyrightId"].orEmpty()}"

    // ── 网络与编解码 ────────────────────────────────────────────────────────

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }

    private suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        }

    private fun encodeKeyword(kw: String): String = URLEncoder.encode(kw, "UTF-8")

    /** mg 签名（walnut mg/musicSearch.js createSignature） */
    private fun createSignature(time: String, str: String): Pair<String, String> {
        val deviceId = "963B7AA0D21511ED807EE5846EC87D20"
        val signatureMd5 = "6cdc72a439cef99a3418d2a78aa28c73"
        val sign = md5("$str${signatureMd5}yyapp2d16148780a1dcc7408e06336b98cfd50${deviceId}$time")
        return sign to deviceId
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val mgHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36",
            "channel" to "0146921",
        )
        private val commentHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1",
        )
    }
}
