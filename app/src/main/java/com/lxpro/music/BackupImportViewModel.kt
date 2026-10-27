package com.lxpro.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.stats.ImportKind
import com.lxpro.core.stats.ImportResult
import com.lxpro.core.stats.LxBackupExporter
import com.lxpro.core.stats.LxBackupImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportUiState(
    val running: Boolean = false,
    /** 正在导入哪一种（两个入口各自显示自己的进度） */
    val runningKind: ImportKind? = null,
    val progress: String = "",
    val result: ImportResult? = null,
) {
    fun isRunning(kind: ImportKind): Boolean = running && runningKind == kind

    fun resultOf(kind: ImportKind): ImportResult? = result?.takeIf { it.kind == kind }

    /** 回执文案（成功 / 失败各一句） */
    fun receiptOf(kind: ImportKind): String? {
        val done = resultOf(kind) ?: return null
        if (done.failed) return "导入失败：${done.error ?: "未知原因"}"
        if (done.isEmptyButOk) return "备份里没有可导入的内容"
        return buildString {
            append("${kind.label}已导入")
            if (done.events > 0) append(" · 统计事件 ${done.events} 条")
            if (done.historyRows > 0) append(" · 播放历史 ${done.historyRows} 条")
            if (done.likedSongs > 0) append(" · 收藏 ${done.likedSongs} 首")
            if (done.playlists > 0) append(" · ${done.playlists} 个歌单（${done.playlistSongs} 首）")
            if (done.tags > 0) append(" · 标签 ${done.tags} 个")
            if (done.noiseSessions > 0) append(" · 白噪音 ${done.noiseSessions} 段")
            if (done.overrides > 0) append(" · 歌曲修正 ${done.overrides} 条")
            if (done.artistIcons > 0) append(" · 歌手图标 ${done.artistIcons} 个")
        }
    }
}

/** 「导出全量备份」的进度与回执 */
data class ExportUiState(
    val running: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val failed: Boolean = false,
)

/** 「扫描 Download 导入」扫到的一个候选文件 */
data class ScannedBackup(
    val uri: Uri,
    val name: String,
    /** MediaStore 的 RELATIVE_PATH（展示用，如 `Download/`、`Download/lx/`） */
    val path: String,
    /** 按 JSON `type` 字段识别的备份类型；null = 未知格式（不可勾选导入） */
    val kind: ImportKind?,
)

/** 「扫描 Download 导入」弹层的状态 */
data class ScanUiState(
    val scanning: Boolean = false,
    /** 是否至少扫完过一次（区分「未扫」与「扫了但 0 个」的空态文案） */
    val scanned: Boolean = false,
    val files: List<ScannedBackup> = emptyList(),
    val importing: Boolean = false,
    val progress: String = "",
    /** 「导入所选」完成后的汇总回执 */
    val receipt: String? = null,
    val failed: Boolean = false,
)

/**
 * 「设置 → 数据与备份」的执行者：**一个全量备份入口**（导出 + 导入）+ Download 扫描。
 *
 * - 导出：本应用全量数据写进单个 `.lxmc`（[LxBackupExporter]）
 * - 导入：按文件 `type` 自动分流（新 `lxpro_all`，旧 `lx_stats_data` / `allData_v3` 仍可用）
 */
@HiltViewModel
class BackupImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: LxBackupImporter,
    private val exporter: LxBackupExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val _export = MutableStateFlow(ExportUiState())
    val export: StateFlow<ExportUiState> = _export.asStateFlow()

    /** 导入任意 .lxmc（自动识别：全量 / 统计 / 歌单） */
    fun importAny(uri: Uri) = run(uri)

    fun consumeResult() {
        _state.value = ImportUiState()
    }

    fun consumeExport() {
        if (!_export.value.running) _export.value = ExportUiState()
    }

    /** 导出全量备份（SAF CreateDocument 选好位置后写入） */
    fun exportTo(uri: Uri) {
        if (_export.value.running) return
        viewModelScope.launch {
            _export.value = ExportUiState(running = true, progress = "准备导出…")
            val failure = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: return@runCatching "打不开这个位置，换个目录再试"
                stream.use { out ->
                    exporter.exportTo(out, BuildConfig.VERSION_NAME) { step ->
                        _export.value = _export.value.copy(progress = step)
                    }
                }
                null
            }.getOrElse { it.message ?: "写入失败" }
            _export.value = if (failure == null) {
                ExportUiState(message = "全量备份已导出")
            } else {
                ExportUiState(message = "导出失败：$failure", failed = true)
            }
        }
    }

    // ── 「扫描 Download 导入」──

    private val _scan = MutableStateFlow(ScanUiState())
    val scan: StateFlow<ScanUiState> = _scan.asStateFlow()

    /** 扫描系统 Download 目录（含子目录）里的 .lxmc，逐个解 gzip 读 `type` 识别备份种类 */
    fun scanDownload() {
        if (_scan.value.scanning || _scan.value.importing) return
        viewModelScope.launch {
            _scan.value = ScanUiState(scanning = true, progress = "正在 Download 目录查找 .lxmc 备份…")
            val found = withContext(Dispatchers.IO) { queryDownloadBackups() }
            _scan.value = ScanUiState(scanned = true, files = found)
        }
    }

    /** 逐个导入勾选的文件，按识别出的种类分派到对应入口，最后汇总成一条回执 */
    fun importScanned(selected: List<ScannedBackup>) {
        val targets = selected.filter { it.kind != null }
        if (targets.isEmpty() || _scan.value.importing || _scan.value.scanning) return
        viewModelScope.launch {
            _scan.value = _scan.value.copy(importing = true, progress = "准备导入…", receipt = null, failed = false)
            var events = 0
            var historyRows = 0
            var liked = 0
            var lists = 0
            var listSongs = 0
            var tags = 0
            var noiseSessions = 0
            var overrides = 0
            var artistIcons = 0
            val failures = mutableListOf<String>()
            targets.forEachIndexed { index, file ->
                val kind = file.kind ?: return@forEachIndexed
                val prefix = "（${index + 1}/${targets.size}）${file.name}"
                _scan.value = _scan.value.copy(progress = "$prefix：读取备份…")
                val result = runCatching {
                    val stream = context.contentResolver.openInputStream(file.uri)
                        ?: return@runCatching ImportResult(kind, failed = true, error = "打不开这个文件")
                    stream.use { input ->
                        val onProgress: (String) -> Unit = { step ->
                            _scan.value = _scan.value.copy(progress = "$prefix：$step")
                        }
                        importer.importAny(input, onProgress)
                    }
                }.getOrElse { ImportResult(kind, failed = true, error = it.message ?: "读取失败") }
                if (result.failed) {
                    failures += "${file.name}：${result.error ?: "未知原因"}"
                } else {
                    events += result.events
                    historyRows += result.historyRows
                    liked += result.likedSongs
                    lists += result.playlists
                    listSongs += result.playlistSongs
                    tags += result.tags
                    noiseSessions += result.noiseSessions
                    overrides += result.overrides
                    artistIcons += result.artistIcons
                }
            }
            val receipt = buildString {
                append("已处理 ${targets.size} 个文件")
                if (events > 0) append(" · 统计事件 $events 条")
                if (historyRows > 0) append(" · 播放历史 $historyRows 条")
                if (liked > 0) append(" · 收藏 $liked 首")
                if (lists > 0) append(" · $lists 个歌单（$listSongs 首）")
                if (tags > 0) append(" · 标签 $tags 个")
                if (noiseSessions > 0) append(" · 白噪音 $noiseSessions 段")
                if (overrides > 0) append(" · 歌曲修正 $overrides 条")
                if (artistIcons > 0) append(" · 歌手图标 $artistIcons 个")
                if (failures.isNotEmpty()) append("\n${failures.size} 个失败：${failures.joinToString("；")}")
            }
            _scan.value = _scan.value.copy(
                importing = false,
                progress = "",
                receipt = receipt,
                failed = failures.isNotEmpty(),
            )
        }
    }

    /** 关掉扫描弹层时重置（导入进行中不允许关，由 UI 拦着） */
    fun dismissScan() {
        if (!_scan.value.importing) _scan.value = ScanUiState()
    }

    /** MediaStore.Files 查 `Download/` 开头的 .lxmc；查不到（权限/机型限制）就返回空表 */
    private fun queryDownloadBackups(): List<ScannedBackup> {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("Download/%", "%.lxmc")
        val out = mutableListOf<ScannedBackup>()
        runCatching {
            resolver.query(
                collection, projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    val kind = runCatching {
                        resolver.openInputStream(uri)?.use { importer.sniffKind(it) }
                    }.getOrNull()
                    out += ScannedBackup(
                        uri = uri,
                        name = cursor.getString(nameCol) ?: uri.lastPathSegment.orEmpty(),
                        path = cursor.getString(pathCol) ?: "Download/",
                        kind = kind,
                    )
                }
            }
        }
        return out
    }

    private fun run(uri: Uri) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.value = ImportUiState(running = true, runningKind = ImportKind.UNIFIED, progress = "读取备份…")
            val result = runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@runCatching ImportResult(
                        ImportKind.UNIFIED,
                        failed = true,
                        error = "打不开这个文件",
                    )
                stream.use { input ->
                    val onProgress: (String) -> Unit = { step ->
                        _state.value = _state.value.copy(progress = step)
                    }
                    importer.importAny(input, onProgress)
                }
            }.getOrElse {
                ImportResult(ImportKind.UNIFIED, failed = true, error = it.message ?: "读取失败")
            }
            _state.value = ImportUiState(result = result)
        }
    }
}