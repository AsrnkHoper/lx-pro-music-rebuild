package com.lxpro.core.stats

import com.lxpro.core.database.dao.StatsEventDao
import com.lxpro.core.database.entity.StatsEventEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.PlaybackHistorySink
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **听歌统计账本**（`stats_events`）—— 统计页的唯一数据源。
 *
 * ⚠️ 这是**与播放历史分开的独立存储**（2026-10-03 用户纠正 + 旧项目 `src/core/player/stats.ts`）：
 * 对应旧项目 `lx_stats.lxmc`（type `lx_stats_data`，LX-Pro **新增**的「统计数据管理」），
 * 旧项目里它是 `stats_daily / stats_song / stats_events` 三块存储，与 `playHistory` 互不相干。
 *
 * 本产品只落 events 一层（在内存里聚合）：一次性导入 2400+ 条事件时，
 * 「全部」档的时间跨度与聚合结果仍完整，而不再维护三层带来的双写复杂度。
 */
@Singleton
class StatsLedgerRepository @Inject constructor(
    private val dao: StatsEventDao,
    private val settings: SettingsRepository,
) : PlaybackHistorySink {

    override suspend fun onSongStarted(song: Song) {
        dao.insert(song.toStatsRow())
        if (dao.count() > MAX_ROWS) dao.trimTo(MAX_ROWS)
    }

    override suspend fun onSongPlayed(song: Song, playedMs: Long, totalMs: Long) {
        val total = if (totalMs > 0L) totalMs else (song.interval ?: 0L) * 1000L
        dao.updatePlayed(
            songId = song.id,
            source = song.source.value,
            playedMs = playedMs,
            totalMs = total,
            effective = isEffectivePlay(
                playedMs, total, settings.minPlayMs(), settings.minRatioPermille(), settings.statsPriorityMode(),
            ),
        )
    }

    /** 统计页读全量流水（聚合由 [StatsCalculator] 的纯函数做，口径可单测） */
    fun observeAll(): Flow<List<StatsEventEntity>> = dao.observeAll()

    /** 「导入听歌统计」的语义 = **覆盖整个账本**（旧项目 `importStatsData` 直接覆盖三层存储） */
    suspend fun replaceAll(rows: List<StatsEventEntity>) {
        if (rows.isEmpty()) {
            dao.clear()
        } else {
            dao.replaceAll(rows)
        }
    }

    suspend fun clear() = dao.clear()

    // ── 统计数据管理（统计页「配置」子页；格式是我们自己的 JSON，与 .lxmc 备份互不相干）──

    /** 导出全量流水为 JSON（`lxpro_stats.json`）；只有有效字段，id 自增列不导 */
    suspend fun exportJson(): String {
        val rows = dao.observeAll().first()
        return buildJsonObject {
            put("type", EXPORT_TYPE)
            put("version", 1)
            put("events", buildJsonArray {
                rows.forEach { row ->
                    add(
                        buildJsonObject {
                            put("songId", row.songId)
                            put("source", row.source)
                            put("name", row.name)
                            put("singer", row.singer)
                            put("albumName", row.albumName)
                            put("coverUrl", row.coverUrl)
                            put("songJson", row.songJson)
                            put("playedAt", row.playedAt)
                            put("playedDurationMs", row.playedDurationMs)
                            put("totalDurationMs", row.totalDurationMs)
                            put("isEffective", row.isEffective)
                        },
                    )
                }
            })
        }.toString()
    }

    /** 导入我们的导出格式：解析失败抛 [IllegalArgumentException]，由界面层转成提示 */
    suspend fun importJson(text: String) {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("不是有效的统计备份文件") }
        if (root["type"]?.jsonPrimitive?.content != EXPORT_TYPE) {
            throw IllegalArgumentException("不是有效的统计备份文件")
        }
        val events = root["events"]?.jsonArray ?: throw IllegalArgumentException("备份里没有事件数据")
        val rows = events.map { element ->
            val o = element.jsonObject
            StatsEventEntity(
                songId = o.str("songId"),
                source = o.str("source"),
                name = o.str("name"),
                singer = o.str("singer"),
                albumName = o.strOrNull("albumName"),
                coverUrl = o.strOrNull("coverUrl"),
                songJson = o.str("songJson"),
                playedAt = o["playedAt"]?.jsonPrimitive?.long ?: 0L,
                playedDurationMs = o["playedDurationMs"]?.jsonPrimitive?.long ?: 0L,
                totalDurationMs = o["totalDurationMs"]?.jsonPrimitive?.long ?: 0L,
                isEffective = o["isEffective"]?.jsonPrimitive?.let { runCatching { it.content.toBoolean() }.getOrDefault(false) } ?: false,
            )
        }
        replaceAll(rows)
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺字段：$key")

    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        /**
         * 旧项目对 events 只保留 90 天；这里按条数封顶（2 万条 ≈ 真实备份密度的 10 个月），
         * 兼顾「全部」档的跨度与库体积。
         */
        const val MAX_ROWS = 20_000

        /** 导出文件的类型标记（导入时校验，防止拿歌单备份误导入） */
        const val EXPORT_TYPE = "lxpro_stats"
    }
}

/** [Song] → 统计事件行（与 `play_history` 同款字段，但写进**另一张表**） */
internal fun Song.toStatsRow(
    playedAt: Long = System.currentTimeMillis(),
    playedMs: Long = 0L,
    isEffective: Boolean = false,
): StatsEventEntity = StatsEventEntity(
    songId = id,
    source = source.value,
    name = name,
    singer = singer,
    albumName = albumName,
    coverUrl = picUrl,
    songJson = Json.encodeToString(Song.serializer(), this),
    playedAt = playedAt,
    totalDurationMs = (interval ?: 0L) * 1000L,
    playedDurationMs = playedMs,
    isEffective = isEffective,
)

internal fun StatsEventEntity.toSong(): Song =
    Json.decodeFromString(Song.serializer(), songJson)