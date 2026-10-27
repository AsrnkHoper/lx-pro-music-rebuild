package com.lxpro.core.media.eq

import android.content.Context
import android.util.Log
import com.lxpro.core.database.dao.EqUserPresetDao
import com.lxpro.core.database.entity.EqUserPresetEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EQ 预设仓库（17 §5）：负责 `assets/eq_presets.json` 的懒加载、去重、检索，
 * 以及把「当前应用的曲线」同步进 [EqConfig]（A/B 双 song player 的处理器实时读取）。
 *
 * 数据形态（2026-10-16 实测）：
 * - 三层目录型号零重叠，去重（每层内首条留存）后 142 条
 * - 列表排序 = 按型号名字典序（与定稿原型 mockup_eq.html 的 142 行一致）
 * - 品牌 = 首词（「64」→「64 Audio」），chips 按出现次数降序
 *
 * 另有 **用户导入的曲线**（17 §5.3，[EqLayer.CUSTOM]）来自 `eq_user_presets` 表，
 * 与内置资产合并进同一份列表；内置资产只读且随版本整体替换，导入项独立可删。
 *
 * 解析用 android 自带 org.json（1.6MB 一次性解析，IO 线程 + Mutex 防重入）。
 */
@Singleton
class EqPresetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eqConfig: EqConfig,
    private val userPresetDao: EqUserPresetDao,
) {

    private val loadMutex = Mutex()

    /** assets 内置 142 条（字典序）；未加载时为 null */
    @Volatile
    private var builtIn: List<EqPreset>? = null

    /** 用户导入的曲线（[EqLayer.CUSTOM]；来自 DB，非 Flow，导入/删除后显式刷新） */
    @Volatile
    private var userPresets: List<EqPreset> = emptyList()

    /** 内置 + 导入的合并列表（字典序）；未加载时为 null（UI 显示加载态） */
    @Volatile
    private var cached: List<EqPreset>? = null

    /** 品牌 → 型号数（按次数降序，其次按名字典序；与 mockup 的品牌 chips 排序一致） */
    @Volatile
    var brands: List<Pair<String, Int>> = emptyList()
        private set

    /** 每层目录的型号数（精度筛选 chips 的计数：raw=58 / raw100=21 / raw400=63） */
    @Volatile
    var layerCounts: Map<EqLayer, Int> = emptyMap()
        private set

    val totalCount: Int get() = cached?.size ?: 0

    /** 幂等加载（首次解析约几十毫秒；之后直接命中内存缓存） */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (cached != null) return@withContext
        loadMutex.withLock {
            if (cached != null) return@withLock
            val t0 = System.currentTimeMillis()
            builtIn = parse()
            userPresets = loadUserPresets()
            rebuild()
            Log.i(TAG, "loaded ${cached?.size} presets in ${System.currentTimeMillis() - t0}ms " +
                "(builtIn=${builtIn?.size}, imported=${userPresets.size}, brands=${brands.size}, layers=$layerCounts)")
        }
    }

    /**
     * 导入 / 删除用户预设后刷新内存合并列表（17 §5.3）。
     * 用户预设来自 DB（非 Flow），改完必须显式翻牌，UI 才会看到新行。
     */
    suspend fun refreshUserPresets() = withContext(Dispatchers.IO) {
        if (builtIn == null) ensureLoaded()      // 先补齐内置集（此处未持锁，避免重入死锁）
        loadMutex.withLock {
            userPresets = loadUserPresets()
            rebuild()
        }
    }

    /** 合并内置 + 导入（字典序，与定稿原型一致），并重算品牌 / 目录计数 */
    private fun rebuild() {
        val merged = (builtIn.orEmpty() + userPresets).sortedBy { it.name }.distinctBy { it.name }
        cached = merged
        brands = merged.groupingBy { it.brand }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        layerCounts = merged.groupingBy { it.layer }.eachCount()
    }

    private suspend fun loadUserPresets(): List<EqPreset> =
        userPresetDao.getAll().mapNotNull { row ->
            val gains = row.gains.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (gains.size != BAND_COUNT) {
                Log.w(TAG, "imported preset dropped (bad gains): ${row.name}")
                null
            } else {
                EqPreset(row.name, EqLayer.CUSTOM, gains.toFloatArray())
            }
        }

    /**
     * 导入一条用户预设（17 §5.3）。
     *
     * ⚠️ 重名一律拒绝：内置 142 条或已导入的同名曲线都不覆盖 —— 内置资产是权威数据，
     * 用户想微调应走「别名 / 收藏集合」而非覆盖型号名。
     *
     * @return true = 已导入；false = 名称为空 / 增益段数不对 / 同名已存在
     */
    suspend fun importUserPreset(name: String, gains: FloatArray, source: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || gains.size != BAND_COUNT) return false
        ensureLoaded()
        if (cached?.any { it.name.equals(trimmed, ignoreCase = true) } == true) return false
        userPresetDao.upsert(
            EqUserPresetEntity(
                name = trimmed,
                // Float.toString() 是 locale 无关的（不会出现逗号小数点）
                gains = gains.joinToString(",") { it.toString() },
                source = source,
                createdAt = System.currentTimeMillis(),
            ),
        )
        refreshUserPresets()
        Log.i(TAG, "imported preset: $trimmed (source=$source)")
        return true
    }

    /** 删除导入的预设（只有 [EqLayer.CUSTOM] 的行可删） */
    suspend fun deleteUserPreset(name: String) {
        userPresetDao.delete(name)
        refreshUserPresets()
    }

    /** 全量列表（调用前必须 [ensureLoaded]；UI 层在 init 里已触发） */
    fun all(): List<EqPreset> = cached.orEmpty()

    fun byName(name: String): EqPreset? = cached?.firstOrNull { it.name == name }

    /**
     * 关键词 + 品牌 + 目录 三维筛选（17 §5.4：搜索命中型号名与品牌两条路径）。
     * 关键词大小写不敏感；品牌 "all" 与目录 [EqLayer.FILTER_ALL] 表示不过滤。
     */
    fun filter(query: String, brand: String, layerKey: String): List<EqPreset> {
        var rows = all()
        if (layerKey != EqLayer.FILTER_ALL) {
            val layer = EqLayer.fromKey(layerKey)
            if (layer != null) rows = rows.filter { it.layer == layer }
        }
        if (brand != BRAND_ALL) rows = rows.filter { it.brand == brand }
        val q = query.trim()
        if (q.isNotEmpty()) {
            rows = rows.filter { it.name.contains(q, ignoreCase = true) || it.brand.contains(q, ignoreCase = true) }
        }
        return rows
    }

    /**
     * 把选定曲线同步进 [EqConfig]（处理器每个 queueInput 都会比对快照）。
     * @param name null = 不加载曲线（直通由 [EqConfig.bypass] 另行控制）
     */
    suspend fun applyToConfig(name: String?) {
        if (name == null) {
            eqConfig.currentGains = null
            eqConfig.presetName = null
            return
        }
        ensureLoaded()
        val preset = byName(name)
        if (preset == null) {
            Log.w(TAG, "applyToConfig: preset not found: $name")
            eqConfig.currentGains = null
            eqConfig.presetName = null
            return
        }
        eqConfig.currentGains = preset.gains
        eqConfig.presetName = preset.name
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    private fun parse(): List<EqPreset> {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        // 层序固定 raw → raw100 → raw400；型号零重叠，所以层序只影响同层内首条的选取
        // （只遍历这三层：CUSTOM 不是资产层，属于用户导入，见 importUserPreset）
        val unique = LinkedHashMap<String, EqPreset>(160)
        for (layer in listOf(EqLayer.RAW, EqLayer.RAW100, EqLayer.RAW400)) {
            val arr = root.optJSONArray(layer.layerKey) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                if (unique.containsKey(name)) continue          // 首条留存（定稿原型的去重规则）
                val bands = obj.optJSONArray("bands") ?: continue
                if (bands.length() < 34) continue
                val gains = FloatArray(32)
                for (b in 0 until 32) {
                    val band = bands.optJSONObject(2 + b) ?: continue
                    gains[b] = band.optDouble("gain", 0.0).toFloat()
                }
                unique[name] = EqPreset(name, layer, gains)
            }
        }
        // 字典序与定稿原型 142 行一致（python sorted() / JS sort() / Kotlin compareTo 同为码点序）
        return unique.values.sortedBy { it.name }
    }

    companion object {
        private const val TAG = "EqPresetRepository"
        private const val ASSET_NAME = "eq_presets.json"

        /** 峰值段数（与 [EQ_PEAK_FREQS] 对齐；导入曲线不足/超出的段数一律拒绝） */
        private const val BAND_COUNT = 32

        /** 品牌 chips「全部」的 id */
        const val BRAND_ALL = "all"
    }
}
