package com.lxpro.source.kw

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
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.Inflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置酷我音源（批十八，移植蓝本：walnut musicSdk/kw）。
 *
 * search / lyric / pic 为内置实现；**取流委托给已导入的 lx 脚本** —— 与 walnut
 * 一致：内置源只有数据接口，getMusicUrl 走 user api 脚本（未导入脚本时抛错提示）。
 */
@Singleton
class KwSource @Inject constructor(
    private val client: OkHttpClient,
    private val scriptManager: Lazy<ScriptSourceManager>,
) : SourceApi {

    override val id: SourceId = SourceId("kw")
    override val name: String = "酷我"
    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC, SourceAction.COMMENT)
    override val qualities: List<Quality> = Quality.entries.toList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 搜索（search.kuwo.cn/r.s，walnut kw/musicSearch.js）──────────────────

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0, false, id)
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${encodeKeyword(keyword)}" +
            "&pn=${page - 1}&rn=$limit&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
            "&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012" +
            "&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"

        // walnut：无响应 / 有总量但被挡（TOTAL!=0 且 SHOW==0）/ N_MINFO 缺失 → 重试，≤3 次
        repeat(3) {
            val root = runCatching { getJson(url) }.getOrNull() ?: return@repeat
            val total = root["TOTAL"].jStr()
            val show = root["SHOW"].jStr()
            if (total != "0" && show == "0") return@repeat

            val songs = mutableListOf<Song>()
            var broken = false
            for (item in root["abslist"].jArray().orEmpty()) {
                val nMinfo = item["N_MINFO"].jStr()
                if (nMinfo == null) {
                    broken = true
                    break
                }
                songs += songOf(item, nMinfo)
            }
            if (broken) return@repeat

            val totalCount = total?.toIntOrNull() ?: songs.size
            return SearchResult(songs, totalCount, page * limit < totalCount, id)
        }
        throw IllegalStateException("酷我搜索失败")
    }

    private fun songOf(item: JsonElement, nMinfo: String): Song {
        val qualitys = mutableListOf<Quality>()
        val sizes = mutableMapOf<String, Long>()
        for (seg in nMinfo.split(';')) {
            val m = M_INFO_EXP.find(seg) ?: continue
            val quality = when (m.groupValues[2]) {
                "20900" -> Quality.MASTER
                "20501" -> Quality.ATMOS_PLUS
                "20201" -> Quality.ATMOS
                "4000" -> Quality.HIRES
                "2000" -> Quality.FLAC
                "320" -> Quality.K320
                "128" -> Quality.K128
                else -> null
            } ?: continue
            if (quality !in qualitys) {
                qualitys += quality
                sizes[quality.value] = sizeToBytes(m.groupValues[4])
            }
        }
        qualitys.reverse()

        return Song(
            id = item["MUSICRID"].jStr().orEmpty().removePrefix("MUSIC_"),
            source = id,
            name = decodeEntities(item["SONGNAME"].jStr().orEmpty()),
            singer = decodeEntities(item["ARTIST"].jStr().orEmpty()).replace('&', '、'),
            albumName = item["ALBUM"].jStr()?.let(::decodeEntities),
            albumId = item["ALBUMID"].jStr()?.let(::decodeEntities),
            picUrl = item["web_albumpic_short"].jStr()
                ?.let { "https://img1.kuwo.cn/star/albumcover/" + it.replace("120/", "500/") },
            interval = item["DURATION"].jStr()?.toLongOrNull(),
            qualitys = qualitys,
            qualitySizes = sizes,
        )
    }

    // ── 取流（委托给脚本源）─────────────────────────────────────────────────

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val api = scriptManager.get().apiForSource(id.value)
            ?: throw IllegalStateException("未导入支持${name}的音源脚本")
        return api.getMusicUrl(song, quality)
    }

    // ── 歌词（newlyric.lrc，walnut kw/lyric.js + decodeLyric.js）─────────────
    // 请求参数整体与 "yeelion" 循环 XOR 后 base64 拼在 query；响应 `tp=content`
    // 头 + \r\n\r\n 之后是 zlib 数据，lrcx=1 时对解压文本再做 base64 → XOR → GB18030。

    override suspend fun getLyric(song: Song): LyricInfo {
        val params =
            "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_${song.id}&lrcx=1"
        val query = Base64.getEncoder()
            .encodeToString(xorYeelion(params.toByteArray(Charsets.UTF_8)))
        val body = runCatching {
            getBytes("http://newlyric.kuwo.cn/newlyric.lrc?$query")
        }.getOrNull() ?: return LyricInfo("")
        val text = decodeLyricBody(body) ?: return LyricInfo("")
        return runCatching { parseLrc(text) }.getOrElse { LyricInfo("") }
    }

    private fun decodeLyricBody(buf: ByteArray): String? {
        if (buf.size < 10 || String(buf, 0, 10, Charsets.UTF_8) != "tp=content") return null
        val sep = indexOfHeaderEnd(buf) ?: return null
        val inflated = inflate(buf.copyOfRange(sep + 4, buf.size))
        val decoded = runCatching {
            Base64.getMimeDecoder().decode(String(inflated, Charsets.UTF_8))
        }.getOrNull() ?: return null
        return String(xorYeelion(decoded), GB18030)
    }

    private data class LrcLine(var time: String, val text: String)

    private fun parseLrc(text: String): LyricInfo {
        val tags = mutableListOf<String>()
        val arr = mutableListOf<LrcLine>()
        for (raw in text.split(Regex("\\r\\n|\\r|\\n"))) {
            val line = raw.trim()
            val m = TIME_EXP.find(line)
            if (m != null) {
                var time = m.groupValues[1]
                if (TWO_DECIMAL_EXP.containsMatchIn(time)) time += '0'
                arr += LrcLine(time, line.replaceFirst(TIME_EXP, "").trim())
            } else if (TAG_EXP.containsMatchIn(line)) {
                tags += line
            }
        }
        val (lrc, lrcT) = sortLrcArr(arr)
        val lyric = decodeEntities(transformLrc(tags, lrc)).replace(WORD_TIME_EXP, "")
        if (!EXIST_TIME_EXP.containsMatchIn(lyric)) return LyricInfo("")
        val tlyric = if (lrcT.isEmpty()) {
            ""
        } else {
            decodeEntities(transformLrc(tags, lrcT)).replace(WORD_TIME_EXP, "")
        }
        return LyricInfo(lyric, tlyric.ifEmpty { null })
    }

    /** walnut sortLrcArr：同时间戳时先出的一行进翻译（时间改为上一行），后出的留正文 */
    private fun sortLrcArr(arr: List<LrcLine>): Pair<List<LrcLine>, List<LrcLine>> {
        val seen = HashSet<String>()
        val lrc = mutableListOf<LrcLine>()
        val lrcT = mutableListOf<LrcLine>()
        var isLyricx = false
        for (item in arr) {
            if (!seen.add(item.time)) {
                if (lrc.size < 2) continue
                val tItem = lrc.removeAt(lrc.lastIndex)
                tItem.time = lrc[lrc.lastIndex].time
                lrcT += tItem
                lrc += item
            } else {
                lrc += item
            }
            if (!isLyricx && LYRICX_TAG.containsMatchIn(item.text)) isLyricx = true
        }
        // 无逐字标签却拆出过多「翻译」→ 判定为解析失败（walnut 原样抛错）
        if (!isLyricx && lrcT.size > lrc.size * 0.3 && lrc.size - lrcT.size > 6) {
            throw IllegalStateException("failed")
        }
        return lrc to lrcT
    }

    private fun transformLrc(tags: List<String>, lines: List<LrcLine>): String =
        tags.joinToString("\n") + "\n" + lines.joinToString("") { "[${it.time}]${it.text}\n" }

    // ── 封面 ───────────────────────────────────────────────────────────────

    override suspend fun getPic(song: Song): String? {
        song.picUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val body = runCatching {
            getString(
                "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic" +
                    "&pictype=500&size=500&rid=${song.id}",
            )
        }.getOrNull() ?: return null
        return body.trim().takeIf { it.startsWith("http") }
    }

    // ── 评论（ncomment.kuwo.cn，walnut kw/comment.js）──────────────────────

    override suspend fun getComment(song: Song, page: Int, limit: Int): CommentResult {
        val url = "http://ncomment.kuwo.cn/com.s?f=web&type=get_comment&aapiver=1" +
            "&prod=kwplayer_ar_10.5.2.0&digest=15&sid=${song.id}" +
            "&start=${limit * (page - 1)}&msgflag=1&count=$limit&newver=3&uid=0"
        val root = runCatching { getJson(url, commentHeaders) }.getOrNull()
            ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["code"]?.jStr() != "200")
            return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = root["comments_counts"]?.jLong()?.toInt() ?: 0
        val comments = filterComment(root["comments"]?.jArray().orEmpty())
        return CommentResult(id, comments, total, page, limit, (total + limit - 1) / limit)
    }

    override suspend fun getHotComment(song: Song, page: Int, limit: Int): CommentResult {
        val url = "http://ncomment.kuwo.cn/com.s?f=web&type=get_rec_comment&aapiver=1" +
            "&prod=kwplayer_ar_10.5.2.0&digest=15&sid=${song.id}" +
            "&start=${limit * (page - 1)}&msgflag=1&count=$limit&newver=3&uid=0"
        val root = runCatching { getJson(url, commentHeaders) }.getOrNull()
            ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["code"]?.jStr() != "200")
            return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = root["hot_comments_counts"]?.jLong()?.toInt() ?: 0
        val comments = filterComment(root["hot_comments"]?.jArray().orEmpty())
        return CommentResult(id, comments, total, page, limit, (total + limit - 1) / limit)
    }

    private fun filterComment(rawList: List<JsonElement>): List<CommentInfo> =
        rawList.map { item -> filterCommentItem(item) }

    private fun filterCommentItem(item: JsonElement): CommentInfo {
        val mpic = item["mpic"]?.jStr()?.takeIf { it.isNotEmpty() }
        return CommentInfo(
            id = item["id"]?.jStr().orEmpty(),
            text = item["msg"]?.jStr().orEmpty(),
            time = item["time"]?.jLong(),
            timeStr = item["time"]?.jLong()?.toString(),
            userName = item["u_name"]?.jStr().orEmpty(),
            avatar = item["u_pic"]?.jStr(),
            userId = item["u_id"]?.jStr(),
            likedCount = item["like_num"]?.jLong(),
            images = mpic?.let { listOf(runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)) }
                ?: emptyList(),
            reply = item["child_comments"]?.jArray().orEmpty().map { filterCommentItem(it) },
        )
    }

    override fun getDetailPageUrl(song: Song): String? = null

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

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun encodeKeyword(keyword: String): String =
        URLEncoder.encode(keyword, Charsets.UTF_8.name()).replace("+", "%20")

    private fun xorYeelion(data: ByteArray): ByteArray {
        val key = "yeelion".toByteArray(Charsets.UTF_8)
        return ByteArray(data.size) { i ->
            (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
    }

    private fun indexOfHeaderEnd(buf: ByteArray): Int? {
        for (i in 0..buf.size - 4) {
            if (buf[i] == 0x0D.toByte() && buf[i + 1] == 0x0A.toByte() &&
                buf[i + 2] == 0x0D.toByte() && buf[i + 3] == 0x0A.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n <= 0) break
                out.write(chunk, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    companion object {
        private val GB18030: Charset = Charset.forName("GB18030")
        private val M_INFO_EXP = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")
        private val SIZE_EXP = Regex("([\\d.]+)([KMG]?)B")
        private val TIME_EXP = Regex("^\\[([\\d:.]*)]")
        private val TWO_DECIMAL_EXP = Regex("\\.\\d\\d$")
        private val TAG_EXP = Regex("\\[(?:ver|ti|ar|al|offset|by|kuwo):\\s*\\S+(?:\\s+\\S+)*\\s*]")
        private val LYRICX_TAG = Regex("^<-?\\d+,-?\\d+>")
        private val WORD_TIME_EXP = Regex("<-?\\d+,-?\\d+(?:,-?\\d+)?>")
        private val EXIST_TIME_EXP = Regex("\\[\\d{1,2}:.*\\d{1,4}]")
        private val commentHeaders = mapOf(
            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9;)",
        )

        /** "8.83MB" → 字节（N_MINFO 的 size 是展示串，仅供音质列表显示） */
        private fun sizeToBytes(raw: String): Long {
            val m = SIZE_EXP.find(raw.uppercase()) ?: return 0L
            val value = m.groupValues[1].toDoubleOrNull() ?: return 0L
            val multiplier = when (m.groupValues[2]) {
                "K" -> 1024.0
                "M" -> 1024.0 * 1024
                "G" -> 1024.0 * 1024 * 1024
                else -> 1.0
            }
            return (value * multiplier).toLong()
        }
    }
}
