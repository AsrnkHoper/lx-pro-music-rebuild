package com.lxpro.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.TextNormalizer
import com.lxpro.core.database.dao.PlaylistSummaryRow
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.playlist.PlaylistRepository
import com.lxpro.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一级页筛选（`mockup_playlist.html` 的 `.fchip`：全部 / 我的 / 收藏 / 含本地） */
enum class PlaylistFilter(val label: String) {
    ALL("全部"),
    MINE("我创建的"),
    LIKED("收藏的"),
    LOCAL("含本地"),
}

/** 一级页两种排布：网格（有封面）/ 列表（紧凑） */
enum class PlaylistLayout { GRID, LIST }

/** 网格里的一张歌单卡 */
data class PlaylistCard(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val itemCount: Int,
    val coverUrl: String?,
    /** 用户从本地图片选的封面（content uri）；非空时优先于 [coverUrl] */
    val coverImageUri: String?,
    /** 至少含一首本地曲目（列表页会打「本地」标） */
    val hasLocal: Boolean,
)

data class PlaylistsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val filter: PlaylistFilter = PlaylistFilter.ALL,
    val layout: PlaylistLayout = PlaylistLayout.GRID,
    val playlists: List<PlaylistCard> = emptyList(),
)

/** 歌单网格页（两级跳转的第一级）。 */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(PlaylistFilter.ALL)
    private val layout = MutableStateFlow(PlaylistLayout.GRID)

    private data class RepoData(
        val playlists: List<PlaylistEntity>,
        val summaries: Map<String, PlaylistSummaryRow>,
        val localIds: Set<String>,
    )

    /** 拆成两段 combine：kotlinx 的具名重载最多 5 个流，这里一共 6 个 */
    private val repoData = combine(
        repository.observePlaylists(),
        repository.observeSummaries(),
        repository.observePlaylistIdsWithLocal(),
    ) { playlists, summaries, localIds -> RepoData(playlists, summaries, localIds) }

    val uiState: StateFlow<PlaylistsUiState> = combine(
        repoData,
        query,
        filter,
        layout,
    ) { data, keyword, currentFilter, currentLayout ->
        val normalized = keyword.trim()
        val cards = data.playlists
            // 页内搜索：歌单数量有限，按名字筛即可；沿用同一套标点归一化
            .filter { normalized.isEmpty() || TextNormalizer.matches(it.name, normalized) }
            .map { playlist ->
                PlaylistCard(
                    id = playlist.id,
                    name = playlist.name,
                    isSystem = playlist.isSystem,
                    itemCount = data.summaries[playlist.id]?.itemCount ?: 0,
                    coverUrl = data.summaries[playlist.id]?.coverUrl,
                    coverImageUri = playlist.coverImageUri,
                    hasLocal = playlist.id in data.localIds,
                )
            }
            .filter { card ->
                when (currentFilter) {
                    PlaylistFilter.ALL -> true
                    PlaylistFilter.MINE -> !card.isSystem
                    PlaylistFilter.LIKED -> card.isSystem
                    PlaylistFilter.LOCAL -> card.hasLocal
                }
            }
        PlaylistsUiState(
            loading = false,
            query = keyword,
            filter = currentFilter,
            layout = currentLayout,
            playlists = cards,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    init {
        viewModelScope.launch {
            // 收藏（liked）是系统歌单，首次进入时确保它存在
            repository.ensureSystemPlaylists()
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterChange(value: PlaylistFilter) {
        filter.value = value
    }

    fun onLayoutChange(value: PlaylistLayout) {
        layout.value = value
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.createPlaylist(name) }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { repository.deletePlaylist(id) }
    }

    /**
     * 一级页拖拽排序落库：传入拖完之后的**全量** id 顺序。
     * 调用方保证列表未被搜索/筛选筛短；系统歌单（收藏）由仓储钳回首位。
     */
    fun reorderPlaylists(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderPlaylists(orderedIds) }
    }

    // ── 组合播放（2026-10-26 用户要求：混听不同歌单）──

    /**
     * 临时混合：按 [ids] 顺序（即一级页展示顺序）把各歌单歌曲拼成一个队列，
     * `songId+source` 去重、先到先得。结果经 [onLoaded] 回调（已在主线程），
     * 由路由层交给播放器起播——本 VM 不持有 PlayerViewModel。
     */
    fun loadCombinedSongs(ids: List<String>, onLoaded: (List<Song>) -> Unit) {
        viewModelScope.launch {
            onLoaded(combineSongs(ids))
        }
    }

    /**
     * 真合并：新建一个歌单并写入去重后的合并曲目，完成后经 [onCreated] 回调新 id
     * （路由层用它跳详情页做确认）。[rawName] 为空时走仓储的自动命名。
     */
    fun mergePlaylists(ids: List<String>, rawName: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val newId = repository.createPlaylist(rawName)
            repository.addSongs(newId, combineSongs(ids))
            onCreated(newId)
        }
    }

    /** 按歌单顺序拼接 + `source:id` 去重（与歌单条目唯一键同一口径） */
    private suspend fun combineSongs(ids: List<String>): List<Song> {
        val merged = LinkedHashMap<String, Song>()
        ids.forEach { id ->
            repository.observeSongs(id).first().forEach { song ->
                merged.putIfAbsent("${song.source.value}:${song.id}", song)
            }
        }
        return merged.values.toList()
    }
}