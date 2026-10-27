package com.lxpro.core.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 白噪音统计口径的纯函数测试（2026-10-26 新增）。
 *
 * 重点守住两条用户裁决：
 * 1. **单独 / 伴随歌曲** 按「当时是否真有歌在播」的毫秒数切分，而不是按设置开关；
 * 2. 区间统计要**按重叠裁剪**，跨天会话不能整段算进今天。
 */
class NoiseStatsCalculatorTest {

    private val zone: ZoneOffset = ZoneOffset.UTC
    private val now = at(2026, 10, 26, 12, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(LocalDate.of(year, month, day), java.time.LocalTime.of(hour, minute))
            .toInstant(zone).toEpochMilli()

    private val min = 60_000L

    @Test
    fun `跨天会话按区间裁剪，只算今天那一段`() {
        val session = NoiseSession(
            startedAt = at(2026, 10, 25, 23, 30),
            endedAt = at(2026, 10, 26, 0, 30),
            durationMs = 60 * min,
            musicMs = 0,
            soundIds = listOf("rain_l"),
            soundNames = listOf("小雨"),
            sceneName = null,
        )
        val snapshot = NoiseStatsCalculator.compute(listOf(session), StatsRange.DAY, now, zone)
        assertEquals(30 * min, snapshot.totalMs)
        assertEquals(1, snapshot.sessionCount)
        assertEquals(30 * min, snapshot.hourly[0].durationMs)
        assertEquals(0L, snapshot.hourly[23].durationMs)
    }

    @Test
    fun `单独聆听与伴随歌曲按时长切分`() {
        val withMusic = NoiseSession(
            startedAt = at(2026, 10, 26, 2, 0),
            endedAt = at(2026, 10, 26, 2, 10),
            durationMs = 10 * min,
            musicMs = 4 * min,
            soundIds = listOf("rain_l"),
            soundNames = listOf("小雨"),
            sceneName = null,
        )
        val snapshot = NoiseStatsCalculator.compute(listOf(withMusic), StatsRange.DAY, now, zone)
        assertEquals(10 * min, snapshot.totalMs)
        assertEquals(4 * min, snapshot.withMusicMs)
        assertEquals(6 * min, snapshot.standaloneMs)
    }

    @Test
    fun `多图层会话给每个声音各计一遍，并按累计时长排序`() {
        val alone = NoiseSession(
            startedAt = at(2026, 10, 26, 2, 0),
            endedAt = at(2026, 10, 26, 2, 20),
            durationMs = 20 * min,
            musicMs = 0,
            soundIds = listOf("rain_l"),
            soundNames = listOf("小雨"),
            sceneName = null,
        )
        val layered = NoiseSession(
            startedAt = at(2026, 10, 26, 3, 0),
            endedAt = at(2026, 10, 26, 3, 10),
            durationMs = 10 * min,
            musicMs = 0,
            soundIds = listOf("fire", "rain_l"),
            soundNames = listOf("壁炉", "小雨"),
            sceneName = "雨夜壁炉",
        )
        val snapshot = NoiseStatsCalculator.compute(listOf(alone, layered), StatsRange.DAY, now, zone)
        assertEquals(listOf("小雨", "壁炉"), snapshot.sounds.map { it.name })
        assertEquals(30 * min, snapshot.sounds[0].durationMs)
        assertEquals(10 * min, snapshot.sounds[1].durationMs)
        assertEquals("雨夜壁炉", snapshot.scenes.single().name)
    }

    @Test
    fun `时段分布按真实小时切片，峰值小时取最长的一小时`() {
        val session = NoiseSession(
            startedAt = at(2026, 10, 26, 1, 45),
            endedAt = at(2026, 10, 26, 2, 15),
            durationMs = 30 * min,
            musicMs = 0,
            soundIds = listOf("white"),
            soundNames = listOf("白噪音"),
            sceneName = null,
        )
        val snapshot = NoiseStatsCalculator.compute(listOf(session), StatsRange.DAY, now, zone)
        assertEquals(15 * min, snapshot.hourly[1].durationMs)
        assertEquals(15 * min, snapshot.hourly[2].durationMs)
        assertEquals(1, snapshot.peakHour)
    }

    @Test
    fun `全部档的 Hero 概览按整段计，活跃天数按开始日去重`() {
        val yesterday = NoiseSession(
            startedAt = at(2026, 10, 25, 23, 30),
            endedAt = at(2026, 10, 26, 0, 30),
            durationMs = 60 * min,
            musicMs = 0,
            soundIds = listOf("rain_l"),
            soundNames = listOf("小雨"),
            sceneName = null,
        )
        val today = NoiseSession(
            startedAt = at(2026, 10, 26, 2, 0),
            endedAt = at(2026, 10, 26, 2, 10),
            durationMs = 10 * min,
            musicMs = 0,
            soundIds = listOf("rain_l"),
            soundNames = listOf("小雨"),
            sceneName = null,
        )
        val overview = NoiseStatsCalculator.overview(listOf(yesterday, today), now, zone)
        assertEquals(70 * min, overview.totalDurationMs)
        assertEquals(2, overview.sessionCount)
        assertEquals(2, overview.activeDays)
    }

    @Test
    fun `趋势固定 14 天，今天包含当日全部白噪音时长`() {
        val today = NoiseSession(
            startedAt = at(2026, 10, 26, 2, 0),
            endedAt = at(2026, 10, 26, 2, 30),
            durationMs = 30 * min,
            musicMs = 0,
            soundIds = listOf("rain_l"),
            soundNames = listOf("小雨"),
            sceneName = null,
        )
        val snapshot = NoiseStatsCalculator.compute(listOf(today), StatsRange.DAY, now, zone)
        assertEquals(14, snapshot.trend.size)
        assertEquals(30 * min, snapshot.trend.last().dayMs)
    }
}
