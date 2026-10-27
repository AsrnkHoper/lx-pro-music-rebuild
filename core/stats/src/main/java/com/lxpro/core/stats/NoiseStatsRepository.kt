package com.lxpro.core.stats

import com.lxpro.core.database.dao.NoiseEventDao
import com.lxpro.core.database.entity.NoiseEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 白噪音收听账本的仓储（统计页「白噪音」子页的数据源）。
 *
 * 与听歌账本（[StatsLedgerRepository]）并列、互不覆盖：导入/清空听歌统计不会动这里，
 * 反之亦然（用户在「配置」清空统计数据时会把两边都清掉，见 `StatsViewModel.clearAll`）。
 */
@Singleton
class NoiseStatsRepository @Inject constructor(
    private val dao: NoiseEventDao,
) {

    fun observeAll(): Flow<List<NoiseSession>> = dao.observeAll().map { rows -> rows.map { it.toSession() } }

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    private fun NoiseEventEntity.toSession() = NoiseSession(
        startedAt = startedAt,
        endedAt = endedAt,
        durationMs = durationMs,
        musicMs = musicMs,
        soundIds = decodeArray(soundIds),
        soundNames = decodeArray(soundNames),
        sceneName = sceneName,
    )

    private fun decodeArray(json: String): List<String> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
