package com.lxpro.core.media

import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song

/** 播放状态（03 §2.2） */
data class PlayerState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val quality: Quality? = null,
)

enum class RepeatMode { OFF, ONE, ALL }

/**
 * 播放器门面。UI 只认这个接口（03 §2.2）。
 *
 * ⚠️ M1 的实现每首歌**单独取流并 setMediaItem**（不做预解析整队列）——
 * 无缝播放本来就在 P2 之外（02 §5.2）。
 */
interface PlayerController {
    val state: kotlinx.coroutines.flow.StateFlow<PlayerState>
    val queue: kotlinx.coroutines.flow.StateFlow<List<Song>>
    val currentIndex: kotlinx.coroutines.flow.StateFlow<Int>

    suspend fun play(song: Song, quality: Quality = Quality.K128, queue: List<Song> = emptyList())

    suspend fun playAt(index: Int)

    fun togglePlayPause()

    suspend fun next()

    suspend fun previous()

    fun seekTo(positionMs: Long)

    fun setRepeatMode(mode: RepeatMode)

    fun setSpeed(speed: Float)

    fun setVolume(volume: Float)

    /** 切换音质：重新取流并保持播放位置 */
    suspend fun switchQuality(quality: Quality)
}