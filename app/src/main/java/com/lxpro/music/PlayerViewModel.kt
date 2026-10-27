package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.PlayerState
import com.lxpro.core.media.timer.SongSleepTimer
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.core.playlist.songOverrideKey
import com.lxpro.core.playlist.withOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放器 UI 状态。播放器本身是应用级单例（[PlayerController]），
 * 所以这里的 ViewModel 只是把它暴露给 Compose。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlayerController,
    private val songOverrides: SongOverrideRepository,
    private val songSleepTimer: SongSleepTimer,
) : ViewModel() {

    /**
     * ⚠️ 播放器里的 Song 是 `play()` 那一刻的**快照**，用户事后「重命名」不会自动回灌 ——
     * 早期表现就是「改完名要重新播一次，播放条才更新」（2026-10-02 真机反馈）。
     * 这里按覆盖表再套一层，播放条与播放页立刻显示新名字。
     */
    val state: StateFlow<PlayerState> = combine(
        controller.state,
        songOverrides.observe(),
    ) { playerState, overrideMap ->
        val current = playerState.current
        if (current == null) {
            playerState
        } else {
            playerState.copy(
                current = current.withOverride(
                    overrideMap[songOverrideKey(current.id, current.source.value)],
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, controller.state.value)

    val queue: StateFlow<List<Song>> = controller.queue

    fun play(song: Song, queue: List<Song>) {
        viewModelScope.launch { controller.play(song, Quality.K320, queue) }
    }

    fun togglePlayPause() = controller.togglePlayPause()

    fun next() {
        viewModelScope.launch { controller.next() }
    }

    fun previous() {
        viewModelScope.launch { controller.previous() }
    }

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    /** 消费播放错误（MainActivity 弹出提示后调用，保证同一错误连续发生时也能再次提示） */
    fun clearError() = controller.clearError()

    /** 切换淡变时长（播放页「更多」→「切换淡变」） */
    val crossfadeMs: Long get() = controller.currentCrossfadeMs()

    fun setCrossfadeMs(ms: Long) = controller.setCrossfadeMs(ms)

    /** 当前倍速（播放页「更多」→「倍速播放」；默认 1.0x） */
    val playbackSpeed: Float get() = controller.currentSpeed()

    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    // ── 歌曲定时关闭（17 §7）──

    /** 当前歌曲定时（null=未开启）；菜单状态与提示条都订阅它 */
    val songTimer: StateFlow<TimerStateEntity?> = songSleepTimer.state

    fun setSongTimerAfterMinutes(minutes: Int, stopNoise: Boolean, finishSongFirst: Boolean = false) {
        viewModelScope.launch { songSleepTimer.setAfterMinutes(minutes, stopNoise, finishSongFirst) }
    }

    fun setSongTimerAfterCurrentTrack(stopNoise: Boolean) {
        viewModelScope.launch { songSleepTimer.setAfterCurrentTrack(stopNoise) }
    }

    fun setSongTimerUntilHour(hour: Int, stopNoise: Boolean, finishSongFirst: Boolean = false) {
        viewModelScope.launch { songSleepTimer.setUntilHour(hour, stopNoise, finishSongFirst) }
    }

    /** 一键取消（提示条「取消」/ 菜单「不开启」） */
    fun cancelSongTimer() {
        viewModelScope.launch { songSleepTimer.cancel() }
    }

    /** 剩余毫秒（仅时间型目标有值；提示条倒计时用） */
    fun songTimerRemainingMs(): Long? = songSleepTimer.remainingMs()
}