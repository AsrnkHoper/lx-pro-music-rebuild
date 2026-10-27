package com.lxpro.core.stats

import com.lxpro.core.database.dao.PlayHistoryDao
import com.lxpro.core.database.entity.PlayHistoryEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.PlaybackHistorySink
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 「常听歌手」的一行 */
data class TopArtist(val singer: String, val trackCount: Int)

/**
 * 入账门槛（**继承前作 LX Music 的统计口径**，见 06「统计一代」与旧项目 `statsThreshold.ts`）：
 *
 * 四种统计方式（`stats.priority`，统计页「配置」子页可调）：
 * - `time` 仅秒数：播放 ≥ 最低计入秒数
 * - `ratio` 仅比例：总时长已知时播放比例 ≥ 最低比例；总时长未知回落秒数
 * - `timeFirst` 都满足·优先检查秒数（默认）：先查秒数再查比例
 * - `ratioFirst` 都满足·优先检查比例：先查比例再查秒数
 *
 * 阈值写入时取最新值，调整只影响之后的新记录，历史流水不重算。
 * 比例比较用整数交叉乘（与旧作浮点 `playTime/maxTime >= minRatio` 等价，无精度漂移）。
 */
fun isEffectivePlay(
    playedMs: Long,
    totalMs: Long,
    minPlayMs: Long = DEFAULT_MIN_PLAY_MS,
    minRatioPermille: Int = DEFAULT_MIN_RATIO_PERMILLE,
    priority: String = PRIORITY_TIME_FIRST,
): Boolean {
    val timeOk = playedMs >= minPlayMs
    val ratioOk = totalMs > 0L && playedMs * 1000L >= totalMs * minRatioPermille
    return when (priority) {
        PRIORITY_TIME -> timeOk
        PRIORITY_RATIO -> if (totalMs > 0L) ratioOk else timeOk
        PRIORITY_RATIO_FIRST -> {
            if (totalMs > 0L) {
                if (!ratioOk) return false
            } else if (!timeOk) return false
            timeOk
        }
        else -> { // timeFirst（默认）：都满足，秒数优先
            if (!timeOk) return false
            if (totalMs > 0L && !ratioOk) return false
            true
        }
    }
}

const val PRIORITY_TIME = "time"
const val PRIORITY_RATIO = "ratio"
const val PRIORITY_TIME_FIRST = "timeFirst"
const val PRIORITY_RATIO_FIRST = "ratioFirst"

private const val DEFAULT_MIN_PLAY_MS = 30_000L
private const val DEFAULT_MIN_RATIO_PERMILLE = 500

/** 当前统计口径 → 最低计入毫秒（DataStore 有内存缓存，按写入频次读完全够用） */
internal suspend fun SettingsRepository.minPlayMs(): Long = statsMinPlaySeconds.first() * 1000L

/** 当前统计口径 → 最低播放比例（千分比，50% = 500） */
internal suspend fun SettingsRepository.minRatioPermille(): Int = statsMinPlayRatio.first() * 10

/** 当前统计方式（time/ratio/timeFirst/ratioFirst） */
internal suspend fun SettingsRepository.statsPriorityMode(): String = statsPriority.first()

/**
 * **播放历史**（`play_history`）—— 首页「最近播放 / 常听歌手」两个货架的来源。
 *
 * ⚠️ 与统计账本 [StatsLedgerRepository] **是两张表、两个功能**（2026-10-03 用户纠正）：
 * 本表对应旧项目 `lx_backup.lxmc` 里的 `playHistory`（LX-Music 自带的播放历史），
 * 被「导入歌单备份」覆盖；统计页**不读**本表。
 */
@Singleton
class PlayHistoryRepository @Inject constructor(
    private val dao: PlayHistoryDao,
    private val settings: SettingsRepository,
) : PlaybackHistorySink {

    override suspend fun onSongStarted(song: Song) {
        dao.insert(song.toHistoryRow())
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

    /** 最近播放（同一首歌只保留最近一次）；显示名由调用方套用「重命名」修正 */
    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<Song>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toSong() } }

    fun observeTopArtists(limit: Int = ARTIST_LIMIT): Flow<List<TopArtist>> =
        dao.observeTopArtists(limit).map { rows ->
            rows.map { TopArtist(it.singer, it.trackCount) }
        }

    /**
     * 「常听歌手」自定义图标的封面候选：该歌手（按统计归并键 [StatsCalculator.normalizeArtist]）
     * 在播放历史里出现过的封面 —— 去重、最近播放在前。一次性查询，进弹层时才调。
     */
    suspend fun artistCoverCandidates(artistKey: String): List<String> =
        dao.listSingerCovers()
            .asSequence()
            .filter { StatsCalculator.normalizeArtist(it.singer) == artistKey }
            .map { it.coverUrl }
            .distinct()
            .toList()

    /** 「导入歌单备份」写播放历史：语义是**覆盖**（旧项目 `savePlayHistory` 亦是覆盖） */
    suspend fun replaceAll(rows: List<PlayHistoryEntity>) {
        dao.clear()
        if (rows.isNotEmpty()) dao.insertAll(rows)
    }

    suspend fun clear() = dao.clear()

    private companion object {
        /** 货架只需要最近的一段；旧项目同样对 playHistory 设了上限 */
        const val MAX_ROWS = 2_000
        const val RECENT_LIMIT = 12
        const val ARTIST_LIMIT = 12
    }
}

/** [Song] → 播放历史行（两个仓储共用同一套字段映射） */
internal fun Song.toHistoryRow(
    playedAt: Long = System.currentTimeMillis(),
    playedMs: Long = 0L,
    isEffective: Boolean = false,
): PlayHistoryEntity = PlayHistoryEntity(
    songId = id,
    source = source.value,
    name = name,
    singer = singer,
    albumName = albumName,
    coverUrl = picUrl,
    songJson = Json.encodeToString(Song.serializer(), this),
    playedAt = playedAt,
    // 先落一个名义总时长（统计/时长分档要用），播放结束时再按播放器报告值校正
    totalDurationMs = (interval ?: 0L) * 1000L,
    playedDurationMs = playedMs,
    isEffective = isEffective,
)

internal fun PlayHistoryEntity.toSong(): Song =
    Json.decodeFromString(Song.serializer(), songJson)