package com.lxpro.feature.download

import android.content.Context
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.database.entity.DownloadState
import com.lxpro.core.download.DownloadEngine
import com.lxpro.core.download.DownloadItem
import com.lxpro.core.download.DownloadRepository
import com.lxpro.core.download.toItemOrNull
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「已完成」的音质筛选（原型 `#fbar`：全部 / 无损 / 320K / 128K） */
enum class QualityFilter(val label: String) {
    ALL("全部"),
    LOSSLESS("无损"),
    K320("320K"),
    K128("128K"),
    ;

    fun matches(quality: Quality?): Boolean = when (this) {
        ALL -> true
        // 无损一档要同时覆盖 flac / hires / master（原型也是这个口径）
        LOSSLESS -> quality in setOf(Quality.FLAC, Quality.HIRES, Quality.MASTER)
        K320 -> quality == Quality.K320
        K128 -> quality == Quality.K128
    }
}

data class DownloadUiState(
    val active: List<DownloadItem> = emptyList(),
    val completed: List<DownloadItem> = emptyList(),
    val completedTotal: Int = 0,
    val filter: QualityFilter = QualityFilter.ALL,
    /** 本地曲库占用（含下载入库的） */
    val localBytes: Long = 0L,
    /** 下载缓存占用 */
    val cacheBytes: Long = 0L,
    val deviceTotalBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,
    val concurrency: Int = 3,
    val autoImport: Boolean = true,
    val retryLimit: Int = 1,
) {
    val anyRunning: Boolean get() = active.any { it.isRunning }
    /** 有「排队中 / 下载中」才谈得上「全部暂停」；否则按钮是「全部继续」 */
    val hasActiveWork: Boolean get() = active.any { it.isRunning || it.isQueued }
}

/**
 * 下载管理页（M3 任务 8，依据 `mockup_download.html`）。
 *
 * 数据全部来自真实下载引擎（[DownloadEngine]）—— 没有 mock 任务。
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val engine: DownloadEngine,
    private val library: LocalMusicRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(QualityFilter.ALL)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val taskBundle = combine(
        repo.observeTasks(),
        filter,
        library.observeTracks(),
    ) { tasks, currentFilter, tracks ->
        TaskBundle(
            tasks = tasks.mapNotNull { task -> task.toItemOrNull() },
            filter = currentFilter,
            localBytes = tracks.sumOf { it.sizeBytes },
        )
    }

    private val prefBundle = combine(
        settings.downloadConcurrency,
        settings.downloadAutoImport,
        settings.downloadRetryLimit,
    ) { concurrency, autoImport, retryLimit -> Triple(concurrency, autoImport, retryLimit) }

    val uiState: StateFlow<DownloadUiState> = combine(taskBundle, prefBundle) { tasks, pref ->
        val active = tasks.tasks.filter { it.state != DownloadState.COMPLETED }
        val done = tasks.tasks.filter { it.isDone }
        val (concurrency, autoImport, retryLimit) = pref
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        DownloadUiState(
            active = active,
            completed = done.filter { tasks.filter.matches(it.quality) },
            completedTotal = done.size,
            filter = tasks.filter,
            localBytes = tasks.localBytes,
            cacheBytes = done.sumOf { it.task.totalBytes.coerceAtLeast(0L) },
            deviceTotalBytes = stat?.totalBytes ?: 0L,
            deviceFreeBytes = stat?.availableBytes ?: 0L,
            concurrency = concurrency,
            autoImport = autoImport,
            retryLimit = retryLimit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadUiState())

    // ── 任务操作 ──

    fun onPause(item: DownloadItem) = engine.pause(item.task.id)

    fun onResume(item: DownloadItem) = engine.resume(item.task.id)

    fun onRetry(item: DownloadItem) = engine.retry(item.task.id)

    fun onRemove(item: DownloadItem) {
        engine.remove(item.task.id)
        report("已删除《${item.task.name}》")
    }

    /** 顶栏主按钮：有进行中就全部暂停，否则全部继续 */
    fun onToggleAll() {
        if (uiState.value.hasActiveWork) {
            engine.pauseAll()
            report("已全部暂停 · 已下载部分保留")
        } else {
            engine.resumeAll()
            report("已全部继续")
        }
    }

    fun onClearCompleted() {
        engine.clearCompleted()
        report("已清空已完成，缓存空间已释放")
    }

    fun onFilterChange(next: QualityFilter) {
        filter.value = next
    }

    // ── 下载设置 ──

    fun onConcurrencyChange(value: Int) {
        viewModelScope.launch { settings.setDownloadConcurrency(value) }
    }

    fun onAutoImportChange(enabled: Boolean) {
        viewModelScope.launch { settings.setDownloadAutoImport(enabled) }
    }

    fun onRetryLimitChange(value: Int) {
        viewModelScope.launch { settings.setDownloadRetryLimit(value) }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private fun report(text: String) {
        _message.value = text
    }

    private data class TaskBundle(
        val tasks: List<DownloadItem>,
        val filter: QualityFilter,
        val localBytes: Long,
    )

    /** 点已完成的下载行 → 直接播放（原型 app 版就是这个行为） */
    fun playQueue(item: DownloadItem): Pair<Song, List<Song>> =
        item.song to uiState.value.completed.map { it.song }
}