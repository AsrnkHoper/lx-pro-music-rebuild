package com.lxpro.core.media.timer

import com.lxpro.core.database.dao.TimerStateDao
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.database.entity.TimerStateEntity.Scope
import com.lxpro.core.database.entity.TimerStateEntity.TargetKind
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
 * 白噪音睡眠定时（17 §7 右列，与「歌曲定时关闭」互不合并）。
 *
 * - **只作用于白噪音**：到点白噪音淡出 3 秒再暂停（[NoiseEngine.fadeOutPauseAll]），歌曲照常播
 * - 选项：不开启 / 15 / 30 / 45 / 60 / 90 / 120 分钟 / 到早上 7:00（**没有**「播完当前歌曲」，
 *   那是歌曲定时独有的目标）
 * - 界面：白噪音页「当前混音」卡副标题显示剩余分钟数（[state] + [remainingMs]）
 *
 * 状态持久化在 Room（timer_states / id="noise"），App 重启后 [start] 恢复：
 * 已过点直接作废删除（重启回来不追杀），未过点按剩余时间重挂 Job。
 */
@Singleton
class NoiseSleepTimer @Inject constructor(
    private val dao: TimerStateDao,
    private val noiseEngine: NoiseEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<TimerStateEntity?>(null)

    /** 当前白噪音定时（null 或 targetKind=off 表示未开启）。UI 订阅 */
    val state: StateFlow<TimerStateEntity?> = _state.asStateFlow()

    private var countdownJob: Job? = null

    /** 到点触发时间戳（System.currentTimeMillis 域；UI 剩余分钟数用） */
    @Volatile
    private var fireAtMs: Long? = null

    /** App 启动恢复（LXProApp.onCreate 调用；内部自行走 IO，不阻塞主线程） */
    fun start() {
        scope.launch {
            val saved = dao.get(Scope.NOISE.id)
            if (saved == null || saved.targetKind == TargetKind.OFF.id) return@launch
            val fireAt = when (saved.targetKind) {
                TargetKind.AFTER_MINUTES.id -> saved.setAt + (saved.valueMinutes ?: 0) * 60_000L
                TargetKind.UNTIL_HOUR.id -> nextHourTimestamp(saved.targetHour ?: 7)
                else -> return@launch
            }
            // 「到早上 7:00」重启后按下一个整点重算，天然不会过点；「N 分钟」可能已过点
            if (saved.targetKind == TargetKind.AFTER_MINUTES.id &&
                fireAt - System.currentTimeMillis() <= 0L
            ) {
                dao.delete(Scope.NOISE.id)   // 已过点：作废不追杀
                return@launch
            }
            _state.value = saved
            armCountdown(fireAt)
        }
    }

    /** 剩余毫秒（UI 混音卡副标题显示剩余分钟数用） */
    fun remainingMs(): Long? = fireAtMs?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) }

    suspend fun setAfterMinutes(minutes: Int) {
        val entity = TimerStateEntity(
            id = Scope.NOISE.id,
            targetKind = TargetKind.AFTER_MINUTES.id,
            valueMinutes = minutes,
            setAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        _state.value = entity
        armCountdown(entity.setAt + minutes * 60_000L)
    }

    suspend fun setUntilHour(hour: Int = 7) {
        val entity = TimerStateEntity(
            id = Scope.NOISE.id,
            targetKind = TargetKind.UNTIL_HOUR.id,
            targetHour = hour,
            setAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        _state.value = entity
        armCountdown(nextHourTimestamp(hour))
    }

    /** 一键取消（17 §7：选「不开启」） */
    suspend fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
        fireAtMs = null
        dao.delete(Scope.NOISE.id)
        _state.value = null
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun armCountdown(fireAt: Long) {
        countdownJob?.cancel()
        fireAtMs = fireAt
        val remain = fireAt - System.currentTimeMillis()
        countdownJob = scope.launch {
            delay(remain.coerceAtLeast(0L))
            fire()
        }
    }

    /** 到点：白噪音淡出 3s 再暂停；**歌曲照常播**（17 §7 右列） */
    private fun fire() {
        noiseEngine.fadeOutPauseAll(FADE_OUT_MS)
        countdownJob = null
        fireAtMs = null
        scope.launch {
            dao.delete(Scope.NOISE.id)
            _state.value = null
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
        private const val LOG_TAG = "NoiseSleepTimer"

        /** 到点淡出时长（17 §7：白噪音淡出 3 秒再暂停） */
        const val FADE_OUT_MS = 3_000L

        /** 可选分钟档（17 §7 右列：15 / 30 / 45 / 60 / 90 / 120） */
        val MINUTE_OPTIONS = listOf(15, 30, 45, 60, 90, 120)
    }
}
