package com.lxpro.core.playlist

import com.lxpro.core.database.dao.PlaylistDao
import com.lxpro.core.database.dao.PlaylistSummaryRow
import com.lxpro.core.database.entity.LIKED_PLAYLIST_ID
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.database.entity.PlaylistItemEntity
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 歌单 + 收藏（02 §6.3：收藏是 id 固定为 `liked` 的系统歌单，不可删除）。 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
) {

    private val json = Json { encodeDefaults = true }

    fun observePlaylists(): Flow<List<PlaylistEntity>> = dao.observePlaylists()

    fun observePlaylist(id: String): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun observeItemCount(playlistId: String): Flow<Int> = dao.observeItemCount(playlistId)

    /** 网格页用：playlistId → (条目数, 封面) */
    fun observeSummaries(): Flow<Map<String, PlaylistSummaryRow>> =
        dao.observeSummaries().map { rows -> rows.associateBy { it.playlistId } }

    fun observeSongs(playlistId: String): Flow<List<Song>> =
        dao.observeItems(playlistId).map { rows -> rows.map { it.toSong() } }

    fun observeLiked(songId: String, source: String): Flow<Boolean> =
        dao.observeContains(LIKED_PLAYLIST_ID, songId, source)

    /** 保证「收藏」这个系统歌单存在（首次进入歌单页时调用） */
    suspend fun ensureSystemPlaylists() {
        if (dao.findPlaylist(LIKED_PLAYLIST_ID) == null) {
            val now = System.currentTimeMillis()
            dao.insertPlaylist(
                PlaylistEntity(
                    id = LIKED_PLAYLIST_ID,
                    name = "收藏",
                    isSystem = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    /** @return 新建歌单的 id；名称为空时自动取一个不重名的默认名 */
    suspend fun createPlaylist(rawName: String, existingNames: List<String>): String {
        val name = PlaylistNames.normalize(rawName)
            ?: PlaylistNames.nextDefaultName(existingNames)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.insertPlaylist(
            PlaylistEntity(id = id, name = name, isSystem = false, createdAt = now, updatedAt = now),
        )
        return id
    }

    /** @return false 表示名称为空（调用方应给出提示），或该歌单是系统歌单 */
    suspend fun rename(id: String, rawName: String): Boolean {
        val name = PlaylistNames.normalize(rawName) ?: return false
        dao.rename(id, name, System.currentTimeMillis())
        return true
    }

    /** 系统歌单（收藏）不可删除 —— 这条约束写在 SQL 的 `isSystem = 0` 条件里 */
    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    /** @return false 表示这首歌已在该歌单里（不重复添加） */
    suspend fun addSong(playlistId: String, song: Song): Boolean {
        val row = PlaylistItemEntity(
            playlistId = playlistId,
            songId = song.id,
            source = song.source.value,
            songJson = json.encodeToString(Song.serializer(), song),
            songName = song.name,
            singer = song.singer,
            coverUrl = song.picUrl,
            position = dao.nextPosition(playlistId),
            addedAt = System.currentTimeMillis(),
        )
        return dao.insertItem(row) != -1L
    }

    suspend fun removeSong(playlistId: String, songId: String, source: String) =
        dao.deleteSong(playlistId, songId, source)

    /** 收藏/取消收藏（播放页与列表长按都用它） */
    suspend fun toggleLiked(song: Song): Boolean {
        ensureSystemPlaylists()
        return if (dao.insertItem(
                PlaylistItemEntity(
                    playlistId = LIKED_PLAYLIST_ID,
                    songId = song.id,
                    source = song.source.value,
                    songJson = json.encodeToString(Song.serializer(), song),
                    songName = song.name,
                    singer = song.singer,
                    coverUrl = song.picUrl,
                    position = dao.nextPosition(LIKED_PLAYLIST_ID),
                    addedAt = System.currentTimeMillis(),
                ),
            ) != -1L
        ) {
            true
        } else {
            dao.deleteSong(LIKED_PLAYLIST_ID, song.id, song.source.value)
            false
        }
    }
}

/** 条目 → Song：反序列化整份快照，`raw` 随之还原（在线取流要用） */
internal fun PlaylistItemEntity.toSong(): Song =
    Json.decodeFromString(Song.serializer(), songJson)