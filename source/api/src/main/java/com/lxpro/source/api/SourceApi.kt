package com.lxpro.source.api

import com.lxpro.core.model.LyricInfo
import com.lxpro.core.model.MusicUrl
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import kotlinx.coroutines.flow.StateFlow

/** 音源支持的 action 集合（04 §2） */
enum class SourceAction { MUSIC_URL, LYRIC, PIC }

data class SearchResult(
    val list: List<Song>,
    val total: Int? = null,
    val hasMore: Boolean = false,
    val source: SourceId,
)

/**
 * 所有音源（内置 + 脚本）统一实现此接口。
 * UI 与播放层**只认这个接口**（03 §2.1）。
 */
interface SourceApi {
    val id: SourceId
    val name: String
    val actions: Set<SourceAction>
    val qualities: List<Quality>

    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): SearchResult

    suspend fun getMusicUrl(song: Song, quality: Quality): MusicUrl

    suspend fun getLyric(song: Song): LyricInfo

    suspend fun getPic(song: Song): String?

    fun getDetailPageUrl(song: Song): String?
}

/** 音源注册表：UI 层通过它拿到当前启用的音源 */
interface SourceRegistry {
    val all: StateFlow<List<SourceApi>>
    val enabled: StateFlow<List<SourceApi>>

    fun byId(id: SourceId): SourceApi?

    /** 导入脚本后动态注册 */
    fun register(api: SourceApi)

    fun unregister(id: SourceId)
}