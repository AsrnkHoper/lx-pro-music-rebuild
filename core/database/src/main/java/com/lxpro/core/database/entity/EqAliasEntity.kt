package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * EQ 预设的别名映射（17 §5.6）。
 *
 * ⚠️ **非破坏性**：别名只是显示名映射，原始型号名一个字都不改（17 §5.6）。
 * - 导入新预设、去重、AutoEQ 对版本都要靠原始型号名 [presetName]
 * - 用户随时能「还原原名」（删本表对应行即可）
 *
 * UI 上：有别名时 h2/行标题显示别名，副标题写原型号（17 §5.6）。
 */
@Entity(tableName = "eq_aliases")
data class EqAliasEntity(
    /** 原始型号名（来自 lanmeipai eq_presets.json 的 `name` 字段） */
    @PrimaryKey
    val presetName: String,
    val alias: String,
    val updatedAt: Long,
)
