package com.lxpro.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.LyricLine
import com.lxpro.core.common.LyricParser
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.model.Song
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LyricsUiState(
    val loading: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    /** 无歌词 / 取词失败时的说明文案（有 lines 时为空） */
    val message: String? = null,
)

/**
 * 歌词取词。
 *
 * ⚠️ 挂在**播放页路由**的作用域上（见 MainActivity），因此只在用户打开播放页时才取词，
 * 不会为后台播放白白发网络请求。
 *
 * 换歌才重新取（`distinctUntilChanged` + `collectLatest` 会取消上一首尚未完成的请求）。
 */
@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val sourceRegistry: SourceRegistry,
    private val localMusicRepository: LocalMusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playerController.state
                .map { it.current }
                .distinctUntilChanged()
                .collectLatest { song -> load(song) }
        }
    }

    private suspend fun load(song: Song?) {
        if (song == null) {
            _uiState.value = LyricsUiState()
            return
        }

        _uiState.value = LyricsUiState(loading = true)

        // 本地曲目：同目录同名 .lrc 优先，其次读音频内嵌歌词（ID3v2 USLT/SYLT、FLAC Vorbis）
        if (song.source == LOCAL_SOURCE_ID) {
            val raw = runCatching { localMusicRepository.lyricsForTrackUri(song.id) }.getOrNull()
            val lines = LyricParser.parse(raw)
            _uiState.value = LyricsUiState(
                lines = lines,
                message = if (lines.isEmpty()) {
                    "这首没有歌词：同目录下没有同名 .lrc，音频里也没有内嵌歌词"
                } else {
                    null
                },
            )
            return
        }

        val source = sourceRegistry.byId(song.source)
        if (source == null) {
            _uiState.value = LyricsUiState(message = "音源不可用，取不到歌词")
            return
        }

        runCatching { source.getLyric(song) }
            .onSuccess { info ->
                val lines = LyricParser.parse(info.lyric, info.tlyric)
                _uiState.value = LyricsUiState(
                    lines = lines,
                    message = if (lines.isEmpty()) "这首没有歌词" else null,
                )
            }
            .onFailure { throwable ->
                _uiState.value = LyricsUiState(message = throwable.message ?: "歌词获取失败")
            }
    }
}