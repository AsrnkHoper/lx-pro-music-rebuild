package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 白噪音预设场景（17 §2.1）。
 *
 * - 8 个内置预设（雨夜书房 / 海边清晨 / 咖啡馆 / 森林深处 / 壁炉边 / 深夜列车 / 纯色噪音 / 助眠）
 * - 用户改动预设后**自动变「XX 副本」**并解除绑定（17 §2.1）；此时 [parentPresetId]
 *   保留原预设 id，便于「还原预设」
 *
 * ⚠️ [layersJson] 存图层列表的 JSON 序列化（List<NoiseLayer>），由 feature/noise 负责序列化。
 */
@Entity(tableName = "noise_presets")
data class NoisePresetEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** 图层列表的 JSON：[{sourceId,volume}, ...] */
    val layersJson: String,
    /** true=内置预设；false=用户副本（17 §2.1 改动预设后自动副本化） */
    val isBuiltin: Boolean,
    /** 副本对应的原始预设 id（仅副本有值）；用于「还原预设」 */
    val parentPresetId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
