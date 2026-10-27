package com.lxpro.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 省流量模式的**进程级门闸**（2026-10-26 用户要求）。
 *
 * 只放三个原始开关 + 网络状态，聚合结论全部是 computed 属性——任何线程任何时刻读到的
 * 都是一致的组合，不需要「重算广播」。写入方只有 app 层的 DataSaverMonitor
 * （设置 Flow + ConnectivityManager 回调），读取方：
 * - [AsyncArtwork-style 图片组件]（designsystem）读 [imagesBlocked]
 * - PlayerControllerImpl.play() 读 [qualityDowngrade]
 *
 * 口径：
 * - 「省流中」= 总开关开 **且** 当前网络计费（蜂窝/热点）。Wi-Fi 下永远不省。
 * - 「非 Wi-Fi 全无图」只挡**网络图**——本地封面（`localart://` 缩略图）不走网络，不受影响。
 * - 音质降档 = 省流中且子开关开 → 播放取流强制 128k（见 PlayerControllerImpl）。
 */
object DataSaver {

    /** 省流量模式总开关（设置页「省流量模式」） */
    @Volatile var enabled: Boolean = false

    /** Wi-Fi 下也启用省流（子开关；默认关 = 只在计费网络生效） */
    @Volatile var applyOnWifi: Boolean = false

    /** 省流时自动降音质（子开关） */
    @Volatile var autoQuality: Boolean = true

    /** 省流时全无图（子开关） */
    @Volatile var noImage: Boolean = true

    /** 当前网络是否**非计费**（Wi-Fi 等）；取不到网络状态时按非计费处理（宁可多花不缺功能） */
    @Volatile var unmetered: Boolean = true

    /** 省流是否正在生效（计费网络必生效；Wi-Fi 下需用户勾选「Wi-Fi 下也启用」） */
    val active: Boolean get() = enabled && (applyOnWifi || !unmetered)

    /** 播放取流是否应强制 128k */
    val qualityDowngrade: Boolean get() = active && autoQuality

    /** 网络图片是否应全部不加载（本地缩略图不受影响） */
    val imagesBlocked: Boolean get() = active && noImage

    // ── Compose 响应式镜像（#128：UI 需要 collectAsState 驱动重组，volatile 做不到）──
    // 写入方（DataSaverMonitor）每次改动 volatile 后必须调 [publish]。

    private val _activeFlow = MutableStateFlow(false)
    /** 省流是否正在生效（响应式） */
    val activeFlow: StateFlow<Boolean> = _activeFlow.asStateFlow()

    private val _imagesBlockedFlow = MutableStateFlow(false)
    /** 是否应进入「无图模式」（响应式） */
    val imagesBlockedFlow: StateFlow<Boolean> = _imagesBlockedFlow.asStateFlow()

    /** 把当前聚合结论推给响应式镜像；幂等，可随时调用 */
    fun publish() {
        _activeFlow.value = active
        _imagesBlockedFlow.value = imagesBlocked
    }
}
