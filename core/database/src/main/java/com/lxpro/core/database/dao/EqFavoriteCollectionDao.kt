package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.EqFavoriteCollectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EqFavoriteCollectionDao {
    @Query("SELECT * FROM eq_favorite_collections ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<EqFavoriteCollectionEntity>>

    @Query("SELECT * FROM eq_favorite_collections WHERE id = :id")
    suspend fun getById(id: String): EqFavoriteCollectionEntity?

    /** 同名集合检测（17 §5.7 用户订正：**不允许同时存在两个同名集合**）；大小写不敏感 */
    @Query("SELECT id FROM eq_favorite_collections WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findIdByName(name: String): String?

    @Upsert
    suspend fun upsert(entity: EqFavoriteCollectionEntity)

    @Query("UPDATE eq_favorite_collections SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    /** 级联删除条目（EqFavoriteItemEntity 的外键 onDelete = CASCADE） */
    @Query("DELETE FROM eq_favorite_collections WHERE id = :id")
    suspend fun delete(id: String)
}
