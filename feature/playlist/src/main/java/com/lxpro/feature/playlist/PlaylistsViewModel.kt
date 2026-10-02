package com.lxpro.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.TextNormalizer
import com.lxpro.core.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 网格里的一张歌单卡 */
data class PlaylistCard(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val itemCount: Int,
    val coverUrl: String?,
)

data class PlaylistsUiState(
    val loading: Boolean = true,
    val query: String = "",
    val playlists: List<PlaylistCard> = emptyList(),
)

/** 歌单网格页（两级跳转的第一级）。 */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<PlaylistsUiState> = combine(
        repository.observePlaylists(),
        repository.observeSummaries(),
        query,
    ) { playlists, summaries, keyword ->
        val normalized = keyword.trim()
        // 页内搜索：歌单数量有限，按名字筛即可；沿用同一套标点归一化
        val visible = if (normalized.isEmpty()) {
            playlists
        } else {
            playlists.filter { TextNormalizer.matches(it.name, normalized) }
        }
        PlaylistsUiState(
            loading = false,
            query = keyword,
            playlists = visible.map { playlist ->
                PlaylistCard(
                    id = playlist.id,
                    name = playlist.name,
                    isSystem = playlist.isSystem,
                    itemCount = summaries[playlist.id]?.itemCount ?: 0,
                    coverUrl = summaries[playlist.id]?.coverUrl,
                )
            },
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

    fun createPlaylist(name: String) {
        val existing = uiState.value.playlists.map { it.name }
        viewModelScope.launch { repository.createPlaylist(name, existing) }
    }
}