package com.lxpro.feature.stats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.core.playlist.songOverrideKey
import com.lxpro.core.stats.NoiseOverview
import com.lxpro.core.stats.NoiseStatsCalculator
import com.lxpro.core.stats.NoiseStatsRepository
import com.lxpro.core.stats.NoiseStatsSnapshot
import com.lxpro.core.stats.PlayEvent
import com.lxpro.core.stats.StatsCalculator
import com.lxpro.core.stats.StatsLedgerRepository
import com.lxpro.core.stats.StatsOverview
import com.lxpro.core.stats.StatsRange
import com.lxpro.core.stats.StatsSnapshot
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val range: StatsRange = StatsRange.default,
    /**
     * 首帧也要能渲染：无数据时给出一份**全 0 的真实快照**（不是 null 空页），
     * 这样 B1 的空态会照常画出 10 个区块并如实显示 0。
     */
    val snapshot: StatsSnapshot = StatsCalculator.compute(
        events = emptyList(),
        range = StatsRange.default,
        nowMs = System.currentTimeMillis(),
    ),
    /** Hero 卡专用：固定「全部」口径，不随档位变（用户 2026-10-03 要求） */
    val overview: StatsOverview = StatsOverview(0L, 0, 0),
)

/** 「白噪音」子页状态：与听歌统计同档位选择，独立快照 */
data class NoiseStatsUiState(
    val range: StatsRange = StatsRange.default,
    val snapshot: NoiseStatsSnapshot = NoiseStatsCalculator.compute(
        sessions = emptyList(),
        range = StatsRange.default,
        nowMs = System.currentTimeMillis(),
    ),
    val overview: NoiseOverview = NoiseOverview(0L, 0, 0),
)

/** 「配置」子页的口径设置（写入时生效，历史流水不重算） */
data class StatsConfigState(
    val minPlaySeconds: Int = 30,
    val minPlayRatio: Int = 50,
    /** 统计方式：time / ratio / timeFirst / ratioFirst（旧 LX-Pro `stats.priority`） */
    val priority: String = "timeFirst",
)

/**
 * 统计页（`mockup_app.html` 的 `data-view="stats"` + 06 的 M3 任务 7）。
 *
 * 数据来自**统计账本** `stats_events` 的真实流水（含导入的前作统计备份）—— 与首页货架的
 * 播放历史是两张表、两个功能，**不是原型里的写死 mock**。
 * 口径：只统计入账事件（≥50% 且 ≥30 秒，阈值可在「配置」子页调整）；缺曲风/语言元数据，
 * 故「听歌画像」用可实测的行为维度。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val ledger: StatsLedgerRepository,
    private val noiseLedger: NoiseStatsRepository,
    overrides: SongOverrideRepository,
    registry: SourceRegistry,
    private val settings: SettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.default)

    /** 配置子页 / 数据管理操作回执（轻提示） */
    val message = MutableStateFlow<String?>(null)

    fun onMessageShown() {
        message.value = null
    }

    /** 流水 → 事件：套用「重命名歌曲 / 作者」的本地修正（与歌单、歌手归并同一口径） */
    private val events = combine(ledger.observeAll(), overrides.observe()) { rows, fixes ->
        rows.map { row ->
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
    }

    val uiState: StateFlow<StatsUiState> = combine(events, range, registry.all) { rows, selected, sources ->
        val now = System.currentTimeMillis()
        StatsUiState(
            range = selected,
            snapshot = StatsCalculator.compute(
                events = rows,
                range = selected,
                nowMs = now,
                sourceLabels = sources.associate { it.id.value to it.name },
            ),
            overview = StatsCalculator.overview(rows, now),
        )
        // B3：聚合是 O(全量流水 × 10 个区块) 的重活，放到 Default 线程算，
        // 避免首次进入统计页时在主线程上卡住一帧（stateIn 默认跑在 Main.immediate）
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** 「白噪音」子页：**独立账本**，与听歌流水无关；同一档位选择器，但重算互不影响 */
    val noiseUiState: StateFlow<NoiseStatsUiState> = combine(
        noiseLedger.observeAll(),
        range,
    ) { sessions, selected ->
        val now = System.currentTimeMillis()
        NoiseStatsUiState(
            range = selected,
            snapshot = NoiseStatsCalculator.compute(sessions, selected, now),
            overview = NoiseStatsCalculator.overview(sessions, now),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoiseStatsUiState())

    /** 「配置」子页的口径（DataStore 持久化；三个流合并成一个状态） */
    val config: StateFlow<StatsConfigState> = combine(
        settings.statsMinPlaySeconds,
        settings.statsMinPlayRatio,
        settings.statsPriority,
    ) { seconds, ratio, priority -> StatsConfigState(seconds, ratio, priority) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsConfigState())

    fun onRangeChange(next: StatsRange) {
        range.value = next
    }

    fun setMinPlaySeconds(value: Int) {
        viewModelScope.launch {
            settings.setStatsMinPlaySeconds(value)
            message.value = "已保存：最低计入 $value 秒（只影响之后的播放）"
        }
    }

    fun setMinPlayRatio(value: Int) {
        viewModelScope.launch {
            settings.setStatsMinPlayRatio(value)
            message.value = "已保存：最低播放比例 $value%（只影响之后的播放）"
        }
    }

    fun setPriority(value: String) {
        viewModelScope.launch {
            settings.setStatsPriority(value)
            val label = when (value) {
                "time" -> "仅秒数"
                "ratio" -> "仅比例"
                "ratioFirst" -> "都满足 · 优先检查比例"
                else -> "都满足 · 优先检查秒数"
            }
            message.value = "已保存：统计方式「$label」（只影响之后的播放）"
        }
    }

    /** 导出全量统计为 JSON（SAF CreateDocument 选好位置后写入） */
    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(ledger.exportJson().toByteArray())
                } ?: error("无法打开目标文件")
            }.onSuccess {
                message.value = "统计数据已导出"
            }.onFailure {
                message.value = "导出失败：${it.message}"
            }
        }
    }

    /** 导入我们的导出格式（覆盖整本账本） */
    fun importFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: error("无法读取所选文件")
                ledger.importJson(text)
            }.onSuccess {
                message.value = "统计数据已导入（覆盖原账本）"
            }.onFailure {
                message.value = if (it is IllegalArgumentException) it.message ?: "导入失败" else "导入失败：${it.message}"
            }
        }
    }

    /** 清空整本统计账本（UI 层负责确认弹窗）；白噪音账本是并列的一本，一并清掉 */
    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            ledger.clear()
            noiseLedger.clear()
            message.value = "统计数据已清空（含白噪音）"
        }
    }
}
