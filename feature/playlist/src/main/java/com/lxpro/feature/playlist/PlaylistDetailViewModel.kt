package com.lxpro.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.TextNormalizer
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.designsystem.component.LXQuality
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 路由参数名，与 `Routes.PLAYLIST_DETAIL` 占位符一致 */
const val ARG_PLAYLIST_ID = "playlistId"

/** 二级页两种排布：有封面 / 无封面（序号） */
enum class SongLayout { COVER, COMPACT }

/** 二级页排序（`dSort` 面板）。「默认」即手动顺序，也是添加顺序，故不再单列「按添加时间」 */
enum class SongSort(val label: String) {
    DEFAULT("默认顺序"),
    NAME("按歌名"),
    SINGER("按歌手"),
    DURATION("按时长"),
}

/** 「添加到其它歌单」面板用的一项 */
data class PlaylistOption(val id: String, val name: String)

data class PlaylistDetailUiState(
    val loading: Boolean = true,
    val name: String = "",
    val isSystem: Boolean = false,
    /** 已套用页内搜索与排序的展示列表 */
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val coverUrl: String? = null,
    /** 已设过自定义封面（歌曲封面或本地图片），弹窗据此显示「恢复默认封面」 */
    val hasCustomCover: Boolean = false,
    /** 「从歌单歌曲选择封面」弹窗用的全量歌曲（不受页内搜索影响） */
    val coverSongs: List<Song> = emptyList(),
    val query: String = "",
    val layout: SongLayout = SongLayout.COVER,
    val sort: SongSort = SongSort.DEFAULT,
    /** sourceId → 展示名，用于每行的来源标记（15 §5：不得出现不知道来源的歌曲行） */
    val sourceNames: Map<String, String> = emptyMap(),
    /** 被用户改过歌名/作者的行（界面上标「已修正」），键见 `songOverrideKey` */
    val correctedKeys: Set<String> = emptySet(),
    /** 可加入的其它歌单（不含当前歌单） */
    val otherPlaylists: List<PlaylistOption> = emptyList(),
    /** 一次性轻提示，展示后由 `onMessageShown` 清空 */
    val message: String? = null,
)

/** 歌单详情页（两级跳转的第二级）。 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val songOverrides: SongOverrideRepository,
    private val sourceRegistry: SourceRegistry,
    private val downloads: DownloadEngine,
    private val crossSourceFinder: CrossSourceFinder,
    private val swapPreviewPlayer: SwapPreviewPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: String = savedStateHandle.get<String>(ARG_PLAYLIST_ID).orEmpty()

    private val query = MutableStateFlow("")
    private val layout = MutableStateFlow(SongLayout.COVER)
    private val sort = MutableStateFlow(SongSort.DEFAULT)
    private val message = MutableStateFlow<String?>(null)

    private data class RepoData(
        val playlist: PlaylistEntity?,
        val songs: List<Song>,
        val sourceNames: Map<String, String>,
        val correctedKeys: Set<String>,
        val otherPlaylists: List<PlaylistOption>,
    )

    private val repoData = combine(
        repository.observePlaylist(playlistId),
        repository.observeSongs(playlistId),
        sourceRegistry.all,
        repository.observePlaylists(),
        songOverrides.observe(),
    ) { playlist, songs, sources, allPlaylists, overrides ->
        RepoData(
            playlist = playlist,
            songs = songs,
            sourceNames = buildMap {
                put(LOCAL_SOURCE_ID.value, LOCAL_SOURCE_NAME)
                sources.forEach { put(it.id.value, it.name) }
            },
            // 只要改过歌名或作者任一项，就算「已修正」
            correctedKeys = overrides
                .filterValues { it.name != null || it.singer != null }
                .keys,
            otherPlaylists = allPlaylists
                .filter { it.id != playlistId }
                .map { PlaylistOption(it.id, it.name) },
        )
    }

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        repoData,
        query,
        layout,
        sort,
        message,
    ) { data, keyword, currentLayout, currentSort, currentMessage ->
        val normalized = keyword.trim()
        val visible = data.songs
            .filter {
                normalized.isEmpty() ||
                    TextNormalizer.matches(it.name, normalized) ||
                    TextNormalizer.matches(it.singer, normalized)
            }
            .sortedWith(sortComparator(currentSort))

        val entity = data.playlist
        // 封面优先级：本地图片 > 用户指定的歌单内歌曲 > 第一首有封面的歌（与一级页网格口径一致）
        val coverSongId = entity?.coverSongId
        val coverSongSource = entity?.coverSource
        val coverFromSong = if (coverSongId != null && coverSongSource != null) {
            data.songs
                .firstOrNull { it.id == coverSongId && it.source.value == coverSongSource }
                ?.picUrl
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        PlaylistDetailUiState(
            loading = false,
            name = entity?.name.orEmpty(),
            isSystem = entity?.isSystem ?: false,
            songs = visible,
            totalCount = data.songs.size,
            coverUrl = entity?.coverImageUri
                ?: coverFromSong
                ?: data.songs.firstOrNull { !it.picUrl.isNullOrBlank() }?.picUrl,
            hasCustomCover = entity?.coverImageUri != null ||
                (coverSongId != null && coverSongSource != null),
            coverSongs = data.songs,
            query = keyword,
            layout = currentLayout,
            sort = currentSort,
            sourceNames = data.sourceNames,
            correctedKeys = data.correctedKeys,
            otherPlaylists = data.otherPlaylists,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onLayoutChange(value: SongLayout) {
        layout.value = value
    }

    fun onSortChange(value: SongSort) {
        sort.value = value
    }

    fun onMessageShown() {
        message.value = null
    }

    fun rename(name: String) {
        viewModelScope.launch {
            if (repository.rename(playlistId, name)) message.value = "歌单已改名为「$name」"
        }
    }

    /** 删除歌单后回调（调用方负责返回上一级）。系统歌单在 SQL 层就被挡住 */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            onDeleted()
        }
    }

    fun removeSong(song: Song) {
        viewModelScope.launch {
            repository.removeSong(playlistId, song.id, song.source.value)
            message.value = "已把「${song.name}」移出本歌单"
        }
    }

    fun addToPlaylist(targetPlaylistId: String, song: Song) {
        viewModelScope.launch {
            val added = repository.addSong(targetPlaylistId, song)
            val name = uiState.value.otherPlaylists
                .firstOrNull { it.id == targetPlaylistId }?.name
                .orEmpty()
            message.value = if (added) {
                "已添加到「$name」"
            } else {
                "「$name」里已经有这首歌了"
            }
        }
    }

    /** 新建歌单并立刻把这首歌放进去（添加到面板里的「新建歌单」） */
    fun createPlaylistAndAdd(song: Song, rawName: String) {
        viewModelScope.launch {
            val id = repository.createPlaylist(rawName)
            repository.addSong(id, song)
            message.value = "已新建歌单并加入这首歌"
        }
    }

    /** 「移动位置」：界面给的是 1 基序号，仓储收 0 基下标 */
    fun moveSong(song: Song, position1Based: Int) {
        viewModelScope.launch {
            val moved = repository.moveSong(
                playlistId = playlistId,
                songId = song.id,
                source = song.source.value,
                targetIndex = position1Based - 1,
            )
            message.value = if (moved) "「${song.name}」已移动到第 $position1Based 位" else "这首歌不在本歌单里"
        }
    }

    /** 拖动排序落库：传入拖完之后的完整顺序 */
    fun reorder(ordered: List<Song>) {
        viewModelScope.launch {
            repository.reorder(
                playlistId = playlistId,
                ordered = ordered.map { it.id to it.source.value },
            )
            message.value = "顺序已保存"
        }
    }

    /** 自定义封面：用歌单内某首歌的封面 */
    fun setCoverFromSong(song: Song) {
        viewModelScope.launch {
            repository.setCoverFromSong(playlistId, song.id, song.source.value)
            message.value = "封面已更新"
        }
    }

    /** 自定义封面：本地图片（调用方须已 takePersistableUriPermission） */
    fun setCoverFromImage(imageUri: String) {
        viewModelScope.launch {
            repository.setCoverFromImage(playlistId, imageUri)
            message.value = "封面已更新"
        }
    }

    /** 恢复默认封面（第一首有封面的歌） */
    fun clearCover() {
        viewModelScope.launch {
            repository.clearCover(playlistId)
            message.value = "已恢复默认封面"
        }
    }

    /** 重命名歌曲（只改本地显示，不写回音源；统计按新名字归并） */
    fun renameSong(song: Song, newName: String) {
        viewModelScope.launch {
            songOverrides.renameSong(song.id, song.source.value, newName)
            message.value = "歌曲名已改为「$newName」"
        }
    }

    /** 重命名作者 —— 修正搬运号的错误署名，避免污染歌手归并 */
    fun renameSinger(song: Song, newSinger: String) {
        viewModelScope.launch {
            songOverrides.renameSinger(song.id, song.source.value, newSinger)
            message.value = "作者已改为「$newSinger」· 听歌统计将按新名字归并"
        }
    }

    /** 「下载」：真正入队（M3 任务 8 的下载引擎） */
    fun notifyDownloadPending(song: Song, quality: Quality) {
        viewModelScope.launch {
            val queued = downloads.enqueue(song, quality)
            message.value = if (queued) {
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

    /** 打开歌曲操作弹层时重置面板状态；钉住信息来自歌曲自带的 `toggleSong`（仓储合并点已带上） */
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

    /** 钉住换源目标：持久化到 song_overrides；面板保持打开，对照卡即时更新（对齐 LX） */
    fun pinSwap(target: Song) {
        val song = swapSong ?: return
        swapPreviewPlayer.stop()
        viewModelScope.launch {
            songOverrides.setToggle(song.id, song.source.value, target)
            _swapState.update { it.copy(pinned = target) }
            val sourceName = uiState.value.sourceNames[target.source.value] ?: target.source.value
            message.value = "已换源到「$sourceName」"
        }
    }

    /** 取消换源：恢复原源 */
    fun clearSwap() {
        val song = swapSong ?: return
        swapPreviewPlayer.stop()
        viewModelScope.launch {
            songOverrides.clearToggle(song.id, song.source.value)
            _swapState.update { it.copy(pinned = null) }
            message.value = "已取消换源，恢复原源"
        }
    }
}

/** 「默认」保持仓储给的顺序（position ASC），其余按字段排 */
private fun sortComparator(sort: SongSort): Comparator<Song> = when (sort) {
    SongSort.DEFAULT -> Comparator { _, _ -> 0 }
    SongSort.NAME -> compareBy { it.name }
    SongSort.SINGER -> compareBy { it.singer }
    SongSort.DURATION -> compareBy { it.interval ?: Long.MAX_VALUE }
}