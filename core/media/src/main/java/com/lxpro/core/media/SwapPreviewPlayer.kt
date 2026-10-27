package com.lxpro.core.media

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.network.RequestHeaderStrategy
import com.lxpro.source.api.CrossSourceFinder
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 换源面板候选「试听」播放器（04 · 换源；2026-10-27 用户要求：换源应该能预览播放）。
 *
 * 与主播放完全隔离：自带一个 ExoPlayer（共享缓存与请求头策略），不动播放队列、
 * 不进 MediaSession/通知栏/历史。试听开始时暂停主播放（记住之前是否在播），
 * 停止 / 切候选 / 面板关闭时结束试听并按需恢复主播放。
 */
@Singleton
class SwapPreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val cache: Cache,
    private val registry: SourceRegistry,
    private val headerStrategy: RequestHeaderStrategy,
    private val playerController: PlayerController,
) {

    /** key = "source:id"（正在试听的那条候选）；loading = 取流/起播中 */
    data class PreviewState(
        val key: String? = null,
        val loading: Boolean = false,
        val playing: Boolean = false,
    )

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var resumeMainOnStop = false
    private var loadGen = 0

    /** 试听切换：同一候选再点 = 停止；不同候选 = 切过去 */
    fun toggle(song: Song) {
        val key = "${song.source.value}:${song.id}"
        if (_state.value.key == key) {
            stop()
            return
        }
        scope.launch { startInternal(song, key) }
    }

    /** 停止试听（面板关闭 / 钉住 / 取消换源时调用方负责调） */
    fun stop() {
        scope.launch { stopInternal() }
    }

    private suspend fun startInternal(song: Song, key: String) {
        val gen = ++loadGen
        releasePlayer()
        _state.value = PreviewState(key = key, loading = true)
        // 主播放暂停（只记一次，连续切候选不重复记）
        if (!resumeMainOnStop && playerController.state.value.isPlaying) {
            playerController.togglePlayPause()
            resumeMainOnStop = true
        }
        val uri = runCatching { fetchPreviewUri(song) }.getOrElse {
            Log.w(LOG_TAG, "preview fetch failed: ${song.source.value}:${song.id}", it)
            if (gen == loadGen) stopInternal()
            return
        }
        if (gen != loadGen) return
        val p = PlayerFactory.create(context, okHttpClient, cache, handleAudioFocus = false)
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (gen == loadGen) _state.value = PreviewState(key = key, loading = false, playing = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && gen == loadGen) stop()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(LOG_TAG, "preview play error: ${error.message}")
                if (gen == loadGen) stop()
            }
        })
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.play()
    }

    private fun stopInternal() {
        ++loadGen
        releasePlayer()
        _state.value = PreviewState()
        if (resumeMainOnStop) {
            resumeMainOnStop = false
            if (!playerController.state.value.isPlaying) playerController.togglePlayPause()
        }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    private suspend fun fetchPreviewUri(song: Song): android.net.Uri = withContext(Dispatchers.IO) {
        val source = registry.byId(song.source) ?: error("音源不可用：${song.source.value}")
        val quality = CrossSourceFinder.pickQuality(Quality.K320, song.qualitys)
        val musicUrl = source.getMusicUrl(song, quality)
        val headers = headerStrategy.headersFor(song) +
            ("User-Agent" to headerStrategy.userAgentFor(song.source))
        val cacheKey = "${song.source.value}:${song.id}:${quality.name}"
        HeaderAwareDataSource.buildUri(musicUrl.url, headers, cacheKey)
    }

    private companion object {
        const val LOG_TAG = "SwapPreview"
    }
}
