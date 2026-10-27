package com.lxpro.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.model.DataSaver
import com.lxpro.core.model.Quality
import com.lxpro.core.model.LOCAL_SOURCE_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 省流量模式的状态源（app 层，`LXProApp.onCreate` 启动，进程活就监着）。
 *
 * 职责只有两件事：
 * 1. 把「设置三开关 + 网络是否计费」写进 [DataSaver]（纯状态对象，全站读写同一份）；
 * 2. 省流状态**翻转**时对正在播的在线歌做一次音质热切换（升/降档），其余消费方
 *    （AsyncArtwork 的无图、play()/loadToPlayer 的取流钳制）直接读 DataSaver，无需广播。
 *
 * 网络口径与 [DownloadEngine] 的「仅 Wi-Fi 下载」一致：`NET_CAPABILITY_NOT_METERED`
 * （Wi-Fi / 免流网络 = 非计费；蜂窝、热点 = 计费）。取不到网络状态按非计费处理。
 */
@Singleton
class DataSaverMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val player: PlayerController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 设置侧四个开关的一次快照（可比较，便于热切换判定） */
    private data class SaverFlags(
        val enabled: Boolean,
        val autoQuality: Boolean,
        val noImage: Boolean,
        val applyOnWifi: Boolean,
    )

    fun start() {
        // ① 网络回调：计费状态一变就更新 + 触发热切换判定
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            DataSaver.unmetered = isUnmeteredNow(cm)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val wasActive = DataSaver.active
                        DataSaver.unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        DataSaver.publish()
                        if (DataSaver.active != wasActive) maybeSwitchQuality()
                    }

                    override fun onLost(network: Network) {
                        val wasActive = DataSaver.active
                        DataSaver.unmetered = isUnmeteredNow(cm)
                        DataSaver.publish()
                        if (DataSaver.active != wasActive) maybeSwitchQuality()
                    }
                },
            )
        }

        // ② 设置开关（DataSaver 的聚合属性会自动反映「是否在省流」）
        scope.launch {
            combine(
                settings.dataSaverEnabled,
                settings.dataSaverAutoQuality,
                settings.dataSaverNoImage,
                settings.dataSaverApplyOnWifi,
            ) { enabled, autoQuality, noImage, applyOnWifi -> SaverFlags(enabled, autoQuality, noImage, applyOnWifi) }
                .collect { flags ->
                    val wasDowngrade = DataSaver.qualityDowngrade
                    DataSaver.enabled = flags.enabled
                    DataSaver.autoQuality = flags.autoQuality
                    DataSaver.noImage = flags.noImage
                    DataSaver.applyOnWifi = flags.applyOnWifi
                    DataSaver.publish()
                    if (DataSaver.qualityDowngrade != wasDowngrade) maybeSwitchQuality()
                }
        }
    }

    /**
     * 省流翻转时对**正在播**的在线歌热切换：降档 128k / 升回「默认音质」。
     * 暂停中不动（重取流反而重新拉一遍流，白耗流量）——后续取流有 loadToPlayer
     * 的钳制兜底，下一首自然生效。用户在播放页显式选的音质（switchQuality）不被覆盖。
     */
    private fun maybeSwitchQuality() {
        val state = player.state.value
        val song = state.current ?: return
        if (song.source == LOCAL_SOURCE_ID) return
        if (!state.isPlaying) return
        scope.launch {
            val want = if (DataSaver.qualityDowngrade) {
                Quality.K128
            } else {
                settings.preferredQuality.first()
                    ?.let { pref -> Quality.entries.firstOrNull { it.value == pref } }
                    ?: Quality.K320
            }
            val current = state.quality ?: return@launch
            if (current != want) player.switchQuality(want)
        }
    }

    private fun isUnmeteredNow(cm: ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
