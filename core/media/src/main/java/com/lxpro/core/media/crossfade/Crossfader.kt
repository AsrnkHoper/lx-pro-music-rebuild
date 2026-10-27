package com.lxpro.core.media.crossfade

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 双 ExoPlayer 交叉淡变工具（17 §6，调研结论：**真重叠淡变**必须双 player 并行 fade）。
 *
 * 为什么必须双 player：
 * - 单 ExoPlayer 的 setMediaItem 会**立即切断旧歌**，A 没消失 B 不可能同时响
 * - VolumeShaper 只对 AudioTrack/MediaPlayer 生效，ExoPlayer 内部 AudioTrack 不暴露
 * - 唯一可行方案：两个 ExoPlayer 各自独立 AudioTrack，并行 setVolume() → 系统音频层天然混音重叠
 *
 * [fade] 是挂起函数，在调用方协程内逐帧执行——这样 PlayerControllerImpl 里
 * `currentFadeJob = scope.launch { launch { fade(A) }; launch { fade(B) } }` 的结构下，
 * cancel() 能把两个 fade 一起取消（避免旧 fade 残留与新 fade 抢音量）。
 *
 * ⚠️ 白噪音不参与（17 §1 硬约束②）：切歌时白噪音一直响。
 */
object Crossfader {

    private const val FADE_STEP_MS = 50L  // 20Hz 步进（足够平滑又不吃 CPU）
    private const val PAUSE_FADE_MS = 200L  // 暂停/继续短淡变（不走 crossfadeMs）

    /**
     * 对 [player] 执行从 [startVol] → [targetVol] 的线性淡变（挂起，调用方协程内执行）。
     * 取消传播由调用方的 Job 结构管理。
     */
    suspend fun fade(player: ExoPlayer, startVol: Float, targetVol: Float, durationMs: Long) {
        if (durationMs <= 0L) {
            player.volume = targetVol.coerceIn(0f, 1f)
            return
        }
        val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1).toInt()
        for (i in 1..steps) {
            player.volume = (startVol + (targetVol - startVol) * (i.toFloat() / steps)).coerceIn(0f, 1f)
            delay(FADE_STEP_MS)
        }
        player.volume = targetVol.coerceIn(0f, 1f)
    }

    /** 暂停/继续短淡变（200ms），独立 Job（fire-and-forget 场景用） */
    fun fadePause(
        scope: CoroutineScope,
        player: ExoPlayer,
        startVol: Float,
        targetVol: Float,
        onDone: (() -> Unit)? = null,
    ): Job = scope.launch {
        fade(player, startVol, targetVol, PAUSE_FADE_MS)
        onDone?.invoke()
    }

    /**
     * 等功率淡出：v(t) = start·cos(θ)，θ = t·π/2（DJ 台标准）。
     * 与 [fadeCrossfadeIn] 叠加时 cos²+sin²=1，重叠期感知响度恒定 → 「丝滑渐变」。
     */
    suspend fun fadeCrossfadeOut(player: ExoPlayer, startVol: Float, durationMs: Long) {
        if (durationMs <= 0L) { player.volume = 0f; return }
        val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1).toInt()
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            player.volume = (startVol * cos(t * PI.toFloat() / 2)).coerceIn(0f, 1f)
            delay(FADE_STEP_MS)
        }
        player.volume = 0f
    }

    /** 等功率淡入：v(t) = target·sin(θ)，与 [fadeCrossfadeOut] 配对 */
    suspend fun fadeCrossfadeIn(player: ExoPlayer, targetVol: Float, durationMs: Long) {
        if (durationMs <= 0L) { player.volume = targetVol.coerceIn(0f, 1f); return }
        val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1).toInt()
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            player.volume = (targetVol * sin(t * PI.toFloat() / 2)).coerceIn(0f, 1f)
            delay(FADE_STEP_MS)
        }
        player.volume = targetVol.coerceIn(0f, 1f)
    }
}
