package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 听歌统计账本（**与播放历史分开的独立存储**）。
 *
 * 为什么必须是两张表（2026-10-03 用户纠正 + 参考旧项目 `LX_Pro_FinalRebuild_0922`）：
 * 旧项目里这是**两个不同功能**、两套互不干扰的存储 ——
 * - `lx_stats.lxmc`（type `lx_stats_data`）：LX-Pro **新增**的「统计数据管理」，
 *   内含 daily / song / events 三层，见旧项目 `src/core/player/stats.ts`
 * - `lx_backup.lxmc`（type `allData_v3`）：LX-Music **自带**的「备份与恢复」，
 *   内含 lists / playHistory / settings / userApis，见旧项目 `Setting/settings/Backup/actions.ts`
 *
 * 若两者共用一张表，「导入统计」与「导入备份」会互相覆盖 —— 这正是用户指出的
 * 「不是或者的关系」。故：
 * - 本表 = 统计账本，统计页的唯一数据源；被「导入听歌统计」覆盖
 * - `play_history` = 播放历史（货架），被「导入歌单备份」覆盖
 *
 * 字段与 [PlayHistoryEntity] 一致（都来自播放器的同一对事件），但**生命期与用途不同**。
 */
@Entity(
    tableName = "stats_events",
    indices = [Index("playedAt"), Index("singer")],
)
data class StatsEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val source: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val coverUrl: String?,
    val songJson: String,
    val playedAt: Long,
    /** 实际播放时长（毫秒） */
    val playedDurationMs: Long = 0L,
    /** 播放总时长（毫秒） */
    val totalDurationMs: Long = 0L,
    /** 是否达到入账门槛（≥50% 且 ≥30 秒） */
    val isEffective: Boolean = false,
)