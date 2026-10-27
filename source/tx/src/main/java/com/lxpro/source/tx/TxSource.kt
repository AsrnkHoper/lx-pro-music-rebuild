package com.lxpro.source.tx

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
import com.lxpro.source.api.jInt
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置 QQ 音乐音源（批十八，移植蓝本：walnut musicSdk/tx）。
 *
 * search / lyric / pic 为内置实现；**取流委托给已导入的 lx 脚本** —— 与 walnut
 * 一致：内置源只有数据接口，getMusicUrl 走 user api 脚本（未导入脚本时抛错提示）。
 */
@Singleton
class TxSource @Inject constructor(
    private val client: OkHttpClient,
    private val scriptManager: Lazy<ScriptSourceManager>,
) : SourceApi {

    override val id: SourceId = SourceId("tx")
    override val name: String = "QQ音乐"
    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC)
    override val qualities: List<Quality> = Quality.entries.toList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 搜索（u.y.qq.com musics.fcg + zzcSign，walnut tx/musicSearch.js）──────

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0, false, id)
        // walnut：双重 code 校验失败 / 异常 → 重试，≤6 次
        repeat(6) {
            val body = buildSearchBody(keyword, page, limit)
            val root = runCatching {
                postJson("https://u.y.qq.com/cgi-bin/musics.fcg?sign=${zzcSign(body)}", body)
            }.onFailure {
                android.util.Log.e("TxSource", "tx search attempt failed", it)
            }.getOrNull() ?: return@repeat
            if (root["code"].jInt() != 0 || root["req"]["code"].jInt() != 0) {
                android.util.Log.e("TxSource", "tx code check failed: ${root.toString().take(300)}")
                return@repeat
            }

            val data = root["req"]["data"]
            val songs = data["body"]["item_song"].jArray().orEmpty().mapNotNull(::songOf)
            val total = data["meta"]["estimate_sum"].jInt() ?: songs.size
            return SearchResult(songs, total, page * limit < total, id)
        }
        throw IllegalStateException("QQ音乐搜索失败")
    }

    private fun songOf(item: JsonElement): Song? {
        val file = item["file"].jObj() ?: return null
        val mediaMid = file["media_mid"].jStr() ?: return null
        val albumMid = item["album"]["mid"].jStr().orEmpty()
        val picUrl = if (albumMid.isEmpty() || albumMid == "空") {
            item["singer"][0]["mid"].jStr()
                ?.let { "https://y.gtimg.cn/music/photo_new/T001R500x500M000$it.jpg" }
        } else {
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
        }

        val qualitys = mutableListOf<Quality>()
        val sizes = mutableMapOf<String, Long>()
        fun addQuality(quality: Quality, size: Long?) {
            if (size != null && size != 0L) {
                qualitys += quality
                sizes[quality.value] = size
            }
        }
        addQuality(Quality.K128, file["size_128mp3"].jLong())
        addQuality(Quality.K320, file["size_320mp3"].jLong())
        addQuality(Quality.FLAC, file["size_flac"].jLong())
        addQuality(Quality.HIRES, file["size_hires"].jLong())
        addQuality(Quality.ATMOS, file["size_new"][1].jLong())
        addQuality(Quality.ATMOS_PLUS, file["size_new"][2].jLong())
        addQuality(Quality.MASTER, file["size_new"][0].jLong())

        return Song(
            id = item["mid"].jStr() ?: return null,
            source = id,
            name = item["title"].jStr().orEmpty(),
            singer = item["singer"].jArray().orEmpty()
                .mapNotNull { it["name"].jStr() }.joinToString("、"),
            albumName = item["album"]["name"].jStr(),
            albumId = albumMid.ifEmpty { null },
            picUrl = picUrl,
            interval = item["interval"].jLong(),
            qualitys = qualitys,
            qualitySizes = sizes,
            raw = mapOf(
                "songId" to item["id"].jStr().orEmpty(),
                "strMediaMid" to mediaMid,
                "albumMid" to albumMid,
            ),
        )
    }

    private fun buildSearchBody(keyword: String, page: Int, limit: Int): String = buildJsonObject {
        putJsonObject("comm") {
            put("ct", "11")
            put("cv", "14090508")
            put("v", "14090508")
            put("tmeAppID", "qqmusic")
            put("phonetype", "EBG-AN10")
            put("deviceScore", "553.47")
            put("devicelevel", "50")
            put("newdevicelevel", "20")
            put("rom", "HuaWei/EMOTION/EmotionUI_14.2.0")
            put("os_ver", "12")
            put("OpenUDID", "0")
            put("OpenUDID2", "0")
            put("QIMEI36", "0")
            put("udid", "0")
            put("chid", "0")
            put("aid", "0")
            put("oaid", "0")
            put("taid", "0")
            put("tid", "0")
            put("wid", "0")
            put("uid", "0")
            put("sid", "0")
            put("modeSwitch", "6")
            put("teenMode", "0")
            put("ui_mode", "2")
            put("nettype", "1020")
            put("v4ip", "")
        }
        putJsonObject("req") {
            put("module", "music.search.SearchCgiService")
            put("method", "DoSearchForQQMusicMobile")
            putJsonObject("param") {
                put("search_type", 0)
                put(
                    "searchid",
                    ThreadLocalRandom.current()
                        .nextLong(1_000_000_000_000L, 10_000_000_000_000L).toString(),
                )
                put("query", keyword)
                put("page_num", page)
                put("num_per_page", limit)
                put("highlight", 0)
                put("nqc_flag", 0)
                put("multi_zhida", 0)
                put("cat", 2)
                put("grp", 1)
                put("sin", 0)
                put("sem", 0)
            }
        }
    }.toString()

    // ── 取流（委托给脚本源）─────────────────────────────────────────────────

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val api = scriptManager.get().apiForSource(id.value)
            ?: throw IllegalStateException("未导入支持${name}的音源脚本")
        return api.getMusicUrl(song, quality)
    }

    // ── 歌词（c.y.qq.com fcg_query_lyric_new，walnut tx/lyric.js）─────────────

    override suspend fun getLyric(song: Song): LyricInfo {
        val root = runCatching {
            getJson(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${song.id}" +
                    "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
                    "&outCharset=utf-8&platform=yqq",
                referer = "https://y.qq.com/portal/player.html",
            )
        }.getOrNull() ?: return LyricInfo("")
        val lyricB64 = root["lyric"].jStr()
        if (root["code"].jInt() != 0 || lyricB64.isNullOrEmpty()) return LyricInfo("")
        val lyric = runCatching { decodeEntities(base64ToUtf8(lyricB64)) }.getOrNull()
            ?: return LyricInfo("")
        val tlyric = root["trans"].jStr()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { decodeEntities(base64ToUtf8(it)) }.getOrNull() }
        return LyricInfo(lyric, tlyric)
    }

    // ── 封面 ───────────────────────────────────────────────────────────────

    override suspend fun getPic(song: Song): String? {
        song.picUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val albumMid = song.raw["albumMid"].orEmpty()
        return if (albumMid.isEmpty()) {
            null
        } else {
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
        }
    }

    // ── 评论（QQ 音乐评论需 songId 解析，暂未实现）──────────────────────────

    override suspend fun getComment(song: Song, page: Int, limit: Int): CommentResult =
        CommentResult(id, emptyList(), 0, page, limit, 1)

    override suspend fun getHotComment(song: Song, page: Int, limit: Int): CommentResult =
        CommentResult(id, emptyList(), 0, page, limit, 1)

    override fun getDetailPageUrl(song: Song): String? = null

    // ── 网络 ───────────────────────────────────────────────────────────────

    private suspend fun getJson(url: String, referer: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            if (referer != null) builder.header("Referer", referer)
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }

    private suspend fun postJson(url: String, body: String): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", "QQMusic 14090508(android 12)")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
        private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
        private val SCRAMBLE_VALUES = intArrayOf(
            89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
            186, 123, 120, 64, 242, 133, 143, 161, 121, 179,
        )
        private val SIGN_STRIP_EXP = Regex("[\\\\/+=]")

        private fun sha1Hex(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun base64ToUtf8(value: String): String =
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)

        /** walnut tx/utils/crypto.js zzcSign —— 逐行对齐 */
        internal fun zzcSign(text: String): String {
            val hash = sha1Hex(text)
            // PART_1 含下标 40（越界）：JS join 会把 undefined 转空串 → 越界下标直接丢弃
            val part1 = PART_1_INDEXES.filter { it < hash.length }.map { hash[it] }.joinToString("")
            val part2 = PART_2_INDEXES.filter { it < hash.length }.map { hash[it] }.joinToString("")
            val part3 = ByteArray(SCRAMBLE_VALUES.size) { i ->
                (SCRAMBLE_VALUES[i] xor hash.substring(i * 2, i * 2 + 2).toInt(16)).toByte()
            }
            val b64 = Base64.getEncoder().encodeToString(part3).replace(SIGN_STRIP_EXP, "")
            return "zzc$part1$b64$part2".lowercase()
        }
    }
}
