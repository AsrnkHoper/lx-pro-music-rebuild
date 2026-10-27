package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.EqUserPresetEntity
import kotlinx.coroutines.flow.Flow

/** 用户导入的 EQ 预设（17 §5.3 导入预设；重名以型号名为准，导入时即拒绝） */
@Dao
interface EqUserPresetDao {
    /** 全量（内存缓存刷新用；量级 = 用户导入条数，很小） */
    @Query("SELECT * FROM eq_user_presets ORDER BY createdAt ASC")
    suspend fun getAll(): List<EqUserPresetEntity>

    @Query("SELECT * FROM eq_user_presets ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<EqUserPresetEntity>>

    @Query("SELECT * FROM eq_user_presets WHERE name = :name")
    suspend fun getByName(name: String): EqUserPresetEntity?

    @Upsert
    suspend fun upsert(entity: EqUserPresetEntity)

    @Query("DELETE FROM eq_user_presets WHERE name = :name")
    suspend fun delete(name: String)
}
