package com.lxpro.feature.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.eq.EqDeviceDetector
import com.lxpro.core.media.eq.EqLayer
import com.lxpro.core.media.eq.EqOutputDevice
import com.lxpro.core.media.eq.EqPreset
import com.lxpro.core.media.eq.EqPresetRepository
import com.lxpro.core.media.eq.EqUserRepository
import com.lxpro.core.media.eq.matchEqPreset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 均衡器页（17 §5）状态。
 *
 * 三维筛选：搜索词（命中型号名与品牌，§5.4）+ 品牌 chips + 目录筛选 chips
 * （2026-10-16 用户裁决：三层型号零重叠，「精度切换」实为目录筛选，默认「全部」；
 * 2026-10-21 加「自定义」层 = 用户导入预设，§5.3）。
 * 搜索词与品牌不持久化；目录筛选持久化在 DataStore（eq_precision_layer）。
 */
@HiltViewModel
class EqViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val repository: EqPresetRepository,
    private val userRepository: EqUserRepository,
    private val deviceDetector: EqDeviceDetector,
) : ViewModel() {

    /** 收藏集合卡片（17 §5.7：网格 2 列；卡片上直接列出里面曲线的别名） */
    data class CollectionUi(
        val id: String,
        val name: String,
        /** 卡片预览：集合内每条的别名（无别名回落原型号），最多 [PREVIEW_MAX] 条 */
        val preview: List<String>,
        val count: Int,
    )

    data class UiState(
        val loading: Boolean = true,
        /** 直通中（17 §5.2：「正常」= 彻底还原，不走滤波链） */
        val bypass: Boolean = true,
        /** 已套用的曲线名（DataStore 持久化；与 bypass 独立，切回非直通即恢复） */
        val currentName: String? = null,
        val query: String = "",
        val brand: String = EqPresetRepository.BRAND_ALL,
        val layer: String = EqLayer.FILTER_ALL,
        val brands: List<Pair<String, Int>> = emptyList(),
        val layerCounts: Map<EqLayer, Int> = emptyMap(),
        val totalCount: Int = 0,
        /** 三维筛选后的列表行 */
        val rows: List<EqPreset> = emptyList(),
        /** 当前套用曲线的完整数据（顶部卡片画曲线用；未套用为 null） */
        val current: EqPreset? = null,
        /** presetName → 显示别名（17 §5.6：有别名时标题显示别名，副标题写原型号） */
        val aliases: Map<String, String> = emptyMap(),
        /** 收藏集合（17 §5.7，updatedAt 降序） */
        val collections: List<CollectionUi> = emptyList(),
        /** presetName → 所属集合 id 集（列表行星标态 / 选集合弹层初始勾选用） */
        val membership: Map<String, Set<String>> = emptyMap(),
        /** 当前输出设备（17 §5.3 自动匹配：外接耳机才参与，外放为 null） */
        val outputDevice: EqOutputDevice? = null,
        /** 按设备名匹配到的曲线（匹配不上为 null，界面提示手动搜索） */
        val match: EqPreset? = null,
        /** 导入预设结果提示（17 §5.3；null = 不显示） */
        val importMessage: String? = null,
    )

    private val loaded = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val brand = MutableStateFlow(EqPresetRepository.BRAND_ALL)

    /**
     * 预设列表的**非 Flow 数据版本号**：导入 / 删除用户预设后自增，
     * 用来让下面这些 combine 重算（内置资产与 DB 都是显式缓存，不是 Flow）。
     */
    private val revision = MutableStateFlow(0)

    /** 5 个筛选条件的聚合（loaded 翻牌后才会出真实行） */
    private data class Filters(
        val loaded: Boolean,
        val query: String,
        val brand: String,
        val layer: String,
    )

    private val filters = combine(
        loaded, query, brand, settings.eqPrecisionLayer, revision,
    ) { l, q, b, layer, _ -> Filters(l, q, b, layer) }

    /** 用户层数据（别名 + 收藏集合）聚合（17 §5.6 / §5.7） */
    private data class Favorites(
        val aliases: Map<String, String>,
        val collections: List<CollectionUi>,
        val membership: Map<String, Set<String>>,
    )

    private val favorites: Flow<Favorites> = combine(
        userRepository.observeAliases(),
        userRepository.observeCollections(),
    ) { aliases, collections ->
        val membership = HashMap<String, MutableSet<String>>()
        collections.forEach { c ->
            c.presetNames.forEach { name -> membership.getOrPut(name) { mutableSetOf() }.add(c.id) }
        }
        Favorites(
            aliases = aliases,
            collections = collections.map { c ->
                CollectionUi(
                    id = c.id,
                    name = c.name,
                    preview = c.presetNames.take(PREVIEW_MAX).map { aliases[it] ?: it },
                    count = c.presetNames.size,
                )
            },
            membership = membership,
        )
    }

    /** 导入结果提示（一次性，弹完清空） */
    private val importMessage = MutableStateFlow<String?>(null)

    val ui: StateFlow<UiState> = combine(
        filters,
        settings.eqBypass,
        settings.eqCurrentPresetName,
        favorites,
        combine(deviceDetector.observeOutput(), importMessage) { device, msg -> device to msg },
    ) { f, bypass, currentName, fav, deviceAndMsg ->
        val rows = if (f.loaded) repository.filter(f.query, f.brand, f.layer) else emptyList()
        UiState(
            loading = !f.loaded,
            bypass = bypass,
            currentName = currentName,
            query = f.query,
            brand = f.brand,
            layer = f.layer,
            brands = repository.brands,
            layerCounts = repository.layerCounts,
            totalCount = repository.totalCount,
            rows = rows,
            current = currentName?.let(repository::byName),
            aliases = fav.aliases,
            collections = fav.collections,
            membership = fav.membership,
            outputDevice = deviceAndMsg.first,
            // 自动匹配：设备名 → 曲线（内置 + 导入都参与）
            match = if (f.loaded) {
                deviceAndMsg.first?.let { matchEqPreset(it, repository.all()) }
            } else {
                null
            },
            importMessage = deviceAndMsg.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            repository.ensureLoaded()
            loaded.value = true
        }
    }

    fun setQuery(value: String) { query.value = value }

    fun setBrand(value: String) { brand.value = value }

    fun setLayer(layerKey: String) {
        viewModelScope.launch { settings.setEqPrecisionLayer(layerKey) }
    }

    /** 重置筛选（空态引导：清空搜索词 + 品牌 + 目录回到「全部」） */
    fun resetFilters() {
        query.value = ""
        brand.value = EqPresetRepository.BRAND_ALL
        viewModelScope.launch { settings.setEqPrecisionLayer(EqLayer.FILTER_ALL) }
    }

    /** 套用曲线（写入 DataStore；PlayerControllerImpl 的 collect 会把增益同步进 EqConfig） */
    fun apply(preset: EqPreset) = applyByName(preset.name)

    /** 套用曲线（按型号名；集合详情页/自动匹配入口用） */
    fun applyByName(presetName: String) {
        viewModelScope.launch {
            settings.setEqCurrentPresetName(presetName)
            settings.setEqBypass(false)
        }
    }

    /** 切「正常（直通）」（17 §5.2：保留已选曲线名，仅关掉滤波 —— 再点曲线秒恢复） */
    fun bypass() {
        viewModelScope.launch { settings.setEqBypass(true) }
    }

    // ── 导入预设（17 §5.3：AutoEQ / PowerAMP 曲线文件）─────────────────────────

    /** 导入一条曲线；重名或段数不符时给提示，不覆盖内置 142 条 */
    fun importPreset(name: String, gains: FloatArray, source: String) {
        viewModelScope.launch {
            repository.ensureLoaded()
            val ok = repository.importUserPreset(name, gains, source)
            revision.value++
            importMessage.value = if (ok) {
                "已导入「$name」，可在列表里搜到（自定义层）"
            } else {
                "导入失败：已存在同名曲线（内置或已导入），换个名字再试"
            }
        }
    }

    /** 删除导入的曲线（内置 142 条不可删） */
    fun deleteUserPreset(name: String) {
        viewModelScope.launch {
            repository.deleteUserPreset(name)
            // 删掉的正好是当前套用曲线 → 回落直通，避免 EqConfig 指向不存在的预设
            if (settings.eqCurrentPresetName.first() == name) settings.setEqBypass(true)
            revision.value++
        }
    }

    fun clearImportMessage() { importMessage.value = null }

    // ── 别名（17 §5.6）────────────────────────────────────────────────────────

    /** 写别名（非破坏性，只改显示名；空串/同名 = 还原原名） */
    fun setAlias(presetName: String, alias: String) {
        viewModelScope.launch { userRepository.setAlias(presetName, alias) }
    }

    /** 还原原名（17 §5.6） */
    fun clearAlias(presetName: String) {
        viewModelScope.launch { userRepository.clearAlias(presetName) }
    }

    // ── 收藏集合（17 §5.7）────────────────────────────────────────────────────

    /** 新建集合（用户订正：重名由仓库拒绝，界面已内联校验过一次） */
    fun createCollection(name: String) {
        viewModelScope.launch { userRepository.createCollection(name) }
    }

    /**
     * 新建集合 + 立刻把 [presetName] 这条曲线加进去（选集合弹层的「加入」）。
     *
     * 同名集合已存在时**复用它**（不新建副本），保证不会有重名集合。
     */
    fun createCollectionAndAdd(name: String, presetName: String) {
        viewModelScope.launch { userRepository.createCollectionAndAdd(name, presetName) }
    }

    fun renameCollection(id: String, name: String) {
        viewModelScope.launch { userRepository.renameCollection(id, name) }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch { userRepository.deleteCollection(id) }
    }

    /** 选集合弹层「确定」：把该条曲线的归属整体设成 [collectionIds]（可多选） */
    fun setMembership(presetName: String, collectionIds: Set<String>) {
        viewModelScope.launch { userRepository.setMembership(presetName, collectionIds) }
    }

    fun removeFromCollection(collectionId: String, presetName: String) {
        viewModelScope.launch { userRepository.removeFromCollection(collectionId, presetName) }
    }

    companion object {
        /** 集合卡片上直接列出里面曲线的别名（17 §5.7）—— 最多显示 3 条 + 「等 N 条」 */
        const val PREVIEW_MAX = 3
    }
}
