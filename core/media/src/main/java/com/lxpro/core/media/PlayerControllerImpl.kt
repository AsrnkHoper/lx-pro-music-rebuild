package com.lxpro.core.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.network.RequestHeaderStrategy
import com.lxpro.source.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M1 实现：自己维护队列与索引，**每首歌单独取流后 setMediaItem**。
 *
 * 这样做的理由：预解析整队列会产生 N 次网络请求；而无缝播放本就在 P2 之外（02 §5.2）。
 */
@Singleton
class PlayerControllerImpl @Inject constructor(
    private val player: ExoPlayer,
    private val sources: SourceRegistry,
    private val headerStrategy: RequestHeaderStrategy,
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private var repeatMode: RepeatMode = RepeatMode.ALL
    private var currentQuality: Quality = Quality.K128

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> _state.update { it.copy(isBuffering = true) }
                    Player.STATE_READY -> _state.update {
                        it.copy(
                            isBuffering = false,
                            durationMs = player.duration.coerceAtLeast(0L),
                        )
                    }
                    Player.STATE_ENDED -> {
                        _state.update { it.copy(isBuffering = false) }
                        scope.launch { handleEnded() }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update {
                    it.copy(isBuffering = false, error = error.message ?: "播放失败")
                }
            }
        })

        // 进度轮询：ExoPlayer 没有 position 的 Flow，只能定时取
        scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = player.currentPosition,
                            bufferedMs = player.bufferedPosition,
                            durationMs = player.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    override suspend fun play(song: Song, quality: Quality, queue: List<Song>) {
        val list = if (queue.isEmpty()) listOf(song) else queue
        _queue.value = list
        currentQuality = quality
        val index = list.indexOfFirst { it.source == song.source && it.id == song.id }
            .coerceAtLeast(0)
        playAt(index)
    }

    override suspend fun playAt(index: Int) {
        val song = _queue.value.getOrNull(index) ?: return
        _currentIndex.value = index
        _state.value = PlayerState(current = song, isBuffering = true, quality = currentQuality)
        runCatching { loadAndPlay(song) }
            .onFailure { throwable ->
                _state.update {
                    it.copy(isBuffering = false, error = throwable.message ?: "取流失败")
                }
            }
    }

    override fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override suspend fun next() {
        val list = _queue.value
        if (list.isEmpty()) return
        val target = _currentIndex.value + 1
        if (target < list.size) {
            playAt(target)
        } else if (repeatMode == RepeatMode.ALL) {
            playAt(0)
        } else {
            player.pause()
        }
    }

    override suspend fun previous() {
        val list = _queue.value
        if (list.isEmpty()) return
        // 播放超过 3 秒时，"上一首"先回到本曲开头（与主流播放器一致）
        if (player.currentPosition > RESTART_THRESHOLD_MS) {
            player.seekTo(0)
            return
        }
        val target = _currentIndex.value - 1
        if (target >= 0) {
            playAt(target)
        } else if (repeatMode == RepeatMode.ALL) {
            playAt(list.lastIndex)
        } else {
            player.seekTo(0)
        }
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        player.repeatMode = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        }
    }

    override fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override suspend fun switchQuality(quality: Quality) {
        val song = _state.value.current ?: return
        val position = player.currentPosition
        val wasPlaying = player.isPlaying
        currentQuality = quality
        runCatching {
            loadAndPlay(song)
            player.seekTo(position)
            if (!wasPlaying) player.pause()
        }.onFailure { throwable ->
            _state.update { it.copy(error = throwable.message ?: "切换音质失败") }
        }
    }

    private fun handleEnded() {
        val list = _queue.value
        if (list.isEmpty()) return
        if (repeatMode == RepeatMode.ONE) {
            player.seekTo(0)
            player.play()
            return
        }
        scope.launch { next() }
    }

    private suspend fun loadAndPlay(song: Song) {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.singer)
            .apply { song.picUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        val mediaUri: Uri = if (song.source == LOCAL_SOURCE_ID) {
            // 本地文件：直连文件，不走网络、不注入任何请求头
            val fileUri = song.raw["uri"] ?: error("本地曲目缺少文件地址")
            Uri.parse(fileUri)
        } else {
            val source = sources.byId(song.source) ?: error("音源不可用：${song.source.value}")
            val musicUrl = source.getMusicUrl(song, currentQuality)
            // ⚠️ 请求头随 spec 走（per-request），不要用全局 setDefaultRequestProperties
            val headers = headerStrategy.headersFor(song) +
                ("User-Agent" to headerStrategy.userAgentFor(song.source))
            HeaderAwareDataSource.buildUri(musicUrl.url, headers)
        }

        player.setMediaItem(
            MediaItem.Builder()
                .setUri(mediaUri)
                .setMediaId("${song.source.value}:${song.id}")
                .setMediaMetadata(metadata)
                .build(),
        )
        player.prepare()
        player.play()
        _state.update { it.copy(error = null, isBuffering = false) }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}