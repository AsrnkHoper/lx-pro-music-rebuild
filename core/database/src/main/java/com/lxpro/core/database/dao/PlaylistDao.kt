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

    /** 系统歌单（收藏）固定首位，其余按用户拖拽的 position */
    @Query("SELECT * FROM playlists ORDER BY isSystem DESC, position ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    /** 拖拽排序时读全量（一次性，不是 Flow） */
    @Query("SELECT * FROM playlists ORDER BY isSystem DESC, position ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun findPlaylist(id: String): PlaylistEntity?

    /** 自动命名要避开重名，必须查**全量**（不能用界面上被筛过的那一份） */
    @Query("SELECT name FROM playlists")
    suspend fun getPlaylistNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    /** 系统歌单不可改名（`isSystem = 0` 是硬条件，不是靠调用方自觉） */
    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id AND isSystem = 0")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id AND isSystem = 0")
    suspend fun deletePlaylist(id: String)

    /** 自定义封面（songId/source 或本地图 uri；全 null = 回落默认汇总封面） */
    @Query(
        "UPDATE playlists SET coverSongId = :songId, coverSource = :source, " +
            "coverImageUri = :imageUri, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateCover(id: String, songId: String?, source: String?, imageUri: String?, updatedAt: Long)

    @Query("UPDATE playlists SET position = :position WHERE id = :id")
    suspend fun updatePlaylistPosition(id: String, position: Int)

    /** 新建歌单插到系统歌单之后：其余歌单整体后移一位 */
    @Query("UPDATE playlists SET position = position + 1 WHERE isSystem = 0")
    suspend fun shiftPositionsForInsert()

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>>

    /** 拖动 / 输入序号排序时读全量（一次性，不是 Flow） */
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getItems(playlistId: String): List<PlaylistItemEntity>

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId")
    suspend fun updatePosition(itemId: Long, position: Int)

    /** 一级页「含本地」筛选用：哪些歌单里至少有这个来源的歌 */
    @Query("SELECT DISTINCT playlistId FROM playlist_items WHERE source = :source")
    fun observePlaylistIdsWithSource(source: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    fun observeItemCount(playlistId: String): Flow<Int>

    /**
     * 各歌单的条目数与封面。封面优先级：
     * 用户指定的歌单内歌曲 → 歌单内第一首有封面的歌。
     * （本地图封面在 `playlists.coverImageUri` 上，由 UI 层优先于本字段渲染）
     *
     * 空歌单也会出现（itemCount = 0, coverUrl = null）。
     */
    @Query(
        "SELECT p.id AS playlistId, " +
            "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount, " +
            "COALESCE(" +
            "(SELECT c.coverUrl FROM playlist_items c WHERE c.playlistId = p.id " +
            "AND c.songId = p.coverSongId AND c.source = p.coverSource), " +
            "(SELECT i2.coverUrl FROM playlist_items i2 WHERE i2.playlistId = p.id " +
            "AND i2.coverUrl IS NOT NULL ORDER BY i2.position ASC LIMIT 1)" +
            ") AS coverUrl " +
            "FROM playlists p",
    )
    fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    /** 备份导入：整批插入（数千首一次性进，逐条 nextPosition 太慢） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: String): Int

    /** 置顶插入位：新歌排最上面（用户要求 2026-10-27）。空歌单返回 0 */
    @Query("SELECT COALESCE(MIN(position), 1) - 1 FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun topPosition(playlistId: String): Int

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