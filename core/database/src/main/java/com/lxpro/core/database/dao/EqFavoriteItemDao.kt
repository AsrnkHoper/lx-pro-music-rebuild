package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.EqFavoriteItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EqFavoriteItemDao {
    /**
     * 全部条目（EQ 页：集合卡片预览 + 列表行的星标态都要它）。
     * 数据量 = 用户手动收藏条数，量级极小，全量观察代价可忽略。
     */
    @Query("SELECT * FROM eq_favorite_items")
    fun observeAll(): Flow<List<EqFavoriteItemEntity>>

    /** 某集合内的全部条目（17 §5.7：集合详情页用） */
    @Query("SELECT * FROM eq_favorite_items WHERE collectionId = :collectionId ORDER BY addedAt DESC")
    fun observeByCollection(collectionId: String): Flow<List<EqFavoriteItemEntity>>

    /** 集合卡片上直接列出别名 —— 不点进去也知道装了啥（17 §5.7） */
    @Query("SELECT * FROM eq_favorite_items WHERE collectionId = :collectionId")
    suspend fun getByCollection(collectionId: String): List<EqFavoriteItemEntity>

    /** 某条曲线落在哪些集合（多选弹层的「当前勾选」写回用） */
    @Query("SELECT * FROM eq_favorite_items WHERE presetName = :presetName")
    suspend fun getByPreset(presetName: String): List<EqFavoriteItemEntity>

    /** 同集合内同型号不重复（unique index） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EqFavoriteItemEntity): Long

    @Query("DELETE FROM eq_favorite_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM eq_favorite_items WHERE collectionId = :collectionId AND presetName = :presetName")
    suspend fun removeFromCollection(collectionId: String, presetName: String)
}
