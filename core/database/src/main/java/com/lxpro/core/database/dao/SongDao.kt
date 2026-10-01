package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(songs: List<SongEntity>)

    @Query("DELETE FROM songs")
    suspend fun clear()
}