package com.lxpro.music.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.BatteryOptimizationUtil
import com.lxpro.core.common.DaySlot
import com.lxpro.core.common.VerseGroup
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.media.timer.NoiseSleepTimer
import com.lxpro.core.model.Quality
import com.lxpro.source.api.SourceRegistry
import com.lxpro.source.script.ScriptSourceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 4 元组（Kotlin 标准库只有 Triple，combine 里要用 4 个值） */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** 5 元组（同理，combine 里要用 5 个值） */
private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

/** 外观分组（combine 的分组载体，避免七拐八绕的嵌套元组） */
private data class LookState(
    val palette: LXPalette,
    val ambient: Boolean,
    val dynamicBg: Boolean,
    val blur: Int,
    val mask: Int,
    val followSystemDark: Boolean,
)

/** 「仅 Wi-Fi 下载」+ 省流量开关（嵌套 combine 的类型载体，外层保持 5 个 Flow） */
private data class SaverState(
    val wifiOnly: Boolean,
    val dataSaverEnabled: Boolean,
    val dataSaverAutoQuality: Boolean,
    val dataSaverNoImage: Boolean,
    val dataSaverApplyOnWifi: Boolean,
)

private data class BehaviorState(
    val rememberPosition: Boolean,
    val pauseOnHeadphone: Boolean,
    val playWithOtherApps: Boolean = false,
    val showCover: Boolean,
    val ignoreShortAudio: Boolean,
    val autoScanOnBoot: Boolean,
    val wifiOnly: Boolean,
    val dataSaverEnabled: Boolean = false,
    val dataSaverAutoQuality: Boolean = true,
    val dataSaverNoImage: Boolean = true,
    val dataSaverApplyOnWifi: Boolean = false,
    val desktopLyric: Boolean,
    val statusBarLyric: Boolean,
    val traditionalLyric: Boolean,
    val desktopLyricLock: Boolean,
    val desktopLyricFontSize: Int,
    val desktopLyricAlignment: Int,
    val desktopLyricOpacity: Int,
    val desktopLyricPlayedColor: String,
    val desktopLyricPendingColor: String,
)

/** 桌面歌词外观 6 项（嵌套 combine 时的类型载体，避免 List<Comparable> 推断问题） */
private data class DesktopLyricLook(
    val lock: Boolean,
    val fontSize: Int,
    val alignment: Int,
    val opacity: Int,
    val playedColor: Int,
    val pendingColor: Int,
)

private data class SoundState(
    val eq: Pair<Boolean, String?>,
    val noiseWithMusic: Boolean,
    val defaultSceneId: String?,
    val timerId: String,
    val verseGroups: List<VerseGroup>,
    val verseEnabledIds: Set<String>,
    val verseSlots: Set<String>,
    val verseIntervalSec: Int,
)

private data class LibraryState(
    val quality: String?,
    val platforms: List<PlatformEntry>,
    val activeScriptId: String?,
    val localCount: Int,
    val localDirs: List<String>,
    val scripts: List<com.lxpro.source.script.ScriptEntry>,
)

/** 白噪音睡眠定时的分钟档位（与 NoiseSleepTimer 内部列表一致） */
internal val NOISE_TIMER_MINUTES = listOf(15, 30, 45, 60, 90, 120)

/** 定时状态 → select 的 option id（off / morning / 分钟数 / c+自定义分钟数） */
private fun timerIdOf(timer: TimerStateEntity?): String {
    if (timer == null || timer.targetKind == TimerStateEntity.TargetKind.OFF.id) return "off"
    return when (timer.targetKind) {
        TimerStateEntity.TargetKind.UNTIL_HOUR.id -> "morning"
        TimerStateEntity.TargetKind.AFTER_MINUTES.id -> {
            val minutes = timer.valueMinutes ?: 0
            if (minutes in NOISE_TIMER_MINUTES) minutes.toString() else "c$minutes"
        }
        else -> "off"
    }
}

/**
 * 设置页（M3 任务 9）的状态与动作。
 *
 * 数据来源全是真实存储：`SettingsRepository`（DataStore）· `SourceRegistry`（音源注册表）·
 * `LocalMusicRepository`（本地曲库）· `ScriptSourceManager`（音源脚本）。**没有一项设置是编出来的**。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val sourceRegistry: SourceRegistry,
    private val library: LocalMusicRepository,
    private val scriptManager: ScriptSourceManager,
    private val sleepTimer: NoiseSleepTimer,
) : ViewModel() {

    /** 电池优化白名单状态（运行时查 PowerManager，非 DataStore 持久化）。 */
    private val _batteryIgnored = MutableStateFlow(BatteryOptimizationUtil.isIgnoring(context))
    val batteryIgnored: StateFlow<Boolean> = _batteryIgnored.asStateFlow()

    /** 当前厂商友好名（"小米 / Redmi / POCO" 等），用于提示文案。 */
    val batteryVendorHint = BatteryOptimizationUtil.vendorHint()

    init {
        // 本地曲库有内存缓存，进设置页时先刷一次，好让「本地音乐」摘要拿到真实条数
        viewModelScope.launch { library.refreshCache() }
    }

    /** 用户从系统设置页回来后调用——刷新电池优化白名单状态。 */
    fun refreshBatteryStatus() {
        _batteryIgnored.value = BatteryOptimizationUtil.isIgnoring(context)
    }

    /** 弹系统白名单请求页（或降级跳设置页）。 */
    fun requestBatteryOptimization() {
        BatteryOptimizationUtil.requestIgnore(context)
    }

    /** 跳厂商自启动 / 后台管理设置页。 */
    fun openAutoStartSettings() {
        BatteryOptimizationUtil.openAutoStartSettings(context)
    }

    /**
     * 音乐平台列表（纯展示）：过滤掉脚本源，只保留内置平台。
     * 脚本源是 [com.lxpro.source.script.ScriptSourceApi]，由社区导入；
     * 内置源是 [KgSource]/[MgSource]/[KwSource]/[TxSource]/[WySource]/[BilibiliSource]。
     */
    private val platformsFlow: Flow<List<PlatformEntry>> = sourceRegistry.all.map { all ->
        all.filterNot { it is com.lxpro.source.script.ScriptSourceApi }
            .map { PlatformEntry(it.id.value, it.name, it.qualities.size) }
    }

    /** 当前激活的取流脚本 id（第一个 enabled 的脚本） */
    private val activeScriptIdFlow: Flow<String?> = scriptManager.scripts.map { scripts ->
        scripts.firstOrNull { it.enabled }?.id
    }

    private val localFlow: Flow<Pair<Int, List<String>>> = combine(
        library.allTracks,
        library.safRoots,
    ) { tracks, roots -> tracks.size to roots.map { it.displayName } }

    /** 外观：配色 + 氛围 + 动态背景三件 + 跟随系统深浅色 */
    private val lookFlow: Flow<LookState> = combine(
        combine(settings.paletteId, settings.ambientEnabled) { id, ambient ->
            LXPalette.fromId(id) to ambient
        },
        combine(settings.dynamicBg, settings.dynamicBgBlur, settings.dynamicBgMaskOpacity) { bg, blur, mask ->
            Triple(bg, blur, mask)
        },
        settings.followSystemDark,
    ) { look, dynBg, follow ->
        LookState(
            palette = look.first,
            ambient = look.second,
            dynamicBg = dynBg.first,
            blur = dynBg.second,
            mask = dynBg.third,
            followSystemDark = follow,
        )
    }

    /** 播放 / 列表 / 下载三组行为开关 */
    private val behaviorFlow: Flow<BehaviorState> = combine(
        combine(
            settings.rememberPlaybackPosition,
            settings.pauseOnHeadphoneUnplug,
            settings.playWithOtherApps,
        ) { remember, pause, withOthers -> Triple(remember, pause, withOthers) },
        combine(settings.listShowCover, settings.ignoreShortAudio, settings.autoScanOnBoot) { cover, ignore, boot ->
            Triple(cover, ignore, boot)
        },
        combine(settings.desktopLyricEnabled, settings.statusBarLyricEnabled, settings.traditionalLyricEnabled) { desktop, statusBar, traditional ->
            Triple(desktop, statusBar, traditional)
        },
        // 「仅 Wi-Fi 下载」+ 省流量模式开关（#125）：合成一组，外层保持 5 个 Flow
        combine(
            settings.downloadWifiOnly,
            settings.dataSaverEnabled,
            settings.dataSaverAutoQuality,
            settings.dataSaverNoImage,
            settings.dataSaverApplyOnWifi,
        ) { wifi, saver, autoQ, noImg, onWifi ->
            SaverState(wifi, saver, autoQ, noImg, onWifi)
        },
        // 桌面歌词外观 6 项（嵌套 combine，因为 kotlinx combine 最多 5 个 Flow 直接合）
        combine(
            combine(settings.desktopLyricLock, settings.desktopLyricFontSize, settings.desktopLyricAlignment) { lock, fontSize, align ->
                Triple(lock, fontSize, align)
            },
            combine(
                combine(settings.desktopLyricOpacity, settings.desktopLyricPlayedColor) { opacity, played -> opacity to played },
                settings.desktopLyricPendingColor,
            ) { opacityPlayed, pending -> Triple(opacityPlayed.first, opacityPlayed.second, pending) },
        ) { lockAlign, opacityColors ->
            DesktopLyricLook(
                lock = lockAlign.first,
                fontSize = lockAlign.second,
                alignment = lockAlign.third,
                opacity = opacityColors.first,
                playedColor = opacityColors.second,
                pendingColor = opacityColors.third,
            )
        },
    ) { play, list, lyric, saver, look ->
        BehaviorState(
            rememberPosition = play.first,
            pauseOnHeadphone = play.second,
            playWithOtherApps = play.third,
            showCover = list.first,
            ignoreShortAudio = list.second,
            autoScanOnBoot = list.third,
            wifiOnly = saver.wifiOnly,
            dataSaverEnabled = saver.dataSaverEnabled,
            dataSaverAutoQuality = saver.dataSaverAutoQuality,
            dataSaverNoImage = saver.dataSaverNoImage,
            dataSaverApplyOnWifi = saver.dataSaverApplyOnWifi,
            desktopLyric = lyric.first,
            statusBarLyric = lyric.second,
            traditionalLyric = lyric.third,
            desktopLyricLock = look.lock,
            desktopLyricFontSize = look.fontSize,
            desktopLyricAlignment = look.alignment,
            desktopLyricOpacity = look.opacity,
            desktopLyricPlayedColor = look.playedColor.toString(16).uppercase().padStart(8, '0'),
            desktopLyricPendingColor = look.pendingColor.toString(16).uppercase().padStart(8, '0'),
        )
    }

    /** 声音层：EQ + 白噪音（同时播放 / 默认场景 / 睡眠定时）+ 文案（组 / 组启停 / 时段 / 间隔） */
    private val soundFlow: Flow<SoundState> = combine(
        combine(settings.eqBypass, settings.eqCurrentPresetName) { bypass, name -> bypass to name },
        combine(settings.noiseWithMusic, settings.noiseDefaultSceneId, sleepTimer.state) { music, scene, timer ->
            Triple(music, scene, timerIdOf(timer))
        },
        combine(
            settings.verseGroups,
            settings.enabledVerseGroupIds,
            settings.enabledVerseSlots,
            settings.verseIntervalSec,
        ) { groups, ids, slots, sec -> Quadruple(groups, ids, slots, sec) },
    ) { eq, noise, verse ->
        SoundState(
            eq = eq,
            noiseWithMusic = noise.first,
            defaultSceneId = noise.second,
            timerId = noise.third,
            verseGroups = verse.first,
            verseEnabledIds = verse.second,
            verseSlots = verse.third,
            verseIntervalSec = verse.fourth,
        )
    }

    /** 曲库与音源：音质 / 平台 / 当前脚本 / 本地条数与目录 / 脚本列表 */
    private val libraryFlow: Flow<LibraryState> = combine(
        settings.preferredQuality,
        platformsFlow,
        activeScriptIdFlow,
        localFlow,
        scriptManager.scripts,
    ) { quality, platforms, activeId, local, scripts ->
        LibraryState(
            quality = quality,
            platforms = platforms,
            activeScriptId = activeId,
            localCount = local.first,
            localDirs = local.second,
            scripts = scripts,
        )
    }

    private val downloadFlow: Flow<Quadruple<Int, Boolean, Int, Boolean>> = combine(
        settings.downloadConcurrency,
        settings.downloadAutoImport,
        settings.downloadRetryLimit,
        settings.downloadWifiOnly,
    ) { concurrency, autoImport, retryLimit, wifiOnly ->
        Quadruple(concurrency, autoImport, retryLimit, wifiOnly)
    }

    val state: StateFlow<SettingsState> = combine(
        combine(
            combine(lookFlow, behaviorFlow, soundFlow, libraryFlow, downloadFlow) { look, behavior, sound, lib, download ->
                Quintuple(look, behavior, sound, lib, download)
            },
            batteryIgnored,
        ) { quint, battery -> Pair(quint, battery) },
        settings.shareTemplate,
    ) { pair, shareTemplate ->
        val (quint, battery) = pair
        val (look, behavior, sound, lib, download) = quint
        SettingsState(
            palette = look.palette,
            ambientEnabled = look.ambient,
            dynamicBg = look.dynamicBg,
            dynamicBgBlur = look.blur,
            dynamicBgMaskOpacity = look.mask,
            followSystemDark = look.followSystemDark,
            rememberPlaybackPosition = behavior.rememberPosition,
            pauseOnHeadphoneUnplug = behavior.pauseOnHeadphone,
            playWithOtherApps = behavior.playWithOtherApps,
            listShowCover = behavior.showCover,
            ignoreShortAudio = behavior.ignoreShortAudio,
            autoScanOnBoot = behavior.autoScanOnBoot,
            wifiOnlyDownload = behavior.wifiOnly,
            dataSaverEnabled = behavior.dataSaverEnabled,
            dataSaverAutoQuality = behavior.dataSaverAutoQuality,
            dataSaverNoImage = behavior.dataSaverNoImage,
            dataSaverApplyOnWifi = behavior.dataSaverApplyOnWifi,
            desktopLyricEnabled = behavior.desktopLyric,
            statusBarLyricEnabled = behavior.statusBarLyric,
            traditionalLyricEnabled = behavior.traditionalLyric,
            desktopLyricLock = behavior.desktopLyricLock,
            desktopLyricFontSize = behavior.desktopLyricFontSize,
            desktopLyricAlignment = behavior.desktopLyricAlignment,
            desktopLyricOpacity = behavior.desktopLyricOpacity,
            desktopLyricPlayedColor = behavior.desktopLyricPlayedColor,
            desktopLyricPendingColor = behavior.desktopLyricPendingColor,
            quality = lib.quality?.let { raw -> Quality.entries.firstOrNull { it.value == raw } } ?: Quality.FLAC,
            platforms = lib.platforms,
            activeScriptId = lib.activeScriptId,
            localCount = lib.localCount,
            localDirNames = lib.localDirs,
            concurrency = download.first,
            autoImport = download.second,
            retryLimit = download.third,
            scripts = lib.scripts,
            eqBypass = sound.eq.first,
            eqPresetName = sound.eq.second,
            noiseWithMusic = sound.noiseWithMusic,
            noiseDefaultSceneId = sound.defaultSceneId,
            noiseTimerId = sound.timerId,
            enabledVerseGroupIds = sound.verseEnabledIds,
            enabledVerseSlots = sound.verseSlots,
            verseGroups = sound.verseGroups,
            verseIntervalSec = sound.verseIntervalSec,
            batteryOptimizationIgnored = battery,
            batteryVendorHint = batteryVendorHint,
            shareTemplate = shareTemplate,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    /** 保存用户修改后的分享邀请语模板 */
    fun setShareTemplate(template: String) {
        viewModelScope.launch { settings.setShareTemplate(template.trim()) }
    }

    fun selectPalette(palette: LXPalette) {
        viewModelScope.launch { settings.setPaletteId(palette.id) }
    }

    fun setAmbient(enabled: Boolean) {
        viewModelScope.launch { settings.setAmbientEnabled(enabled) }
    }

    fun setDynamicBg(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicBg(enabled) }
    }

    fun setDynamicBgBlur(value: Int) {
        viewModelScope.launch { settings.setDynamicBgBlur(value) }
    }

    fun setDynamicBgMaskOpacity(value: Int) {
        viewModelScope.launch { settings.setDynamicBgMaskOpacity(value) }
    }

    fun setQuality(quality: Quality) {
        viewModelScope.launch { settings.setPreferredQuality(quality.value) }
    }

    fun setConcurrency(value: Int) {
        viewModelScope.launch { settings.setDownloadConcurrency(value) }
    }

    fun setAutoImport(enabled: Boolean) {
        viewModelScope.launch { settings.setDownloadAutoImport(enabled) }
    }

    fun setRetryLimit(value: Int) {
        viewModelScope.launch { settings.setDownloadRetryLimit(value) }
    }

    // ── 行为开关（设置页新增项，全部直写 DataStore）──

    fun setFollowSystemDark(enabled: Boolean) {
        viewModelScope.launch { settings.setFollowSystemDark(enabled) }
    }

    fun setRememberPlaybackPosition(enabled: Boolean) {
        viewModelScope.launch { settings.setRememberPlaybackPosition(enabled) }
    }

    fun setPauseOnHeadphoneUnplug(enabled: Boolean) {
        viewModelScope.launch { settings.setPauseOnHeadphoneUnplug(enabled) }
    }

    fun setPlayWithOtherApps(enabled: Boolean) {
        viewModelScope.launch { settings.setPlayWithOtherApps(enabled) }
    }

    fun setDesktopLyricEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setDesktopLyricEnabled(enabled) }
    }

    fun setStatusBarLyricEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setStatusBarLyricEnabled(enabled) }
    }

    fun setTraditionalLyricEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setTraditionalLyricEnabled(enabled) }
    }

    // ── 桌面歌词外观（对齐 LX Music desktopLyric.* 全集） ──

    fun setDesktopLyricLock(locked: Boolean) {
        viewModelScope.launch { settings.setDesktopLyricLock(locked) }
    }

    fun setDesktopLyricFontSize(size: Int) {
        viewModelScope.launch { settings.setDesktopLyricFontSize(size) }
    }

    fun setDesktopLyricAlignment(id: String) {
        id.toIntOrNull()?.let { viewModelScope.launch { settings.setDesktopLyricAlignment(it) } }
    }

    fun setDesktopLyricOpacity(opacity: Int) {
        viewModelScope.launch { settings.setDesktopLyricOpacity(opacity) }
    }

    /** @param hexColor 8 位 ARGB 十六进制（如 "FFFFFFFF"） */
    fun setDesktopLyricPlayedColor(hexColor: String) {
        hexColor.toLongOrNull(16)?.toInt()?.let {
            viewModelScope.launch { settings.setDesktopLyricPlayedColor(it) }
        }
    }

    /** @param hexColor 8 位 ARGB 十六进制（如 "99FFFFFF"） */
    fun setDesktopLyricPendingColor(hexColor: String) {
        hexColor.toLongOrNull(16)?.toInt()?.let {
            viewModelScope.launch { settings.setDesktopLyricPendingColor(it) }
        }
    }

    fun setListShowCover(enabled: Boolean) {
        viewModelScope.launch { settings.setListShowCover(enabled) }
    }

    fun setIgnoreShortAudio(enabled: Boolean) {
        viewModelScope.launch { settings.setIgnoreShortAudio(enabled) }
    }

    fun setAutoScanOnBoot(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoScanOnBoot(enabled) }
    }

    fun setWifiOnlyDownload(enabled: Boolean) {
        viewModelScope.launch { settings.setDownloadWifiOnly(enabled) }
    }

    fun setNoiseWithMusic(enabled: Boolean) {
        viewModelScope.launch { settings.setNoiseWithMusic(enabled) }
    }

    /** 省流量模式：总开关 / 省流时降音质 / 省流时全无图（DataSaverMonitor 自动感知） */
    fun setDataSaverEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setDataSaverEnabled(enabled) }
    }

    fun setDataSaverAutoQuality(enabled: Boolean) {
        viewModelScope.launch { settings.setDataSaverAutoQuality(enabled) }
    }

    fun setDataSaverNoImage(enabled: Boolean) {
        viewModelScope.launch { settings.setDataSaverNoImage(enabled) }
    }

    fun setDataSaverApplyOnWifi(enabled: Boolean) {
        viewModelScope.launch { settings.setDataSaverApplyOnWifi(enabled) }
    }

    /** @param sceneId null = 沿用上次混音 */
    fun setNoiseDefaultScene(sceneId: String?) {
        viewModelScope.launch { settings.setNoiseDefaultSceneId(sceneId) }
    }

    /** 白噪音睡眠定时：off / morning / 分钟数 / c+自定义分钟数 */
    fun setNoiseSleepTimer(optionId: String) {
        viewModelScope.launch {
            when {
                optionId == "off" -> sleepTimer.cancel()
                optionId == "morning" -> sleepTimer.setUntilHour(7)
                optionId.startsWith("c") -> optionId.drop(1).toIntOrNull()?.let { sleepTimer.setAfterMinutes(it) }
                else -> optionId.toIntOrNull()?.let { sleepTimer.setAfterMinutes(it) }
            }
        }
    }

    /**
     * 启用 / 停用文案组。语义与原型一致：全都关掉时自动回落到全部，不出现空屏 ——
     * 空集 = 全开，不做「至少留一个」拦截。
     */
    fun setVerseGroupEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val allIds = settings.verseGroups.first().map { it.id }.toSet()
            val current = settings.enabledVerseGroupIds.first().ifEmpty { allIds }
            val next = if (enabled) current + id else current - id
            settings.setEnabledVerseGroupIds(next.filter { it in allIds }.toSet())
        }
    }

    /** 启用 / 停用时段分组（原型 `vgroups`）；空集 = 全开（全部关掉 = 回到全开） */
    fun setVerseSlot(slot: DaySlot, enabled: Boolean) {
        viewModelScope.launch {
            val current = settings.enabledVerseSlots.first()
                .ifEmpty { DaySlot.entries.map { it.name }.toSet() }
            val next = if (enabled) current + slot.name else current - slot.name
            settings.setEnabledVerseSlots(next)
        }
    }

    /** 新建文案组；@param onResult false = 空名/重名/已达组数上限（如实告知，不做假成功） */
    fun addVerseGroup(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(settings.addVerseGroup(name)) }
    }

    /** 重命名文案组；@param onResult false = 空名/与其他组重名 */
    fun renameVerseGroup(id: String, name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(settings.renameVerseGroup(id, name)) }
    }

    fun removeVerseGroup(id: String) {
        viewModelScope.launch { settings.removeVerseGroup(id) }
    }

    /** 批量添加文案（每行一条，空行自动忽略）；@param onResult 实际添加条数 */
    fun addVersesToGroup(id: String, lines: List<String>, onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(settings.addVersesToGroup(id, lines)) }
    }

    /** 修改组内一条文案；@param onResult false = 为空/未变化/与组内重复 */
    fun updateVerseInGroup(id: String, oldText: String, newText: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(settings.updateVerseInGroup(id, oldText, newText)) }
    }

    fun removeVerseFromGroup(id: String, text: String) {
        viewModelScope.launch { settings.removeVerseFromGroup(id, text) }
    }

    /** 文案轮换间隔（秒；首页/歌单/统计共用） */
    fun setVerseIntervalSec(sec: Int) {
        viewModelScope.launch { settings.setVerseIntervalSec(sec) }
    }

    /** 导入音源脚本（SAF uri）；结果经回调回 UI（成功/失败都如实提示） */
    fun importScript(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch { onResult(scriptManager.import(uri)) }
    }

    /**
     * 设为当前取流脚本（单选：启用时自动停用其他脚本）。
     * @param onResult 回调被停用的脚本名列表（空列表 = 原本就只有这一个）
     */
    fun setScriptActive(scriptId: String, onResult: (List<String>?) -> Unit) {
        viewModelScope.launch { onResult(scriptManager.setActive(scriptId)) }
    }

    /** 删除脚本（卸载 + 删文件 + 删记录） */
    fun removeScript(scriptId: String) {
        viewModelScope.launch { scriptManager.remove(scriptId) }
    }

    /** 测试脚本是否可用（重新加载验证 INIT） */
    fun testScript(scriptId: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val error = scriptManager.testScript(scriptId)
            onResult(error ?: "脚本测试通过，可正常加载")
        }
    }
}