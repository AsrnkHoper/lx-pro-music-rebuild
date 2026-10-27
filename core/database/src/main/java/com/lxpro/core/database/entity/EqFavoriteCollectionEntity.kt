package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * EQ 收藏集合（17 §5.7）。
 *
 * ⚠️ **是集合，不是扁平喜欢列表**：用户在 142 条预设里挑出来的东西天然按场景分组
 * （通勤 / 夜里 / 工作），扁平列表两周后又是一团乱（17 §5.7）。
 *
 * 集合 → 详情两级跳转（与「我的歌单」同一套交互，**不做同屏**，用户明确要求）。
 * 集合可新建 / 重命名 / 移除单条 / 在详情里直接「应用」（17 §5.7）。
 */
@Entity(tableName = "eq_favorite_collections")
data class EqFavoriteCollectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)
