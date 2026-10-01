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

    @Query("SELECT COUNT(*) FROM local_tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM local_tracks")
    suspend fun all(): List<LocalTrackEntity>

    @Upsert
    suspend fun upsertAll(tracks: List<LocalTrackEntity>)

    /** 标记-清除：本批次没再扫到的行即视为文件已删除 */
    @Query("DELETE FROM local_tracks WHERE lastScanBatch != :batch")
    suspend fun deleteStale(batch: Long)

    @Query("DELETE FROM local_tracks")
    suspend fun clear()
}