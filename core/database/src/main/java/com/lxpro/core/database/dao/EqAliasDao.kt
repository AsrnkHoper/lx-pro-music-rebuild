package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.EqAliasEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EqAliasDao {
    /** 全部别名映射（UI 渲染列表时一并加载） */
    @Query("SELECT * FROM eq_aliases")
    fun observeAll(): Flow<List<EqAliasEntity>>

    @Query("SELECT * FROM eq_aliases WHERE presetName = :presetName")
    suspend fun get(presetName: String): EqAliasEntity?

    /** 非破坏性：仅写入映射；原始型号名不在本表（17 §5.6） */
    @Upsert
    suspend fun upsert(entity: EqAliasEntity)

    /** 还原原名 = 删别名（17 §5.6） */
    @Query("DELETE FROM eq_aliases WHERE presetName = :presetName")
    suspend fun delete(presetName: String)
}
