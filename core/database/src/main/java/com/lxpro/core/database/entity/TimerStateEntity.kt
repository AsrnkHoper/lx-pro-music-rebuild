package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 两个独立定时器的状态（17 §7）。
 *
 * ⚠️ **不合并**：「听雨入睡、歌继续」与「定时关掉全部」是两个不同诉求（17 §7）。
 *
 * - id="song"：歌曲定时关闭；到点先淡出 3s 再停；可选「播完当前歌曲」
 *   额外开关：「同时停止白噪音」（默认开）—— 这是唯一重叠处理
 * - id="noise"：白噪音睡眠定时；到点白噪音淡出 3s 再暂停，**歌曲照常播**
 *
 * [targetKind] 取值（与 17 §9 `SleepTarget` 对齐）：
 * - "off"：未开启
 * - "after_minutes"：[valueMinutes] 分钟后停
 * - "after_current_track"：仅歌曲定时有，播完当前歌曲（17 §7）
 * - "until_hour"：到早上 [targetHour] 点（默认 7）
 */
@Entity(tableName = "timer_states")
data class TimerStateEntity(
    /** "song" 或 "noise" —— 与 [Scope] 对齐 */
    @PrimaryKey
    val id: String,
    val targetKind: String,
    /** 仅 [targetKind] = "after_minutes" 时有值 */
    val valueMinutes: Int? = null,
    /** 仅 [targetKind] = "until_hour" 时有值（24 小时制，0..23） */
    val targetHour: Int? = null,
    /** 仅歌曲定时：到点同时停止白噪音（默认开，17 §7） */
    val stopNoiseToo: Boolean = true,
    /**
     * 仅歌曲定时：到点时**等当前歌曲播完**再停（问题2，用户 2026-10-20 要求）。
     * 默认 false=到点立刻淡出 3s 停；true=到点后挂起，等 STATE_ENDED 由 PlayerControllerImpl
     * 的 stopAfterCurrentTrack 机制触发 pause（不会播下一首）。
     *
     * 与 [TargetKind.AFTER_CURRENT_TRACK] 的区别：那个是**纯锚点**模式（不设时间），
     * 这个是「有时间但时间到后等歌自然结束」的叠加态。
     */
    val finishSongFirst: Boolean = false,
    /** 设置时间戳（用于计算剩余分钟，17 §4「当前混音」副标题显示） */
    val setAt: Long,
) {
    enum class Scope(val id: String) {
        SONG("song"),
        NOISE("noise"),
    }
    enum class TargetKind(val id: String) {
        OFF("off"),
        AFTER_MINUTES("after_minutes"),
        AFTER_CURRENT_TRACK("after_current_track"),
        UNTIL_HOUR("until_hour"),
    }
}
