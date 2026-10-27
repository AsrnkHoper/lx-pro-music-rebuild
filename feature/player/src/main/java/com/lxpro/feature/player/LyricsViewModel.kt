package com.lxpro.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.LyricLine
import com.lxpro.core.common.LyricParser
import com.lxpro.core.database.dao.LyricCacheDao
import com.lxpro.core.database.entity.LyricCacheEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.lyric.LyricsCenter
import com.lxpro.core.model.Song
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.source.api.SourceAction
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    private val lyricsCenter: LyricsCenter,
    private val settings: SettingsRepository,
    private val lyricCacheDao: LyricCacheDao,
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

    /** 单源取词结果：lines 为空时 message 说明原因 */
    private data class LyricFetch(val lines: List<LyricLine>, val message: String?)

    private suspend fun load(song: Song?) {
        if (song == null) {
            _uiState.value = LyricsUiState()
            return
        }

        _uiState.value = LyricsUiState(loading = true)

        // 换源钉住的歌：实际出声的是目标源，歌词必须跟目标源走（时间轴才对齐），
        // 目标源取不到再回落原源（04 · 换源，2026-10-27 用户反馈「歌词怎么没识别出来」）
        val primary = lyricLinesFor(song.toggleSong ?: song)
        val fetch = if (primary.lines.isEmpty() && song.toggleSong != null) {
            val fallback = lyricLinesFor(song)
            if (fallback.lines.isNotEmpty()) fallback else primary
        } else {
            primary
        }

        _uiState.value = LyricsUiState(
            lines = fetch.lines.maybeTraditional(),
            message = if (fetch.lines.isEmpty()) fetch.message else null,
        )
    }

    /** 单源取词：本地 → 同目录 .lrc / 音频内嵌；在线 → lyric_cache 先查，未命中再在线请求写回。 */
    private suspend fun lyricLinesFor(song: Song): LyricFetch {
        if (song.source == LOCAL_SOURCE_ID) {
            val raw = runCatching { localMusicRepository.lyricsForTrackUri(song.id) }.getOrNull()
            val lines = LyricParser.parse(raw)
            return LyricFetch(
                lines,
                if (lines.isEmpty()) {
                    "这首没有歌词：同目录下没有同名 .lrc，音频里也没有内嵌歌词"
                } else {
                    null
                },
            )
        }

        // 1) 本地缓存命中 → 直接返回（零网络）
        val cache = runCatching { lyricCacheDao.get(song.source.value, song.id) }.getOrNull()
        if (cache != null) {
            val lines = LyricParser.parse(cache.lyric, cache.tlyric)
            if (lines.isNotEmpty()) return LyricFetch(lines, null)
        }

        // 2) 未命中 → 在线请求
        val source = sourceRegistry.byId(song.source)
            ?: return LyricFetch(emptyList(), "音源不可用，取不到歌词")

        if (SourceAction.LYRIC !in source.actions) {
            return LyricFetch(emptyList(), "该音源不支持歌词")
        }

        return runCatching { source.getLyric(song) }.fold(
            onSuccess = { info ->
                val lines = LyricParser.parse(info.lyric, info.tlyric)
                if (lines.isNotEmpty()) {
                    // 3) 成功落盘缓存（TTL 7 天）
                    runCatching {
                        lyricCacheDao.upsert(
                            LyricCacheEntity(
                                source = song.source.value,
                                songId = song.id,
                                lyric = info.lyric,
                                tlyric = info.tlyric,
                                cachedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                LyricFetch(lines, if (lines.isEmpty()) "这首没有歌词" else null)
            },
            onFailure = { LyricFetch(emptyList(), it.message ?: "歌词获取失败") },
        )
    }

    /** 如果用户开了繁体歌词（默认开），把每行 text + translation 转繁体。
     *  调用 LyricsCenter.convertForDisplay，共享同一个缓存（Singleton）。 */
    private suspend fun List<LyricLine>.maybeTraditional(): List<LyricLine> {
        if (!settings.traditionalLyricEnabled.first()) return this
        return map { line ->
            line.copy(
                text = lyricsCenter.convertForDisplay(line.text),
                translation = line.translation?.let { lyricsCenter.convertForDisplay(it) },
            )
        }
    }
}