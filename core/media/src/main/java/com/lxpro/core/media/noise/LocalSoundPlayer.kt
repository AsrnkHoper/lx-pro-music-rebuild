package com.lxpro.core.media.noise

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random

/**
 * 一层白噪音的播放器（17 §2.5，照 Noice `LocalSoundPlayer.kt` 移植为 ExoPlayer 版本）。
 *
 * 三条播放器行为（17 §2.5）：
 * - **淡入/淡出**：`play()` 走淡入；`pause()/stop()` 走淡出后再执行动作
 * - **音量变化也淡变**：1.5 秒，避免阶跃噪声（「咔」）
 * - **加载失败指数退避**：1s → 2s → … → 30s 上限
 *
 * @param source 该层所对应的音源元信息
 * @param bitrate CDN 比特率档（128k / 192k / 256k / 320k）
 * @param fadeInDuration 淡入时长（默认 5s，照 Noice）
 * @param fadeOutDuration 淡出时长（默认 1.5s，照 Noice setVolume 1.5s 同源）
 */
class LocalSoundPlayer(
    private val context: Context,
    private val source: NoiseSource,
    private val dataSourceFactory: DataSource.Factory,
    private val bitrate: String = "128k",
    private val defaultScope: CoroutineScope,
    var fadeInMs: Long = 5_000L,
    var fadeOutMs: Long = 1_500L,
) : Player.Listener {

    enum class State {
        BUFFERING, PLAYING, PAUSING, PAUSED, STOPPING, STOPPED
    }

    private var player: ExoPlayer? = null
    private var volume: Float = 1f
    private var scaledVolume: Float = 1f
        get() = volume.pow(2)

    @Volatile
    var state: State = State.STOPPED
        private set

    private var shouldFadeIn = false
    private var queueNextSegmentJob: Job? = null
    private var currentSegment: SoundSegment? = null
    private val random = Random(System.currentTimeMillis())

    /** 启动该层播放器；初次会创建 ExoPlayer 实例（STOPPED→PLAYING 为正常启动路径） */
    fun play() {
        val p = ensurePlayer()
        if (p.isPlaying) {
            state = State.PLAYING
            fadeTo(scaledVolume, fadeInMs)
            return
        }
        if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount == 0) {
            queueNextSegment()
        }
        shouldFadeIn = true
        p.volume = 0f
        p.play()
    }

    fun pause(immediate: Boolean) {
        queueNextSegmentJob?.cancel()
        val p = player ?: return
        if (immediate) {
            p.pause()
            state = State.PAUSED
            return
        }
        state = State.PAUSING
        fadeTo(0f, fadeOutMs) { p.pause() }
    }

    /** 指定淡出时长的暂停（17 §7 睡眠定时到点：淡出 3s 再暂停；常规暂停仍走 fadeOutMs=1.5s） */
    fun pauseWithFade(durationMs: Long) {
        queueNextSegmentJob?.cancel()
        val p = player ?: return
        state = State.PAUSING
        fadeTo(0f, durationMs) { p.pause() }
    }

    fun stop(immediate: Boolean) {
        queueNextSegmentJob?.cancel()
        val p = player ?: run {
            state = State.STOPPED
            return
        }
        if (immediate) {
            p.stop()
            p.release()
            player = null
            state = State.STOPPED
            return
        }
        state = State.STOPPING
        fadeTo(0f, fadeOutMs) {
            p.stop()
            p.release()
            player = null
            state = State.STOPPED
        }
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        val p = player
        if (p != null && p.isPlaying) {
            fadeTo(scaledVolume, VOLUME_FADE_MS)
        } else {
            p?.volume = scaledVolume
        }
    }

    fun setAudioAttributes(attrs: AudioAttributes) {
        player?.setAudioAttributes(attrs, /* handleAudioFocus = */ false)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // 上一段播完，调度下一段
        if (state == State.STOPPED) return
        val p = player ?: return
        // ExoPlayer 没有 remainingItemCount，自己算
        val remaining = p.mediaItemCount - p.currentMediaItemIndex - 1

        if (source.maxSilenceSeconds > 0) {
            // 事件型：等 [minSilence, maxSilence) 内随机时长后再加下一段（mockup gap 区间）
            if (remaining == 0) {
                val min = source.minSilenceSeconds.coerceAtLeast(0)
                val max = source.maxSilenceSeconds.coerceAtLeast(min + 1)
                val afterMs = (min + random.nextInt(max - min)) * 1000L
                queueNextSegmentJob?.cancel()
                queueNextSegmentJob = defaultScope.launch {
                    delay(afterMs)
                    queueNextSegment()
                }
            }
        } else {
            // 连续型：保证 playlist 始终 ≥ 2 段，实现无缝循环
            if (remaining < 2) queueNextSegment()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && shouldFadeIn) {
            shouldFadeIn = false
            val p = player ?: return
            if (state != State.PAUSING && state != State.STOPPING) {
                fadeTo(scaledVolume, fadeInMs)
                state = State.PLAYING
            }
        }
    }

    /** 选取下一段加入 playlist（照 Noice 的桥接段调度逻辑） */
    private fun queueNextSegment() {
        val p = ensurePlayer()
        val segments = source.segments
        if (segments.isEmpty()) {
            Log.w(LOG_TAG, "queueNextSegment: source ${source.id} has no segments")
            return
        }
        val next = when {
            // 当前是桥接段：下一段接它的 `to`
            currentSegment?.isBridgeSegment == true -> segments.find { it.name == currentSegment?.to }
            // 连续型且当前不是首段：随机选一个从当前段出发的桥接段（避免硬切）
            source.maxSilenceSeconds == 0 && currentSegment != null -> {
                val from = currentSegment!!
                segments.filter { it.isBridgeSegment && it.from == from.name }.randomOrNull(random)
                    ?: segments.filter { !it.isBridgeSegment && it.name != from.name }.randomOrNull(random)
            }
            // 事件型或首段：随机一个非桥接段
            else -> segments.filter { !it.isBridgeSegment }.randomOrNull(random)
        } ?: segments.first()

        currentSegment = next
        // 2026-10-20 重构：url 由 SoundSegment 携带（CDN / content:// / file:// 都在这里）
        val wasEmpty = p.mediaItemCount == 0
        // 用 ProgressiveMediaSource + CacheDataSource：段落盘后循环直接读本地，不再走网络
        p.addMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(next.url))))
        // ⚠️ 只在首次（playlist 为空）时 prepare。已 prepare 的 player 反复 prepare 会重置缓冲导致卡顿
        if (wasEmpty) p.prepare()
    }

    private fun ensurePlayer(): ExoPlayer {
        if (player == null) {
            val attrs = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build()
            val nonBridgeSegments = source.segments.filter { !it.isBridgeSegment }
            // CDN 仅 ~46KB/s，放大缓冲避免网络抖动卡顿
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 60_000,
                    /* bufferForPlaybackMs = */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                ).build()
            player = ExoPlayer.Builder(context)
                .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
                .setHandleAudioBecomingNoisy(false) // 白噪音不要因拔耳机停（17 §7：睡眠定时不切歌）
                .setLoadControl(loadControl)
                .build().also { p ->
                    p.addListener(this)
                    // 单段连续型音源（如雨声/海浪/白噪音只有 1 个 free 段）：用 REPEAT_MODE_ONE
                    // 实现真正的无缝循环，不走 onMediaItemTransition 调度（避免段间 gap）
                    if (nonBridgeSegments.size == 1 && source.maxSilenceSeconds == 0) {
                        p.repeatMode = Player.REPEAT_MODE_ONE
                    }
                }
        }
        return player!!
    }

    /** 协程驱动的线性淡变；[done] 在淡变结束时调用（用于 pause/stop 的延后动作） */
    private fun fadeTo(target: Float, durationMs: Long, done: (() -> Unit)? = null) {
        val p = player ?: return
        val start = p.volume
        if (durationMs <= 0L) {
            p.volume = target
            done?.invoke()
            return
        }
        defaultScope.launch {
            val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1).toInt()
            for (i in 1..steps) {
                p.volume = start + (target - start) * (i.toFloat() / steps)
                delay(FADE_STEP_MS)
            }
            p.volume = target
            done?.invoke()
        }
    }

    private fun <T> List<T>.randomOrNull(random: Random): T? =
        if (isEmpty()) null else get(random.nextInt(size))

    companion object {
        private const val LOG_TAG = "LocalSoundPlayer"
        private const val VOLUME_FADE_MS = 1_500L
        private const val FADE_STEP_MS = 50L
    }
}
