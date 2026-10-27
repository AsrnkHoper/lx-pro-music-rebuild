package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * EQ 收藏集合内的条目（17 §5.7）。
 *
 * 集合是多对多关系（一条曲线可在多个集合，一个集合含多条曲线）—— 用本表表达关联。
 *
 * ⚠️ 集合卡片上直接列出里面曲线的别名 —— 不点进去也知道装了啥（17 §5.7）。
 *
 * ⚠️ 删除集合时级联删除条目（[parent] = [EqFavoriteCollectionEntity]）。
 */
@Entity(
    tableName = "eq_favorite_items",
    foreignKeys = [
        ForeignKey(
            entity = EqFavoriteCollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("collectionId"), Index("collectionId", "presetName", unique = true)],
)
data class EqFavoriteItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val collectionId: String,
    /** 原始型号名（与 [EqAliasEntity.presetName] 一致） */
    val presetName: String,
    val addedAt: Long,
)
