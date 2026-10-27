package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.ArtistIconOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistIconOverrideDao {

    @Query("SELECT * FROM artist_icon_overrides")
    fun observeAll(): Flow<List<ArtistIconOverrideEntity>>

    /** 全量备份导出用（一次性） */
    @Query("SELECT * FROM artist_icon_overrides")
    suspend fun getAll(): List<ArtistIconOverrideEntity>

    @Upsert
    suspend fun upsert(entity: ArtistIconOverrideEntity)

    /** 全量备份导入：整批 upsert（同一 artistKey 覆盖） */
    @Upsert
    suspend fun upsertAll(rows: List<ArtistIconOverrideEntity>)

    @Query("DELETE FROM artist_icon_overrides WHERE artistKey = :artistKey")
    suspend fun delete(artistKey: String)
}
