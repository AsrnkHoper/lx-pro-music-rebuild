package com.lxpro.core.media.timer

import com.lxpro.core.database.dao.TimerStateDao
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.database.entity.TimerStateEntity.Scope
import com.lxpro.core.database.entity.TimerStateEntity.TargetKind
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.noise.NoiseEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌曲定时关闭（17 §7，与「白噪音睡眠定时」互不合并）。
 *
 * 三种目标：
 * - [TargetKind.AFTER_MINUTES]：N 分钟后停
 * - [TargetKind.AFTER_CURRENT_TRACK]：播完当前歌曲停（监听 [PlayerController.state] 的 current 变化触发）
 * - [TargetKind.UNTIL_HOUR]：到早上 N 点停（默认 7）
 *
 * 到点行为：歌曲淡出 3 秒再停（[PlayerController.fadeOutPause]）；
 * 「同时停止白噪音」（默认开）→ [NoiseEngine.stopAll]（白噪音自身的 1.5s fadeOut）。
 *
 * 状态持久化在 Room（timer_states / id="song"），App 重启后 [start] 恢复：
 * - 时间型目标：已过点则直接作废删除（重启回来不追杀），未过则按剩余时间重挂 Job
 * - 播完当前歌：重新挂监听（锚点换成重启后实际在播的歌）
 */
@Singleton
class SongSleepTimer @Inject constructor(
    private val dao: TimerStateDao,
    private val controller: PlayerController,
    private val noiseEngine: NoiseEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<TimerStateEntity?>(null)

    /** 当前歌曲定时（null 或 targetKind=off 表示未开启）。UI 提示条订阅 */
    val state: StateFlow<TimerStateEntity?> = _state.asStateFlow()

    /** 时间型目标的倒计时 Job */
    private var countdownJob: Job? = null

    /** 「播完当前歌曲」模式的切歌监听 Job */
    private var watchTrackJob: Job? = null

    /** 到点触发时间戳（System.currentTimeMillis 域；仅时间型目标有值，UI 倒计时用） */
    @Volatile
    private var fireAtMs: Long? = null

    /** App 启动恢复（LXProApp.onCreate 调用；内部自行走 IO，不阻塞主线程） */
    fun start() {
        scope.launch {
            val saved = dao.get(Scope.SONG.id)
            if (saved == null || saved.targetKind == TargetKind.OFF.id) return@launch
            when (saved.targetKind) {
                TargetKind.AFTER_MINUTES.id -> {
                    val fireAt = saved.setAt + (saved.valueMinutes ?: 0) * 60_000L
                    val remain = fireAt - System.currentTimeMillis()
                    if (remain <= 0L) {
                        dao.delete(Scope.SONG.id)   // 已过点：作废不追杀
                        return@launch
                    }
                    _state.value = saved
                    armCountdown(fireAt)
                }
                TargetKind.UNTIL_HOUR.id -> {
                    val fireAt = nextHourTimestamp(saved.targetHour ?: 7)
                    _state.value = saved
                    armCountdown(fireAt)
                }
                TargetKind.AFTER_CURRENT_TRACK.id -> {
                    // ✅ 修复（问题3）：旧版 armTrackWatch 靠监听 controller.state.current 变化做
                    // 判断，但 current 变化发生在 handleEnded→next→playAt 之后 —— 那时下一首已经
                    // start 了，再 fadeOutPause 就是在**下一首**上淡出（用户亲眼所见）。
                    // 现在改成 PlayerControllerImpl 内部的 stopAfterCurrentTrack 标志位，
                    // 由 handleEnded() 主动拦截 —— 下一首根本不会 start，自然没有竞态。
                    _state.value = saved
                    controller.setStopAfterCurrentTrack(true)
                }
                else -> Unit
            }
        }
    }

    /** 剩余毫秒（仅时间型目标；UI 提示条显示倒计时） */
    fun remainingMs(): Long? = fireAtMs?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) }

    suspend fun setAfterMinutes(minutes: Int, stopNoiseToo: Boolean = true, finishSongFirst: Boolean = false) {
        val entity = TimerStateEntity(
            id = Scope.SONG.id,
            targetKind = TargetKind.AFTER_MINUTES.id,
            valueMinutes = minutes,
            stopNoiseToo = stopNoiseToo,
            finishSongFirst = finishSongFirst,
            setAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        _state.value = entity
        armCountdown(entity.setAt + minutes * 60_000L)
    }

    suspend fun setAfterCurrentTrack(stopNoiseToo: Boolean = true) {
        val entity = TimerStateEntity(
            id = Scope.SONG.id,
            targetKind = TargetKind.AFTER_CURRENT_TRACK.id,
            stopNoiseToo = stopNoiseToo,
            setAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        _state.value = entity
        // ✅ 问题3 根因修法：直接让 PlayerControllerImpl 的 handleEnded 拦截，不播下一首
        controller.setStopAfterCurrentTrack(true)
    }

    suspend fun setUntilHour(hour: Int = 7, stopNoiseToo: Boolean = true, finishSongFirst: Boolean = false) {
        val entity = TimerStateEntity(
            id = Scope.SONG.id,
            targetKind = TargetKind.UNTIL_HOUR.id,
            targetHour = hour,
            stopNoiseToo = stopNoiseToo,
            finishSongFirst = finishSongFirst,
            setAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        _state.value = entity
        armCountdown(nextHourTimestamp(hour))
    }

    /** 一键取消（17 §11 验收项 #14） */
    suspend fun cancel() {
        disarm()
        dao.delete(Scope.SONG.id)
        _state.value = null
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun disarm() {
        countdownJob?.cancel()
        countdownJob = null
        fireAtMs = null
        controller.setStopAfterCurrentTrack(false)   // 清拦截标志（避免残留影响后续播放）
    }

    private fun armCountdown(fireAt: Long) {
        disarm()
        fireAtMs = fireAt
        val remain = fireAt - System.currentTimeMillis()
        countdownJob = scope.launch {
            delay(remain.coerceAtLeast(0L))
            fire()
        }
    }

    /**
     * 到点。
     *
     * - finishSongFirst=false（默认）：立刻 fadeOutPause 3s 停
     * - finishSongFirst=true：不立刻停，改设 stopAfterCurrentTrack=true 等 PlayerControllerImpl
     *   的 handleEnded 拦截（问题2：用户 2026-10-20 要求「等歌曲播放完整之后再停止」）
     *
     * CURRENT_TRACK 模式不经过这里（它在 setAfterCurrentTrack() 里就直接设标志了）。
     */
    private fun fire() {
        val entity = _state.value
        val stopNoise = entity?.stopNoiseToo ?: true
        val finishSongFirst = entity?.finishSongFirst ?: false
        if (finishSongFirst) {
            controller.setStopAfterCurrentTrack(true)
            // 等 PlayerControllerImpl.handleEnded() 拦截后我们自己清 Room + state ——
            // 用 isPlaying=false 监听触发（handleEnded 里会 update isPlaying=false）
            scope.launch {
                controller.state.collect { st ->
                    if (!st.isPlaying) {
                        dao.delete(Scope.SONG.id)
                        _state.value = null
                        controller.setStopAfterCurrentTrack(false)
                        return@collect
                    }
                }
            }
        } else {
            controller.fadeOutPause(FADE_OUT_MS)
            if (stopNoise) noiseEngine.stopAll(immediate = false)
            disarm()
            scope.launch {
                dao.delete(Scope.SONG.id)
                _state.value = null
            }
        }
    }

    /** 下一个 [hour] 点整的时间戳（今天已过则取明天） */
    private fun nextHourTimestamp(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, hour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    companion object {
        private const val LOG_TAG = "SongSleepTimer"

        /** 到点淡出时长（17 §7：先淡出 3 秒再停） */
        const val FADE_OUT_MS = 3_000L
    }
}
