package com.lxpro.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.library.ScanOutcome
import com.lxpro.core.library.toSong
import com.lxpro.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalLibraryUiState(
    val scanning: Boolean = false,
    val indexedCount: Int = 0,
    val lastOutcome: ScanOutcome? = null,
    val error: String? = null,
)

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val repository: LocalMusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalLibraryUiState())
    val uiState: StateFlow<LocalLibraryUiState> = _uiState.asStateFlow()

    /** 本地曲目（统一转成 Song，可直接进播放队列） */
    val tracks: StateFlow<List<Song>> = repository.observeTracks()
        .map { entities -> entities.map { it.toSong() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // 进页面先把索引读进内存缓存，否则本地搜索会是空的
            repository.refreshCache()
        }
        viewModelScope.launch {
            repository.trackCount.collect { count ->
                _uiState.value = _uiState.value.copy(indexedCount = count)
            }
        }
    }

    fun scan() {
        if (_uiState.value.scanning) return
        _uiState.value = _uiState.value.copy(scanning = true, error = null)
        viewModelScope.launch {
            runCatching { repository.scan() }
                .onSuccess { outcome ->
                    _uiState.value = _uiState.value.copy(scanning = false, lastOutcome = outcome)
                }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        scanning = false,
                        error = throwable.message ?: "扫描失败",
                    )
                }
        }
    }

    fun reportPermissionDenied() {
        _uiState.value = _uiState.value.copy(error = "没有读取音频的权限，无法扫描本机音乐")
    }
}