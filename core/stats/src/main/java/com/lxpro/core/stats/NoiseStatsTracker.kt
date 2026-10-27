package com.lxpro.core.stats

import android.os.SystemClock
import android.util.Log
import com.lxpro.core.database.dao.NoiseEventDao
import com.lxpro.core.database.dao.NoiseUserPackDao
import com.lxpro.core.database.dao.NoiseUserSourceDao
import com.lxpro.core.database.entity.NoiseEventEntity
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.noise.NoiseCatalog
import com.lxpro.core.media.noise.NoiseEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 白噪音收听记账器（Application 级，`LXProApp.onCreate` 启动）。
 *
 * 为什么放在 `core:stats` 而不是 `core:media`：账本与聚合属于统计侧，**媒体侧不认识数据库**；
 * `core:stats` 已经依赖 `core:media`（[PlaybackSinkRouter] 实现其 sink 接口），方向天然正确，
 * 不需要再给 media 加反向依赖，也不需要新增接口。
 *
 * 「单独 / 伴随歌曲」的判定（用户 2026-10-26 裁决）：
 * **只看那一刻歌曲是否真的在播**，不看「与歌曲同时播放」开关。因此这里同时订阅
 * [NoiseEngine.state] 与 [PlayerController.state]，在会话进行中累计 `musicMs`。
 * 于是「开关开着但只放了白噪音」自然落在单独聆听里。
 *
 * 一段会话 = 引擎从「在播」到「暂停/停止」之间的时间；中途增删图层不拆段，
 * 只把出现过的音源并进集合。
 */
@Singleton
class NoiseStatsTracker @Inject constructor(
    private val engine: NoiseEngine,
    private val player: PlayerController,
    private val dao: NoiseEventDao,
    private val userSourceDao: NoiseUserSourceDao,
    private val packDao: NoiseUserPackDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: Session? = null

    fun start() {
        scope.launch {
            combine(engine.state, player.state) { noise, music ->
                Tick(
                    noisePlaying = noise.playing && noise.activeSourceIds.isNotEmpty(),
                    soundIds = noise.activeSourceIds,
                    sceneName = noise.currentSceneName,
                    musicPlaying = music.isPlaying,
                )
            }
                .distinctUntilChanged()
                .collect { tick -> onTick(tick) }
        }
    }

    /** 单次 tick 的快照（可比较，用于 `distinctUntilChanged`） */
    private data class Tick(
        val noisePlaying: Boolean,
        val soundIds: Set<String>,
        val sceneName: String?,
        val musicPlaying: Boolean,
    )

    private class Session(
        val startedAt: Long,
        var lastElapsed: Long,
        var musicPlaying: Boolean,
        val soundIds: MutableSet<String>,
        var sceneName: String?,
    ) {
        var durationMs: Long = 0L
        var musicMs: Long = 0L
    }

    private suspend fun onTick(tick: Tick) {
        val elapsed = SystemClock.elapsedRealtime()
        val current = session
        if (current == null) {
            if (tick.noisePlaying) {
                session = Session(
                    startedAt = System.currentTimeMillis(),
                    lastElapsed = elapsed,
                    musicPlaying = tick.musicPlaying,
                    soundIds = tick.soundIds.toMutableSet(),
                    sceneName = tick.sceneName,
                )
            }
            return
        }

        // 先把「上一段状态持续的时间」入账，再更新状态
        val delta = (elapsed - current.lastElapsed).coerceAtLeast(0L)
        current.durationMs += delta
        if (current.musicPlaying) current.musicMs += delta
        current.lastElapsed = elapsed
        current.musicPlaying = tick.musicPlaying
        if (tick.soundIds.isNotEmpty()) current.soundIds.addAll(tick.soundIds)
        if (tick.sceneName != null) current.sceneName = tick.sceneName

        if (!tick.noisePlaying) {
            session = null
            persist(current)
        }
    }

    private suspend fun persist(current: Session) {
        if (current.durationMs < MIN_SESSION_MS) return
        runCatching {
            val names = resolveNames(current.soundIds)
            val endedAt = current.startedAt + current.durationMs
            dao.insert(
                NoiseEventEntity(
                    startedAt = current.startedAt,
                    endedAt = endedAt,
                    durationMs = current.durationMs,
                    musicMs = current.musicMs.coerceIn(0L, current.durationMs),
                    soundIds = JSONArray(current.soundIds.toList()).toString(),
                    soundNames = JSONArray(names).toString(),
                    sceneName = current.sceneName,
                ),
            )
            dao.trimTo(MAX_ROWS)
        }.onFailure { Log.w(LOG_TAG, "白噪音记账失败：${it.message}") }
    }

    /** 内置目录 → 本地导入 → 音源包 → 兜底用 id（自定义音源也要能出现在排行里） */
    private suspend fun resolveNames(ids: Set<String>): List<String> {
        val users = runCatching { userSourceDao.getAll().associate { it.id to it.name } }
            .getOrDefault(emptyMap())
        val packs = runCatching { packDao.getAll().associate { it.id to it.name } }
            .getOrDefault(emptyMap())
        return ids.map { id -> NoiseCatalog.entryOf(id)?.displayName ?: users[id] ?: packs[id] ?: id }
    }

    companion object {
        private const val LOG_TAG = "NoiseStatsTracker"

        /** 太短的开关抖动不入账（误触/切页面的瞬时起停） */
        private const val MIN_SESSION_MS = 1_000L
        private const val MAX_ROWS = 20_000
    }
}
