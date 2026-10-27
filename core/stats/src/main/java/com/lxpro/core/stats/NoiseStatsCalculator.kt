package com.lxpro.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * 一段白噪音收听（= `noise_events` 的一行）。
 *
 * [musicMs] 是**本段中歌曲同时也在播**的毫秒数；`durationMs - musicMs` 即「单独听」。
 * 这里刻意不用设置里的「与歌曲同时播放」开关来判断 —— 开关开着但没歌在放，
 * 仍按单独聆听计入（用户 2026-10-26 明确要求）。
 */
data class NoiseSession(
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val musicMs: Long,
    val soundIds: List<String>,
    val soundNames: List<String>,
    val sceneName: String?,
)

/** 白噪音声音排行的一行（多图层会话会给每个声音都计一遍，代表「这个声音陪了你多久」） */
data class NoiseSoundRank(
    val id: String,
    val name: String,
    val durationMs: Long,
    val sessions: Int,
)

/** 24 小时分布：`durationMs` 是落在该小时内的收听时长（不是次数） */
data class NoiseHourBucket(val hour: Int, val durationMs: Long)

/** 「白噪音」子页 Hero 的三个数字（固定按「全部」算，与听歌 Hero 同口径） */
data class NoiseOverview(
    val totalDurationMs: Long,
    val sessionCount: Int,
    val activeDays: Int,
)

data class NoiseStatsSnapshot(
    val range: StatsRange,
    val rangeSpan: String,
    val rangeNote: String,
    val hasData: Boolean,
    // ① 收听报告
    val totalMs: Long,
    val sessionCount: Int,
    val activeDays: Int,
    val dailyAvgMs: Long,
    // ② 聆听方式：单独 / 伴随歌曲
    val standaloneMs: Long,
    val withMusicMs: Long,
    // ③ 活跃时段（按收听时长）
    val hourly: List<NoiseHourBucket>,
    val peakHour: Int,
    val peakHourMs: Long,
    // ④ 趋势（近 14 天）
    val trend: List<DayPoint>,
    val trendCards: TrendCards,
    // ⑤ 声音排行 / 场景排行
    val sounds: List<NoiseSoundRank>,
    val scenes: List<NoiseSoundRank>,
    // ⑥ 收听习惯
    val habits: List<Metric>,
)

/**
 * 白噪音统计聚合（**纯函数**，不碰数据库 / Android），口径对齐听歌统计：
 *
 * - 区间裁剪：跨区间的会话按重叠部分计入（不是「开始时在区间内就算整段」），
 *   否则 23:58 开始、00:30 结束的一段会把整段算进昨天。
 * - `musicMs` 按重叠比例分摊（账本只记了整段的伴随时长）。
 * - 声音排行按「每个声音各自被陪伴的时长」累加，多图层会话会重复计入总分母，
 *   因此排行条的百分比用「相对榜首」而不是「占总量」。
 */
object NoiseStatsCalculator {

    private const val TREND_WINDOW_DAYS = 14
    private val dayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

    fun compute(
        sessions: List<NoiseSession>,
        range: StatsRange,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): NoiseStatsSnapshot {
        val from = StatsCalculator.rangeStartMs(range, nowMs, zone)
        val clipped = sessions.mapNotNull { clip(it, from, nowMs) }
        val totalMs = clipped.sumOf { it.durationMs }
        val musicMs = clipped.sumOf { it.musicMs }

        // 每日 / 每小时分布：按真实时段切片，跨天跨小时都正确
        val dailyMs = HashMap<Long, Long>()
        val hourlyMs = LongArray(24)
        clipped.forEach { s ->
            forEachDaySlice(s.startedAt, s.endedAt, zone) { epochDay, ms ->
                dailyMs[epochDay] = (dailyMs[epochDay] ?: 0L) + ms
            }
            forEachHourSlice(s.startedAt, s.endedAt, zone) { hour, ms ->
                hourlyMs[hour] += ms
            }
        }

        val peakHour = hourlyMs.indices.maxByOrNull { hourlyMs[it] } ?: 0
        val peakHourMs = hourlyMs[peakHour]

        val activeDays = dailyMs.count { it.value > 0L }
        val dailyAvgMs = if (activeDays > 0) totalMs / activeDays else 0L
        val longest = clipped.maxOfOrNull { it.durationMs } ?: 0L
        val avgSession = if (clipped.isEmpty()) 0L else totalMs / clipped.size

        val trend = trend(sessions, nowMs, zone)
        val trendCards = trendCards(sessions, nowMs, zone)

        return NoiseStatsSnapshot(
            range = range,
            rangeSpan = spanLabel(range, from, nowMs, sessions, zone),
            rangeNote = rangeNote(range, nowMs, zone),
            hasData = totalMs > 0L,
            totalMs = totalMs,
            sessionCount = clipped.size,
            activeDays = activeDays,
            dailyAvgMs = dailyAvgMs,
            standaloneMs = (totalMs - musicMs).coerceAtLeast(0L),
            withMusicMs = musicMs.coerceAtLeast(0L),
            hourly = (0..23).map { NoiseHourBucket(it, hourlyMs[it]) },
            peakHour = peakHour,
            peakHourMs = peakHourMs,
            trend = trend,
            trendCards = trendCards,
            sounds = soundRanks(clipped),
            scenes = sceneRanks(clipped),
            habits = habits(clipped, peakHour, avgSession, longest),
        )
    }

    /** Hero 卡：永远按「全部」算（与听歌 Hero 同口径） */
    fun overview(
        sessions: List<NoiseSession>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): NoiseOverview {
        val valid = sessions.filter { it.startedAt <= nowMs && it.durationMs > 0L }
        return NoiseOverview(
            totalDurationMs = valid.sumOf { it.durationMs },
            sessionCount = valid.size,
            activeDays = valid.map { dayKey(it.startedAt, zone) }.toSet().size,
        )
    }

    /** 把一段会话裁到 `[fromMs, nowMs]`，伴随时长按比例缩放 */
    private fun clip(session: NoiseSession, fromMs: Long, nowMs: Long): NoiseSession? {
        val start = max(session.startedAt, fromMs)
        val end = minOf(session.endedAt, nowMs)
        val total = session.endedAt - session.startedAt
        val kept = end - start
        if (kept <= 0L || total <= 0L) return null
        val music = if (session.musicMs <= 0L) 0L else session.musicMs * kept / total
        return session.copy(
            startedAt = start,
            endedAt = end,
            durationMs = kept,
            musicMs = music.coerceIn(0L, kept),
        )
    }

    private inline fun forEachDaySlice(
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        action: (epochDay: Long, ms: Long) -> Unit,
    ) {
        var cursor = startMs
        while (cursor < endMs) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(nextDay, endMs)
            action(date.toEpochDay(), sliceEnd - cursor)
            cursor = sliceEnd
        }
    }

    private inline fun forEachHourSlice(
        startMs: Long,
        endMs: Long,
        zone: ZoneId,
        action: (hour: Int, ms: Long) -> Unit,
    ) {
        var cursor = startMs
        while (cursor < endMs) {
            val zdt = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = zdt.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plusHours(1).toInstant().toEpochMilli()
            val sliceEnd = minOf(nextHour, endMs)
            action(zdt.hour, sliceEnd - cursor)
            cursor = sliceEnd
        }
    }

    /** 近 14 天趋势（不受档位影响，与听歌趋势一致的口径） */
    private fun trend(
        sessions: List<NoiseSession>,
        nowMs: Long,
        zone: ZoneId,
    ): List<DayPoint> {
        val today = dayKey(nowMs, zone)
        val byDay = sessionDayMs(sessions, zone)
        return (TREND_WINDOW_DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayPoint(date.format(dayLabel), byDay[date] ?: 0L)
        }
    }

    private fun sessionDayMs(sessions: List<NoiseSession>, zone: ZoneId): Map<LocalDate, Long> {
        val result = HashMap<LocalDate, Long>()
        sessions.forEach { s ->
            forEachDaySlice(s.startedAt, s.endedAt, zone) { epochDay, ms ->
                val date = LocalDate.ofEpochDay(epochDay)
                result[date] = (result[date] ?: 0L) + ms
            }
        }
        return result
    }

    private fun trendCards(
        sessions: List<NoiseSession>,
        nowMs: Long,
        zone: ZoneId,
    ): TrendCards {
        val today = dayKey(nowMs, zone)
        val byDay = sessionDayMs(sessions, zone)
        val window = trend(sessions, nowMs, zone)
        val peak = window.maxByOrNull { it.dayMs }
        val last7 = (0..6).sumOf { byDay[today.minusDays(it.toLong())] ?: 0L }
        val prev7 = (7..13).sumOf { byDay[today.minusDays(it.toLong())] ?: 0L }
        val ratio = if (prev7 > 0L) (last7 - prev7).toDouble() / prev7 else 0.0
        val workdays = (0..13).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek.value <= 5 }
            .map { byDay[it] ?: 0L }
        val weekends = (0..13).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek.value >= 6 }
            .map { byDay[it] ?: 0L }
        val workdayAvg = if (workdays.isEmpty()) 0L else workdays.sum() / workdays.size
        val weekendAvg = if (weekends.isEmpty()) 0L else weekends.sum() / weekends.size
        return TrendCards(
            peakValue = StatsCalculator.formatHours(peak?.dayMs ?: 0L),
            peakLabel = peak?.label ?: "—",
            last7Value = StatsCalculator.formatHours(last7),
            last7Label = when {
                prev7 <= 0L -> "无上期对照"
                ratio >= 0 -> "环比 +${(ratio * 100).toInt()}%"
                else -> "环比 ${(ratio * 100).toInt()}%"
            },
            workdayValue = StatsCalculator.formatHours(workdayAvg),
            workdayLabel = "周末 ${StatsCalculator.formatHours(weekendAvg)}",
        )
    }

    private fun soundRanks(sessions: List<NoiseSession>): List<NoiseSoundRank> {
        val durations = LinkedHashMap<String, Long>()
        val names = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        sessions.forEach { s ->
            s.soundIds.forEachIndexed { index, id ->
                if (id.isBlank()) return@forEachIndexed
                durations[id] = (durations[id] ?: 0L) + s.durationMs
                names[id] = s.soundNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: id
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        return durations.entries
            .sortedByDescending { it.value }
            .take(8)
            .map { (id, ms) ->
                NoiseSoundRank(
                    id = id,
                    name = names[id] ?: id,
                    durationMs = ms,
                    sessions = counts[id] ?: 0,
                )
            }
    }

    private fun sceneRanks(sessions: List<NoiseSession>): List<NoiseSoundRank> {
        val durations = LinkedHashMap<String, Long>()
        val counts = HashMap<String, Int>()
        sessions.forEach { s ->
            val name = s.sceneName?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            durations[name] = (durations[name] ?: 0L) + s.durationMs
            counts[name] = (counts[name] ?: 0) + 1
        }
        return durations.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { (name, ms) ->
                NoiseSoundRank(id = name, name = name, durationMs = ms, sessions = counts[name] ?: 0)
            }
    }

    private fun habits(
        sessions: List<NoiseSession>,
        peakHour: Int,
        avgSessionMs: Long,
        longestMs: Long,
    ): List<Metric> = listOf(
        Metric(StatsCalculator.formatHours(avgSessionMs), "平均每次"),
        Metric(StatsCalculator.formatHours(longestMs), "最长一次"),
        Metric("$peakHour 时", "最常听时段"),
        Metric("${sessions.size} 段", "收听段数"),
    )

    private fun dayKey(ms: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun spanLabel(
        range: StatsRange,
        fromMs: Long,
        nowMs: Long,
        sessions: List<NoiseSession>,
        zone: ZoneId,
    ): String {
        val startMs = if (range == StatsRange.ALL) {
            sessions.minOfOrNull { it.startedAt } ?: nowMs
        } else {
            fromMs
        }
        val start = dayKey(startMs, zone).format(dayLabel)
        val end = dayKey(nowMs, zone).format(dayLabel)
        return if (start == end) start else "$start – $end"
    }

    private fun rangeNote(range: StatsRange, nowMs: Long, zone: ZoneId): String {
        val date = dayKey(nowMs, zone)
        return when (range) {
            StatsRange.DAY -> "今天"
            StatsRange.WEEK -> "周一起"
            StatsRange.MONTH -> "月初起"
            StatsRange.QUARTER -> {
                val quarter = (date.monthValue - 1) / 3 + 1
                val firstMonth = (quarter - 1) * 3 + 1
                if (date.monthValue == firstMonth) "Q$quarter 首月 · 与本月同区间" else "Q$quarter"
            }

            StatsRange.YEAR -> "年初起"
            StatsRange.ALL -> "全部记录"
        }
    }
}
