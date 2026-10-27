package com.lxpro.core.media.noise

import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「与歌曲同时播放」开关的跨轨联动（17 §4，批二遗留到批四实装）。
 *
 * 开关**关闭**（withMusic=false）= 白噪音与歌曲互斥：
 * - 白噪音开播 → 歌曲暂停
 * - 歌曲开播 → 白噪音暂停（走 1.5s 淡出，不生硬）
 *
 * 开关**开启**（默认）= 双轨各自独立，互不打断。
 *
 * 只在「未播 → 在播」边沿动作，反向（在播 → 暂停）不触发对方，因此**不会乒乓**。
 * 协调器活在 Application 级（[start] 由 LXProApp.onCreate 调用），不依赖白噪音页打开。
 */
@Singleton
class NoiseMusicCoordinator @Inject constructor(
    private val noiseEngine: NoiseEngine,
    private val player: PlayerController,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        // 白噪音开播边沿 → 歌曲暂停
        scope.launch {
            noiseEngine.state
                .map { it.playing }
                .distinctUntilChanged()
                .collect { playing ->
                    if (playing && player.state.value.isPlaying && !settings.noiseWithMusic.first()) {
                        player.togglePlayPause()
                    }
                }
        }
        // 歌曲开播边沿 → 白噪音暂停
        scope.launch {
            player.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    if (playing && noiseEngine.state.value.playing && !settings.noiseWithMusic.first()) {
                        noiseEngine.pauseAll(immediate = false)
                    }
                }
        }
    }

    companion object {
        private const val LOG_TAG = "NoiseMusicCoordinator"
    }
}
