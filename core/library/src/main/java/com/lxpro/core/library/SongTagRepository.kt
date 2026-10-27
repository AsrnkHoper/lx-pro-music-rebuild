package com.lxpro.core.library

import com.lxpro.core.database.dao.SongTagDao
import com.lxpro.core.database.dao.StatsEventDao
import com.lxpro.core.database.entity.SongTagEntity
import com.lxpro.core.database.entity.StatsEventEntity
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌曲的「软件自贴标签」原始数据（2026-10-26 用户要求第 8 项）。
 *
 * 全部由 `stats_events` 实时聚合，**不落库**——统计账本变了，标签自然跟着变，
 * 不会出现「标签说 37 次、统计页说 40 次」的两套账。
 */
data class SongTagFacts(
    /** 第一次遇见（最早一条播放记录的时间） */
    val firstPlayedAt: Long? = null,
    /** 最近一次播放 */
    val lastPlayedAt: Long? = null,
    /** 计入统计口径的播放次数（≥50% 且 ≥30 秒） */
    val plays: Int = 0,
    /** 起播次数（含没听够门槛的），用于播放次数为 0 时如实说明 */
    val playStarts: Int = 0,
    /** 累计实际收听时长（毫秒） */
    val listenedMs: Long = 0,
    /** 最常听的整点（0–23）；没有记录为 null */
    val peakHour: Int? = null,
    /** 平均听完的比例（千分比 0–1000）；时长未知为 null */
    val completionPermille: Int? = null,
    /** 有播放记录的天数 */
    val activeDays: Int = 0,
)

/**
 * 歌曲标签仓储：用户文字标签（`song_tags`）+ 软件自贴标签（`stats_events` 聚合）。
 */
@Singleton
class SongTagRepository @Inject constructor(
    private val tagDao: SongTagDao,
    private val statsDao: StatsEventDao,
) {

    fun observeUserTags(song: Song): Flow<List<SongTagEntity>> =
        tagDao.observeFor(song.id, song.source.value)

    fun observeFacts(song: Song): Flow<SongTagFacts> =
        statsDao.observeForSong(song.id, song.source.value).map(::factsOf)

    /** 贴一个标签；成功返回 null，失败返回给用户看的说明 */
    suspend fun addTag(song: Song, raw: String): String? {
        val tag = raw.trim().removePrefix("#").trim().take(MAX_TAG_LENGTH)
        if (tag.isBlank()) return "标签不能为空"
        val songId = song.id
        val source = song.source.value
        if (tagDao.countFor(songId, source) >= MAX_TAGS_PER_SONG) {
            return "每首歌最多 $MAX_TAGS_PER_SONG 个标签"
        }
        val inserted = tagDao.insert(
            SongTagEntity(songId = songId, source = source, tag = tag, createdAt = System.currentTimeMillis()),
        )
        return if (inserted == -1L) "已经贴过「$tag」了" else null
    }

    suspend fun removeTag(tag: SongTagEntity) = tagDao.deleteById(tag.id)

    private fun factsOf(rows: List<StatsEventEntity>): SongTagFacts {
        if (rows.isEmpty()) return SongTagFacts()
        val zone = ZoneId.systemDefault()
        val hours = IntArray(24)
        val days = HashSet<String>()
        rows.forEach { row ->
            val at = Instant.ofEpochMilli(row.playedAt).atZone(zone)
            hours[at.hour]++
            days += at.toLocalDate().toString()
        }
        val played = rows.sumOf { it.playedDurationMs }
        val total = rows.sumOf { it.totalDurationMs }
        return SongTagFacts(
            firstPlayedAt = rows.minOf { it.playedAt },
            lastPlayedAt = rows.maxOf { it.playedAt },
            plays = rows.count { it.isEffective },
            playStarts = rows.size,
            listenedMs = played,
            peakHour = hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 },
            completionPermille = if (total > 0L) ((played.toDouble() / total) * 1000).toInt().coerceIn(0, 1000) else null,
            activeDays = days.size,
        )
    }

    companion object {
        const val MAX_TAG_LENGTH = 12
        const val MAX_TAGS_PER_SONG = 12
    }
}
