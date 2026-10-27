package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.database.entity.DownloadState
import com.lxpro.core.download.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadIndicatorState(
    /** 排队中 + 下载中的任务数；0 = 悬浮球隐藏 */
    val activeCount: Int = 0,
    /** 整体进度（各任务进度的平均），画外圈进度环用 */
    val progress: Int = 0,
)

/**
 * 右下角下载悬浮球的数据源（用户 2026-10-03 要求：进行中才显示，结束自动消失）。
 *
 * 放在应用模块：悬浮球要**跨页面**常驻（Tab、详情页、播放页都能看到）。
 */
@HiltViewModel
class DownloadIndicatorViewModel @Inject constructor(
    repository: DownloadRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<DownloadIndicatorState> = repository.observeTasks()
        .map { tasks ->
            val active = tasks.filter {
                it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING
            }
            DownloadIndicatorState(
                activeCount = active.size,
                progress = if (active.isEmpty()) 0 else active.sumOf { it.progress } / active.size,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadIndicatorState())

    /** 悬浮球停留位置（比例 0–1）；null = 从未拖动过 */
    val ballPosition: StateFlow<Pair<Float, Float>?> = settings.downloadBallPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 松手落盘：下次进 App 还在原地（用户 2026-10-05 要求可移动） */
    fun saveBallPosition(xFraction: Float, yFraction: Float) {
        viewModelScope.launch { settings.setDownloadBallPosition(xFraction, yFraction) }
    }
}