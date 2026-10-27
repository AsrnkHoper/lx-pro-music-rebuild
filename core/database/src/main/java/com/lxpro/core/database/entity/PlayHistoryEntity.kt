package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放历史（每次起播一行，append-only）。
 *
 * 存在的理由：首页「最近播放」「常听歌手」两个货架、以及统计页的全部区块都要它，
 * 而它们**无服务端可算**（`mockup_home.html` 的「刻意不做」明确写了没有服务端）。
 *
 * 为什么要 `songJson`：点「最近播放」要能直接起播，而在线播放需要 `raw` 里的源特有字段，
 * 只存歌名艺人是不够的。口径与 `playlist_items.songJson` 一致。
 *
 * 统计口径（v8 起，**继承前作 LX Music 的账本口径**，见 03 §6.1 与 06「统计一代」）：
 * - [playedDurationMs]：**实际播放时长**（挂机/暂停不计），不是整首时长
 * - [totalDurationMs]：该次播放的总时长（播放器报告值，导入时取歌曲 interval）
 * - [isEffective]：是否入账 —— 入账门槛「播放 ≥ 总时长 50% 且 ≥ 30 秒」
 *
 * [albumName] 单独成列（而不是只留在 songJson 里）是为了统计页的「专辑排行 / 最常听专辑」——
 * 那些区块要按专辑分组，2500+ 行逐条反序列化 JSON 没有必要。
 */
@Entity(
    tableName = "play_history",
    indices = [Index("playedAt"), Index("singer")],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val source: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val coverUrl: String?,
    val songJson: String,
    val playedAt: Long,
    /** 实际播放时长（毫秒）；0 = 尚未回填 */
    val playedDurationMs: Long = 0L,
    /** 播放总时长（毫秒）；0 = 未知，统计时按 0 处理 */
    val totalDurationMs: Long = 0L,
    /** 是否达到入账门槛（≥50% 且 ≥30 秒） */
    val isEffective: Boolean = false,
)