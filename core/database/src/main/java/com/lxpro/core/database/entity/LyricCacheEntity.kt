package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 在线歌曲歌词缓存（2026-10-27 用户反馈「音频和歌词居然不缓存」）。
 *
 * - **source + songId 作主键**：一首歌一个源的歌词只有一份；换源钉住后目标源会写新行，
 *   原源行留着（TTL 到期自动清理），方便切回原源时秒出。
 * - **TTL 策略**：cachedAt + 7 天视为过期，过期行不删、读时忽略、清理任务再处理。
 * - **不存本地歌词**：本地曲目直接读同名 .lrc / 音频内嵌，不走这张表。
 */
@Entity(
    tableName = "lyric_cache",
    primaryKeys = ["source", "songId"],
    indices = [Index("cachedAt")],
)
data class LyricCacheEntity(
    val source: String,
    val songId: String,
    val lyric: String,
    val tlyric: String?,
    val cachedAt: Long,
)
