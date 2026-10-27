package com.lxpro.feature.stats

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.core.playlist.songOverrideKey
import com.lxpro.core.stats.PlayEvent
import com.lxpro.core.stats.StatsCalculator
import com.lxpro.core.stats.StatsLedgerRepository
import com.lxpro.core.stats.StatsListEntry
import com.lxpro.core.stats.StatsListScope
import com.lxpro.core.stats.StatsRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

const val ARG_STATS_LIST_KIND = "kind"
const val ARG_STATS_LIST_KEY = "key"
const val ROUTE_STATS_LIST =
    "stats/list?$ARG_STATS_LIST_KIND={$ARG_STATS_LIST_KIND}&$ARG_STATS_LIST_KEY={$ARG_STATS_LIST_KEY}"

/** 榜单项的跳转目标（歌手 / 专辑 列表页） */
fun statsListRoute(scope: StatsListScope, key: String): String =
    "stats/list?$ARG_STATS_LIST_KIND=${scope.key}&$ARG_STATS_LIST_KEY=${Uri.encode(key)}"

data class StatsListUiState(
    val scope: StatsListScope = StatsListScope.ARTIST,
    val key: String = "",
    val entries: List<StatsListEntry> = emptyList(),
)

/**
 * 统计榜单列表页：某个**歌手 / 专辑**在统计口径下听过的歌。
 *
 * 结构同源（06 的 M3 任务 10「歌手详情 / 专辑详情」计划合并成同一套页面），
 * 这里先落最小可用版：只做「账本里这个维度有哪些歌 + 各听了几次」。
 * 口径固定按「全部」，避免从某个时间档进来后列表被截断得莫名其妙。
 */
@HiltViewModel
class StatsListViewModel @Inject constructor(
    savedState: SavedStateHandle,
    ledger: StatsLedgerRepository,
    overrides: SongOverrideRepository,
) : ViewModel() {

    private val scope = StatsListScope.fromKey(savedState.get<String>(ARG_STATS_LIST_KIND))
    private val key = savedState.get<String>(ARG_STATS_LIST_KEY).orEmpty()

    val uiState: StateFlow<StatsListUiState> =
        combine(ledger.observeAll(), overrides.observe()) { rows, fixes ->
            val events = rows.map { row ->
                val fix = fixes[songOverrideKey(row.songId, row.source)]
                PlayEvent(
                    songId = row.songId,
                    source = row.source,
                    name = fix?.name ?: row.name,
                    singer = fix?.singer ?: row.singer,
                    albumName = row.albumName,
                    coverUrl = row.coverUrl,
                    songJson = row.songJson,
                    playedAt = row.playedAt,
                    playedMs = row.playedDurationMs,
                    totalMs = row.totalDurationMs,
                    effective = row.isEffective,
                )
            }
            StatsListUiState(
                scope = scope,
                key = key,
                entries = StatsCalculator.listEntries(
                    events = events,
                    scope = scope,
                    key = key,
                    range = StatsRange.ALL,
                    nowMs = System.currentTimeMillis(),
                ),
            )
            // B3：同统计页，把全量流水的映射与聚合移出主线程
        }.flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                StatsListUiState(scope = scope, key = key),
            )
}