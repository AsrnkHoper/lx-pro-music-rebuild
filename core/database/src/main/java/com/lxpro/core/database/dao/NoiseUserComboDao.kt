package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.NoiseUserComboEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoiseUserComboDao {
    @Query("SELECT * FROM noise_user_combos ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoiseUserComboEntity>>

    @Query("SELECT * FROM noise_user_combos WHERE id = :id")
    suspend fun getById(id: String): NoiseUserComboEntity?

    @Upsert
    suspend fun upsert(entity: NoiseUserComboEntity)

    @Query("DELETE FROM noise_user_combos WHERE id = :id")
    suspend fun delete(id: String)
}
