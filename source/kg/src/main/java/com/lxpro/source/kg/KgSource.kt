package com.lxpro.source.kg

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
import com.lxpro.source.api.jStr
import com.lxpro.source.script.ScriptSourceManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置酷狗音源（批十九，移植蓝本：walnut musicSdk/kg）。
 *
 * search / lyric / pic / comment 为内置实现；**取流委托给已导入的 lx 脚本**。
 */
@Singleton
class KgSource @Inject constructor(
    private val client: OkHttpClient,
    private val scriptManager: Lazy<ScriptSourceManager>,
) : SourceApi {

    override val id: SourceId = SourceId("kg")
    override val name: String = "酷狗"
    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC, SourceAction.COMMENT)
    override val qualities: List<Quality> = Quality.entries.toList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 搜索（songsearch.kugou.com，walnut kg/musicSearch.js）──────────────────

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0, false, id)
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=" +
            "${encodeKeyword(keyword)}&page=$page&pagesize=$limit" +
            "&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1" +
            "&privilege_filter=0&area_code=1"

        val root = runCatching { getJson(url) }.getOrNull()
            ?: return SearchResult(emptyList(), 0, false, id)
        if (root["error_code"]?.jStr() != "0" && root["error_code"]?.jLong() != 0L)
            return SearchResult(emptyList(), 0, false, id)

        val data = root["data"] ?: return SearchResult(emptyList(), 0, false, id)
        val total = data["total"]?.jLong()?.toInt() ?: 0
        val lists = data["lists"]?.jArray().orEmpty()

        // 去重：Audioid + FileHash（含 Grp 子项）
        val seen = HashSet<String>()
        val items = mutableListOf<JsonElement>()
        for (item in lists) {
            val audioId = item["Audioid"]?.jStr().orEmpty()
            val fileHash = item["FileHash"]?.jStr().orEmpty()
            val key = audioId + fileHash
            if (seen.add(key)) items += item
            for (child in item["Grp"]?.jArray().orEmpty()) {
                val ck = child["Audioid"]?.jStr().orEmpty() + child["FileHash"]?.jStr().orEmpty()
                if (seen.add(ck)) items += child
            }
        }

        // 批量取音质信息
        val hashList = items.mapNotNull { it["FileHash"]?.jStr() }
        val qualityMap = getBatchQuality(hashList)

        val songs = items.map { item -> songOf(item, qualityMap) }
        return SearchResult(songs, total, page * limit < total, id)
    }

    private fun songOf(item: JsonElement, qualityMap: Map<String, Pair<List<Quality>, Map<String, Long>>>): Song {
        val hash = item["FileHash"]?.jStr().orEmpty()
        val (qualitys, sizes) = qualityMap[hash] ?: (emptyList<Quality>() to emptyMap())
        val singers = item["Singers"]?.jArray().orEmpty()
        val singer = singers.joinToString("、") { decodeEntities(it["name"]?.jStr().orEmpty()) }
        return Song(
            id = item["Audioid"]?.jStr().orEmpty(),
            source = id,
            name = decodeEntities(item["SongName"]?.jStr().orEmpty()),
            singer = singer,
            albumName = item["AlbumName"]?.jStr()?.let(::decodeEntities),
            albumId = item["AlbumID"]?.jStr(),
            picUrl = item["Image"]?.jStr()?.replace("{size}", "480"),
            interval = item["Duration"]?.jLong(),
            qualitys = qualitys,
            qualitySizes = sizes,
            raw = mapOf("hash" to hash),
        )
    }

    /** 批量音质信息（gateway.kugou.com，walnut kg/quality_detail.js） */
    private suspend fun getBatchQuality(hashList: List<String>): Map<String, Pair<List<Quality>, Map<String, Long>>> {
        if (hashList.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("behavior", "play")
            put("clientver", "20049")
            put("area_code", "1")
            put("quality", "128")
            put("qualities", buildJsonArray {
                listOf("128", "320", "flac", "high", "dolby", "viper_atmos", "viper_tape", "viper_clear").forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            })
            put("resource", buildJsonArray {
                hashList.forEach { h ->
                    add(buildJsonObject { put("id", 0); put("type", "audio"); put("hash", h) })
                }
            })
        }
        val url = "https://gateway.kugou.com/goodsmstore/v1/get_res_privilege" +
            "?appid=1005&clientver=20049&clienttime=${System.currentTimeMillis()}&mid=NeZha"
        val root = runCatching {
            postJson(url, body.toString(), mapOf("624868746c" to "624868746c"))
        }.getOrNull() ?: return emptyMap()
        if (root["error_code"]?.jStr() != "0" && root["error_code"]?.jLong() != 0L) return emptyMap()

        val result = mutableMapOf<String, Pair<List<Quality>, Map<String, Long>>>()
        val dataArr = root["data"]?.jArray().orEmpty()
        for ((index, songData) in dataArr.withIndex()) {
            val hash = hashList.getOrNull(index) ?: continue
            val relate = songData["relate_goods"]?.jArray().orEmpty()
            val qualitys = mutableListOf<Quality>()
            val sizes = mutableMapOf<String, Long>()
            for (q in relate) {
                val quality = when (q["quality"]?.jStr()) {
                    "128" -> Quality.K128
                    "320" -> Quality.K320
                    "flac" -> Quality.FLAC
                    "high" -> Quality.HIRES
                    "viper_clear" -> Quality.MASTER
                    "viper_atmos" -> Quality.ATMOS
                    else -> null
                } ?: continue
                if (quality !in qualitys) {
                    qualitys += quality
                    sizes[quality.value] = q["info"]?.get("filesize")?.jLong() ?: 0L
                }
            }
            qualitys.reverse()
            result[hash] = qualitys to sizes
        }
        return result
    }

    // ── 取流（委托脚本）──────────────────────────────────────────────────────

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val api = scriptManager.get().apiForSource(id.value)
            ?: throw IllegalStateException("未导入支持${name}的音源脚本")
        return api.getMusicUrl(song, quality)
    }

    // ── 歌词（2 步：search → download，krc 需 XOR+inflate，walnut kg/lyric.js）──

    override suspend fun getLyric(song: Song): LyricInfo {
        val hash = song.raw["hash"] ?: return LyricInfo("")
        val time = song.interval ?: 0L
        // Step 1: search lyric
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=${encodeKeyword(song.name)}&hash=$hash&timelength=$time&lrctxt=1"
        val searchRoot = runCatching { getJson(searchUrl, kgHeaders) }.getOrNull()
            ?: return LyricInfo("")
        val candidates = searchRoot["candidates"]?.jArray().orEmpty()
        if (candidates.isEmpty()) return LyricInfo("")
        val info = candidates[0]
        val lid = info["id"]?.jStr() ?: return LyricInfo("")
        val accessKey = info["accesskey"]?.jStr() ?: return LyricInfo("")
        val krcType = info["krctype"]?.jStr()?.toIntOrNull() ?: 0
        val fmt = if (krcType == 1 && info["contenttype"]?.jStr() != "1") "krc" else "lrc"

        // Step 2: download lyric
        val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=$lid&accesskey=$accessKey&fmt=$fmt&charset=utf8"
        val dlRoot = runCatching { getJson(dlUrl, kgHeaders) }.getOrNull()
            ?: return LyricInfo("")
        val content = dlRoot["content"]?.jStr() ?: return LyricInfo("")
        val actualFmt = dlRoot["fmt"]?.jStr() ?: fmt

        return when (actualFmt) {
            "krc" -> {
                val decoded = decodeKrc(content) ?: return LyricInfo("")
                parseKrcLyric(decoded)
            }
            else -> LyricInfo(
                lyric = runCatching { String(Base64.getDecoder().decode(content), Charsets.UTF_8) }
                    .getOrDefault("")
            )
        }
    }

    /** krc 解密：base64 → 去 4 字节头 → XOR enc_key → zlib inflate（walnut kg/util.js decodeLyric） */
    private fun decodeKrc(content: String): String? {
        val raw = runCatching { Base64.getDecoder().decode(content) }.getOrNull() ?: return null
        if (raw.size <= 4) return null
        val buf = ByteArray(raw.size - 4) { raw[it + 4] }
        for (i in buf.indices) buf[i] = (buf[i].toInt() xor ENC_KEY[i % ENC_KEY.size].toInt()).toByte()
        return inflate(buf)
    }

    /**
     * krc 歌词解析（walnut kg/lyric.js parseLyric）：
     * - 去除 [id:$xxx] 头
     * - 提取 [language:base64] 翻译（type 0=rlyric, 1=tlyric）
     * - 转换逐字时间戳 [(ms,count)] → [mm:ss.ms]
     * - 清理 <word,timing> 标签
     */
    private fun parseKrcLyric(str: String): LyricInfo {
        var s = str.replace("\r", "")
        if (HEAD_EXP.containsMatchIn(s)) s = HEAD_EXP.replace(s, "")

        var rlyric: List<String>? = null
        var tlyric: List<String>? = null
        val transMatch = LANG_EXP.find(s)
        if (transMatch != null) {
            s = LANG_EXP.replace(s, "")
            val json = runCatching {
                json.parseToJsonElement(String(Base64.getDecoder().decode(transMatch.groupValues[1]), Charsets.UTF_8)).jsonObject
            }.getOrNull()
            val content = json?.get("content")?.jArray().orEmpty()
            for (item in content) {
                val type = item["type"]?.jLong()?.toInt() ?: -1
                val lc = item["lyricContent"]?.jArray()?.map { it.jsonPrimitive.content }
                when (type) {
                    0 -> rlyric = lc
                    1 -> tlyric = lc
                }
            }
        }

        val rlyMut = rlyric?.toMutableList() ?: mutableListOf()
        val tlyMut = tlyric?.toMutableList() ?: mutableListOf()
        val lrcLines = mutableListOf<String>()
        val lxlLines = mutableListOf<String>()

        for (line in s.split("\n")) {
            val m = KRC_TIME_EXP.matchEntire(line) ?: continue
            val time = m.groupValues[2].toLong()
            val ms = time % 1000
            val t = time / 1000
            val timeStr = "%02d:%02d.%d".format(t / 60, t % 60, ms)
            val idx = lrcLines.size
            val raw = line.substringAfter("]")
            val lxl = raw.replace(WORD_CLOSE_EXP, "<$1>")
            val lrc = lxl.replace(WORD_INLINE_EXP, "")
            lxlLines += "[$timeStr]$lxl"
            lrcLines += "[$timeStr]$lrc"
            if (idx < rlyMut.size) rlyMut[idx] = "[$timeStr]${rlyMut[idx]}"
            if (idx < tlyMut.size) tlyMut[idx] = "[$timeStr]${tlyMut[idx]}"
        }
        val lrc = decodeEntities(lrcLines.joinToString("\n"))
        val lxl = decodeEntities(lxlLines.joinToString("\n"))
        val rly = rlyric?.let { decodeEntities(rlyMut.joinToString("\n")) }.orEmpty()
        val tly = tlyric?.let { decodeEntities(tlyMut.joinToString("\n")) }.orEmpty()
        return LyricInfo(lrc, tly.ifEmpty { null }, rly.ifEmpty { null }, lxl.ifEmpty { null })
    }

    // ── 封面（media.store.kugou.com，walnut kg/pic.js）────────────────────────

    override suspend fun getPic(song: Song): String? {
        song.picUrl?.takeIf { it.startsWith("http") }?.let { return it }
        val hash = song.raw["hash"] ?: return null
        val body = buildJsonObject {
            put("appid", 1001)
            put("area_code", "1")
            put("behavior", "play")
            put("clientver", "9020")
            put("need_hash_offset", 1)
            put("relate", 1)
            put("resource", buildJsonArray {
                add(buildJsonObject {
                    put("album_audio_id", song.id)
                    put("album_id", song.albumId ?: "")
                    put("hash", hash)
                    put("id", 0)
                    put("name", "${song.singer} - ${song.name}.mp3")
                    put("type", "audio")
                })
            })
            put("token", "")
            put("userid", 2626431536)
            put("vip", 1)
        }
        val root = runCatching {
            postJson("http://media.store.kugou.com/v1/get_res_privilege", body.toString(), kgHeaders)
        }.getOrNull() ?: return null
        if (root["error_code"]?.jStr() != "0" && root["error_code"]?.jLong() != 0L) return null
        val info = root["data"]?.jArray()?.firstOrNull()?.get("info") ?: return null
        val imgSize = info["imgsize"]?.jArray()
        val image = info["image"]?.jStr() ?: return null
        return if (imgSize != null) image.replace("{size}", imgSize.firstOrNull()?.jStr().orEmpty()) else image
    }

    // ── 评论（m.comment.service.kugou.com，walnut kg/comment.js）──────────────

    override suspend fun getComment(song: Song, page: Int, limit: Int): CommentResult {
        val hash = song.raw["hash"] ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val params = buildCommentParams(hash, page, limit)
        val root = runCatching {
            getJson("http://m.comment.service.kugou.com/r/v1/rank/newest?$params&signature=${signatureParams(params)}", commentHeaders)
        }.getOrNull() ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["err_code"]?.jStr() != "0" && root["err_code"]?.jLong() != 0L)
            return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = root["count"]?.jLong()?.toInt() ?: 0
        return CommentResult(id, filterComment(root["list"]?.jArray().orEmpty()), total, page, limit, (total + limit - 1) / limit)
    }

    override suspend fun getHotComment(song: Song, page: Int, limit: Int): CommentResult {
        val hash = song.raw["hash"] ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val params = buildCommentParams(hash, page, limit)
        val root = runCatching {
            getJson("http://m.comment.service.kugou.com/r/v1/rank/topliked?$params&signature=${signatureParams(params)}", commentHeaders)
        }.getOrNull() ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["err_code"]?.jStr() != "0" && root["err_code"]?.jLong() != 0L)
            return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = root["count"]?.jLong()?.toInt() ?: 0
        return CommentResult(id, filterComment(root["list"]?.jArray().orEmpty()), total, page, limit, (total + limit - 1) / limit)
    }

    private fun buildCommentParams(hash: String, page: Int, limit: Int): String =
        "dfid=0&mid=16249512204336365674023395779019&clienttime=${System.currentTimeMillis()}" +
            "&uuid=0&extdata=$hash&appid=1005&code=fc4be23b4e972707f36b8a828a93ba8a" +
            "&schash=$hash&clientver=11409&p=$page&clienttoken=&pagesize=$limit&ver=10&kugouid=0"

    private fun filterComment(rawList: List<JsonElement>): List<CommentInfo> =
        rawList.map { item -> filterCommentItem(item) }

    private fun filterCommentItem(item: JsonElement): CommentInfo {
        val content = item["content"]?.jStr().orEmpty()
        val userName = item["user_name"]?.jStr().orEmpty()
        val avatar = item["user_pic"]?.jStr()
        val userId = item["user_id"]?.jStr()
        val likedCount = item["like"]?.get("likenum")?.jLong()
        val replyNum = item["reply_num"]?.jLong()
        val time = item["addtime"]?.jLong()
        val images = item["images"]?.jArray()?.mapNotNull { it["url"]?.jStr() } ?: emptyList()
        val location = item["location"]?.jStr()
        val id = item["id"]?.jStr() ?: ""
        val pcontent = item["pcontent"]?.jStr()
        return if (pcontent != null) {
            CommentInfo(
                id = id, text = decodeEntities(pcontent), time = null, userName = item["puser"]?.jStr().orEmpty(),
                avatar = null, userId = item["puser_id"]?.jStr(), likedCount = null, replyNum = null,
                reply = listOf(CommentInfo(id, decodeEntities(content), time, time?.toString(), userName, avatar, userId, likedCount, replyNum, location, images)),
            )
        } else {
            CommentInfo(id, decodeEntities(content), time, time?.toString(), userName, avatar, userId, likedCount, replyNum, location, images)
        }
    }

    override fun getDetailPageUrl(song: Song): String? =
        "https://www.kugou.com/song/#hash=${song.raw["hash"].orEmpty()}&album_id=${song.albumId.orEmpty()}"

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

    private suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }

    private fun encodeKeyword(kw: String): String = URLEncoder.encode(kw, "UTF-8")

    private fun inflate(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }

    /** kg 签名（walnut kg/util.js signatureParams）：排序 params → 包裹 keyparam → md5 */
    private fun signatureParams(params: String): String {
        val keyparam = "OIlwieks28dk2k092lksi2UIkp"
        val sorted = params.split("&").sorted().joinToString("")
        return md5("$keyparam$sorted$keyparam")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val ENC_KEY = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )
        private val kgHeaders = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        )
        private val commentHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36 Edg/107.0.1418.24",
        )
        private val HEAD_EXP = Regex("^.*\\[id:\\$\\w+\\]\n")
        private val LANG_EXP = Regex("\\[language:([\\w=\\\\/+]+)\\]\n")
        private val KRC_TIME_EXP = Regex("\\[((\\d+),\\d+)\\].*")
        private val WORD_CLOSE_EXP = Regex("<(\\d+,\\d+),\\d+>")
        private val WORD_INLINE_EXP = Regex("<\\d+,\\d+>")
    }
}
