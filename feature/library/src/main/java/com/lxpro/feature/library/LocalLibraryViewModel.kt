package com.lxpro.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.database.entity.SafRootEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.library.ScanOutcome
import com.lxpro.core.library.toSong
import com.lxpro.core.model.Song
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.core.playlist.songOverrideKey
import com.lxpro.core.playlist.withOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LocalLibraryUiState(
    val scanning: Boolean = false,
    val lastOutcome: ScanOutcome? = null,
    val error: String? = null,
)

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val repository: LocalMusicRepository,
    private val songOverrides: SongOverrideRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalLibraryUiState())
    val uiState: StateFlow<LocalLibraryUiState> = _uiState.asStateFlow()

    /** 歌曲行是否显示封面（设置里的默认值；页内切换只影响本次会话） */
    val showCover: StateFlow<Boolean> = settings.listShowCover
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * 本地曲目（统一转成 Song，可直接进播放队列；已被仓储按指纹去重）。
     * ⚠️ 与歌单详情、搜索结果同一口径：**套用「重命名」修正**，
     * 否则同一首歌在三处的名字会不一致。
     */
    val tracks: StateFlow<List<Song>> = combine(
        repository.observeTracks(),
        songOverrides.observe(),
    ) { entities, overrideMap ->
        entities.map { entity ->
            val song = entity.toSong()
            song.withOverride(overrideMap[songOverrideKey(song.id, song.source.value)])
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 用户通过 SAF 授权的目录 */
    val safRoots: StateFlow<List<SafRootEntity>> = repository.safRoots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // 进页面先把索引读进内存缓存，否则本地搜索会是空的
            repository.refreshCache()
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

    fun addSafRoot(treeUri: String, displayName: String) {
        _uiState.value = _uiState.value.copy(scanning = true, error = null)
        viewModelScope.launch {
            runCatching { repository.addSafRoot(treeUri, displayName) }
                .onSuccess { _uiState.value = _uiState.value.copy(scanning = false) }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        scanning = false,
                        // 权限类失败要给**可操作**的提示，而不是笼统的"读取失败"
                        error = when (throwable) {
                            is SecurityException ->
                                "系统限制无法访问该目录。Download 根目录、Android/data 这类位置" +
                                    "系统不允许授权，请改选它的子目录。"
                            else -> throwable.message ?: "读取该目录失败"
                        },
                    )
                }
        }
    }

    fun removeSafRoot(treeUri: String) {
        viewModelScope.launch {
            runCatching { repository.removeSafRoot(treeUri) }
                .onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(error = throwable.message ?: "移除目录失败")
                }
        }
    }

    fun reportPermissionDenied() {
        _uiState.value = _uiState.value.copy(error = "没有读取音频的权限，无法扫描本机音乐")
    }
}