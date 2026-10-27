package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.NoiseUserSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoiseUserSourceDao {
    @Query("SELECT * FROM noise_user_sources ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoiseUserSourceEntity>>

    @Query("SELECT * FROM noise_user_sources WHERE id = :id")
    suspend fun getById(id: String): NoiseUserSourceEntity?

    @Query("SELECT * FROM noise_user_sources")
    suspend fun getAll(): List<NoiseUserSourceEntity>

    @Upsert
    suspend fun upsert(entity: NoiseUserSourceEntity)

    @Query("DELETE FROM noise_user_sources WHERE id = :id")
    suspend fun delete(id: String)
}
