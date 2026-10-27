package com.lxpro.source.script

import com.lxpro.core.model.CommentResult
import com.lxpro.core.model.LyricInfo
import com.lxpro.core.model.MusicUrl
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.source.api.SearchResult
import com.lxpro.source.api.SourceAction
import com.lxpro.source.api.SourceApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 脚本音源的 [SourceApi] 实现（M2 #6）：一个 lx 2.0 脚本声明 N 个源（kw/wy/tx…），
 * 每个源一个本实例，全部请求经 [ScriptSandboxManager] 转发给脚本。
 *
 * 能力边界（04 §1.1）：lx 2.0 协议只有 musicUrl / lyric / pic —— **脚本源不参与
 * 搜索**（search 返回空），它的价值是给「导入歌单 / 历史 / 换源」里的歌取流。
 *
 * musicInfo 字段映射：与 walnut `toOldMusicInfo`（LX v1 旧格式）逐字段对齐 ——
 * 所有源统一用 `songmid`（= Song.id），源特有字段从 [Song.raw] 平铺展开，
 * `interval` 转换为 "mm:ss" 格式化串（脚本侧普遍按它展示/比较）。
 */
class ScriptSourceApi(
    private val scriptId: String,
    override val id: SourceId,
    override val name: String,
    declaredActions: Set<SourceAction>,
    override val qualities: List<Quality>,
    private val manager: ScriptSandboxManager,
) : SourceApi {

    override val actions: Set<SourceAction> =
        setOf(SourceAction.MUSIC_URL, SourceAction.LYRIC, SourceAction.PIC)
            .filter { it in declaredActions }.toSet().ifEmpty { setOf(SourceAction.MUSIC_URL) }

    override suspend fun search(keyword: String, page: Int, limit: Int): SearchResult =
        SearchResult(listOf(), source = id) // 脚本源不做搜索（协议无此 action）

    override suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl {
        val response = manager.callAction(scriptId, id.value, "musicUrl", musicInfoPayload(song, quality))
        val url = manager.musicUrlOf(response)
            ?: throw ScriptException("script returned no url for ${song.name}")
        return MusicUrl(url, quality)
    }

    override suspend fun getLyric(song: Song): LyricInfo {
        // 源未声明 lyric 能力时不转发——脚本的内部报错文案（「不支持动作」等）不应上屏
        if (SourceAction.LYRIC !in actions) throw ScriptException("该音源不支持歌词")
        val response = manager.callAction(scriptId, id.value, "lyric", musicInfoPayload(song, null))
        val data = manager.responseData(response)?.jsonObject
            ?: throw ScriptException("script returned no lyric for ${song.name}")
        return LyricInfo(
            lyric = data.str("lyric") ?: "",
            tlyric = data.str("tlyric"),
            rlyric = data.str("rlyric"),
            lxlyric = data.str("lxlyric"),
        )
    }

    override suspend fun getPic(song: Song): String? = runCatching {
        val response = manager.callAction(scriptId, id.value, "pic", musicInfoPayload(song, null))
        manager.picUrlOf(response)
    }.getOrNull()

    override fun getDetailPageUrl(song: Song): String? = null

    /** 脚本源不支持评论（lx 2.0 协议无 comment action） */
    override suspend fun getComment(song: Song, page: Int, limit: Int): CommentResult =
        CommentResult(id, emptyList(), 0, page, limit, 1)

    override suspend fun getHotComment(song: Song, page: Int, limit: Int): CommentResult =
        CommentResult(id, emptyList(), 0, page, limit, 1)

    companion object {
        private val json = Json

        /**
         * Song → lx 2.0 info 载荷（与 preload handleRequest 的解构及 walnut
         * sendUserApiRequest 对齐）：`{ type, musicInfo }` 双层结构。
         * musicInfo 为 walnut toOldMusicInfo 同构：songmid/name/singer/source/
         * interval/albumName/albumId/img + 源特有字段（hash/strMediaMid/albumMid/
         * copyrightId/songId 等，从 Song.raw 平铺）+ types/_types。
         */
        internal fun musicInfoPayload(song: Song, quality: Quality?): JsonObject = buildJsonObject {
            quality?.let { put("type", it.value) }
            put("musicInfo", buildJsonObject {
                put("songmid", song.id)
                put("name", song.name)
                put("singer", song.singer)
                put("source", song.source.value)
                put("interval", formatInterval(song.interval))
                put("albumName", song.albumName ?: "")
                put("albumId", song.albumId ?: "")
                put("img", song.picUrl ?: "")
                put("fee", song.fee)
                // 源特有字段：walnut 按源挑字段，这里平铺 raw 全集（等价覆盖）
                song.raw.forEach { (k, v) -> put(k, v) }
                put("types", kotlinx.serialization.json.buildJsonArray {
                    song.qualitys.forEach { q ->
                        add(
                            buildJsonObject {
                                put("type", q.value)
                                put("size", song.qualitySizes[q.value] ?: 0L)
                            },
                        )
                    }
                })
                put("_types", buildJsonObject {
                    song.qualitys.forEach { q ->
                        put(q.value, buildJsonObject {
                            put("size", song.qualitySizes[q.value] ?: 0L)
                        })
                    }
                })
            })
        }

        /** 秒 → "mm:ss"（脚本侧对 interval 的普遍期望格式） */
        private fun formatInterval(seconds: Long?): String {
            val total = seconds ?: 0L
            return "%02d:%02d".format(total / 60, total % 60)
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    }
}
