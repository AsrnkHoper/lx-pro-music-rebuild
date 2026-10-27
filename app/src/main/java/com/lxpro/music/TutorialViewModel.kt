package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.model.SearchMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 教程问答的当前选择（全部即时写入 DataStore，教程结束即可直接使用） */
data class TutorialUiState(
    /** 引导是否正在进行；false 时遮罩层完全不参与布局 */
    val active: Boolean = false,
    /** 当前步骤下标，见 [TUTORIAL_STEPS] */
    val stepIndex: Int = 0,
    val paletteId: String = LXPalette.default.id,
    val quality: String = "320k",
    val searchMode: SearchMode = SearchMode.ALL,
    val dataSaver: Boolean = false,
    val rememberPosition: Boolean = true,
    /** 白噪音默认场景 id；null = 不预置（沿用上次混音） */
    val noiseSceneId: String? = null,
    val finishing: Boolean = false,
)

/**
 * 新手教程：偏好问答 + 实机高亮引导（2026-10-27 用户要求）。
 *
 * 两个要点：
 * 1. 偏好**选一项就落一项**，不攒到结束再写 —— 教程中途改配色就能立刻看到整个界面变色
 * 2. 引导步骤本身不画假界面：遮罩层按 [TUTORIAL_STEPS] 里的 targetId 找到真实控件挖洞，
 *    用户点到了才前进（Tab 步由路由变化推进）
 */
@HiltViewModel
class TutorialViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(TutorialUiState())
    val ui: StateFlow<TutorialUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { loadCurrentPreferences() }
    }

    /** 开始 / 重看引导 */
    fun start() {
        _ui.update { it.copy(active = true, stepIndex = 0, finishing = false) }
    }

    fun next() {
        if (!_ui.value.active) return
        _ui.update { it.copy(stepIndex = (it.stepIndex + 1).coerceAtMost(TUTORIAL_STEPS.lastIndex)) }
    }

    fun back() {
        if (!_ui.value.active) return
        _ui.update { it.copy(stepIndex = (it.stepIndex - 1).coerceAtLeast(0)) }
    }

    /**
     * 路由变化驱动推进：某一步声明了 `advanceOnRoute`，用户亲手点 Tab 跳过去后自动走下一步。
     * 用户点的是真实控件而不是教程按钮 —— 这就是「游戏式」的关键。
     */
    fun onRouteChanged(route: String?) {
        val state = _ui.value
        if (!state.active) return
        val step = TUTORIAL_STEPS.getOrNull(state.stepIndex) as? TutorialStep.Tour ?: return
        val expected = step.advanceOnRoute ?: return
        if (route == expected) next()
    }

    /** 用当前已存的值回填（重装后首启都是默认值；重看教程则沿用上次的选择） */
    private suspend fun loadCurrentPreferences() {
        val paletteId = settings.paletteId.first() ?: LXPalette.default.id
        val quality = settings.preferredQuality.first() ?: "320k"
        val searchMode = settings.searchMode.first()
        val dataSaver = settings.dataSaverEnabled.first()
        val rememberPosition = settings.rememberPlaybackPosition.first()
        val noiseSceneId = settings.noiseDefaultSceneId.first()
        _ui.update {
            it.copy(
                paletteId = paletteId,
                quality = quality,
                searchMode = searchMode,
                dataSaver = dataSaver,
                rememberPosition = rememberPosition,
                noiseSceneId = noiseSceneId,
            )
        }
    }

    fun pickPalette(id: String) {
        _ui.update { it.copy(paletteId = id) }
        viewModelScope.launch { settings.setPaletteId(id) }
    }

    fun pickQuality(value: String) {
        _ui.update { it.copy(quality = value) }
        viewModelScope.launch { settings.setPreferredQuality(value) }
    }

    fun pickSearchMode(mode: SearchMode) {
        _ui.update { it.copy(searchMode = mode) }
        viewModelScope.launch { settings.setSearchMode(mode) }
    }

    /** 省流量模式：一次写入总开关 + 省流时降音质 + 省流时全无图（三个键的老行为一致） */
    fun setDataSaver(enabled: Boolean) {
        _ui.update { it.copy(dataSaver = enabled) }
        viewModelScope.launch {
            settings.setDataSaverEnabled(enabled)
            settings.setDataSaverAutoQuality(enabled)
            settings.setDataSaverNoImage(enabled)
        }
    }

    fun setRememberPosition(enabled: Boolean) {
        _ui.update { it.copy(rememberPosition = enabled) }
        viewModelScope.launch { settings.setRememberPlaybackPosition(enabled) }
    }

    fun pickNoiseScene(id: String?) {
        _ui.update { it.copy(noiseSceneId = id) }
        viewModelScope.launch { settings.setNoiseDefaultSceneId(id) }
    }

    /** 完成 / 跳过教程：收尾交给界面（标记 onboarded） */
    fun finish(onDone: () -> Unit) {
        if (_ui.value.finishing) return
        _ui.update { it.copy(active = false, finishing = false) }
        onDone()
    }
}
