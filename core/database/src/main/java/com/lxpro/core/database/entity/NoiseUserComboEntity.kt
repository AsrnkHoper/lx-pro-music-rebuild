package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户自建的「我的组合」（17 §2.1）。
 *
 * 与 [NoisePresetEntity] 分表：用户组合没有「副本解除绑定」语义，且不会被预设还原清掉。
 *
 * ⚠️ [layersJson] 存图层列表的 JSON（List<NoiseLayer>）。
 */
@Entity(tableName = "noise_user_combos")
data class NoiseUserComboEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val layersJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
