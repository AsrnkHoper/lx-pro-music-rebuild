package com.lxpro.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.LOCAL_SOURCE_NAME
import com.lxpro.core.model.Song
import com.lxpro.core.playlist.PlaylistRepository
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 路由参数名，与 `Routes.PLAYLIST_DETAIL` 占位符一致 */
const val ARG_PLAYLIST_ID = "playlistId"

data class PlaylistDetailUiState(
    val loading: Boolean = true,
    val name: String = "",
    val isSystem: Boolean = false,
    val songs: List<Song> = emptyList(),
    /** sourceId → 展示名，用于每行的来源标记（15 §5：不得出现不知道来源的歌曲行） */
    val sourceNames: Map<String, String> = emptyMap(),
)

/** 歌单详情页（两级跳转的第二级）。 */
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val repository: PlaylistRepository,
    private val sourceRegistry: SourceRegistry,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: String = savedStateHandle.get<String>(ARG_PLAYLIST_ID).orEmpty()

    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        repository.observePlaylist(playlistId),
        repository.observeSongs(playlistId),
        sourceRegistry.all,
    ) { playlist, songs, sources ->
        PlaylistDetailUiState(
            loading = false,
            name = playlist?.name.orEmpty(),
            isSystem = playlist?.isSystem ?: false,
            songs = songs,
            sourceNames = buildMap {
                put(LOCAL_SOURCE_ID.value, LOCAL_SOURCE_NAME)
                sources.forEach { put(it.id.value, it.name) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailUiState())

    fun rename(name: String) {
        viewModelScope.launch { repository.rename(playlistId, name) }
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
        }
    }
}