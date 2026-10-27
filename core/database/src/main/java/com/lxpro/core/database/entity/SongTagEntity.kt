package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户给歌曲贴的文字标签（2026-10-26 用户要求第 8 项）。
 *
 * 与「软件自贴标签」（首听时间 / 播放次数 / 累计时长…）分开存：
 * 后者不落库，每次由 `stats_events` 实时聚合；本表只放用户自己写的那部分。
 *
 * 唯一键 = `source + songId + tag`：同一首歌重复贴同一个词只留一条（INSERT OR IGNORE）。
 */
@Entity(
    tableName = "song_tags",
    indices = [
        Index(value = ["source", "songId", "tag"], unique = true),
        Index("songId"),
    ],
)
data class SongTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val source: String,
    val tag: String,
    val createdAt: Long,
)
