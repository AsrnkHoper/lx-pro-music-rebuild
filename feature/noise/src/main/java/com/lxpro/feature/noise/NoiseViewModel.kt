package com.lxpro.feature.noise

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.database.dao.NoiseUserComboDao
import com.lxpro.core.database.dao.NoiseUserPackDao
import com.lxpro.core.database.dao.NoiseUserSourceDao
import com.lxpro.core.database.entity.NoiseUserComboEntity
import com.lxpro.core.database.entity.NoiseUserPackEntity
import com.lxpro.core.database.entity.NoiseUserSourceEntity
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.noise.NoiseCatalog
import com.lxpro.core.media.noise.NoiseCategory
import com.lxpro.core.media.noise.NoiseEngine
import com.lxpro.core.media.noise.NoiseLayer
import com.lxpro.core.media.noise.NoiseScene
import com.lxpro.core.media.noise.NoiseSource
import com.lxpro.core.media.noise.SoundMetadataRepository
import com.lxpro.core.media.noise.SoundSegment
import com.lxpro.core.media.timer.NoiseSleepTimer
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.source.api.SourceApi
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * 白噪音页 ViewModel（17 §2 / §4）。
 *
 * 状态分层：
 * - **当前混音结构**（名称/图层/音量）由本 VM 持有并持久化到 DataStore（[SettingsRepository.noiseCurrentSceneJson]）；
 *   重启后恢复结构但**不自动续播**（与 Noice 一致）
 * - **播放态**（playing/activeSourceIds）以 [NoiseEngine.state] 为唯一真相
 * - **目录**（23 音源段信息）来自 [SoundMetadataRepository]（CDN manifest，三级缓存）
 *
 * 副本规则（17 §2.1）：改动**预设场景**的图层 → 自动改名「XX 副本」并解绑；
 * 改动**我的组合** → 直接回写该组合（用户资产，改动即保存，与 Noice 用户预设一致）。
 */
@HiltViewModel
class NoiseViewModel @Inject constructor(
    private val engine: NoiseEngine,
    private val player: PlayerController,
    private val metadataRepo: SoundMetadataRepository,
    private val comboDao: NoiseUserComboDao,
    private val userSourceDao: NoiseUserSourceDao,
    private val packDao: NoiseUserPackDao,
    private val settings: SettingsRepository,
    private val sleepTimer: NoiseSleepTimer,
    /** 在线搜索复用音源聚合（17 §3：不另做一套检索） */
    private val sourceRegistry: SourceRegistry,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val catalogLoading: Boolean = true,
        val catalogError: String? = null,
        /** id → 音源（含段清单），catalog ready 后非空 */
        val sources: Map<String, NoiseSource> = emptyMap(),
        val mixName: String = "自由混音",
        /** 绑定的预设场景 id；null = 自由混音或我的组合 */
        val mixSceneId: String? = null,
        /** 绑定的我的组合 id；null = 预设场景或自由混音 */
        val mixComboId: String? = null,
        /** 当前混音图层（有序：音源 id → 音量 0..1） */
        val mixLayers: List<Pair<String, Float>> = emptyList(),
        /** 引擎播放态（playing 且确有活动图层） */
        val playing: Boolean = false,
        /** 段预下载中（CDN 慢，多图层首次播放需串行缓存） */
        val loading: Boolean = false,
        /** 歌曲轨音量（0..1，双轨独立；17 §1 硬约束③） */
        val songVolume: Float = 1f,
        /** 引擎已加载的图层（含暂停），用于瓦片「已叠进」指示 */
        val activeSourceIds: Set<String> = emptySet(),
        /** 白噪音轨总音量（0..1，双轨独立；17 §1 硬约束③） */
        val totalVolume: Float = 1f,
        val withMusic: Boolean = true,
        /** 音源库分类过滤；null = 全部 */
        val category: NoiseCategory? = null,
        val combos: List<NoiseUserComboEntity> = emptyList(),
        val fadeInMs: Long = 5_000L,
        val fadeOutMs: Long = 1_500L,
        /** 睡眠定时（17 §7 右列；null = 未开启）。混音卡副标题显示剩余分钟数 */
        val noiseTimer: TimerStateEntity? = null,
        /** 本地导入的音源（17 §3 本地导入；与 23 条内置同权） */
        val userSources: List<NoiseUserSourceEntity> = emptyList(),
        // ── 在线搜索（17 §3 第三种获取方式）──
        val onlineQuery: String = "",
        val onlineSearching: Boolean = false,
        val onlineResults: List<Song> = emptyList(),
        val onlineError: String? = null,
        /** 取流 / 缓存结果回执 */
        val onlineMessage: String? = null,
        /** 在线搜索注入的临时音源（流式，只存内存，重启即清） */
        val onlineSources: Map<String, NoiseSource> = emptyMap(),
        /** 远端音源包（17 §3；解压到私有目录，离线可用） */
        val userPacks: List<NoiseUserPackEntity> = emptyList(),
        /** 声音包导入结果回执 */
        val packMessage: String? = null,
    ) {
        /** 正在响的预设场景卡 id（用于 accent 描边）；自由混音为 null */
        val activeSceneId: String? get() = mixSceneId

        /** 定时为「N 分钟」档时的 N（对话框选中态用）；否则 null */
        val noiseTimerMinutes: Int?
            get() = if (noiseTimer?.targetKind == TimerStateEntity.TargetKind.AFTER_MINUTES.id)
                noiseTimer?.valueMinutes else null

        /** 定时为「到早上 7:00」档 */
        val noiseTimerUntilMorning: Boolean
            get() = noiseTimer?.targetKind == TimerStateEntity.TargetKind.UNTIL_HOUR.id
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 2026-10-20：内置 CDN 目录与本地导入目录分别加载，合并进 [_ui.sources] */
    private val builtinCatalog = MutableStateFlow<Map<String, NoiseSource>>(emptyMap())

    init {
        // 引擎播放态 → UI
        viewModelScope.launch {
            engine.state.collect { st ->
                _ui.update {
                    it.copy(
                        playing = st.playing && st.activeSourceIds.isNotEmpty(),
                        loading = st.loading,
                        activeSourceIds = st.activeSourceIds,
                    )
                }
            }
        }
        // 歌曲轨音量 → UI（双轨独立，17 §1 硬约束③）
        viewModelScope.launch {
            player.volumeFlow.collect { v -> _ui.update { it.copy(songVolume = v) } }
        }
        // 我的组合列表
        viewModelScope.launch {
            comboDao.observeAll().collect { list -> _ui.update { it.copy(combos = list) } }
        }
        // 睡眠定时状态 → UI（17 §7：混音卡副标题剩余分钟数 + 定时 chip 高亮）
        viewModelScope.launch {
            sleepTimer.state.collect { t -> _ui.update { it.copy(noiseTimer = t) } }
        }
        // 本地导入音源 → 与内置目录合并（2026-10-20：URL 上移后，本地源与 CDN 源同构）
        viewModelScope.launch {
            userSourceDao.observeAll().collect { users ->
                _ui.update { st -> rebuildSources(st.copy(userSources = users)) }
            }
        }
        // 远端音源包 → 同样并入 sources（17 §3）
        viewModelScope.launch {
            packDao.observeAll().collect { packs ->
                _ui.update { st -> rebuildSources(st.copy(userPacks = packs)) }
            }
        }
        // 设置恢复 + 目录加载
        viewModelScope.launch {
            val total = settings.noiseTotalVolume.first()
            val fadeIn = settings.noiseFadeInMs.first()
            val fadeOut = settings.noiseFadeOutMs.first()
            val withMusic = settings.noiseWithMusic.first()
            val defaultSceneId = settings.noiseDefaultSceneId.first()
            engine.setTotalVolume(total)
            engine.fadeInMs = fadeIn
            engine.fadeOutMs = fadeOut
            _ui.update {
                it.copy(totalVolume = total, withMusic = withMusic, fadeInMs = fadeIn, fadeOutMs = fadeOut)
            }
            restoreSnapshot(defaultSceneId)
            loadCatalog()
        }
    }

    // ── 目录 ──

    fun loadCatalog(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _ui.update { it.copy(catalogLoading = true, catalogError = null) }
            metadataRepo.loadCatalog(forceRefresh)
                .onSuccess { catalog ->
                    builtinCatalog.value = catalog
                    // ⚠️ 批二遗留 + 本轮重构引入：userSourceDao.observeAll() 的 collect 只在 Flow emit 时触发，
                    // loadCatalog 成功回调不会让它重新 emit——所以这里必须立刻合并写回 _ui.sources，
                    // 否则 sources 永远停空 → 整个音源库看不见、白噪音全废。
                    _ui.update { st ->
                        rebuildSources(
                            st.copy(catalogLoading = false),
                            builtin = catalog,
                            filterByCatalog = true,
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(catalogLoading = false, catalogError = e.message ?: "目录加载失败")
                    }
                }
        }
    }

    // ── 本地导入（17 §3 本地导入）───────────────────────────────────

    /** 导入一个本地音频文件作为可叠进的白噪音音源（连续型，整段循环） */
    fun importUserSource(name: String, uri: String, categoryLabel: String = NoiseCategory.LIFE.label) {
        val trimmed = name.trim().ifEmpty { return }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            userSourceDao.upsert(
                NoiseUserSourceEntity(
                    id = "user_${UUID.randomUUID()}",
                    name = trimmed,
                    category = categoryLabel,
                    uri = uri,
                    minSilenceSeconds = 0,
                    maxSilenceSeconds = 0,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    fun deleteUserSource(id: String) {
        viewModelScope.launch {
            // 如果正在混音里叠了这个源，先移除（UI 侧 removeLayer 会先调）
            _ui.value.mixLayers.firstOrNull { it.first == id }?.let { removeLayer(id) }
            userSourceDao.delete(id)
        }
    }

    /** 把 Room 实体转为 [NoiseSource]（17 §3：本地导入段 = 一段完整文件，url = uri） */
    private fun List<NoiseUserSourceEntity>.toNoiseSourceMap(): Map<String, NoiseSource> =
        associate { entity ->
            entity.id to NoiseSource(
                id = entity.id,
                displayName = entity.name,
                category = NoiseCategory.entries.firstOrNull { it.label == entity.category }
                    ?: NoiseCategory.LIFE,
                isRemote = false,
                minSilenceSeconds = entity.minSilenceSeconds,
                maxSilenceSeconds = entity.maxSilenceSeconds,
                segments = listOf(
                    SoundSegment(
                        name = "main",
                        url = entity.uri,
                        basePath = "",
                    )
                ),
            )
        }

    // ── 远端音源包（17 §3 第三种获取方式：zip → 私有目录 → 离线可用）──

    /**
     * 导入声音包（zip）。解压到 `filesDir/noise_packs/<id>/`，元数据落
     * [NoiseUserPackEntity]，段清单由 [NoisePackImporter] 解析（含桥接段与静音间隔）。
     */
    fun importPack(uri: android.net.Uri) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val dir = File(context.filesDir, "noise_packs/$id")
            val parsed = withContext(Dispatchers.IO) {
                runCatching { NoisePackImporter.import(context, uri, dir) }
            }
            parsed.onFailure { e ->
                dir.deleteRecursively()
                _ui.update {
                    it.copy(packMessage = "导入失败：${e.message ?: "无法识别这个声音包"}")
                }
            }
            parsed.onSuccess { pack ->
                val segmentsJson = JSONArray().apply {
                    pack.segments.forEach { seg ->
                        put(
                            JSONObject().apply {
                                put("name", seg.name)
                                put("file", seg.file)
                                put("bridge", seg.bridge)
                                seg.from?.let { put("from", it) }
                                seg.to?.let { put("to", it) }
                            }
                        )
                    }
                }.toString()
                val entity = NoiseUserPackEntity(
                    id = id,
                    name = pack.name,
                    category = pack.category,
                    dirPath = dir.absolutePath,
                    segmentsJson = segmentsJson,
                    minSilenceSeconds = pack.minSilence,
                    maxSilenceSeconds = pack.maxSilence,
                    createdAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { packDao.upsert(entity) }
                _ui.update {
                    it.copy(packMessage = "已导入「${pack.name}」（${pack.segments.size} 段），可在下方点选")
                }
            }
        }
    }

    fun deletePack(id: String) {
        viewModelScope.launch {
            val pack = _ui.value.userPacks.firstOrNull { it.id == id } ?: return@launch
            _ui.value.mixLayers.firstOrNull { it.first == id }?.let { removeLayer(id) }
            withContext(Dispatchers.IO) {
                File(pack.dirPath).deleteRecursively()
                packDao.delete(id)
            }
        }
    }

    fun clearPackMessage() {
        _ui.update { it.copy(packMessage = null) }
    }

    /** 把声音包实体转为 [NoiseSource]（段 URL = 私有目录里的 file://） */
    private fun List<NoiseUserPackEntity>.toPackSourceMap(): Map<String, NoiseSource> =
        associate { pack ->
            pack.id to NoiseSource(
                id = pack.id,
                displayName = pack.name,
                category = NoiseCategory.entries.firstOrNull { it.label == pack.category }
                    ?: NoiseCategory.NATURE,
                isRemote = false,
                minSilenceSeconds = pack.minSilenceSeconds,
                maxSilenceSeconds = pack.maxSilenceSeconds,
                segments = parsePackSegments(pack),
            )
        }

    private fun parsePackSegments(pack: NoiseUserPackEntity): List<SoundSegment> =
        runCatching {
            val array = JSONArray(pack.segmentsJson)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").trim()
                if (file.isEmpty()) return@mapNotNull null
                val from = o.optString("from").trim().ifEmpty { null }
                val to = o.optString("to").trim().ifEmpty { null }
                SoundSegment(
                    name = o.optString("name").trim().ifEmpty { File(file).nameWithoutExtension },
                    url = File(pack.dirPath, file).toURI().toString(),
                    isBridgeSegment = o.optBoolean("bridge", false) && from != null && to != null,
                    from = from,
                    to = to,
                )
            }
        }.getOrDefault(emptyList())

    /**
     * 合并三类动态源 + 内置目录 → [UiState.sources]。
     *
     * @param filterByCatalog true 时剔除已不存在的图层（目录加载成功后调用）；
     *   启动早期目录未就绪必须传 false，否则会把恢复出来的混音结构清空。
     */
    private fun rebuildSources(
        st: UiState,
        builtin: Map<String, NoiseSource> = builtinCatalog.value,
        filterByCatalog: Boolean = builtin.isNotEmpty(),
    ): UiState {
        val combined = builtin +
            st.userSources.toNoiseSourceMap() +
            st.userPacks.toPackSourceMap() +
            st.onlineSources
        val layers = if (filterByCatalog) {
            st.mixLayers.filter { (id, _) -> combined.containsKey(id) }
        } else {
            st.mixLayers
        }
        return st.copy(sources = combined, mixLayers = layers)
    }

    // ── 在线搜索（17 §3 第三种获取方式：复用音源聚合，默认流式，点缓存才落盘）──

    fun setOnlineQuery(query: String) {
        _ui.update { it.copy(onlineQuery = query) }
    }

    /** 跨已启用音源并发搜索，按音源顺序拼接去重（与搜索页同一策略） */
    fun searchOnline() {
        val keyword = _ui.value.onlineQuery.trim()
        if (keyword.isEmpty() || _ui.value.onlineSearching) return
        viewModelScope.launch {
            _ui.update { it.copy(onlineSearching = true, onlineError = null, onlineResults = emptyList()) }
            val apis = sourceRegistry.enabled.value.ifEmpty { sourceRegistry.all.value }
            if (apis.isEmpty()) {
                _ui.update {
                    it.copy(
                        onlineSearching = false,
                        onlineError = "没有可用的音源：请先在设置里导入音源脚本",
                    )
                }
                return@launch
            }
            val batches = coroutineScope {
                apis.map { api -> async { runCatching { api.search(keyword, 1, 8) } } }.awaitAll()
            }
            val songs = batches.mapNotNull { it.getOrNull()?.list }
                .flatten()
                .distinctBy { "${it.name.lowercase()}|${it.singer.lowercase()}" }
                .take(60)
            _ui.update {
                it.copy(
                    onlineSearching = false,
                    onlineResults = songs,
                    onlineError = if (songs.isEmpty()) {
                        "没有搜到「$keyword」。音乐平台的曲库里白噪音资源有限，换个关键词试试"
                    } else null,
                )
            }
        }
    }

    /** 流式播放一条在线结果：取流 → 作为临时音源叠进混音（不落盘） */
    fun playOnline(song: Song) {
        viewModelScope.launch {
            val api = sourceRegistry.byId(song.source)
            if (api == null) {
                _ui.update { it.copy(onlineMessage = "未找到音源「${song.source.value}」") }
                return@launch
            }
            val url = resolveUrl(api, song)
            if (url == null) {
                _ui.update {
                    it.copy(onlineMessage = "取流失败：该平台可能未导入取流脚本，或这首没有版权链接")
                }
                return@launch
            }
            val sid = onlineSourceId(song)
            val src = NoiseSource(
                id = sid,
                displayName = song.name,
                category = NoiseCategory.NOISE,
                isRemote = true,
                streaming = true,
                segments = listOf(SoundSegment(name = "main", url = url)),
            )
            _ui.update {
                it.copy(
                    sources = it.sources + (sid to src),
                    onlineSources = it.onlineSources + (sid to src),
                    onlineMessage = null,
                )
            }
            tapSource(sid)
            if (!_ui.value.playing) togglePlay()
        }
    }

    /** 缓存一条在线结果：取流 → 下载到应用私有目录 → 落库成「我的白噪音」 */
    fun cacheOnline(song: Song) {
        viewModelScope.launch {
            val api = sourceRegistry.byId(song.source)
            if (api == null) {
                _ui.update { it.copy(onlineMessage = "未找到音源「${song.source.value}」") }
                return@launch
            }
            val url = resolveUrl(api, song)
            if (url == null) {
                _ui.update { it.copy(onlineMessage = "取流失败，无法缓存") }
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "noise_user").apply { mkdirs() }
                    val ext = url.substringBefore('?').substringAfterLast('.', "mp3").take(4)
                    val file = File(dir, "${UUID.randomUUID()}.$ext")
                    URL(url).openStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    val now = System.currentTimeMillis()
                    userSourceDao.upsert(
                        NoiseUserSourceEntity(
                            id = UUID.randomUUID().toString(),
                            name = song.name,
                            category = NoiseCategory.NOISE.label,
                            uri = file.toURI().toString(),
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                }.isSuccess
            }
            _ui.update {
                it.copy(
                    onlineMessage = if (ok) "已缓存「${song.name}」，可在「我的白噪音」里找到"
                    else "缓存失败：下载或写入出错",
                )
            }
        }
    }

    fun clearOnlineMessage() {
        _ui.update { it.copy(onlineMessage = null) }
    }

    private fun onlineSourceId(song: Song): String = "online_${song.source.value}_${song.id}"

    /** 取流：优先 128k（白噪音不需要高码率，省流量），退化到音源支持的第一档 */
    private suspend fun resolveUrl(api: SourceApi, song: Song): String? {
        val quality = api.qualities.firstOrNull { it == Quality.K128 }
            ?: song.qualitys.firstOrNull()
            ?: api.qualities.firstOrNull()
            ?: Quality.K128
        return runCatching { api.getMusicUrl(song, quality).url }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // ── 场景 / 组合应用 ──

    /**
     * 点预设场景卡：填充混音结构；若正在播放则平滑切换（17 §2.1 playScene 语义）。
     *
     * **再次点击当前场景 = 取消选中**（2026-10-23 用户要求）：清空混音并停止播放，
     * 卡片 accent 描边随之消失。
     */
    fun applyScene(scene: NoiseScene) {
        val cur = _ui.value
        if (cur.mixSceneId == scene.id && cur.mixLayers.isNotEmpty()) {
            clearMix()
            return
        }
        _ui.update {
            it.copy(
                mixName = scene.name,
                mixSceneId = scene.id,
                mixComboId = null,
                mixLayers = scene.layers.map { l -> l.sourceId to l.volume },
            )
        }
        afterLayersChanged()
    }

    /**
     * 点我的组合：应用并绑定（后续改动自动回写该组合）；
     * **再次点击当前已绑定的组合 = 取消选中**（清空混音并停止）。
     */
    fun applyCombo(combo: NoiseUserComboEntity) {
        val cur = _ui.value
        if (cur.mixComboId == combo.id && cur.mixLayers.isNotEmpty()) {
            clearMix()
            return
        }
        _ui.update {
            it.copy(
                mixName = combo.name,
                mixSceneId = null,
                mixComboId = combo.id,
                mixLayers = parseLayers(combo.layersJson),
            )
        }
        afterLayersChanged()
    }

    /** 清空混音并停止白噪音轨（场景/组合卡「取消选中」用） */
    private fun clearMix() {
        _ui.update {
            it.copy(
                mixName = "自由混音",
                mixSceneId = null,
                mixComboId = null,
                mixLayers = emptyList(),
            )
        }
        engine.stopAll()
        persistSnapshot()
    }

    // ── 图层操作 ──

    /**
     * 点音源库瓦片 / 我的白噪音行：**选中 ⇄ 取消选中**（2026-10-21 用户订正）。
     *
     * - 未叠进 → 加层，默认音量 .6
     * - 已叠进 → 直接移除该层（与瓦片右下角 accent 点、行内高亮一一对应）
     *
     * 音量调高低由混音卡的竖条拖动完成；点击不再承担「+15%」语义，避免用户
     * 重复点击却找不到取消入口（原实现到 100% 后点击无任何反应）。
     */
    fun tapSource(sourceId: String) {
        val cur = _ui.value
        if (!cur.sources.containsKey(sourceId)) return
        if (cur.mixLayers.any { it.first == sourceId }) {
            removeLayer(sourceId)   // removeLayer 内部已走 afterLayersChanged
            return
        }
        _ui.update { it.copy(mixLayers = it.mixLayers + (sourceId to .6f)) }
        afterLayersChanged()
    }

    /** 图层音量（竖条混音器拖动增量，避免闭包旧值问题，批十四铁律） */
    fun nudgeLayerVolume(sourceId: String, delta: Float) {
        val cur = _ui.value
        val layers = cur.mixLayers.map {
            if (it.first == sourceId) it.first to (it.second + delta).coerceIn(0f, 1f) else it
        }
        _ui.update { it.copy(mixLayers = layers) }
        // 拖动是高频：只同步引擎音量，不做持久化/副本判定（松手由 commitLayerVolumes 收尾）
        if (cur.playing) engine.setLayerVolume(sourceId, currentVolumeOf(sourceId))
    }

    /** 竖条拖动收尾（松手）：副本判定 + 持久化 + 组合回写 */
    fun commitLayerVolumes() = afterLayersChanged()

    /** 移除图层（引擎同步统一走 afterLayersChanged → syncEngineScene，幂等） */
    fun removeLayer(sourceId: String) {
        _ui.update { st -> st.copy(mixLayers = st.mixLayers.filter { it.first != sourceId }) }
        afterLayersChanged()
    }

    /** 重命名当前混音（编辑按钮；改名不解绑预设绑定，但组合名会回写） */
    fun renameMix(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        _ui.update { it.copy(mixName = trimmed) }
        afterLayersChanged(skipUnbind = true)
    }

    // ── 播放控制 ──

    /** 混音卡播放/暂停键 */
    fun togglePlay() {
        val st = _ui.value
        if (st.playing) {
            engine.pauseAll()
            return
        }
        if (st.activeSourceIds.isNotEmpty()) {
            // 有暂停中的图层：先同步当前混音结构再恢复
            syncEngineScene()
            engine.resumeAll()
            return
        }
        if (st.mixLayers.isEmpty()) return
        syncEngineScene()
    }

    /** 总音量（17 §2.4：平方曲线在播放器内部完成） */
    fun setTotalVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        engine.setTotalVolume(clamped)
        _ui.update { it.copy(totalVolume = clamped) }
        viewModelScope.launch { settings.setNoiseTotalVolume(clamped) }
    }

    /** 歌曲轨音量（双轨独立，17 §1 硬约束③；接 PlayerController，不经过白噪音引擎） */
    fun setSongVolume(v: Float) {
        player.setVolume(v.coerceIn(0f, 1f))
    }

    /** 「与歌曲同时播放」开关（17 §4；跨轨互斥联动由 NoiseMusicCoordinator 在后台执行） */
    fun setWithMusic(value: Boolean) {
        _ui.update { it.copy(withMusic = value) }
        viewModelScope.launch { settings.setNoiseWithMusic(value) }
    }

    // ── 睡眠定时（17 §7 右列：只作用白噪音，歌曲照常播） ──

    fun setSleepTimerMinutes(minutes: Int) {
        viewModelScope.launch { sleepTimer.setAfterMinutes(minutes) }
    }

    /** 到早上 7:00（17 §7 固定档位） */
    fun setSleepTimerUntilMorning() {
        viewModelScope.launch { sleepTimer.setUntilHour(7) }
    }

    /** 选「不开启」= 一键取消 */
    fun cancelSleepTimer() {
        viewModelScope.launch { sleepTimer.cancel() }
    }

    /** 剩余毫秒（混音卡副标题轮询用） */
    fun sleepTimerRemainingMs(): Long? = sleepTimer.remainingMs()

    fun selectCategory(cat: NoiseCategory?) {
        _ui.update { it.copy(category = if (it.category == cat) null else cat) }
    }

    // ── 我的组合 ──

    /** 把当前混音存为新的「我的组合」 */
    fun saveCurrentAsCombo() {
        val st = _ui.value
        if (st.mixLayers.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = "combo_$now"
            comboDao.upsert(
                NoiseUserComboEntity(
                    id = id,
                    name = st.mixName,
                    layersJson = serializeLayers(st.mixLayers),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            _ui.update { it.copy(mixComboId = id, mixSceneId = null) }
            persistSnapshot()
        }
    }

    fun deleteCombo(id: String) {
        viewModelScope.launch {
            comboDao.delete(id)
            if (_ui.value.mixComboId == id) {
                _ui.update { it.copy(mixComboId = null) }
                persistSnapshot()
            }
        }
    }

    // ── 内部 ──

    /** 当前混音音量（供拖动增量换算绝对值） */
    private fun currentVolumeOf(sourceId: String): Float =
        _ui.value.mixLayers.firstOrNull { it.first == sourceId }?.second ?: 0f

    /** 把当前混音同步进引擎（[autoPlay] 沿用当前播放态：暂停中改图层不应自己响起来） */
    private fun syncEngineScene(autoPlay: Boolean = true) {
        val st = _ui.value
        val scene = NoiseScene(
            id = st.mixSceneId ?: st.mixComboId ?: "custom",
            name = st.mixName,
            layers = st.mixLayers.map { (id, v) -> NoiseLayer(id, v) },
            isBuiltin = st.mixSceneId != null,
        )
        engine.playScene(scene, st.sources, autoPlay)
    }

    /**
     * 图层结构变更后的统一收尾：
     * 1. 预设场景被改动 → 自动改名「XX 副本」并解绑（17 §2.1）
     * 2. 我的组合被改动 → 回写该组合
     * 3. 引擎已加载图层（播放中或暂停中）→ 平滑同步
     * 4. 持久化快照
     */
    private fun afterLayersChanged(skipUnbind: Boolean = false) {
        var st = _ui.value
        if (!skipUnbind) {
            st.mixSceneId?.let { sceneId ->
                val preset = NoiseCatalog.presetScenes.firstOrNull { it.id == sceneId }
                val presetLayers = preset?.layers?.map { it.sourceId to it.volume }
                if (presetLayers != st.mixLayers) {
                    _ui.update {
                        it.copy(
                            mixName = "${preset?.name ?: it.mixName} 副本",
                            mixSceneId = null,
                        )
                    }
                }
            }
        }
        st = _ui.value
        // 组合回写
        st.mixComboId?.let { comboId ->
            viewModelScope.launch {
                comboDao.getById(comboId)?.let { entity ->
                    comboDao.upsert(
                        entity.copy(
                            name = st.mixName,
                            layersJson = serializeLayers(st.mixLayers),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
        // 播放中才同步引擎；暂停中只改结构（否则 playScene 会把暂停的混音重新响起来）
        if (st.activeSourceIds.isNotEmpty()) syncEngineScene(autoPlay = st.playing)
        persistSnapshot()
    }

    private fun persistSnapshot() {
        val st = _ui.value
        viewModelScope.launch {
            val json = JSONObject()
                .put("n", st.mixName)
                .put("sid", st.mixSceneId ?: JSONObject.NULL)
                .put("cid", st.mixComboId ?: JSONObject.NULL)
                .put("L", JSONArray().also { arr ->
                    st.mixLayers.forEach { (id, v) ->
                        arr.put(JSONObject().put("s", id).put("v", v.toDouble()))
                    }
                })
            settings.setNoiseCurrentSceneJson(json.toString())
        }
    }

    /**
     * 恢复混音结构。
     * @param defaultSceneId 设置里选的默认场景（null = 沿用上次混音）；
     *   选了默认场景就**每次进页都预置**该场景，不自动续播
     */
    private suspend fun restoreSnapshot(defaultSceneId: String?) {
        if (defaultSceneId != null) {
            applyDefaultScene(defaultSceneId)
            return
        }
        val raw = settings.noiseCurrentSceneJson.first() ?: return
        runCatching {
            val json = JSONObject(raw)
            val arr = json.getJSONArray("L")
            val layers = ArrayList<Pair<String, Float>>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                layers += o.getString("s") to o.getDouble("v").toFloat().coerceIn(0f, 1f)
            }
            _ui.update {
                it.copy(
                    mixName = json.optString("n").ifEmpty { "自由混音" },
                    mixSceneId = if (json.isNull("sid")) null else json.getString("sid"),
                    mixComboId = if (json.isNull("cid")) null else json.getString("cid"),
                    mixLayers = layers,
                )
            }
        }
    }

    /** 按设置里的默认场景预置混音结构（不落盘，切回「沿用上次混音」仍能恢复旧快照） */
    private fun applyDefaultScene(sceneId: String) {
        val scene = NoiseCatalog.presetScenes.firstOrNull { it.id == sceneId } ?: return
        _ui.update {
            it.copy(
                mixName = scene.name,
                mixSceneId = scene.id,
                mixComboId = null,
                mixLayers = scene.layers.map { l -> l.sourceId to l.volume },
            )
        }
    }

    companion object {
        fun serializeLayers(layers: List<Pair<String, Float>>): String =
            JSONArray().also { arr ->
                layers.forEach { (id, v) -> arr.put(JSONObject().put("s", id).put("v", v.toDouble())) }
            }.toString()

        fun parseLayers(json: String): List<Pair<String, Float>> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("s") to o.getDouble("v").toFloat().coerceIn(0f, 1f)
            }
        }.getOrDefault(emptyList())

        /** 图层副标题（17 §2.3：「无缝循环」或「间隔 X–Ys」必须显性化） */
        fun kindLabel(source: NoiseSource): String =
            if (source.maxSilenceSeconds > 0) "间隔 ${source.minSilenceSeconds}–${source.maxSilenceSeconds}s"
            else "无缝循环"

        fun percentOf(volume: Float): Int = (volume * 100).roundToInt()
    }
}
