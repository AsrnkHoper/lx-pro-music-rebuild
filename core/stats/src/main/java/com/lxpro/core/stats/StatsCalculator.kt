package com.lxpro.core.stats

import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceLabels
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 六档时间维度（15 §2 / mockup_app.html 的 `RANGES`） */
enum class StatsRange(val key: String, val label: String) {
    DAY("day", "今天"),
    WEEK("week", "本周"),
    MONTH("month", "本月"),
    QUARTER("quarter", "本季"),
    YEAR("year", "本年"),
    ALL("all", "全部"),
    ;

    companion object {
        /** 默认档「今天」（批次三 #6：用户要求统计页默认展示当天数据） */
        val default: StatsRange = DAY
        fun fromKey(key: String?): StatsRange = entries.firstOrNull { it.key == key } ?: default
    }
}

/** 统计的输入事件（已套用「重命名」修正、已归一化 id/source） */
data class PlayEvent(
    val songId: String,
    val source: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val coverUrl: String?,
    /** 歌曲快照（点榜单行要能直接起播；只在需要时解码） */
    val songJson: String,
    val playedAt: Long,
    val playedMs: Long,
    val totalMs: Long,
    val effective: Boolean,
)

/** 「最常听」的一张卡 */
data class FavCard(
    val kind: String,
    val name: String,
    val plays: Int,
    /** 「有图模式」用的封面（歌手取最常听那首的封面、专辑取该专辑封面） */
    val coverUrl: String?,
)

/** 报告 / 习惯的「大数值 + 说明」 */
data class Metric(val value: String, val label: String)

data class HourBucket(val hour: Int, val plays: Int)

data class DayPoint(val label: String, val dayMs: Long)

data class RadarAxis(val name: String, val score: Int)

/** 堆叠条的一段（`tone` 交给 UI 映射到主题色阶，不硬编码颜色） */
data class StackSegment(val name: String, val share: Float, val tone: Int, val detail: String)

data class StackRow(val title: String, val categoryCount: Int, val segments: List<StackSegment>)

data class PlatformSlice(val name: String, val value: Int, val share: Float)

data class SongRank(
    /** 榜单行点一下就能起播，所以直接带上歌曲本身 */
    val song: Song,
    val name: String,
    val singer: String,
    val plays: Int,
    val durationMs: Long,
    val coverUrl: String?,
)

data class ArtistRank(
    val name: String,
    val plays: Int,
    val trackCount: Int,
    val mergedCount: Int,
    /** 「有图模式」用的封面：取该歌手听得最多那首的封面 */
    val coverUrl: String?,
)

/** Hero 卡的三个数字（固定按「全部」算） */
data class StatsOverview(
    val totalDurationMs: Long,
    val effectivePlays: Int,
    val activeDays: Int,
)

/** 榜单列表页的一行（歌手 / 专辑 下的歌） */
data class StatsListEntry(
    val song: Song,
    val plays: Int,
    val durationMs: Long,
)

/** 榜单列表页要按什么筛 */
enum class StatsListScope(val key: String, val title: String) {
    ARTIST("artist", "歌手"),
    ALBUM("album", "专辑"),
    ;

    companion object {
        fun fromKey(key: String?): StatsListScope =
            entries.firstOrNull { it.key == key } ?: ARTIST
    }
}

/** 歌手归并的说明条数据（15 §6.4：检测到同名歌手拆条时给出提示） */
data class ArtistMergeInfo(
    /** 被拆分过的组数 */
    val groupCount: Int,
    /** 这些组一共占了多少条原始条目 */
    val splitEntries: Int,
    /** 归并后榜首的次数提升（用于文案「榜首 60 次 → 157 次」） */
    val rawTopPlays: Int,
    val mergedTopPlays: Int,
)

/** 活跃时长趋势下方的三张对比卡 */
data class TrendCards(
    val peakValue: String,
    val peakLabel: String,
    val last7Value: String,
    val last7Label: String,
    val workdayValue: String,
    val workdayLabel: String,
)

/**
 * 热力图选中日的单曲行（按「歌 + 来源」聚合当天多次播放）。
 * 口径与 `dailyMs` 一致：全量有效事件，不随档位变（批次三 #7）。
 */
data class DailySongRow(
    val songId: String,
    val name: String,
    val singer: String,
    /** 来源展示名（已套用 [SourceLabels]，可直接上屏） */
    val sourceLabel: String,
    /** 当天播放次数 */
    val plays: Int,
    /** 当天累计有效时长 */
    val playedMs: Long,
    /** 「有图模式」用的封面（当天第一条事件的快照） */
    val coverUrl: String? = null,
)

data class StatsSnapshot(
    val range: StatsRange,
    /** 区间跨度（如「9/28 – 10/3」）—— 直接写在副标题上，避免「本周怎么比本月还多」的困惑 */
    val rangeSpan: String,
    /**
     * 口径说明（如「Q4（10–12 月）首月」）。
     *
     * 为什么需要：10/3 时「本月」与「本季」都是 10/1 起 —— 看似重复，其实是
     * 「10 月正好是本季度的第一个月」。不写出来就会被当成 bug。
     */
    val rangeNote: String,
    /** 区间内一条有效记录都没有 */
    val hasData: Boolean,
    /**
     * 全量（不受档位影响）每日有效时长：`epochDay → 毫秒`。
     *
     * 供「听歌热力图」按日历月绘制（口径同旧项目 `MonthHeatMap`：当天累计有效时长）。
     * 空账本时为空表，热力格全按 0 呈现，不出现假值。
     */
    val dailyMs: Map<Long, Long>,
    /**
     * 热力图选中日的明细（批次三 #7）：`epochDay → 按「歌+来源」聚合的当天播放列表`。
     * 只为「点格子看那天听了什么」服务，行内已带可直接上屏的来源展示名。
     */
    val dailySongs: Map<Long, List<DailySongRow>>,
    // ① 听歌报告
    val totalDurationMs: Long,
    val effectivePlays: Int,
    val activeDays: Int,
    val dailyAvgMs: Long,
    // ② 最常听
    val favSinger: FavCard?,
    val favSong: FavCard?,
    val favAlbum: FavCard?,
    // ③ 活跃时间分布
    val hourly: List<HourBucket>,
    val peakHour: Int,
    val peakHourPlays: Int,
    // ④ 活跃时长趋势（近 14 天滚动窗口）
    val trendDays: Int,
    val trend: List<DayPoint>,
    val trendCards: TrendCards,
    // ⑤ 听歌画像（六维 —— 全部由可实测行为算出）
    val radar: List<RadarAxis>,
    // ⑥ 听歌习惯
    val habits: List<Metric>,
    // ⑦ 长期偏好画像
    val longTerm: List<StackRow>,
    // ⑧ 音乐平台占比
    val platforms: List<PlatformSlice>,
    // ⑨ 歌曲排行
    val songs: List<SongRank>,
    // ⑩ 歌手排行
    val artistsRaw: List<ArtistRank>,
    val artistsMerged: List<ArtistRank>,
    val artistMerge: ArtistMergeInfo,
)

/**
 * 统计聚合（**纯函数**，不碰数据库、不碰 Android）。
 *
 * 口径（2026-10-03 用户裁决）：
 * - 全部区块只统计**入账事件**（`effective`），即「≥50% 且 ≥30 秒」
 * - 缺曲风/语言元数据，故「听歌画像」用**可实测的行为维度**，不照搬原型的曲风六维
 * - 歌手排行做**归并**（同名异写合并），并提供原始榜对照
 */
object StatsCalculator {

    /** 趋势图固定看「近 14 天」滚动窗口（原型也是固定窗口，不随档位变） */
    const val TREND_WINDOW_DAYS = 14
    private const val TOP_N = 10
    private const val PLATFORM_N = 5

    private val dayLabel = DateTimeFormatter.ofPattern("M/d")

    fun compute(
        events: List<PlayEvent>,
        range: StatsRange,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        /** 已装音源的展示名（source 值 → 名字）；缺的回落 [SourceLabels] 内置表 */
        sourceLabels: Map<String, String> = emptyMap(),
    ): StatsSnapshot {
        val from = rangeStartMs(range, nowMs, zone)
        val inRange = events.filter { it.playedAt in from until nowMs }
        val eff = inRange.filter { it.effective }

        val totalDurationMs = eff.sumOf { it.playedMs }
        val activeDays = eff.map { dayKey(it.playedAt, zone) }.toSet()
        val dailyAvgMs = if (activeDays.isEmpty()) 0L else totalDurationMs / activeDays.size

        // 热力图口径：**全量**有效记录（不随档位变），按天求和
        val dailyMs = events
            .filter { it.effective && it.playedAt <= nowMs }
            .groupBy { dayKey(it.playedAt, zone).toEpochDay() }
            .mapValues { (_, rows) -> rows.sumOf { it.playedMs } }

        // 热力图选中日明细：同一天同一首（歌+来源）聚合，按时长降序（最长先看到）
        val dailySongs = events
            .filter { it.effective && it.playedAt <= nowMs }
            .groupBy { dayKey(it.playedAt, zone).toEpochDay() }
            .mapValues { (_, rows) ->
                rows.groupBy { it.songId to it.source }
                    .map { (_, same) ->
                        val first = same.first()
                        DailySongRow(
                            songId = first.songId,
                            name = first.name,
                            singer = first.singer,
                            sourceLabel = SourceLabels.of(first.source, sourceLabels),
                            plays = same.size,
                            playedMs = same.sumOf { it.playedMs },
                            coverUrl = first.coverUrl,
                        )
                    }
                    .sortedByDescending { it.playedMs }
            }

        return StatsSnapshot(
            range = range,
            rangeSpan = spanLabel(range, from, nowMs, events, zone),
            rangeNote = rangeNote(range, nowMs, zone),
            hasData = eff.isNotEmpty(),
            dailyMs = dailyMs,
            dailySongs = dailySongs,
            totalDurationMs = totalDurationMs,
            effectivePlays = eff.size,
            activeDays = activeDays.size,
            dailyAvgMs = dailyAvgMs,
            favSinger = topBy(eff, TopKind.SINGER),
            favSong = topBy(eff, TopKind.SONG),
            favAlbum = topBy(eff, TopKind.ALBUM),
            hourly = hourly(eff, zone),
            peakHour = peakHourOf(eff, zone),
            peakHourPlays = peakPlaysOf(eff, zone),
            trendDays = TREND_WINDOW_DAYS,
            trend = trend(eff, nowMs, zone),
            trendCards = trendCards(eff, nowMs, zone),
            radar = radar(eff, inRange, activeDays.size),
            habits = habits(eff, inRange, activeDays.size, zone),
            longTerm = longTerm(eff),
            platforms = platforms(eff, sourceLabels),
            songs = songRanks(eff),
            artistsRaw = artistRaw(eff),
            artistsMerged = artistMerged(eff),
            artistMerge = mergeInfo(eff),
        )
    }

    // ── 时间区间 ──

    fun rangeStartMs(range: StatsRange, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val start: LocalDate = when (range) {
            StatsRange.DAY -> date
            // ISO 周：周一为一周之始
            StatsRange.WEEK -> date.minusDays((date.dayOfWeek.value - 1).toLong())
            StatsRange.MONTH -> date.withDayOfMonth(1)
            StatsRange.QUARTER -> date.withMonth(((date.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1)
            StatsRange.YEAR -> date.withDayOfYear(1)
            StatsRange.ALL -> LocalDate.of(1970, 1, 1)
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun dayKey(ms: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /**
     * 区间跨度文案。`ALL` 档的起点取**最早一条记录**（否则会写成 1970/1/1，毫无意义）。
     */
    private fun spanLabel(
        range: StatsRange,
        fromMs: Long,
        nowMs: Long,
        events: List<PlayEvent>,
        zone: ZoneId,
    ): String {
        val startMs = if (range == StatsRange.ALL) {
            events.minOfOrNull { it.playedAt } ?: nowMs
        } else {
            fromMs
        }
        val start = dayKey(startMs, zone).format(dayLabel)
        val end = dayKey(nowMs, zone).format(dayLabel)
        return if (start == end) start else "$start – $end"
    }

    /**
     * 档位口径说明 —— 让「奇怪但正确」的区间自我解释。
     *
     * 最典型的是本季：10/3 时它与「本月」完全同区间，因为 **10 月正是 Q4 的第一个月**。
     * 不写出来就会被当成 bug（2026-10-03 用户两次质疑）。
     */
    private fun rangeNote(range: StatsRange, nowMs: Long, zone: ZoneId): String {
        val date = dayKey(nowMs, zone)
        return when (range) {
            StatsRange.DAY -> "今天"
            StatsRange.WEEK -> "周一起"
            StatsRange.MONTH -> "月初起"
            StatsRange.QUARTER -> {
                val quarter = (date.monthValue - 1) / 3 + 1
                val firstMonth = (quarter - 1) * 3 + 1
                val lastMonth = firstMonth + 2
                if (date.monthValue == firstMonth) {
                    "Q$quarter（$firstMonth–$lastMonth 月）首月 · 与本月同区间"
                } else {
                    "Q$quarter（$firstMonth–$lastMonth 月）"
                }
            }

            StatsRange.YEAR -> "年初起"
            StatsRange.ALL -> "全部记录"
        }
    }

    /** Hero 卡专用：**永远按「全部」算**（用户 2026-10-03 要求：顶部卡片不随档位变） */
    fun overview(
        events: List<PlayEvent>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StatsOverview {
        val eff = events.filter { it.effective && it.playedAt <= nowMs }
        return StatsOverview(
            totalDurationMs = eff.sumOf { it.playedMs },
            effectivePlays = eff.size,
            activeDays = eff.map { dayKey(it.playedAt, zone) }.toSet().size,
        )
    }

    private fun hourOf(ms: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(ms).atZone(zone).hour

    // ── ② 最常听 ──

    private enum class TopKind { SINGER, SONG, ALBUM }

    private fun topBy(events: List<PlayEvent>, kind: TopKind): FavCard? {
        val keys: (PlayEvent) -> String? = when (kind) {
            TopKind.SINGER -> { e -> e.singer.ifBlank { null } }
            TopKind.SONG -> { e -> e.name.ifBlank { null } }
            TopKind.ALBUM -> { e -> e.albumName?.ifBlank { null } }
        }
        val counts = LinkedHashMap<String, Int>()
        events.forEach { e -> keys(e)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        val top = counts.maxByOrNull { it.value } ?: return null
        val label = when (kind) {
            TopKind.SINGER -> "歌手"
            TopKind.SONG -> "歌曲"
            TopKind.ALBUM -> "专辑"
        }
        // 封面取该维度下第一首有图的歌（「有图模式」用）
        val cover = events.firstOrNull { keys(it) == top.key && !it.coverUrl.isNullOrBlank() }?.coverUrl
        return FavCard(label, top.key, top.value, cover)
    }

    // ── ③ 活跃时间分布 ──

    private fun hourly(events: List<PlayEvent>, zone: ZoneId): List<HourBucket> {
        val buckets = IntArray(24)
        events.forEach { buckets[hourOf(it.playedAt, zone)]++ }
        return buckets.mapIndexed { hour, plays -> HourBucket(hour, plays) }
    }

    private fun peakHourOf(events: List<PlayEvent>, zone: ZoneId): Int =
        hourly(events, zone).maxByOrNull { it.plays }?.hour ?: 0

    private fun peakPlaysOf(events: List<PlayEvent>, zone: ZoneId): Int =
        hourly(events, zone).maxOfOrNull { it.plays } ?: 0

    // ── ④ 活跃时长趋势 ──

    private fun trend(events: List<PlayEvent>, nowMs: Long, zone: ZoneId): List<DayPoint> {
        val today = dayKey(nowMs, zone)
        val byDay = events.groupBy { dayKey(it.playedAt, zone) }
        return (TREND_WINDOW_DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayPoint(date.format(dayLabel), byDay[date]?.sumOf { it.playedMs } ?: 0L)
        }
    }

    private fun trendCards(events: List<PlayEvent>, nowMs: Long, zone: ZoneId): TrendCards {
        val today = dayKey(nowMs, zone)
        val byDay = events.groupBy { dayKey(it.playedAt, zone) }
        val window = trend(events, nowMs, zone)

        val peak = window.maxByOrNull { it.dayMs }
        val last7 = (0..6).sumOf { byDay[today.minusDays(it.toLong())]?.sumOf { e -> e.playedMs } ?: 0L }
        val prev7 = (7..13).sumOf { byDay[today.minusDays(it.toLong())]?.sumOf { e -> e.playedMs } ?: 0L }
        val ratio = if (prev7 > 0L) (last7 - prev7).toDouble() / prev7 else 0.0

        val workdays = (0..13).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek.value <= 5 }
            .map { byDay[it]?.sumOf { e -> e.playedMs } ?: 0L }
        val weekends = (0..13).map { today.minusDays(it.toLong()) }
            .filter { it.dayOfWeek.value >= 6 }
            .map { byDay[it]?.sumOf { e -> e.playedMs } ?: 0L }
        val workdayAvg = if (workdays.isEmpty()) 0L else workdays.sum() / workdays.size
        val weekendAvg = if (weekends.isEmpty()) 0L else weekends.sum() / weekends.size

        return TrendCards(
            peakValue = formatHours(peak?.dayMs ?: 0L),
            peakLabel = peak?.label ?: "—",
            last7Value = formatHours(last7),
            last7Label = when {
                prev7 <= 0L -> "无上期对照"
                ratio >= 0 -> "环比 +${(ratio * 100).toInt()}%"
                else -> "环比 ${(ratio * 100).toInt()}%"
            },
            workdayValue = formatHours(workdayAvg),
            workdayLabel = "周末 ${formatHours(weekendAvg)}",
        )
    }

    // ── ⑤ 听歌画像（六维 · 可实测） ──

    private fun radar(
        eff: List<PlayEvent>,
        allInRange: List<PlayEvent>,
        activeDayCount: Int,
    ): List<RadarAxis> {
        if (eff.isEmpty()) {
            return RADAR_NAMES.map { RadarAxis(it, 0) }
        }
        val distinctSongs = eff.map { it.songId to it.source }.toSet().size

        // 循环度：最常听那一首占了多少
        val topShare = topBy(eff, TopKind.SONG)?.plays?.toFloat()?.div(eff.size) ?: 0f
        // 曲目广度：区间内听过多少首不同的歌（200 首封顶）
        val breadth = (distinctSongs / 2f).coerceAtMost(100f)
        // 深夜收听：0–5 点的占比
        val nightShare = eff.count { hourOf(it.playedAt, ZoneId.systemDefault()) in 0..4 }.toFloat() / eff.size
        // 完整度：入账次数 / 全部起播次数
        val completeness = if (allInRange.isEmpty()) 0f else eff.size.toFloat() / allInRange.size
        // 活跃强度：每个活跃日的有效收听次数（40 次封顶）
        val intensity = if (activeDayCount == 0) 0f
        else (eff.size.toFloat() / activeDayCount / 40f * 100f).coerceAtMost(100f)
        // 单次时长：平均每次听了多久（4 分钟封顶）
        val avgMinutes = (eff.sumOf { it.playedMs } / eff.size) / 60_000f
        val session = (avgMinutes / 4f * 100f).coerceAtMost(100f)

        val scores = listOf(
            (topShare * 100f).coerceIn(0f, 100f),
            breadth,
            (nightShare * 100f).coerceIn(0f, 100f),
            (completeness * 100f).coerceIn(0f, 100f),
            intensity,
            session,
        )
        return RADAR_NAMES.mapIndexed { i, name -> RadarAxis(name, scores[i].toInt()) }
    }

    // ── ⑥ 听歌习惯 ──

    private fun habits(
        eff: List<PlayEvent>,
        allInRange: List<PlayEvent>,
        activeDayCount: Int,
        zone: ZoneId,
    ): List<Metric> {
        // 空态如实显示 0（用户 2026-10-05 要求：是 0 就是 0，不给「—」这类假值）
        if (eff.isEmpty()) {
            return listOf(
                Metric("0 时", "最晚一次"),
                Metric("0 分", "单次最长"),
                Metric("0 次", "一天最多"),
                Metric("0%", "完整播放率"),
                Metric("0 首", "每天不重复"),
                Metric("0 天", "连续活跃"),
            )
        }
        val latestHour = eff.maxByOrNull { it.playedAt }?.let { hourOf(it.playedAt, zone) } ?: 0
        val longest = eff.maxOf { it.playedMs }
        val busiestDay = eff.groupBy { dayKey(it.playedAt, zone) }.maxOfOrNull { it.value.size } ?: 0
        val completion = eff.size.toFloat() / allInRange.size.coerceAtLeast(1)
        val songsPerDay = (eff.map { it.songId to it.source }.toSet().size.toFloat() /
            activeDayCount.coerceAtLeast(1)).toInt()
        val streak = longestStreak(eff, zone)

        return listOf(
            Metric("$latestHour 时", "最晚一次"),
            Metric(formatHours(longest), "单次最长"),
            Metric("$busiestDay 次", "一天最多"),
            Metric("${(completion * 100).toInt()}%", "完整播放率"),
            Metric("$songsPerDay 首", "每天不重复"),
            Metric("$streak 天", "连续活跃"),
        )
    }

    private fun longestStreak(events: List<PlayEvent>, zone: ZoneId): Int {
        val days = events.map { dayKey(it.playedAt, zone) }.distinct().sorted()
        if (days.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (days[i] == days[i - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    // ── ⑦ 长期偏好画像（三维 · 可实测） ──

    private fun longTerm(eff: List<PlayEvent>): List<StackRow> {
        // 空态也要照常给出三行（计数全 0、条形为空），不留空区块
        val byPeriod = listOf("夜深 0–5" to 0..4, "清晨 5–11" to 5..10, "日间 11–18" to 11..17, "夜晚 18–24" to 18..23)
        val periodCounts = byPeriod.map { (name, hours) ->
            name to eff.count { hourOf(it.playedAt, ZoneId.systemDefault()) in hours }
        }

        val durationBuckets = listOf("≤2 分" to 0..2, "2–4 分" to 3..4, "4–6 分" to 5..6, ">6 分" to 7..Int.MAX_VALUE)
        val durationCounts = durationBuckets.map { (name, minutes) ->
            name to eff.count { (it.totalMs / 60_000).toInt() in minutes }
        }

        val repeatBuckets = listOf("只听一次" to 0..1, "偶尔 2–3" to 2..3, "常听 4–9" to 4..9, "循环 ≥10" to 10..Int.MAX_VALUE)
        val perSong = eff.groupingBy { it.songId to it.source }.eachCount()
        val repeatCounts = repeatBuckets.map { (name, times) ->
            name to perSong.count { it.value in times }
        }

        return listOf(
            stackRow("时段偏好", periodCounts),
            stackRow("时长偏好", durationCounts),
            stackRow("重听偏好", repeatCounts),
        )
    }

    private fun stackRow(title: String, counts: List<Pair<String, Int>>): StackRow {
        val total = counts.sumOf { it.second }.coerceAtLeast(1)
        val kept = counts.filter { it.second > 0 }
        return StackRow(
            title = title,
            categoryCount = kept.size,
            segments = kept.mapIndexed { index, (name, count) ->
                val share = count.toFloat() / total
                StackSegment(name, share, index, "${(share * 100).toInt()}%")
            },
        )
    }

    // ── ⑧ 音乐平台占比 ──

    private fun platforms(eff: List<PlayEvent>, labels: Map<String, String>): List<PlatformSlice> {
        val total = eff.size.coerceAtLeast(1)
        return eff.groupingBy { it.source }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(PLATFORM_N)
            .map { (source, count) ->
                PlatformSlice(SourceLabels.of(source, labels), count, count.toFloat() / total)
            }
    }

    // ── ⑨ 歌曲排行 ──

    private fun songRanks(eff: List<PlayEvent>): List<SongRank> =
        eff.groupBy { it.songId to it.source }
            .map { (_, rows) ->
                // rows 已按 playedAt 倒序（仓储的查询顺序），first() 即最近一次
                val latest = rows.first()
                SongRank(
                    // ⚠️ songJson 是**写入时**的快照，里面的歌名/作者是旧值。
                    //    重命名修正只在 name/singer 两列上（见 StatsViewModel 的 combine），
                    //    所以这里必须把修正**回填进 song**，否则任何读 song.name 的界面
                    //    仍会显示旧名字（用户 2026-10-05 反馈「重命名后统计不变」）。
                    song = (decodeSong(latest.songJson) ?: return@map null)
                        .copy(name = latest.name, singer = latest.singer),
                    name = latest.name,
                    singer = latest.singer,
                    plays = rows.size,
                    durationMs = rows.sumOf { it.playedMs },
                    coverUrl = latest.coverUrl,
                )
            }
            .filterNotNull()
            .sortedByDescending { it.plays }
            .take(TOP_N)

    /** 榜单列表页：某歌手 / 某专辑下听过哪些歌（按次数降序） */
    fun listEntries(
        events: List<PlayEvent>,
        scope: StatsListScope,
        key: String,
        range: StatsRange,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StatsListEntry> {
        val from = rangeStartMs(range, nowMs, zone)
        val matched = events.filter { it.effective && it.playedAt in from until nowMs }
            .filter { event ->
                when (scope) {
                    StatsListScope.ARTIST -> event.singer == key
                    StatsListScope.ALBUM -> event.albumName == key
                }
            }
        return matched.groupBy { it.songId to it.source }
            .mapNotNull { (_, rows) ->
                val latest = rows.first()
                // 同 songRanks：把重命名修正回填进 song，避免榜单列表页显示旧名字
                decodeSong(latest.songJson)
                    ?.copy(name = latest.name, singer = latest.singer)
                    ?.let { song ->
                        StatsListEntry(song, rows.size, rows.sumOf { it.playedMs })
                    }
            }
            .sortedByDescending { it.plays }
    }

    private fun decodeSong(json: String): Song? =
        runCatching { Json.decodeFromString(Song.serializer(), json) }.getOrNull()

    // ── ⑩ 歌手排行（原始 + 归并） ──

    private fun artistRaw(eff: List<PlayEvent>): List<ArtistRank> =
        eff.groupBy { it.singer.ifBlank { "未知歌手" } }
            .map { (singer, rows) ->
                ArtistRank(
                    name = singer,
                    plays = rows.size,
                    trackCount = rows.map { it.songId to it.source }.toSet().size,
                    mergedCount = 0,
                    coverUrl = rows.firstNotNullOfOrNull { it.coverUrl },
                )
            }
            .sortedByDescending { it.plays }
            .take(TOP_N)

    private fun artistMerged(eff: List<PlayEvent>): List<ArtistRank> =
        eff.groupBy { normalizeArtist(it.singer) }
            .map { (_, rows) ->
                // 展示名取该组里最长的一个原名（信息量最大）
                val display = rows.map { it.singer }.maxByOrNull { it.length }?.ifBlank { "未知歌手" }
                val distinctRawNames = rows.map { it.singer }.distinct().size
                ArtistRank(
                    name = display ?: "未知歌手",
                    plays = rows.size,
                    trackCount = rows.map { it.songId to it.source }.toSet().size,
                    mergedCount = if (distinctRawNames > 1) distinctRawNames else 0,
                    coverUrl = rows.firstNotNullOfOrNull { it.coverUrl },
                )
            }
            .sortedByDescending { it.plays }
            .take(TOP_N)

    private fun mergeInfo(eff: List<PlayEvent>): ArtistMergeInfo {
        val raw = eff.groupBy { it.singer.ifBlank { "未知歌手" } }.mapValues { it.value.size }
        val groups = eff.groupBy { normalizeArtist(it.singer) }
        val split = groups.values.filter { rows -> rows.map { it.singer }.distinct().size > 1 }
        val rawTop = raw.values.maxOrNull() ?: 0
        val mergedTop = groups.values.maxOfOrNull { it.size } ?: 0
        return ArtistMergeInfo(
            groupCount = split.size,
            splitEntries = split.sumOf { rows -> rows.map { it.singer }.distinct().size },
            rawTopPlays = rawTop,
            mergedTopPlays = mergedTop,
        )
    }

    /** 15 §6.4：忽略大小写与空格；取「、／,／and／feat／&」前的首位署名 */
    fun normalizeArtist(raw: String): String =
        raw.lowercase()
            .split(Regex("[、／,/]|\\s+and\\s+|\\s+feat\\.?\\s+|\\s*&\\s*"))
            .firstOrNull()
            .orEmpty()
            .replace(Regex("\\s+"), "")
            .ifBlank { raw.trim().lowercase() }

    // ── 格式化 ──

    /** 时长 → 「130.1 h」/「42 分」/「38 秒」（带单位，不给裸数字） */
    fun formatHours(ms: Long): String {
        val hours = ms / 3_600_000.0
        return when {
            ms <= 0L -> "0 分"
            hours < 1.0 && ms < 60_000L -> "${ms / 1000} 秒"
            hours < 1.0 -> "${ms / 60_000} 分"
            else -> String.format("%.1f h", hours)
        }
    }

    val RADAR_NAMES = listOf("循环度", "曲目广度", "深夜收听", "完整度", "活跃强度", "单次时长")
}