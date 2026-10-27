package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户导入的 EQ 预设（17 §5.3「导入预设」：AutoEQ / PowerAMP 曲线文件）。
 *
 * ⚠️ 与 assets 里的 142 条内置曲线**分开存**：内置资产是只读、随版本升级整体替换，
 * 导入的属于用户数据，必须能单独删、且升级不丢。
 *
 * @param name 显示名（通常取文件名，用户可在「别名」里再改）
 * @param gains 32 个峰值段增益 dB，逗号分隔（与 [com.lxpro.core.media.eq.EQ_PEAK_FREQS] 对齐）
 * @param source 来源格式标记（graphic_eq / parametric_eq），列表里溯源用
 */
@Entity(tableName = "eq_user_presets")
data class EqUserPresetEntity(
    @PrimaryKey
    val name: String,
    val gains: String,
    val source: String,
    val createdAt: Long,
)
