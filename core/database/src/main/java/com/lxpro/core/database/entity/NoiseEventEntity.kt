package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 白噪音收听账本（**一段连续收听 = 一行**）。
 *
 * 口径（2026-10-26 用户裁决）：
 * - 白噪音也要被统计，与听歌统计同权，但**独立成表**——两者的维度完全不同
 *   （歌曲有歌名/歌手/专辑，白噪音只有音源层与场景），混表会让两边的聚合互相污染。
 * - 「单独听」与「与歌曲共同播放」不是看设置开关，而是看**真实是否同时有歌在播**：
 *   即使用户开了「与歌曲同时播放」，只要那一刻没有歌在响，本段仍按单独聆听计入。
 *   故这里记 [musicMs]（本段中歌曲同时在播的毫秒数），`durationMs - musicMs` 即独立聆听时长。
 *
 * 一段会话的音源集合取**本次会话期间出现过的全部图层**（中途加减图层不会拆成多段）。
 */
@Entity(
    tableName = "noise_events",
    indices = [Index("startedAt")],
)
data class NoiseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 会话开始（墙上时钟，毫秒） */
    val startedAt: Long,
    /** 会话结束（墙上时钟，毫秒） */
    val endedAt: Long,
    /** 白噪音实际在播时长（毫秒） */
    val durationMs: Long,
    /** 上述时长中「歌曲同时也在播」的部分（毫秒），0 = 全程单独听 */
    val musicMs: Long = 0L,
    /** 本次会话出现过的音源 id（JSON 字符串数组） */
    val soundIds: String = "[]",
    /** 与 [soundIds] 同序的显示名（JSON 字符串数组） */
    val soundNames: String = "[]",
    /** 场景名；用户是逐个叠图层而非选场景时为空 */
    val sceneName: String? = null,
)
