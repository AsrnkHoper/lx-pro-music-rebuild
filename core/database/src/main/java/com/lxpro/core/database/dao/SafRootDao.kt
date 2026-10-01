package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.SafRootEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SafRootDao {

    @Query("SELECT * FROM saf_roots ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<SafRootEntity>>

    @Query("SELECT * FROM saf_roots ORDER BY addedAt ASC")
    suspend fun all(): List<SafRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(root: SafRootEntity)

    @Query("DELETE FROM saf_roots WHERE treeUri = :treeUri")
    suspend fun delete(treeUri: String)
}