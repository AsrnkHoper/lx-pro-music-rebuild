package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.NoisePresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoisePresetDao {
    @Query("SELECT * FROM noise_presets ORDER BY isBuiltin DESC, updatedAt ASC")
    fun observeAll(): Flow<List<NoisePresetEntity>>

    @Query("SELECT * FROM noise_presets WHERE id = :id")
    suspend fun getById(id: String): NoisePresetEntity?

    @Query("SELECT * FROM noise_presets WHERE isBuiltin = 1")
    suspend fun getBuiltins(): List<NoisePresetEntity>

    @Upsert
    suspend fun upsert(entity: NoisePresetEntity)

    @Query("DELETE FROM noise_presets WHERE id = :id")
    suspend fun delete(id: String)
}
