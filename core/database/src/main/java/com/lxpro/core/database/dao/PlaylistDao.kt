package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.database.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

/** 歌单网格用的汇总行（歌单 + 条目数 + 封面取第一条的歌） */
data class PlaylistSummaryRow(
    val playlistId: String,
    val itemCount: Int,
    val coverUrl: String?,
)

@Dao
interface PlaylistDao {

    /** 系统歌单（收藏）排最前，其余按最近更新 */
    @Query("SELECT * FROM playlists ORDER BY isSystem DESC, updatedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun findPlaylist(id: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    /** 系统歌单不可改名（`isSystem = 0` 是硬条件，不是靠调用方自觉） */
    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id AND isSystem = 0")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id AND isSystem = 0")
    suspend fun deletePlaylist(id: String)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    fun observeItemCount(playlistId: String): Flow<Int>

    /**
     * 各歌单的条目数与封面。封面取歌单内第一首有封面的歌。
     * 空歌单不会出现在结果里 —— 调用方按 0/null 处理。
     */
    @Query(
        "SELECT playlistId, COUNT(*) AS itemCount, " +
            "(SELECT coverUrl FROM playlist_items i2 WHERE i2.playlistId = i.playlistId " +
            "AND coverUrl IS NOT NULL ORDER BY position ASC LIMIT 1) AS coverUrl " +
            "FROM playlist_items i GROUP BY playlistId",
    )
    fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND songId = :songId AND source = :source")
    suspend fun deleteSong(playlistId: String, songId: String, source: String)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM playlist_items " +
            "WHERE playlistId = :playlistId AND songId = :songId AND source = :source)",
    )
    fun observeContains(playlistId: String, songId: String, source: String): Flow<Boolean>
}