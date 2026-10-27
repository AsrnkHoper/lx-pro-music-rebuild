package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.designsystem.component.LXQuality
import com.lxpro.core.designsystem.component.LXSheetOption
import com.lxpro.core.designsystem.component.SwapUiState
import com.lxpro.core.download.DownloadEngine
import com.lxpro.core.media.SwapPreviewPlayer
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.LOCAL_SOURCE_NAME
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.playlist.PlaylistRepository
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.source.api.CrossSourceFinder
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 跨页面共用的「歌曲操作」状态（搜索结果与播放页的「更多」都走它）。
 *
 * 为什么放在应用模块：这是**组合根**才看得到全部仓储的地方，而 feature 模块之间
 * 不允许互相依赖 —— 与其在搜索页和播放页各写一份仓储胶水，不如在组合根持有一份。
 * 歌单详情页有自己的 ViewModel（它多出「移除 / 移动位置」这类歌单上下文动作）。
 */
@HiltViewModel
class SongActionsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val songOverrides: SongOverrideRepository,
    private val sourceRegistry: SourceRegistry,
    private val downloads: DownloadEngine,
    private val crossSourceFinder: CrossSourceFinder,
    private val swapPreviewPlayer: SwapPreviewPlayer,
) : ViewModel() {

    /** 「添加到」面板：可加入的歌单 */
    val playlists: StateFlow<List<LXSheetOption>> = playlistRepository.observePlaylists()
        .map { list -> list.map { LXSheetOption(it.id, it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** sourceId → 展示名（本地 + 各音源）：行内源标记与「换源」面板共用 */
    val sourceNames: StateFlow<Map<String, String>> = sourceRegistry.all
        .map { list ->
            buildMap {
                put(LOCAL_SOURCE_ID.value, LOCAL_SOURCE_NAME)
                list.forEach { put(it.id.value, it.name) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onMessageShown() {
        _message.value = null
    }

    fun addToPlaylist(playlistId: String, playlistName: String, song: Song) {
        viewModelScope.launch {
            val added = playlistRepository.addSong(playlistId, song)
            _message.value = if (added) {
                "已添加到「$playlistName」"
            } else {
                "「$playlistName」里已经有这首歌了"
            }
        }
    }

    fun createPlaylistAndAdd(song: Song, rawName: String) {
        viewModelScope.launch {
            val id = playlistRepository.createPlaylist(rawName)
            playlistRepository.addSong(id, song)
            _message.value = "已新建歌单并加入这首歌"
        }
    }

    fun renameSong(song: Song, newName: String) {
        viewModelScope.launch {
            songOverrides.renameSong(song.id, song.source.value, newName)
            _message.value = "歌曲名已改为「$newName」"
        }
    }

    /** 修正搬运号的错误署名，避免污染歌手归并（真实数据里 DJ Okawari 被拆成 8 条） */
    fun renameSinger(song: Song, newSinger: String) {
        viewModelScope.launch {
            songOverrides.renameSinger(song.id, song.source.value, newSinger)
            _message.value = "作者已改为「$newSinger」· 听歌统计将按新名字归并"
        }
    }

    /** 「下载」：真正入队（M3 任务 8 已落地下载引擎） */
    fun download(song: Song, quality: Quality) {
        viewModelScope.launch {
            val queued = downloads.enqueue(song, quality)
            _message.value = if (queued) {
                "已加入下载队列 · ${LXQuality.label(quality)}"
            } else {
                "这首歌已经下载过了"
            }
        }
    }

    // ── 换源（04 · 对齐 LX MusicToggleModal / findMusic）────────────────────

    private val _swapState = MutableStateFlow(SwapUiState())
    val swapState: StateFlow<SwapUiState> = _swapState.asStateFlow()

    /** 换源候选试听状态（key = "source:id"）；面板行内 ▶/⏸ 用它渲染 */
    val swapPreviewState: StateFlow<SwapPreviewPlayer.PreviewState> = swapPreviewPlayer.state

    /** 当前打开操作弹层的那首歌（换源面板的作用对象） */
    private var swapSong: Song? = null
    private var swapJob: Job? = null

    /** 打开歌曲操作弹层时重置面板状态；钉住信息来自歌曲自带的 `toggleSong` */
    fun resetSwap(song: Song) {
        swapSong = song
        swapJob?.cancel()
        swapPreviewPlayer.stop()
        _swapState.value = SwapUiState(pinned = song.toggleSong)
    }

    /** 面板打开：并行搜各在线源（排除当前源），每回一源立刻上屏，不等最慢源 */
    fun loadSwapCandidates() {
        val song = swapSong ?: return
        swapJob?.cancel()
        _swapState.update { it.copy(loading = true, failed = false, candidates = emptyMap()) }
        swapJob = viewModelScope.launch {
            val ok = runCatching {
                crossSourceFinder.searchAcrossSources(song.name, song.singer) { id, list ->
                    _swapState.update { s -> s.copy(candidates = s.candidates + (id.value to list)) }
                }
            }.isSuccess
            _swapState.update { it.copy(loading = false, failed = !ok) }
        }
    }

    /** 试听候选：同一候选再点 = 停止；不同候选 = 切过去（试听期间主播放暂停） */
    fun toggleSwapPreview(candidate: Song) {
        swapPreviewPlayer.toggle(candidate)
    }

    /** 面板关闭时停止试听（并按需恢复主播放） */
    fun stopSwapPreview() {
        swapPreviewPlayer.stop()
    }

    /** 钉住换源目标：持久化到 song_overrides；返回目标源名供调用方提示 */
    fun pinSwap(target: Song) {
        val song = swapSong ?: return
        swapPreviewPlayer.stop()
        viewModelScope.launch {
            songOverrides.setToggle(song.id, song.source.value, target)
            _swapState.update { it.copy(pinned = target) }
            val sourceName = sourceNames.value[target.source.value] ?: target.source.value
            _message.value = "已换源到「$sourceName」"
        }
    }

    /** 取消换源：恢复原源 */
    fun clearSwap() {
        val song = swapSong ?: return
        swapPreviewPlayer.stop()
        viewModelScope.launch {
            songOverrides.clearToggle(song.id, song.source.value)
            _swapState.update { it.copy(pinned = null) }
            _message.value = "已取消换源，恢复原源"
        }
    }
}