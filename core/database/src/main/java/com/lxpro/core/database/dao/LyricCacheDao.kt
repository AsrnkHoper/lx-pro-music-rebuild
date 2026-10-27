package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.LyricCacheEntity

@Dao
interface LyricCacheDao {

    @Query("SELECT * FROM lyric_cache WHERE source = :source AND songId = :songId LIMIT 1")
    suspend fun get(source: String, songId: String): LyricCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricCacheEntity)

    @Query("DELETE FROM lyric_cache WHERE cachedAt < :beforeMs")
    suspend fun pruneExpired(beforeMs: Long): Int

    @Query("DELETE FROM lyric_cache")
    suspend fun clear()
}
