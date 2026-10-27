package com.lxpro.feature.eq

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.eq.EqPreset
import com.lxpro.core.media.eq.EqPresetRepository
import com.lxpro.core.media.eq.EqUserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 收藏集合详情页（17 §5.7）状态。
 *
 * 集合 id 走路由参数（[ARG_EQ_COLLECTION_ID]，SavedStateHandle 取）。
 * 集合名 / 条目都来自 Flow：重命名、移除后页面自己刷新，不需要手动回填。
 */
@HiltViewModel
class EqCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settings: SettingsRepository,
    private val presets: EqPresetRepository,
    private val userRepository: EqUserRepository,
) : ViewModel() {

    private val collectionId: String =
        savedStateHandle.get<String>(ARG_EQ_COLLECTION_ID).orEmpty()

    /** 一条曲线 = 预设数据 + 它的显示别名（17 §5.6：有别名时标题显示别名） */
    data class RowUi(val preset: EqPreset, val alias: String?)

    data class UiState(
        val name: String = "",
        val rows: List<RowUi> = emptyList(),
        /** 直通中（卡片高亮要排除直通态） */
        val bypass: Boolean = true,
        val currentName: String? = null,
        /** 全部集合名（重命名时的同名检测用，用户订正：不允许两个同名集合） */
        val existingNames: List<String> = emptyList(),
    )

    /** 预设表是内存缓存（非 Flow），加载完成需要自己翻牌触发一次重算 */
    private val loaded = MutableStateFlow(false)

    val ui: StateFlow<UiState> = combine(
        userRepository.observeCollections(),
        userRepository.observeAliases(),
        settings.eqBypass,
        settings.eqCurrentPresetName,
        loaded,
    ) { collections, aliases, bypass, currentName, isLoaded ->
        val collection = collections.firstOrNull { it.id == collectionId }
        UiState(
            name = collection?.name.orEmpty(),
            rows = if (!isLoaded) {
                emptyList()
            } else {
                collection?.presetNames.orEmpty().mapNotNull { name ->
                    presets.byName(name)?.let { RowUi(it, aliases[name]) }
                }
            },
            bypass = bypass,
            currentName = currentName,
            existingNames = collections.map { it.name },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            presets.ensureLoaded()
            loaded.value = true
        }
    }

    /** 套用曲线（17 §5.7：集合详情里直接「应用」） */
    fun apply(preset: EqPreset) {
        viewModelScope.launch {
            settings.setEqCurrentPresetName(preset.name)
            settings.setEqBypass(false)
        }
    }

    /** 从集合移除单条（17 §5.7） */
    fun remove(presetName: String) {
        viewModelScope.launch { userRepository.removeFromCollection(collectionId, presetName) }
    }

    /** 重命名集合（17 §5.7） */
    fun rename(name: String) {
        viewModelScope.launch { userRepository.renameCollection(collectionId, name) }
    }

    /** 删除集合（条目随外键 CASCADE 一并删除；界面已先确认）—— 删完回上一页 */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            userRepository.deleteCollection(collectionId)
            onDeleted()
        }
    }
}
