package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 白噪音本地导入音源（17 §3「我的白噪音 · 本地导入」）。
 *
 * 与 [NoiseCatalog] 的 23 条 CDN 内置源**同权**：可叠进混音、可存组合、不经过 EQ。
 *
 * - 一条导入音源一般 = 一个音频文件（「雨声 8 小时.mp3」这种整段连续白噪音）
 * - 段清单由导入时解析，绝大多数只有 1 段；事件型 / 分段型音源暂不支持导入
 * - uri 存 SAF 授权的 content:// 或 file://
 *
 * ⚠️ 这是**独立数据源**：与歌曲本地库（LocalTrackEntity）完全分离，因为：
 *   ① 导入的白噪音**不解析歌曲标签**、不进 MediaStore
 *   ② 白噪音不显示在歌曲列表、不参与 EQ
 *   ③ 歌曲库走 SAF 扫描，白噪音走用户显式导入
 */
@Entity(tableName = "noise_user_sources")
data class NoiseUserSourceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** 六类之一（与 NoiseCategory label 对应） */
    val category: String,
    /** 完整可播 URI：content://（SAF 授权）或 file://（直接路径） */
    val uri: String,
    /** true=事件型（两次发声间随机静音）；本地导入绝大多数连续型，留 0 */
    val minSilenceSeconds: Int = 0,
    val maxSilenceSeconds: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)
