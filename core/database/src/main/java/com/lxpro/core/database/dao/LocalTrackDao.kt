package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.LocalTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalTrackDao {

    @Query("SELECT * FROM local_tracks ORDER BY dateAddedSec DESC")
    fun observeAll(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_tracks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): LocalTrackEntity?

    @Query("SELECT * FROM local_tracks")
    suspend fun all(): List<LocalTrackEntity>

    @Upsert
    suspend fun upsertAll(tracks: List<LocalTrackEntity>)

    /**
     * 标记-清除（**按来源通道**）。
     *
     * ⚠️ 必须带 sourceKind：MediaStore 扫描只该清理 media_store 行，
     * 否则每次扫媒体库都会把用户 SAF 授权的目录导入的行全删掉（同一首歌两条 uri，互不代表对方不存在）。
     */
    @Query("DELETE FROM local_tracks WHERE sourceKind = :kind AND lastScanBatch != :batch")
    suspend fun deleteStaleByKind(kind: String, batch: Long)

    /** 标记-清除（按授权目录）：重新扫某个 SAF 目录时，只清这个目录带来的旧行 */
    @Query("DELETE FROM local_tracks WHERE safRootUri = :rootUri AND lastScanBatch != :batch")
    suspend fun deleteStaleBySafRoot(rootUri: String, batch: Long)

    /** 用户移除某个授权目录时，连带清掉它贡献的全部行 */
    @Query("DELETE FROM local_tracks WHERE safRootUri = :rootUri")
    suspend fun deleteBySafRoot(rootUri: String)

    @Query("DELETE FROM local_tracks")
    suspend fun clear()
}