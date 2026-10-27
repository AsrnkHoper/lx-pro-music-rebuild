package com.lxpro.source.wy

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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内置网易云服务音源（批十八，移植蓝本：walnut musicSdk/wy）。
 *
 * search / lyric / pic 为内置实现；**取流委托给已导入的 lx 脚本** —— 与 walnut
 * 一致：内置源只有数据接口，getMusicUrl 走 user api 脚本（未导入脚本时抛错提示）。
 */
@Singleton
class WySource @Inject constructor(
    private val client: OkHttpClient,
    private val scriptManager: Lazy<ScriptSourceManager>,
) : SourceApi {

    override val id: SourceId = SourceId("wy")
    override val name: String = "网易云"
    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC, SourceAction.COMMENT)
    override val qualities: List<Quality> =
        listOf(Quality.K128, Quality.K320, Quality.FLAC, Quality.HIRES)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 搜索（eapi/batch，walnut wy/musicSearch.js）──────────────────────────

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult {
        if (keyword.isBlank()) return SearchResult(emptyList(), 0, false, id)
        val text = buildJsonObject {
            put("keyword", keyword)
            put("needCorrect", "1")
            put("channel", "typing")
            put("offset", limit * (page - 1))
            put("scene", "normal")
            put("total", page == 1)
            put("limit", limit)
        }.toString()

        // walnut：响应 code 非 200 / 异常 → 重试，≤3 次
        repeat(3) {
            val root = runCatching { postEapi(BATCH_ENDPOINT, SEARCH_API, text) }.getOrNull()
                ?: return@repeat
            if (root["code"].jInt() != 200) return@repeat

            val data = root["data"]
            val songs = data["resources"].jArray().orEmpty()
                .mapNotNull { it["baseInfo"]["simpleSongData"].jObj() }
                .mapNotNull(::songOf)
            val total = maxOf(data["totalCount"].jInt() ?: 0, songs.size)
            return SearchResult(songs, total, page * limit < total, id)
        }
        throw IllegalStateException("网易云搜索失败")
    }

    private fun songOf(item: JsonObject): Song? {
        val songId = item["id"].jStr() ?: return null

        val qualitys = mutableListOf<Quality>()
        val sizes = mutableMapOf<String, Long>()
        fun addQuality(quality: Quality, size: Long?) {
            if (size != null && size != 0L && quality !in qualitys) {
                qualitys += quality
                sizes[quality.value] = size
            }
        }
        addQuality(Quality.HIRES, item["hr"]["size"].jLong())
        addQuality(Quality.FLAC, item["sq"]["size"].jLong())
        addQuality(Quality.K320, item["h"]["size"].jLong())
        addQuality(Quality.K128, item["m"]["size"].jLong())
        addQuality(Quality.K128, item["l"]["size"].jLong())
        qualitys.reverse()

        val picUrl = item["al"]["picUrl"].jStr()
        return Song(
            id = songId,
            source = id,
            name = item["name"].jStr().orEmpty(),
            singer = item["ar"].jArray().orEmpty()
                .mapNotNull { it["name"].jStr() }.joinToString("、"),
            albumName = item["al"]["name"].jStr(),
            albumId = item["al"]["id"].jStr(),
            picUrl = picUrl,
            interval = item["dt"].jLong()?.let { it / 1000 },
            qualitys = qualitys,
            qualitySizes = sizes,
            fee = item["fee"].jInt() ?: 0,
            raw = mapOf("songId" to songId, "picUrl" to picUrl.orEmpty()),
        )
    }

    // ── 取流（委托给脚本源）─────────────────────────────────────────────────

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val api = scriptManager.get().apiForSource(id.value)
            ?: throw IllegalStateException("未导入支持${name}的音源脚本")
        return api.getMusicUrl(song, quality)
    }

    // ── 歌词（eapi /api/song/lyric/v1，walnut wy/lyric.js，简化只出 lyric/tlyric/rlyric）

    override suspend fun getLyric(song: Song): LyricInfo {
        val text = buildJsonObject {
            song.id.toLongOrNull()?.let { put("id", it) } ?: put("id", song.id)
            put("cp", false)
            put("tv", 0)
            put("lv", 0)
            put("rv", 0)
            put("kv", 0)
            put("yv", 0)
            put("ytv", 0)
            put("yrv", 0)
        }.toString()

        repeat(3) {
            val root = runCatching { postEapi(LYRIC_ENDPOINT, LYRIC_API, text) }.getOrNull()
                ?: return@repeat
            if (root["code"].jInt() != 200) return@repeat
            // 无 lrc.lyric = 纯音乐等情况，不算失败（walnut 同样直接返回空）
            val lyric = root["lrc"]["lyric"].jStr() ?: return LyricInfo("")
            return LyricInfo(
                lyric = fixTimeLabel(extractHeaderLines(lyric).joinToString("\n")),
                tlyric = root["tlyric"]["lyric"].jStr()
                    ?.let { fixTimeLabel(extractHeaderLines(it).joinToString("\n")) },
                rlyric = root["romalrc"]["lyric"].jStr()?.let(::fixRomaTimeLabel),
            )
        }
        return LyricInfo("")
    }

    // ── 封面（搜索结果已带 al.picUrl，无需额外请求）──────────────────────────

    override suspend fun getPic(song: Song): String? =
        song.picUrl?.takeIf { it.isNotBlank() }
            ?: song.raw["picUrl"]?.takeIf { it.isNotBlank() }

    // ── 评论（weapi/comment/resource，walnut wy/comment.js）──────────────────

    override suspend fun getComment(song: Song, page: Int, limit: Int): CommentResult {
        val threadId = "R_SO_4_${song.id}"
        val text = buildJsonObject {
            put("cursor", System.currentTimeMillis())
            put("offset", 0)
            put("orderType", 1)
            put("pageNo", page)
            put("pageSize", limit)
            put("rid", threadId)
            put("threadId", threadId)
        }.toString()
        val root = runCatching { postWeapi(COMMENT_ENDPOINT, text) }.getOrNull()
            ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["code"].jInt() != 200) return CommentResult(id, emptyList(), 0, page, limit, 1)
        val data = root["data"] ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = data["totalCount"].jInt() ?: 0
        val comments = filterComment(data["comments"].jArray().orEmpty())
        return CommentResult(id, comments, total, page, limit, (total + limit - 1) / limit)
    }

    override suspend fun getHotComment(song: Song, page: Int, limit: Int): CommentResult {
        val threadId = "R_SO_4_${song.id}"
        val text = buildJsonObject {
            put("rid", threadId)
            put("limit", limit)
            put("offset", limit * (page - 1))
            put("beforeTime", System.currentTimeMillis().toString())
        }.toString()
        val root = runCatching { postWeapi("$HOTCOMMENT_PREFIX$threadId", text) }.getOrNull()
            ?: return CommentResult(id, emptyList(), 0, page, limit, 1)
        if (root["code"].jInt() != 200) return CommentResult(id, emptyList(), 0, page, limit, 1)
        val total = root["total"].jInt() ?: 0
        val comments = filterComment(root["hotComments"].jArray().orEmpty())
        return CommentResult(id, comments, total, page, limit, (total + limit - 1) / limit)
    }

    private fun filterComment(rawList: List<JsonElement>): List<CommentInfo> =
        rawList.map { item -> filterCommentItem(item) }

    private fun filterCommentItem(item: JsonElement): CommentInfo {
        val user = item["user"]
        val beReplied = item["beReplied"]?.jArray()?.firstOrNull()
        return CommentInfo(
            id = item["commentId"]?.jStr().orEmpty(),
            text = item["content"]?.jStr().orEmpty(),
            time = item["time"]?.jLong(),
            timeStr = item["time"]?.jLong()?.toString(),
            userName = user?.get("nickname")?.jStr().orEmpty(),
            avatar = user?.get("avatarUrl")?.jStr(),
            userId = user?.get("userId")?.jStr(),
            likedCount = item["likedCount"]?.jLong(),
            reply = if (beReplied != null) listOf(filterBeReplied(beReplied)) else emptyList(),
        )
    }

    private fun filterBeReplied(item: JsonElement): CommentInfo {
        val user = item["user"]
        return CommentInfo(
            id = item["beRepliedCommentId"]?.jStr().orEmpty(),
            text = item["content"]?.jStr().orEmpty(),
            userName = user?.get("nickname")?.jStr().orEmpty(),
            avatar = user?.get("avatarUrl")?.jStr(),
            userId = user?.get("userId")?.jStr(),
        )
    }

    override fun getDetailPageUrl(song: Song): String? = null

    // ── eapi 加密与请求（walnut wy/utils/crypto.js）─────────────────────────

    private suspend fun postEapi(endpoint: String, api: String, text: String): JsonObject =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().add("params", eapiParams(api, text)).build()
            val request = Request.Builder().url(endpoint)
                .header("User-Agent", UA)
                .header("origin", "https://music.163.com")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }

    /**
     * weapi 加密与请求（walnut wy/utils/crypto.js weapi）：
     * `params = b64(AES(b64(AES(text, presetKey, iv)), secretKey, iv))`，
     * `encSecKey = hex(RSA(reversed(secretKey) zero-padded 128B, pubkey))`。
     */
    private suspend fun postWeapi(url: String, text: String): JsonObject =
        withContext(Dispatchers.IO) {
            val (params, encSecKey) = weapiParams(text)
            val body = FormBody.Builder()
                .add("params", params)
                .add("encSecKey", encSecKey)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("origin", "https://music.163.com")
                .header("Referer", "https://music.163.com")
                .post(body)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }

    private fun fixTimeLabel(lrc: String): String =
        lrc.replace(WEIRD_TIME_EXP) { "[${it.groupValues[1]}.${it.groupValues[2]}]" }

    /**
     * walnut parseHeaderInfo：新版歌词里 `{"t":0,"c":[{"tx":"作词: "},…]}` 这样的
     * JSON 行要转成 `[mm:ss.ms]文本`，否则 credits 会以原始 JSON 混进歌词。
     */
    private fun extractHeaderLines(text: String): List<String> =
        text.replace("\r", "").split('\n').map { raw ->
            val line = raw.trim()
            if (!line.startsWith("{\"")) return@map line
            val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return@map ""
            val time = formatMs(obj["t"].jLong() ?: return@map "") ?: return@map ""
            val content = obj["c"].jArray().orEmpty().mapNotNull { it["tx"].jStr() }.joinToString("")
            time + content
        }

    private fun formatMs(timeMs: Long): String? {
        if (timeMs < 0) return null
        val ms = timeMs % 1000
        val totalSec = timeMs / 1000
        return "[%02d:%02d.%d]".format(totalSec / 60, totalSec % 60, ms)
    }

    private fun fixRomaTimeLabel(lrc: String): String =
        lrc.replace(ROMA_TIME_EXP) { "[${it.groupValues[1]}.${it.groupValues[2]}]" }
            .replace(ROMA_ZERO_EXP) { "[${it.groupValues[1]}]" }

    companion object {
        private const val BATCH_ENDPOINT = "https://interface.music.163.com/eapi/batch"
        private const val SEARCH_API = "/api/search/song/list/page"
        private const val LYRIC_ENDPOINT = "https://interface3.music.163.com/eapi/song/lyric/v1"
        private const val LYRIC_API = "/api/song/lyric/v1"
        private const val COMMENT_ENDPOINT =
            "https://music.163.com/weapi/comment/resource/comments/get"
        private const val HOTCOMMENT_PREFIX =
            "https://music.163.com/weapi/v1/resource/hotcomments/"
        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/60.0.3112.90 Safari/537.36"
        private val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)

        // weapi 加密常量（walnut wy/utils/crypto.js weapi）
        private val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
        private val WEAPI_IV = "0102030405060708".toByteArray(Charsets.UTF_8)
        private val WEAPI_PUB_KEY by lazy {
            val pemBody = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ3" +
                "7BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK" +
                "9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
            KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pemBody)))
        }
        private val SECRET_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        private val secureRandom = SecureRandom()

        private val WEIRD_TIME_EXP = Regex("\\[(\\d{2}:\\d{2}):(\\d{2})]")
        private val ROMA_TIME_EXP = Regex("\\[(\\d{2}:\\d{2}):(\\d{2,3})]")
        private val ROMA_ZERO_EXP = Regex("\\[(\\d{2}:\\d{2}\\.\\d{2})0]")

        private fun md5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /**
         * walnut `eapi(url, object)`：`md5(nobody+url+use+text+md5forencrypt)` 拼进
         * `url-36cd479b6b5-text-36cd479b6b5-digest`，AES/ECB/PKCS7 加密后大写 hex。
         * （node 原版 createCipheriv 默认开 PKCS7 自动填充，故这里用 PKCS5Padding。）
         */
        internal fun eapiParams(api: String, text: String): String {
            val digest = md5Hex("nobody${api}use${text}md5forencrypt")
            val data = "$api-36cd479b6b5-$text-36cd479b6b5-$digest"
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY, "AES"))
            return cipher.doFinal(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }

        /**
         * walnut `weapi(object)`：两次 AES-CBC-128-PKCS5 后接 RSA/NoPadding。
         * - Step1: AES(text, presetKey, iv) → base64 → UTF-8 bytes
         * - Step2: AES(step1, secretKey, iv) → base64 = params
         * - encSecKey: reversed(secretKey) zero-padded 128B → RSA/NoPadding → hex（256 字符）
         */
        internal fun weapiParams(text: String): Pair<String, String> {
            val secretKey = randomSecretKey()
            val step1 = aesCbcBase64(text.toByteArray(Charsets.UTF_8), WEAPI_PRESET_KEY)
            val params = aesCbcBase64(step1.toByteArray(Charsets.UTF_8), secretKey.toByteArray(Charsets.UTF_8))
            val encSecKey = rsaNoPaddingHex(secretKey)
            return params to encSecKey
        }

        private fun randomSecretKey(): String = buildString {
            repeat(16) { append(SECRET_CHARS[secureRandom.nextInt(SECRET_CHARS.size)]) }
        }

        private fun aesCbcBase64(data: ByteArray, key: ByteArray): String {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(WEAPI_IV),
            )
            return Base64.getEncoder().encodeToString(cipher.doFinal(data))
        }

        private fun rsaNoPaddingHex(secretKey: String): String {
            val reversed = secretKey.reversed().toByteArray(Charsets.UTF_8)
            val padded = ByteArray(128)
            System.arraycopy(reversed, 0, padded, 128 - reversed.size, reversed.size)
            val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, WEAPI_PUB_KEY)
            val hex = cipher.doFinal(padded)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            // NoPadding 可能丢掉前导 0 字节，统一补齐到 256 hex 字符
            return hex.padStart(256, '0')
        }
    }
}
