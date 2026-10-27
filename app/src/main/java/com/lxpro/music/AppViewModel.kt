package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.VerseGroup
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.media.MediaSessionKeeper
import com.lxpro.core.media.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val player: PlayerController,
    private val sessionKeeper: MediaSessionKeeper,
) : ViewModel() {

    init {
        // 文案组首启初始化（幂等）：内置 6 组 + 旧版自建文案迁移为「自建」组
        viewModelScope.launch { settings.ensureVerseGroupsInitialized() }
        // 有歌在播就确保媒体会话服务已启动（系统通知栏/锁屏/蓝牙控制的前提）
        viewModelScope.launch {
            player.state.collect { state ->
                if (state.current != null) sessionKeeper.ensureConnected()
            }
        }
    }

    /**
     * null = **还没读到 DataStore**（不是「没选过」）。
     *
     * ⚠️ 首帧必须等这个值到位再套主题：若先用默认 ④ 白渲染、再动画过渡到用户存的那套，
     * 启动时就会看到一段「白 → 深色」的变色过程（用户 2026-10-05 反馈）。
     */
    val palette: StateFlow<LXPalette?> = settings.paletteId
        .map { LXPalette.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val ambientEnabled: StateFlow<Boolean> = settings.ambientEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── 动态背景（对齐 LX-Music「使用动态背景」）：封面模糊 + 当前配色遮罩 ──
    val dynamicBgEnabled: StateFlow<Boolean> = settings.dynamicBg
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val dynamicBgBlur: StateFlow<Int> = settings.dynamicBgBlur
        .stateIn(viewModelScope, SharingStarted.Eagerly, 18)

    val dynamicBgMaskOpacity: StateFlow<Int> = settings.dynamicBgMaskOpacity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 76)

    /** 跟随系统深浅色（13 §4：浅色配色在系统深色时回落到 ① 暗绿） */
    val followSystemDark: StateFlow<Boolean> = settings.followSystemDark
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 文案轮换：全部文案组 + 组级启停 + 时段分组启停 + 间隔，供全局 LXVerseLine 取用 */
    val verseGroups: StateFlow<List<VerseGroup>> = settings.verseGroups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val enabledVerseGroupIds: StateFlow<Set<String>> = settings.enabledVerseGroupIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val enabledVerseSlots: StateFlow<Set<String>> = settings.enabledVerseSlots
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val verseIntervalSec: StateFlow<Int> = settings.verseIntervalSec
        .stateIn(viewModelScope, SharingStarted.Eagerly, 9)

    /** 当前播放曲目的封面（动态背景的数据源；null = 没有封面，背景层不渲染） */
    val currentCover: StateFlow<String?> = player.state
        .map { it.current?.picUrl }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** OOBE 是否看过；null = 还没读到（首帧不据此跳转，避免给老用户闪一下引导） */
    val onboarded: StateFlow<Boolean?> = settings.onboarded
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun selectPalette(palette: LXPalette) {
        viewModelScope.launch { settings.setPaletteId(palette.id) }
    }

    fun setAmbientEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setAmbientEnabled(enabled) }
    }

    fun markOnboarded() {
        viewModelScope.launch { settings.setOnboarded() }
    }
}

