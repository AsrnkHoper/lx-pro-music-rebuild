package com.lxpro.core.playlist

import com.lxpro.core.database.dao.SongOverrideDao
import com.lxpro.core.database.entity.SongOverrideEntity
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户对某首歌的本地修正 + 换源目标（null 表示该项未设置）。
 *
 * @property toggleSong 用户钉住的换源目标（04 · 换源，对齐 LX `meta.toggleMusicInfo`）
 */
data class SongOverride(val name: String?, val singer: String?, val toggleSong: Song? = null)

/** 覆盖表的键：`source` + `songId`（分隔符用不可见字符，避免与 id 本身冲突） */
fun songOverrideKey(songId: String, source: String): String = "$source$songId"

/** 把修正应用到歌曲上（只影响本地显示与播放取流，不回写音源） */
fun Song.withOverride(override: SongOverride?): Song =
    if (override == null) {
        this
    } else {
        copy(
            name = override.name ?: name,
            singer = override.singer ?: singer,
            toggleSong = override.toggleSong,
        )
    }

/**
 * 歌曲元数据的本地修正（`mockup_playlist.html`：重命名歌曲 / 重命名作者）。
 *
 * 为什么需要：B 站搬运号把作品标题照搬成歌名、把发布者 ID 当作者，真实备份里
 * 「DJ Okawari」因此被拆成 8 个歌手条目合计 157 次播放 —— 统计被污染且只增不减。
 * 改动只在本地生效，统计侧按修正后的名字归并。
 *
 * v20 起同表携带「换源目标」：用户在换源面板钉住的候选 Song（JSON），
 * 播放链路优先用它取流（对齐 LX `meta.toggleMusicInfo`）。
 */
@Singleton
class SongOverrideRepository @Inject constructor(
    private val dao: SongOverrideDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun observe(): Flow<Map<String, SongOverride>> =
        dao.observeAll().map { rows -> rows.toMap() }

    /** 一次性读取（搜索结果这类非响应式场景用） */
    suspend fun snapshot(): Map<String, SongOverride> = dao.getAll().toMap()

    /** 改歌名（`name` 为空则不改这一项） */
    suspend fun renameSong(songId: String, source: String, name: String) =
        upsert(songId, source, name = name, singer = null, toggle = ToggleOp.KEEP)

    /** 改作者名（`singer` 为空则不改这一项） */
    suspend fun renameSinger(songId: String, source: String, singer: String) =
        upsert(songId, source, name = null, singer = singer, toggle = ToggleOp.KEEP)

    /** 钉住换源目标（播放时优先用 target 取流；对齐 LX handleToggleSource） */
    suspend fun setToggle(songId: String, source: String, target: Song) =
        upsert(songId, source, name = null, singer = null, toggle = ToggleOp.Set(target))

    /** 取消换源，恢复原源 */
    suspend fun clearToggle(songId: String, source: String) =
        upsert(songId, source, name = null, singer = null, toggle = ToggleOp.CLEAR)

    private sealed interface ToggleOp {
        data object KEEP : ToggleOp
        data class Set(val target: Song) : ToggleOp
        data object CLEAR : ToggleOp
    }

    private suspend fun upsert(songId: String, source: String, name: String?, singer: String?, toggle: ToggleOp) {
        val existing = dao.find(songId, source)
        val toggleJson = when (toggle) {
            ToggleOp.KEEP -> existing?.toggleSongJson
            ToggleOp.CLEAR -> null
            is ToggleOp.Set -> json.encodeToString(Song.serializer(), toggle.target)
        }
        dao.upsert(
            SongOverrideEntity(
                songId = songId,
                source = source,
                // 只改一项时保留其它项已有的修正
                name = name ?: existing?.name,
                singer = singer ?: existing?.singer,
                toggleSongJson = toggleJson,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun List<SongOverrideEntity>.toMap(): Map<String, SongOverride> =
        associate {
            songOverrideKey(it.songId, it.source) to SongOverride(
                name = it.name,
                singer = it.singer,
                toggleSong = it.toggleSongJson?.let { raw ->
                    runCatching { json.decodeFromString(Song.serializer(), raw) }.getOrNull()
                },
            )
        }
}
