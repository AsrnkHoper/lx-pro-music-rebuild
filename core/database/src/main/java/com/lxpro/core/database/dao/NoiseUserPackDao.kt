package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.NoiseUserPackEntity
import kotlinx.coroutines.flow.Flow

/** 白噪音远端音源包（17 §3） */
@Dao
interface NoiseUserPackDao {

    @Query("SELECT * FROM noise_user_packs ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<NoiseUserPackEntity>>

    @Query("SELECT * FROM noise_user_packs")
    suspend fun getAll(): List<NoiseUserPackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: NoiseUserPackEntity)

    @Query("DELETE FROM noise_user_packs WHERE id = :id")
    suspend fun delete(id: String)
}
