package com.lxpro.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lxpro.core.common.VerseGroup
import com.lxpro.core.common.builtinVerseGroups
import com.lxpro.core.model.SearchMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lxProDataStore: DataStore<Preferences> by preferencesDataStore(name = "lxpro_settings")

/** 文案组数上限（内置 6 组 + 自建组合计；设置页 UI 也引用，故为顶层公共常量） */
const val VERSE_GROUP_LIMIT = 20

/** 单个文案组的文案条数上限（同上，顶层公共） */
const val VERSES_PER_GROUP_LIMIT = 100

/**
 * 一条偏好的原始快照（全量备份用）。
 *
 * 为什么存「原始键 + 类型」而不是逐项列设置：设置项会持续新增（2026-10-27 用户要求
 * 把「设置偏好」也纳入备份），整表快照能自动覆盖以后新增的键，不用每次改备份代码。
 *
 * @property type boolean / int / long / float / double / string / stringSet
 * @property value 统一编码为字符串；stringSet 用 JSON 数组编码
 */
data class PrefSnapshot(val key: String, val type: String, val value: String)

/**
 * 设置项存储（DataStore Preferences，02 §6.2）。
 * ⚠️ 只存**轻量设置**；歌单/收藏/历史走 Room。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val paletteId = stringPreferencesKey("palette_id")
        val ambientEnabled = booleanPreferencesKey("ambient_enabled")

        // ── 动态背景（对齐 LX-Music「设置 → 主题设置 → 使用动态背景」）──
        /** 动态背景开关：开启后全局背景 = 当前播放封面（模糊 + 主题色遮罩） */
        val dynamicBg = booleanPreferencesKey("dynamic_bg")

        /** 模糊强度（对应 LX 的 theme.blur，默认 18） */
        val dynamicBgBlur = intPreferencesKey("dynamic_bg_blur")

        /** 遮罩不透明度百分比（对应 LX 的 theme.picOpacity，默认 76） */
        val dynamicBgMaskOpacity = intPreferencesKey("dynamic_bg_mask_opacity")

        /** 跟随系统深浅色：系统切深色时，浅色配色回落到 ① 暗绿（13 §4） */
        val followSystemDark = booleanPreferencesKey("follow_system_dark")

        // ── 播放行为 ──
        /** 记忆上次播放位置：再次播放同一首时从上次位置继续 */
        val rememberPlaybackPosition = booleanPreferencesKey("remember_playback_position")

        /** 上次播放位置表：songKey(source:id) → 毫秒（有界，见 PLAYBACK_POSITION_LIMIT） */
        val playbackPositions = stringPreferencesKey("playback_positions")

        /** 耳机拔出时暂停（仅插线耳机：ACTION_AUDIO_BECOMING_NOISY） */
        val pauseOnHeadphoneUnplug = booleanPreferencesKey("pause_on_headphone_unplug")

        /** 与其他应用同时播放（默认关=独占音频焦点；开=不请求焦点，混音同播） */
        val playWithOtherApps = booleanPreferencesKey("play_with_other_apps")

        // ── 列表 / 本地扫描 ──
        /** 歌曲行显示封面（本地音乐页默认视图，页内仍可临时切换） */
        val listShowCover = booleanPreferencesKey("list_show_cover")

        /** 忽略短于 30 秒的文件（关闭时只过滤 10 秒以内的提示音） */
        val ignoreShortAudio = booleanPreferencesKey("ignore_short_audio")

        /** 开机自动扫描本地曲库 */
        val autoScanOnBoot = booleanPreferencesKey("auto_scan_on_boot")

        /** OOBE 引导是否已看过（M3 任务 16）；只需「看过一次」这个布尔，不做分步进度 */
        val onboarded = booleanPreferencesKey("onboarded")

        val preferredQuality = stringPreferencesKey("preferred_quality")
        val searchMode = stringPreferencesKey("search_mode")
        val searchHistory = stringPreferencesKey("search_history")
        val searchCounts = stringPreferencesKey("search_counts")
        val enabledSources = stringPreferencesKey("enabled_sources")

        /** 播放页歌词设置（模式 / 立体档 / 对齐 / 翻译 / 立体参数），结构由 feature:player 定义 */
        val lyricSettings = stringPreferencesKey("lyric_settings")

        // ── 下载设置（M3 任务 8）──
        val downloadConcurrency = intPreferencesKey("download_concurrency")
        val downloadAutoImport = booleanPreferencesKey("download_auto_import")
        val downloadRetryLimit = intPreferencesKey("download_retry_limit")

        /** 仅 Wi-Fi 下载：移动网络下不开始新任务 */
        val downloadWifiOnly = booleanPreferencesKey("download_wifi_only")

        // ── 省流量模式（2026-10-26 用户要求：非 Wi-Fi 自动降音质 + 全无图）──

        /** 省流量模式总开关：开启后仅在计费网络（蜂窝/热点）下生效 */
        val dataSaverEnabled = booleanPreferencesKey("data_saver_enabled")

        /** 省流生效时播放音质自动降为 128k */
        val dataSaverAutoQuality = booleanPreferencesKey("data_saver_auto_quality")

        /** 省流生效时全无图（不加载任何网络图片；本地缩略图不受影响） */
        val dataSaverNoImage = booleanPreferencesKey("data_saver_no_image")

        /** 省流量模式在 Wi-Fi 下也启用（默认关：Wi-Fi 下一切照旧） */
        val dataSaverApplyOnWifi = booleanPreferencesKey("data_saver_apply_on_wifi")

        /** 下载悬浮球的位置：存**比例**（0–1）而非绝对像素，换分辨率也不会跑出屏幕 */
        val downloadBallX = floatPreferencesKey("download_ball_x")
        val downloadBallY = floatPreferencesKey("download_ball_y")

        // ── 统计口径（统计页「配置」子页，参照旧 LX-Pro StatsConfig）──
        val statsMinPlaySeconds = intPreferencesKey("stats_min_play_seconds")
        val statsMinPlayRatio = intPreferencesKey("stats_min_play_ratio")
        val statsPriority = stringPreferencesKey("stats_priority")

        // ── M4 声音层（17 全篇，批一·地基）──
        // 白噪音（17 §2.4 / §2.5）
        val noiseTotalVolume = floatPreferencesKey("noise_total_volume")
        val noiseFadeInMs = longPreferencesKey("noise_fade_in_ms")
        val noiseFadeOutMs = longPreferencesKey("noise_fade_out_ms")

        /** 当前混音快照（批二）：JSON {name, sceneId?, layers:[{sourceId,volume}]}，重启恢复用 */
        val noiseCurrentSceneJson = stringPreferencesKey("noise_current_scene_json")

        /** 「与歌曲同时播放」开关（17 §4 当前混音卡），默认开 */
        val noiseWithMusic = booleanPreferencesKey("noise_with_music")

        /** 白噪音默认场景（进入白噪音页时的预置混音）；null = 沿用上次混音 */
        val noiseDefaultSceneId = stringPreferencesKey("noise_default_scene_id")

        /**
         * 启用的文案组 id 集合（空集 = 全开）。
         * 键名是 v1 的历史遗留（原为「分类启停」）：内置组 id = 分类名，天然兼容，直接复用。
         */
        val enabledVerseCategories = stringPreferencesKey("enabled_verse_categories")

        /** 文案时段分组的启停（DaySlot.name 集合；空集 = 全开） */
        val enabledVerseSlots = stringPreferencesKey("enabled_verse_slots")

        /** 旧版自建文案库（v1）：已被文案组取代，仅 ensureVerseGroupsInitialized 迁移时读一次 */
        val customVerses = stringPreferencesKey("custom_verses")

        /** 文案组列表（JSON List<VerseGroup>：内置 6 组 + 自建组） */
        val verseGroups = stringPreferencesKey("verse_groups")

        /** 文案轮换间隔（秒，默认 9；首页/歌单/统计三处共用） */
        val verseIntervalSec = intPreferencesKey("verse_interval_sec")

        // EQ（17 §5.1 / §5.2）
        val eqBypass = booleanPreferencesKey("eq_bypass")
        val eqCurrentPresetName = stringPreferencesKey("eq_current_preset_name")
        /** 精度档（17 §5.1 + 2026-10-13 用户裁决）：raw / raw100 / raw400 */
        val eqPrecisionLayer = stringPreferencesKey("eq_precision_layer")

        // 切歌淡变（17 §6.1 / §6.2）
        val crossfadeMs = longPreferencesKey("crossfade_ms")
        val pauseResumeFadeEnabled = booleanPreferencesKey("pause_resume_fade_enabled")

        // 倍速播放（2026-10-27 新增：0.5x ~ 2.0x 六档，默认 1.0x）
        val playbackSpeed = floatPreferencesKey("playback_speed")

        // ── 系统识别歌词 / 桌面歌词（2026-10-25 系统识别补齐批）──
        /** 桌面歌词悬浮窗开关（默认关；需悬浮窗权限，位置见 desktopLyricOffsetY） */
        val desktopLyricEnabled = booleanPreferencesKey("desktop_lyric_enabled")

        /** 状态栏歌词开关（默认开）：把当前歌词行按 Lyric Getter 协议提供给 Xposed 歌词模块 */
        val statusBarLyricEnabled = booleanPreferencesKey("status_bar_lyric_enabled")

        /** 桌面歌词悬浮窗的纵向偏移（px，距屏幕顶）；null = 从未拖动过，用默认值 */
        val desktopLyricOffsetY = intPreferencesKey("desktop_lyric_offset_y")

        /** 桌面歌词锁定（默认关；开了防误触拖动，对齐 LX Music 的 desktopLyric.isLock） */
        val desktopLyricLock = booleanPreferencesKey("desktop_lyric_lock")

        /** 桌面歌词字号（sp，默认 18，对齐 LX Music 的 desktopLyric.fontSize） */
        val desktopLyricFontSize = intPreferencesKey("desktop_lyric_font_size")

        /** 桌面歌词对齐（0=左，1=中，2=右；默认 1=中，对齐 LX Music 的 textAlignment） */
        val desktopLyricAlignment = intPreferencesKey("desktop_lyric_alignment")

        /** 桌面歌词透明度（0-100，默认 100=全不透明，对齐 LX Music 的 opacity） */
        val desktopLyricOpacity = intPreferencesKey("desktop_lyric_opacity")

        /** 桌面歌词已播放行颜色（ARGB int，默认 0xFFFFFFFF 白字） */
        val desktopLyricPlayedColor = intPreferencesKey("desktop_lyric_played_color")

        /** 桌面歌词未播放行颜色（ARGB int，默认 0x99FFFFFF 半透明白） */
        val desktopLyricPendingColor = intPreferencesKey("desktop_lyric_pending_color")

        /** 繁体歌词开关（默认开，对齐 LX Music 的 player.isS2t）：简体自动转繁体显示 */
        val traditionalLyricEnabled = booleanPreferencesKey("traditional_lyric_enabled")

        /** 分享邀请语模板（用户自定义），占位符：{title} 歌名 {artist} 艺术家 {link} 软链接 */
        val shareTemplate = stringPreferencesKey("share_template")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前配色 id；null 表示从未选择，由上层回落 [LXPalette.default] 对应的 id */
    val paletteId: Flow<String?> = context.lxProDataStore.data.map { it[Keys.paletteId] }

    /** 氛围动效开关；关闭后氛围层完全不绘制（省电） */
    val ambientEnabled: Flow<Boolean> = context.lxProDataStore.data.map { it[Keys.ambientEnabled] ?: true }

    /** 动态背景开关（对齐 LX-Music，默认开） */
    val dynamicBg: Flow<Boolean> = context.lxProDataStore.data.map { it[Keys.dynamicBg] ?: true }

    /** 动态背景模糊强度（对齐 LX theme.blur，默认 18） */
    val dynamicBgBlur: Flow<Int> = context.lxProDataStore.data.map { it[Keys.dynamicBgBlur] ?: 18 }

    /** 动态背景遮罩不透明度 %（对齐 LX theme.picOpacity，默认 76） */
    val dynamicBgMaskOpacity: Flow<Int> = context.lxProDataStore.data.map { it[Keys.dynamicBgMaskOpacity] ?: 76 }

    suspend fun setDynamicBg(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.dynamicBg] = enabled }
    }

    suspend fun setDynamicBgBlur(value: Int) {
        context.lxProDataStore.edit { it[Keys.dynamicBgBlur] = value }
    }

    suspend fun setDynamicBgMaskOpacity(value: Int) {
        context.lxProDataStore.edit { it[Keys.dynamicBgMaskOpacity] = value }
    }

    /** 跟随系统深浅色（默认关；系统切深色时浅色配色回落到 ① 暗绿） */
    val followSystemDark: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.followSystemDark] ?: false }

    suspend fun setFollowSystemDark(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.followSystemDark] = enabled }
    }

    // ── 播放行为 ──

    /** 记忆上次播放位置（默认开） */
    val rememberPlaybackPosition: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.rememberPlaybackPosition] ?: true }

    /** songKey → 上次位置（毫秒）；只保留最近 [PLAYBACK_POSITION_LIMIT] 首 */
    val playbackPositions: Flow<Map<String, Long>> = context.lxProDataStore.data
        .map { decodeLongMap(it[Keys.playbackPositions]) }

    /** 耳机拔出时暂停（默认开；只对插线耳机生效） */
    val pauseOnHeadphoneUnplug: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.pauseOnHeadphoneUnplug] ?: true }

    /** 与其他应用同时播放（默认关=独占焦点，被抢即暂停） */
    val playWithOtherApps: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.playWithOtherApps] ?: false }

    suspend fun setPlayWithOtherApps(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.playWithOtherApps] = enabled }
    }

    suspend fun setRememberPlaybackPosition(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.rememberPlaybackPosition] = enabled }
    }

    suspend fun setPauseOnHeadphoneUnplug(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.pauseOnHeadphoneUnplug] = enabled }
    }

    /** 记下某首歌的播放位置；写入后按最近使用顺序裁剪到上限 */
    suspend fun savePlaybackPosition(songKey: String, positionMs: Long) {
        if (songKey.isEmpty() || positionMs < 0L) return
        context.lxProDataStore.edit { prefs ->
            val map = LinkedHashMap(decodeLongMap(prefs[Keys.playbackPositions]))
            map.remove(songKey)
            map[songKey] = positionMs
            val trimmed = map.entries.toList().takeLast(PLAYBACK_POSITION_LIMIT)
                .associate { it.key to it.value }
            prefs[Keys.playbackPositions] =
                json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), trimmed)
        }
    }

    /** 清掉某首歌的播放位置（播完 / 快进到结尾时用） */
    suspend fun clearPlaybackPosition(songKey: String) {
        if (songKey.isEmpty()) return
        context.lxProDataStore.edit { prefs ->
            val map = LinkedHashMap(decodeLongMap(prefs[Keys.playbackPositions]))
            if (map.remove(songKey) == null) return@edit
            prefs[Keys.playbackPositions] =
                json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), map)
        }
    }

    // ── 列表 / 本地扫描 ──

    /** 歌曲行显示封面（默认开） */
    val listShowCover: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.listShowCover] ?: true }

    /** 忽略短于 30 秒的文件（默认开；关闭时只过滤 10 秒以内） */
    val ignoreShortAudio: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.ignoreShortAudio] ?: true }

    /** 开机自动扫描（默认关） */
    val autoScanOnBoot: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.autoScanOnBoot] ?: false }

    suspend fun setListShowCover(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.listShowCover] = enabled }
    }

    suspend fun setIgnoreShortAudio(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.ignoreShortAudio] = enabled }
    }

    suspend fun setAutoScanOnBoot(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.autoScanOnBoot] = enabled }
    }

    /**
     * OOBE 引导看过没有；键不存在 = 没看过（返回 false，不是 null）。
     * AppViewModel 的 StateFlow 初始值才是 null（首帧别据此跳转，否则会给老用户闪一下引导）。
     */
    val onboarded: Flow<Boolean?> = context.lxProDataStore.data.map { it[Keys.onboarded] ?: false }

    suspend fun setOnboarded() {
        context.lxProDataStore.edit { it[Keys.onboarded] = true }
    }

    val preferredQuality: Flow<String?> = context.lxProDataStore.data.map { it[Keys.preferredQuality] }

    /**
     * 双模式状态（15 §4.1：全局单例、跨页面保持；首页与搜索页共用）。
     * 落盘是为了重启后仍是用户上次选的模式。
     */
    val searchMode: Flow<SearchMode> = context.lxProDataStore.data
        .map { SearchMode.fromId(it[Keys.searchMode]) }

    suspend fun setSearchMode(mode: SearchMode) {
        context.lxProDataStore.edit { it[Keys.searchMode] = mode.name }
    }

    suspend fun setPaletteId(id: String) {
        context.lxProDataStore.edit { it[Keys.paletteId] = id }
    }

    suspend fun setAmbientEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.ambientEnabled] = enabled }
    }

    suspend fun setPreferredQuality(quality: String) {
        context.lxProDataStore.edit { it[Keys.preferredQuality] = quality }
    }

    // ── 搜索历史 / 常搜 / 音源选择（首页内联搜索面板用）──

    /** 搜索历史：**最近在前**，上限 [HISTORY_LIMIT] 条 */
    val searchHistory: Flow<List<String>> = context.lxProDataStore.data
        .map { decodeList(it[Keys.searchHistory]) }

    /**
     * 常搜：本地统计的高频搜索词，按次数降序取前 [HOT_LIMIT]。
     * ⚠️ 本产品没有服务端，所以「热搜」只能是本地高频词，不假装有云端榜单。
     */
    val hotQueries: Flow<List<String>> = context.lxProDataStore.data
        .map { decodeCounts(it[Keys.searchCounts]) }

    /** 常搜频次原始表（全量备份导出用，不是展示用的截断列表） */
    val searchCountsMap: Flow<Map<String, Int>> = context.lxProDataStore.data
        .map { decodeCountMap(it[Keys.searchCounts]) }

    /** 用户勾选的音源 id；**空集 = 全开**（与设置页未落地前的默认一致） */
    val enabledSourceIds: Flow<Set<String>> = context.lxProDataStore.data
        .map { decodeList(it[Keys.enabledSources]).toSet() }

    /** 记一次搜索：写历史 + 累加频次，供「常搜」排序 */
    suspend fun recordSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        context.lxProDataStore.edit { prefs ->
            val history = decodeList(prefs[Keys.searchHistory])
                .filterNot { it.equals(query, ignoreCase = true) }
                .let { listOf(query) + it }
                .take(HISTORY_LIMIT)
            prefs[Keys.searchHistory] = json.encodeToString(ListSerializer(String.serializer()), history)

            val counts = decodeCountMap(prefs[Keys.searchCounts])
            val next = counts + (query to ((counts[query] ?: 0) + 1))
            val trimmed = next.entries.sortedByDescending { it.value }.take(COUNT_LIMIT).associate { it.key to it.value }
            prefs[Keys.searchCounts] =
                json.encodeToString(MapSerializer(String.serializer(), Int.serializer()), trimmed)
        }
    }

    suspend fun clearSearchHistory() {
        context.lxProDataStore.edit { prefs ->
            prefs.remove(Keys.searchHistory)
            prefs.remove(Keys.searchCounts)
        }
    }

    /** 全量备份导入：整体恢复搜索历史与常搜频次（覆盖） */
    suspend fun restoreSearchState(rawHistory: List<String>, counts: Map<String, Int>) {
        context.lxProDataStore.edit { prefs ->
            prefs[Keys.searchHistory] = json.encodeToString(
                ListSerializer(String.serializer()),
                rawHistory.map { it.trim() }.filter { it.isNotEmpty() }.take(HISTORY_LIMIT),
            )
            prefs[Keys.searchCounts] = json.encodeToString(
                MapSerializer(String.serializer(), Int.serializer()),
                counts.entries.sortedByDescending { it.value }.take(COUNT_LIMIT).associate { it.key to it.value },
            )
        }
    }

    /** 全量备份：整表快照（含全部设置项与界面偏好，自动覆盖以后新增的键） */
    suspend fun snapshotPreferences(): List<PrefSnapshot> {
        val prefs = context.lxProDataStore.data.first()
        return prefs.asMap().mapNotNull { (key, value) ->
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is Double -> "double"
                is String -> "string"
                is Set<*> -> "stringSet"
                else -> return@mapNotNull null
            }
            val encoded = when (value) {
                is Set<*> -> json.encodeToString(
                    ListSerializer(String.serializer()),
                    value.filterIsInstance<String>().sorted(),
                )
                else -> value.toString()
            }
            PrefSnapshot(key.name, type, encoded)
        }.sortedBy { it.key }
    }

    /**
     * 全量备份：按类型写回偏好。
     *
     * 覆盖同名键，**不删除**备份里没有的键（避免把更新版本新增的设置清掉）；
     * 类型对不上或解析失败的条目直接跳过，不让一条坏数据毁掉整个恢复。
     */
    suspend fun restorePreferences(entries: List<PrefSnapshot>) {
        if (entries.isEmpty()) return
        context.lxProDataStore.edit { prefs ->
            entries.forEach { entry ->
                when (entry.type) {
                    "boolean" -> entry.value.toBooleanStrictOrNull()
                        ?.let { prefs[booleanPreferencesKey(entry.key)] = it }

                    "int" -> entry.value.toIntOrNull()
                        ?.let { prefs[intPreferencesKey(entry.key)] = it }

                    "long" -> entry.value.toLongOrNull()
                        ?.let { prefs[longPreferencesKey(entry.key)] = it }

                    "float" -> entry.value.toFloatOrNull()
                        ?.let { prefs[floatPreferencesKey(entry.key)] = it }

                    "double" -> entry.value.toDoubleOrNull()
                        ?.let { prefs[doublePreferencesKey(entry.key)] = it }

                    "string" -> prefs[stringPreferencesKey(entry.key)] = entry.value

                    "stringSet" -> runCatching {
                        json.decodeFromString(ListSerializer(String.serializer()), entry.value)
                    }.getOrNull()?.let { prefs[stringSetPreferencesKey(entry.key)] = it.toSet() }
                }
            }
        }
    }

    /** 删单条历史（历史 chip 上的 ×；与「清空」并存） */
    suspend fun removeSearchHistory(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        context.lxProDataStore.edit { prefs ->
            val history = decodeList(prefs[Keys.searchHistory]).filterNot { it == query }
            prefs[Keys.searchHistory] = json.encodeToString(ListSerializer(String.serializer()), history)
        }
    }

    /**
     * 播放页歌词设置：存一段 JSON，结构（键名与默认值）由 `feature:player` 自己定义。
     * ⚠️ 这里不做结构化存储，是为了不让 core 模块依赖 feature 的界面模型。
     */
    val lyricSettings: Flow<String?> = context.lxProDataStore.data.map { it[Keys.lyricSettings] }

    suspend fun setLyricSettings(json: String) {
        context.lxProDataStore.edit { it[Keys.lyricSettings] = json }
    }

    // ── 下载设置（M3 任务 8）──

    /** 同时下载数（15 §6.2：默认并发上限 3） */
    val downloadConcurrency: Flow<Int> = context.lxProDataStore.data
        .map { (it[Keys.downloadConcurrency] ?: DEFAULT_CONCURRENCY).coerceIn(1, MAX_CONCURRENCY) }

    /** 完成后自动入本地库（双模式闭环的关键，默认开） */
    val downloadAutoImport: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.downloadAutoImport] ?: true }

    /** 失败自动重试次数（0 = 不重试） */
    val downloadRetryLimit: Flow<Int> = context.lxProDataStore.data
        .map { (it[Keys.downloadRetryLimit] ?: DEFAULT_RETRY).coerceIn(0, MAX_RETRY) }

    /** 仅 Wi-Fi 下载（默认关）：移动网络下不开始新任务 */
    val downloadWifiOnly: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.downloadWifiOnly] ?: false }

    /** 省流量模式总开关（默认关） */
    val dataSaverEnabled: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.dataSaverEnabled] ?: false }

    /** 省流时自动降音质（默认开） */
    val dataSaverAutoQuality: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.dataSaverAutoQuality] ?: true }

    /** 省流时全无图（默认开） */
    val dataSaverNoImage: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.dataSaverNoImage] ?: true }

    /** Wi-Fi 下也启用省流（默认关） */
    val dataSaverApplyOnWifi: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.dataSaverApplyOnWifi] ?: false }

    /** 下载悬浮球的位置（比例 0–1）；null = 从未拖动过，按默认右下角 */
    val downloadBallPosition: Flow<Pair<Float, Float>?> = context.lxProDataStore.data
        .map { prefs ->
            val x = prefs[Keys.downloadBallX]
            val y = prefs[Keys.downloadBallY]
            if (x == null || y == null) null else x to y
        }

    suspend fun setDownloadBallPosition(xFraction: Float, yFraction: Float) {
        context.lxProDataStore.edit {
            it[Keys.downloadBallX] = xFraction.coerceIn(0f, 1f)
            it[Keys.downloadBallY] = yFraction.coerceIn(0f, 1f)
        }
    }

    suspend fun setDownloadConcurrency(value: Int) {
        context.lxProDataStore.edit { it[Keys.downloadConcurrency] = value.coerceIn(1, MAX_CONCURRENCY) }
    }

    suspend fun setDownloadAutoImport(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.downloadAutoImport] = enabled }
    }

    suspend fun setDownloadRetryLimit(value: Int) {
        context.lxProDataStore.edit { it[Keys.downloadRetryLimit] = value.coerceIn(0, MAX_RETRY) }
    }

    suspend fun setDownloadWifiOnly(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.downloadWifiOnly] = enabled }
    }

    suspend fun setDataSaverEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.dataSaverEnabled] = enabled }
    }

    suspend fun setDataSaverAutoQuality(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.dataSaverAutoQuality] = enabled }
    }

    suspend fun setDataSaverNoImage(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.dataSaverNoImage] = enabled }
    }

    suspend fun setDataSaverApplyOnWifi(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.dataSaverApplyOnWifi] = enabled }
    }

    suspend fun setEnabledSourceIds(ids: Set<String>) {
        context.lxProDataStore.edit { prefs ->
            prefs[Keys.enabledSources] = json.encodeToString(ListSerializer(String.serializer()), ids.toList())
        }
    }

    // ── 统计口径（统计页「配置」子页）──
    //
    // 播放必须「≥ 最低播放比例 且 ≥ 最低计入秒数」才算一次有效收听（写入时判定；
    // 调整只影响之后的新记录，历史流水不重算 —— 与旧 LX-Pro 的 stats 配置一致）。

    /** 最低计入秒数（默认 30，范围 1–600） */
    val statsMinPlaySeconds: Flow<Int> = context.lxProDataStore.data
        .map { (it[Keys.statsMinPlaySeconds] ?: 30).coerceIn(1, 600) }

    /** 最低播放比例百分比（默认 50，范围 1–100） */
    val statsMinPlayRatio: Flow<Int> = context.lxProDataStore.data
        .map { (it[Keys.statsMinPlayRatio] ?: 50).coerceIn(1, 100) }

    suspend fun setStatsMinPlaySeconds(value: Int) {
        context.lxProDataStore.edit { it[Keys.statsMinPlaySeconds] = value.coerceIn(1, 600) }
    }

    suspend fun setStatsMinPlayRatio(value: Int) {
        context.lxProDataStore.edit { it[Keys.statsMinPlayRatio] = value.coerceIn(1, 100) }
    }

    /**
     * 统计方式（对齐旧 LX-Pro `stats.priority`，默认 "timeFirst"）：
     * time=仅秒数 / ratio=仅比例 / timeFirst=都满足·优先检查秒数 / ratioFirst=都满足·优先检查比例
     */
    val statsPriority: Flow<String> = context.lxProDataStore.data
        .map { it[Keys.statsPriority]?.takeIf { v -> v in STATS_PRIORITY_VALUES } ?: "timeFirst" }

    suspend fun setStatsPriority(value: String) {
        if (value !in STATS_PRIORITY_VALUES) return
        context.lxProDataStore.edit { it[Keys.statsPriority] = value }
    }

    // ── M4 声音层（17 全篇，批一·地基）──

    /** 白噪音总音量（17 §2.4：走平方曲线，默认 1.0） */
    val noiseTotalVolume: Flow<Float> = context.lxProDataStore.data
        .map { it[Keys.noiseTotalVolume] ?: 1f }

    /** 白噪音淡入时长（17 §2.5：Noice 默认 5s） */
    val noiseFadeInMs: Flow<Long> = context.lxProDataStore.data
        .map { it[Keys.noiseFadeInMs] ?: 5_000L }

    /** 白噪音淡出时长（17 §2.5：1.5s） */
    val noiseFadeOutMs: Flow<Long> = context.lxProDataStore.data
        .map { it[Keys.noiseFadeOutMs] ?: 1_500L }

    /** EQ 直通开关（17 §5.2：切「正常」= 彻底还原，不走滤波链） */
    val eqBypass: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.eqBypass] ?: true }

    /** 当前 EQ 预设（null = 直通；17 §5.1） */
    val eqCurrentPresetName: Flow<String?> = context.lxProDataStore.data
        .map { it[Keys.eqCurrentPresetName] }

    /**
     * EQ 目录筛选（2026-10-16 用户裁决：三层型号零重叠，「精度切换」实为**目录筛选器**）。
     * 取值：all（默认，142 条全集）/ raw(58) / raw100(21) / raw400(63)。
     */
    val eqPrecisionLayer: Flow<String> = context.lxProDataStore.data
        .map { it[Keys.eqPrecisionLayer] ?: "all" }

    /** 切歌淡变时长（17 §6.1 / §6.2：默认 4s） */
    val crossfadeMs: Flow<Long> = context.lxProDataStore.data
        .map { (it[Keys.crossfadeMs] ?: 4_000L).coerceIn(0L, 8_000L) }

    /** 倍速播放（2026-10-27：默认 1.0x） */
    val playbackSpeed: Flow<Float> = context.lxProDataStore.data
        .map { (it[Keys.playbackSpeed] ?: 1.0f).coerceIn(0.5f, 2.0f) }

    /** 暂停/继续是否走淡变（17 §6.1，默认开） */
    val pauseResumeFadeEnabled: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.pauseResumeFadeEnabled] ?: true }

    suspend fun setNoiseTotalVolume(value: Float) {
        context.lxProDataStore.edit { it[Keys.noiseTotalVolume] = value.coerceIn(0f, 1f) }
    }

    suspend fun setNoiseFadeInMs(value: Long) {
        context.lxProDataStore.edit { it[Keys.noiseFadeInMs] = value.coerceAtLeast(0L) }
    }

    suspend fun setNoiseFadeOutMs(value: Long) {
        context.lxProDataStore.edit { it[Keys.noiseFadeOutMs] = value.coerceAtLeast(0L) }
    }

    /** 当前混音快照（批二）：null = 从未播放过混音 */
    val noiseCurrentSceneJson: Flow<String?> = context.lxProDataStore.data
        .map { it[Keys.noiseCurrentSceneJson] }

    /** 「与歌曲同时播放」开关（17 §4），默认开 */
    val noiseWithMusic: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.noiseWithMusic] ?: true }

    suspend fun setNoiseCurrentSceneJson(value: String?) {
        context.lxProDataStore.edit {
            if (value == null) it.remove(Keys.noiseCurrentSceneJson)
            else it[Keys.noiseCurrentSceneJson] = value
        }
    }

    suspend fun setNoiseWithMusic(value: Boolean) {
        context.lxProDataStore.edit { it[Keys.noiseWithMusic] = value }
    }

    /** 白噪音默认场景 id；null = 沿用上次混音 */
    val noiseDefaultSceneId: Flow<String?> = context.lxProDataStore.data
        .map { it[Keys.noiseDefaultSceneId] }

    suspend fun setNoiseDefaultSceneId(value: String?) {
        context.lxProDataStore.edit {
            if (value == null) it.remove(Keys.noiseDefaultSceneId)
            else it[Keys.noiseDefaultSceneId] = value
        }
    }

    /** 启用的文案组 id；**空集 = 全开**（与 VersePicker 语义一致） */
    val enabledVerseGroupIds: Flow<Set<String>> = context.lxProDataStore.data
        .map { decodeList(it[Keys.enabledVerseCategories]).toSet() }

    suspend fun setEnabledVerseGroupIds(ids: Set<String>) {
        context.lxProDataStore.edit { prefs ->
            prefs[Keys.enabledVerseCategories] =
                json.encodeToString(ListSerializer(String.serializer()), ids.toList())
        }
    }

    /** 文案时段分组启停；**空集 = 全开**（与组启停同一语义） */
    val enabledVerseSlots: Flow<Set<String>> = context.lxProDataStore.data
        .map { decodeList(it[Keys.enabledVerseSlots]).toSet() }

    suspend fun setEnabledVerseSlots(slots: Set<String>) {
        context.lxProDataStore.edit { prefs ->
            prefs[Keys.enabledVerseSlots] =
                json.encodeToString(ListSerializer(String.serializer()), slots.toList())
        }
    }

    // ── 文案组（设置 → 文案自定义，2026-10-25 全量自定义版）──

    /** 全部文案组；空列表 = 还没初始化（首启由 [ensureVerseGroupsInitialized] 填充） */
    val verseGroups: Flow<List<VerseGroup>> = context.lxProDataStore.data
        .map { decodeVerseGroups(it[Keys.verseGroups]) }

    /** 文案轮换间隔（秒，默认 9） */
    val verseIntervalSec: Flow<Int> = context.lxProDataStore.data
        .map { (it[Keys.verseIntervalSec] ?: 9).coerceIn(3, 60) }

    suspend fun setVerseIntervalSec(value: Int) {
        context.lxProDataStore.edit { it[Keys.verseIntervalSec] = value.coerceIn(3, 60) }
    }

    /**
     * 首启初始化（幂等）：键不存在时写入内置 6 组；
     * 旧版 `custom_verses` 非空则追加一个「自建」组（id="custom"）。迁移后旧键不再使用。
     */
    suspend fun ensureVerseGroupsInitialized() {
        context.lxProDataStore.edit { prefs ->
            if (prefs[Keys.verseGroups] == null) {
                val migrated = decodeList(prefs[Keys.customVerses])
                val groups = builtinVerseGroups() +
                    if (migrated.isNotEmpty()) {
                        listOf(VerseGroup(id = "custom", name = "自建", verses = migrated))
                    } else {
                        emptyList()
                    }
                prefs[Keys.verseGroups] = encodeVerseGroups(groups)
            }
        }
    }

    /** 新建文案组：trim / 空名拒 / 重名拒 / 组数 ≤ [VERSE_GROUP_LIMIT]；返回是否成功 */
    suspend fun addVerseGroup(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) return false
        var added = false
        context.lxProDataStore.edit { prefs ->
            val groups = decodeVerseGroups(prefs[Keys.verseGroups])
            if (groups.size < VERSE_GROUP_LIMIT && groups.none { it.name == name }) {
                prefs[Keys.verseGroups] = encodeVerseGroups(
                    groups + VerseGroup(id = "u${System.currentTimeMillis()}", name = name),
                )
                added = true
            }
        }
        return added
    }

    /** 重命名组：trim / 空名拒 / 与其他组重名拒；内置组同样允许（高度自定义） */
    suspend fun renameVerseGroup(id: String, rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty()) return false
        var renamed = false
        context.lxProDataStore.edit { prefs ->
            val groups = decodeVerseGroups(prefs[Keys.verseGroups])
            val index = groups.indexOfFirst { it.id == id }
            if (index >= 0 && groups.none { it.id != id && it.name == name }) {
                prefs[Keys.verseGroups] = encodeVerseGroups(
                    groups.toMutableList().apply { set(index, get(index).copy(name = name)) },
                )
                renamed = true
            }
        }
        return renamed
    }

    suspend fun removeVerseGroup(id: String) {
        context.lxProDataStore.edit { prefs ->
            val groups = decodeVerseGroups(prefs[Keys.verseGroups]).filterNot { it.id == id }
            prefs[Keys.verseGroups] = encodeVerseGroups(groups)
        }
    }

    /**
     * 批量添加文案到组：每行一条，trim + **去空行** + 组内去重，单组 ≤ [VERSES_PER_GROUP_LIMIT]。
     * @return 实际添加的条数（0 = 全是空行/重复/已满，如实告知，不做假成功）
     */
    suspend fun addVersesToGroup(id: String, lines: List<String>): Int {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return 0
        var added = 0
        context.lxProDataStore.edit { prefs ->
            val groups = decodeVerseGroups(prefs[Keys.verseGroups])
            val index = groups.indexOfFirst { it.id == id }
            if (index >= 0) {
                val group = groups[index]
                val merged = (group.verses + clean.filter { it !in group.verses })
                    .take(VERSES_PER_GROUP_LIMIT)
                added = merged.size - group.verses.size
                if (added > 0) {
                    prefs[Keys.verseGroups] = encodeVerseGroups(
                        groups.toMutableList().apply { set(index, group.copy(verses = merged)) },
                    )
                }
            }
        }
        return added
    }

    /** 修改组内一条文案：trim / 空拒 / 未变化拒 / 与组内其他条重复拒 */
    suspend fun updateVerseInGroup(id: String, oldText: String, rawNew: String): Boolean {
        val newText = rawNew.trim()
        if (newText.isEmpty() || newText == oldText) return false
        var updated = false
        context.lxProDataStore.edit { prefs ->
            val groups = decodeVerseGroups(prefs[Keys.verseGroups])
            val index = groups.indexOfFirst { it.id == id }
            if (index >= 0) {
                val group = groups[index]
                if (group.verses.contains(oldText) &&
                    group.verses.none { it != oldText && it == newText }
                ) {
                    val verses = group.verses.map { if (it == oldText) newText else it }
                    prefs[Keys.verseGroups] = encodeVerseGroups(
                        groups.toMutableList().apply { set(index, group.copy(verses = verses)) },
                    )
                    updated = true
                }
            }
        }
        return updated
    }

    suspend fun removeVerseFromGroup(id: String, text: String) {
        context.lxProDataStore.edit { prefs ->
            val groups = decodeVerseGroups(prefs[Keys.verseGroups])
            val index = groups.indexOfFirst { it.id == id }
            if (index >= 0) {
                val group = groups[index]
                prefs[Keys.verseGroups] = encodeVerseGroups(
                    groups.toMutableList()
                        .apply { set(index, group.copy(verses = group.verses.filterNot { it == text })) },
                )
            }
        }
    }

    suspend fun setEqBypass(bypass: Boolean) {
        context.lxProDataStore.edit { it[Keys.eqBypass] = bypass }
    }

    suspend fun setEqCurrentPresetName(name: String?) {
        context.lxProDataStore.edit {
            if (name == null) it.remove(Keys.eqCurrentPresetName)
            else it[Keys.eqCurrentPresetName] = name
        }
    }

    suspend fun setEqPrecisionLayer(layer: String) {
        // 「全部」是合法值（否则「全部」chip 一点就被写成高精度）；「custom」= 导入预设层（17 §5.3）
        val allowed = setOf("all", "raw", "raw100", "raw400", "custom")
        context.lxProDataStore.edit {
            it[Keys.eqPrecisionLayer] = if (layer in allowed) layer else "all"
        }
    }

    suspend fun setCrossfadeMs(value: Long) {
        context.lxProDataStore.edit { it[Keys.crossfadeMs] = value.coerceIn(0L, 8_000L) }
    }

    suspend fun setPlaybackSpeed(value: Float) {
        context.lxProDataStore.edit { it[Keys.playbackSpeed] = value.coerceIn(0.5f, 2.0f) }
    }

    // ── 系统识别歌词 / 桌面歌词 ──

    /** 桌面歌词悬浮窗开关（默认关） */
    val desktopLyricEnabled: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricEnabled] ?: false }

    /** 状态栏歌词开关（默认开：Lyric Getter 协议推送给系统侧歌词模块） */
    val statusBarLyricEnabled: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.statusBarLyricEnabled] ?: true }

    /** 桌面歌词悬浮窗纵向偏移（px）；null = 默认位置 */
    val desktopLyricOffsetY: Flow<Int?> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricOffsetY] }

    suspend fun setDesktopLyricEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.desktopLyricEnabled] = enabled }
    }

    suspend fun setStatusBarLyricEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.statusBarLyricEnabled] = enabled }
    }

    suspend fun setDesktopLyricOffsetY(value: Int) {
        context.lxProDataStore.edit { it[Keys.desktopLyricOffsetY] = value.coerceAtLeast(0) }
    }

    // ── 桌面歌词外观（对齐 LX Music desktopLyric.* 全集） ──

    /** 锁定窗口防误触拖动（默认关） */
    val desktopLyricLock: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricLock] ?: false }
    suspend fun setDesktopLyricLock(v: Boolean) {
        context.lxProDataStore.edit { it[Keys.desktopLyricLock] = v }
    }

    /** 桌面歌词字号（sp，默认 18） */
    val desktopLyricFontSize: Flow<Int> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricFontSize] ?: 18 }
    suspend fun setDesktopLyricFontSize(v: Int) {
        context.lxProDataStore.edit { it[Keys.desktopLyricFontSize] = v.coerceIn(10, 36) }
    }

    /** 桌面歌词对齐（0=左 1=中 2=右，默认中） */
    val desktopLyricAlignment: Flow<Int> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricAlignment] ?: 1 }
    suspend fun setDesktopLyricAlignment(v: Int) {
        context.lxProDataStore.edit { it[Keys.desktopLyricAlignment] = v.coerceIn(0, 2) }
    }

    /** 桌面歌词透明度（0-100，默认 100） */
    val desktopLyricOpacity: Flow<Int> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricOpacity] ?: 100 }
    suspend fun setDesktopLyricOpacity(v: Int) {
        context.lxProDataStore.edit { it[Keys.desktopLyricOpacity] = v.coerceIn(10, 100) }
    }

    /** 桌面歌词已播放行颜色（ARGB，默认 0xFFFFFFFF 白） */
    val desktopLyricPlayedColor: Flow<Int> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricPlayedColor] ?: 0xFFFFFFFF.toInt() }
    suspend fun setDesktopLyricPlayedColor(v: Int) {
        context.lxProDataStore.edit { it[Keys.desktopLyricPlayedColor] = v }
    }

    /** 桌面歌词未播放行颜色（ARGB，默认 0x99FFFFFF 半透明白） */
    val desktopLyricPendingColor: Flow<Int> = context.lxProDataStore.data
        .map { it[Keys.desktopLyricPendingColor] ?: 0x99FFFFFF.toInt() }
    suspend fun setDesktopLyricPendingColor(v: Int) {
        context.lxProDataStore.edit { it[Keys.desktopLyricPendingColor] = v }
    }

    /** 繁体歌词开关（默认开，对齐 LX Music）：简体歌词自动转繁体显示 */
    val traditionalLyricEnabled: Flow<Boolean> = context.lxProDataStore.data
        .map { it[Keys.traditionalLyricEnabled] ?: true }

    suspend fun setTraditionalLyricEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.traditionalLyricEnabled] = enabled }
    }

    /** 默认分享邀请语（对齐 LX Music 风格，{title}/{artist}/{link} 占位符） */
    private val DEFAULT_SHARE_TEMPLATE = "我在阿玉音乐听《{title}-{artist}》，复制前往阿玉音乐聆听！{link}"

    val shareTemplate: Flow<String> = context.lxProDataStore.data
        .map { it[Keys.shareTemplate] ?: DEFAULT_SHARE_TEMPLATE }

    suspend fun setShareTemplate(template: String) {
        context.lxProDataStore.edit { it[Keys.shareTemplate] = template.ifBlank { DEFAULT_SHARE_TEMPLATE } }
    }

    /** 用模板填充歌名/艺术家/软链接——分享文案生成器（核心逻辑） */
    fun fillShareTemplate(template: String, title: String, artist: String, link: String): String {
        val safeTitle = title.ifBlank { "未命名歌曲" }
        val safeArtist = artist.ifBlank { "未知艺术家" }
        return template
            .replace("{title}", safeTitle)
            .replace("{artist}", safeArtist)
            .replace("{link}", link)
    }

    suspend fun setPauseResumeFadeEnabled(enabled: Boolean) {
        context.lxProDataStore.edit { it[Keys.pauseResumeFadeEnabled] = enabled }
    }

    private fun decodeList(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun decodeVerseGroups(raw: String?): List<VerseGroup> =
        raw?.let {
            runCatching { json.decodeFromString(ListSerializer(VerseGroup.serializer()), it) }.getOrNull()
        } ?: emptyList()

    private fun encodeVerseGroups(groups: List<VerseGroup>): String =
        json.encodeToString(ListSerializer(VerseGroup.serializer()), groups)

    private fun decodeCountMap(raw: String?): Map<String, Int> =
        raw?.let {
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), it)
            }.getOrNull()
        } ?: emptyMap()

    private fun decodeLongMap(raw: String?): Map<String, Long> =
        raw?.let {
            runCatching {
                json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), it)
            }.getOrNull()
        } ?: emptyMap()

    private fun decodeCounts(raw: String?): List<String> =
        decodeCountMap(raw).entries.sortedByDescending { it.value }.map { it.key }.take(HOT_LIMIT)

    private companion object {
        const val HISTORY_LIMIT = 20
        const val HOT_LIMIT = 12

        /** 频次表可以比历史多留一些，历史上限之外的老词也能参与「常搜」 */
        const val COUNT_LIMIT = 60

        /** 播放位置最多记多少首（有界，不随曲库增长） */
        const val PLAYBACK_POSITION_LIMIT = 50

        const val DEFAULT_CONCURRENCY = 3
        const val MAX_CONCURRENCY = 5

        /** 统计方式白名单（旧 LX-Pro `stats.priority` 的四个合法值） */
        val STATS_PRIORITY_VALUES = setOf("time", "ratio", "timeFirst", "ratioFirst")
        const val DEFAULT_RETRY = 1
        const val MAX_RETRY = 3
    }
}