package com.lxpro.core.database.entity

import androidx.room.Entity

/**
 * 用户对歌曲元数据的本地修正（`mockup_playlist.html`：重命名歌曲 / 重命名作者）。
 *
 * 为什么要这张表：B 站搬运号的标题是引流长串、作者写成发布者 ID，会被照搬进播放器，
 * 并**污染听歌统计的歌手归并**（真实数据里「DJ Okawari」被拆成 8 个条目）。
 * 因此歌曲名与作者名都允许用户改，且改动**只作用于本地显示**，不回写音源。
 *
 * `name` / `singer` 为 null 表示该字段未改（用音源原值）；两个都为 null 的行没有意义，会被删掉。
 */
@Entity(tableName = "song_overrides", primaryKeys = ["songId", "source"])
data class SongOverrideEntity(
    val songId: String,
    val source: String,
    val name: String?,
    val singer: String?,
    /**
     * 用户钉住的换源目标（候选 Song 的 JSON；04 · 换源，对齐 LX `meta.toggleMusicInfo`）。
     * null = 未换源。v20 新增；旧备份 JSON 没有该键，靠默认值兼容。
     */
    val toggleSongJson: String? = null,
    val updatedAt: Long,
)