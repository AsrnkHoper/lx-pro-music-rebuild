package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.SongOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongOverrideDao {

    @Query("SELECT * FROM song_overrides")
    fun observeAll(): Flow<List<SongOverrideEntity>>

    @Query("SELECT * FROM song_overrides")
    suspend fun getAll(): List<SongOverrideEntity>

    @Query("SELECT * FROM song_overrides WHERE songId = :songId AND source = :source")
    suspend fun find(songId: String, source: String): SongOverrideEntity?

    /** 同一首歌重复改名直接覆盖（主键是 `songId + source`） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SongOverrideEntity)

    /** 全量备份导入：整批覆盖写入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<SongOverrideEntity>)
}