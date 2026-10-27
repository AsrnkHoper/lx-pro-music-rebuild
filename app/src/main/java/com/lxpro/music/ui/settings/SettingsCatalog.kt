package com.lxpro.music.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector
import com.lxpro.core.common.DaySlot
import com.lxpro.core.common.FlowRandom
import com.lxpro.core.common.VerseGroup
import com.lxpro.core.common.VersePicker
import com.lxpro.core.common.builtinVerseGroups
import com.lxpro.core.datastore.VERSE_GROUP_LIMIT
import com.lxpro.core.designsystem.component.LXQuality
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.media.noise.NoiseCatalog
import com.lxpro.core.model.Quality
import com.lxpro.music.BuildConfig
import java.time.LocalTime

/** 分类 key（15 §6：`SettingsCategory(key)`，与原型 SET_CATS 一一对应） */
object SettingsCategoryKeys {
    const val SOURCE = "source"
    const val LOCAL = "local"
    const val LOOK = "look"
    const val PLAY = "play"
    const val NOISE = "noise"
    const val DOWN = "down"
    const val LIST = "list"
    const val VERSE = "verse"

    /** 用户 2026-10-05 要求：「数据与备份」独立成一类，不再挂在「关于」下 */
    const val DATA = "data"
    const val ABOUT = "about"

    /** 详情页从路由参数取 key 时的白名单（非法 key 直接回 hub，不崩） */
    val ALL = listOf(SOURCE, LOCAL, LOOK, PLAY, NOISE, DOWN, LIST, VERSE, DATA, ABOUT)
}

/**
 * 设置行的四种控件形态（对齐原型 `setRow`）：
 * `toggle` 右侧开关 · `select` 右侧当前值+`›` · `static` 纯展示 · `themes` 配色卡。
 *
 * ⚠️ 凡是**上游功能尚未落地**的设置项一律用 [SettingsInfoRow]（static）如实说明，
 * 不做「开关能点但什么都不发生」的假交互 —— 与「不造假数据」同一条铁律。
 */
sealed interface SettingsRow {
    val title: String
    val subtitle: String?
}

class SettingsToggleRow(
    override val title: String,
    override val subtitle: String?,
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit,
) : SettingsRow

/** 数值滑杆（动态背景的模糊强度 / 遮罩不透明度） */
class SettingsSliderRow(
    override val title: String,
    override val subtitle: String?,
    val value: Int,
    val range: IntRange,
    val unit: String = "",
    val onChange: (Int) -> Unit,
) : SettingsRow

/** 一个可选值；`id` 由调用方解释（音质用 [Quality.value] 这类稳定标识，不用展示名） */
data class SettingsOption(val id: String, val label: String, val hint: String? = null)

class SettingsSelectRow(
    override val title: String,
    override val subtitle: String?,
    val options: List<SettingsOption>,
    val currentId: String,
    val onPick: (String) -> Unit,
) : SettingsRow {
    val currentLabel: String get() = options.firstOrNull { it.id == currentId }?.label ?: currentId
}

/** 可点但只做一件事（进关于页 / 去本地音乐页扫描导入） */
class SettingsActionRow(
    override val title: String,
    override val subtitle: String?,
    val onClick: () -> Unit,
) : SettingsRow

/** 只读展示（原型 static）：上游未落地或只是说明 */
class SettingsInfoRow(
    override val title: String,
    override val subtitle: String?,
) : SettingsRow

/** 配色卡（外观分类专用，原型 `.themes`） */
class SettingsThemesRow(
    override val title: String = "配色",
    override val subtitle: String? = null,
) : SettingsRow

/** 脚本行：点击后弹出操作面板（设当前 / 测试 / 删除），不是 select 值选择 */
class SettingsScriptRow(
    override val title: String,
    override val subtitle: String?,
    val scriptId: String,
    val isActive: Boolean,
    val onPick: (action: String) -> Unit,
) : SettingsRow

/** 文案组行：行点击进组详情，右侧开关直接启停 */
class SettingsVerseGroupRow(
    override val title: String,
    override val subtitle: String?,
    val groupId: String,
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit,
    val onClick: () -> Unit,
) : SettingsRow

data class SettingsGroup(val header: String?, val rows: List<SettingsRow>)

data class SettingsCategory(
    val key: String,
    val name: String,
    val summary: String,
    val icon: ImageVector,
    val groups: List<SettingsGroup>,
)

/**
 * 目录构建所需的、来自界面的动作。
 * ViewModel 的状态与「去哪个页面」是两回事，所以导航类动作从外面传进来。
 */
data class SettingsHandlers(
    val onPalette: (LXPalette) -> Unit,
    val onAmbient: (Boolean) -> Unit,
    /** 动态背景：开关 / 模糊强度 / 遮罩不透明度（对齐 LX-Music 主题设置） */
    val onDynamicBg: (Boolean) -> Unit,
    val onDynamicBgBlur: (Int) -> Unit,
    val onDynamicBgMask: (Int) -> Unit,
    val onQuality: (Quality) -> Unit,
    val onImportScript: () -> Unit,
    /** 设为当前取流脚本（单选：启用时自动停用其他脚本） */
    val onScriptActive: (String) -> Unit,
    val onScriptRemove: (String) -> Unit,
    val onScriptTest: (String) -> Unit,
    val onConcurrency: (Int) -> Unit,
    val onAutoImport: (Boolean) -> Unit,
    val onRetryLimit: (Int) -> Unit,
    /** 行为开关（设置页「完善」批）：主题跟随 / 播放位置 / 耳机 / 列表封面 / 扫描 / Wi-Fi / 白噪音 / 文案 */
    val onFollowSystemDark: (Boolean) -> Unit,
    val onRememberPosition: (Boolean) -> Unit,
    val onPauseOnHeadphone: (Boolean) -> Unit,
    /** 与其他应用同时播放（开=不抢音频焦点，混音同播） */
    val onPlayWithOtherApps: (Boolean) -> Unit,
    /** 桌面歌词开关（详情页会先查悬浮窗权限，无权限先引导不写入） */
    val onDesktopLyric: (Boolean) -> Unit,
    /** 状态栏歌词开关（Lyric Getter 协议，提供给「墨·状态栏歌词」等模块） */
    val onStatusBarLyric: (Boolean) -> Unit,
    /** 繁体歌词开关（默认开，简体自动转繁体显示，对齐 LX Music） */
    val onTraditionalLyric: (Boolean) -> Unit,
    /** 桌面歌词外观 6 项（对齐 LX Music desktopLyric.* 全集） */
    val onDesktopLyricLock: (Boolean) -> Unit,
    val onDesktopLyricFontSize: (Int) -> Unit,
    val onDesktopLyricAlignment: (String) -> Unit,
    val onDesktopLyricOpacity: (Int) -> Unit,
    val onDesktopLyricPlayedColor: (String) -> Unit,
    val onDesktopLyricPendingColor: (String) -> Unit,
    val onIgnoreShortAudio: (Boolean) -> Unit,
    val onAutoScanOnBoot: (Boolean) -> Unit,
    val onWifiOnlyDownload: (Boolean) -> Unit,
    /** 省流量模式：总开关 / 省流时降音质 / 省流时全无图 */
    val onDataSaverEnabled: (Boolean) -> Unit,
    val onDataSaverAutoQuality: (Boolean) -> Unit,
    val onDataSaverNoImage: (Boolean) -> Unit,
    val onDataSaverApplyOnWifi: (Boolean) -> Unit,
    val onShowCover: (Boolean) -> Unit,
    val onNoiseWithMusic: (Boolean) -> Unit,
    val onNoiseDefaultScene: (String?) -> Unit,
    val onNoiseSleepTimer: (String) -> Unit,
    /** 文案组启停（右侧开关，空集 = 全开） */
    val onVerseGroupToggle: (id: String, enabled: Boolean) -> Unit,
    /** 打开文案组详情（组内文案的查看/增删改） */
    val onOpenVerseGroup: (id: String) -> Unit,
    /** 打开「添加文案组」弹窗 */
    val onAddVerseGroupClick: () -> Unit,
    /** 时段分组启停（原型 `vgroups`） */
    val onVerseSlot: (DaySlot, Boolean) -> Unit,
    /** 文案轮换间隔（秒） */
    val onVerseInterval: (Int) -> Unit,
    val onScanFolder: () -> Unit,
    val onImportMusic: () -> Unit,
    val onOpenAbout: () -> Unit,
    /** 打开均衡器页（17 §5，feature/eq） */
    val onOpenEq: () -> Unit,
    val onNotice: (String) -> Unit,
    /** 后台保活：请求加入电池优化白名单（弹系统对话框 / 降级跳设置页） */
    val onRequestBatteryOptimization: () -> Unit,
    /** 后台保活：跳厂商自启动 / 后台管理设置页 */
    val onOpenAutoStartSettings: () -> Unit,
    /** 后台保活：刷新电池优化白名单状态（用户从系统设置回来后调用） */
    val onRefreshBatteryStatus: () -> Unit,
    /** 弹分享邀请语编辑框（AlertDialog + TextField） */
    val onEditShareTemplate: () -> Unit,
    /** 保存用户修改后的分享邀请语（SettingsViewModel → DataStore） */
    val onSetShareTemplate: (String) -> Unit,
)

/** 音乐平台一行：id + 展示名 + 音质档数（纯展示，不参与启停） */
data class PlatformEntry(val id: String, val name: String, val qualityCount: Int)

/**
 * 设置状态：全部来自真实数据源（DataStore / 音源注册表 / 本地曲库），
 * 没有一项是编出来的（见 06 的「不造假数据」约束）。
 */
data class SettingsState(
    val palette: LXPalette = LXPalette.default,
    val ambientEnabled: Boolean = true,
    /** 动态背景（对齐 LX-Music「使用动态背景」）：封面模糊 + 当前配色遮罩 */
    val dynamicBg: Boolean = true,
    val dynamicBgBlur: Int = 18,
    val dynamicBgMaskOpacity: Int = 76,
    /** 跟随系统深浅色：系统深色时浅色系回落到 ① 暗绿 */
    val followSystemDark: Boolean = false,
    /** 播放行为：记忆上次位置 / 耳机拔出暂停 / 与其他应用同播 */
    val rememberPlaybackPosition: Boolean = true,
    val pauseOnHeadphoneUnplug: Boolean = true,
    val playWithOtherApps: Boolean = false,
    /** 歌词出口：桌面悬浮窗 / 状态栏歌词模块（Lyric Getter 协议） / 繁体转换 */
    val desktopLyricEnabled: Boolean = false,
    val statusBarLyricEnabled: Boolean = true,
    val traditionalLyricEnabled: Boolean = true,
    /** 桌面歌词外观（对齐 LX Music desktopLyric.* 全集） */
    val desktopLyricLock: Boolean = false,
    val desktopLyricFontSize: Int = 18,
    val desktopLyricAlignment: Int = 1,
    val desktopLyricOpacity: Int = 100,
    val desktopLyricPlayedColor: String = "FFFFFFFF",
    val desktopLyricPendingColor: String = "99FFFFFF",
    /** 列表与扫描：歌曲行显示封面 / 忽略短音频 / 开机自动扫描 */
    val listShowCover: Boolean = true,
    val ignoreShortAudio: Boolean = true,
    val autoScanOnBoot: Boolean = false,
    /** 仅 Wi-Fi 下载 */
    val wifiOnlyDownload: Boolean = false,
    /** 省流量模式（总开关只在计费网络下生效；两个子开关决定生效内容） */
    val dataSaverEnabled: Boolean = false,
    val dataSaverAutoQuality: Boolean = true,
    val dataSaverNoImage: Boolean = true,
    val dataSaverApplyOnWifi: Boolean = false,
    /** 白噪音：与歌曲同时播放 / 默认场景 / 睡眠定时 */
    val noiseWithMusic: Boolean = true,
    val noiseDefaultSceneId: String? = null,
    val noiseTimerId: String = "off",
    /** 全部文案组（内置 6 组 + 自建组；首启初始化完成前为空列表，展示层回落内置组） */
    val verseGroups: List<VerseGroup> = emptyList(),
    /** 启用的文案组 id（空集 = 全开） */
    val enabledVerseGroupIds: Set<String> = emptySet(),
    /** 文案时段分组的启停（DaySlot.name 集合；空集 = 全开） */
    val enabledVerseSlots: Set<String> = emptySet(),
    /** 文案轮换间隔（秒） */
    val verseIntervalSec: Int = 9,
    val quality: Quality = Quality.FLAC,
    /** 音乐平台列表（纯展示，不参与启停） */
    val platforms: List<PlatformEntry> = emptyList(),
    /** 已导入的脚本列表 */
    val scripts: List<com.lxpro.source.script.ScriptEntry> = emptyList(),
    /** 当前激活的取流脚本 id（null = 未设置） */
    val activeScriptId: String? = null,
    val localCount: Int = 0,
    val localDirNames: List<String> = emptyList(),
    val concurrency: Int = 3,
    val autoImport: Boolean = true,
    val retryLimit: Int = 1,
    /** EQ 直通中（true = 「正常」，17 §5.2）；false 且 eqPresetName 非空 = 已套用曲线 */
    val eqBypass: Boolean = true,
    /** 当前套用的 EQ 曲线名（null = 未套用） */
    val eqPresetName: String? = null,
    /** 后台保活：电池优化白名单状态（运行时查 PowerManager，非持久化） */
    val batteryOptimizationIgnored: Boolean = false,
    /** 后台保活：当前厂商名，用于提示文案（"小米 / Redmi / POCO" 等） */
    val batteryVendorHint: String = "",
    /** 分享邀请语模板：{title} 歌名 {artist} 艺术家 {link} 软链接 */
    val shareTemplate: String = "我在阿玉音乐听《{title}-{artist}》，复制前往阿玉音乐聆听！{link}",
)

private val SELECTABLE_QUALITIES = listOf(Quality.K128, Quality.K320, Quality.FLAC, Quality.HIRES)

private fun qualityLabel(quality: Quality): String = LXQuality.label(quality)

/** 音质 select 的通用选项表（播放 / 下载两处共用） */
private fun qualityOptions(): List<SettingsOption> =
    SELECTABLE_QUALITIES.map { SettingsOption(it.value, qualityLabel(it)) }

/** 桌面歌词颜色预设（对齐 LX Music 默认主题配色，覆盖深色/浅色场景） */
private data class ColorPreset(val id: String, val label: String)

private val COLOR_PRESETS_PLAYED = listOf(
    ColorPreset("FFFFFFFF", "纯白 · 默认"),
    ColorPreset("FFE6E6E6", "淡灰"),
    ColorPreset("FF1F1F1F", "深黑（浅色背景）"),
    ColorPreset("FF4ADE80", "绿色"),
    ColorPreset("FF60A5FA", "蓝色"),
    ColorPreset("FFF472B6", "粉色"),
    ColorPreset("FFBBF24", "紫色"),
    ColorPreset("FFFFA020", "橙色"),
)

private val COLOR_PRESETS_PENDING = listOf(
    ColorPreset("99FFFFFF", "半透明白 · 默认"),
    ColorPreset("66FFFFFF", "更淡透明"),
    ColorPreset("991F1F1F", "半透明黑"),
    ColorPreset("664ADE80", "半透明绿"),
    ColorPreset("6660A5FA", "半透明蓝"),
)

/** 白噪音默认场景：第一项 = 不预置，沿用上次混音 */
private fun noiseSceneOptions(): List<SettingsOption> =
    listOf(SettingsOption("last", "沿用上次混音")) +
        NoiseCatalog.presetScenes.map { SettingsOption(it.id, it.name) }

/** 睡眠定时档位（只在白噪音定时器上生效）；自定义分钟也如实出现在选项里 */
private fun noiseTimerOptions(currentId: String): List<SettingsOption> = buildList {
    add(SettingsOption("off", "不开启"))
    NOISE_TIMER_MINUTES.forEach { add(SettingsOption(it.toString(), "$it 分钟")) }
    add(SettingsOption("morning", "到早上 7:00"))
    if (currentId.startsWith("c")) add(SettingsOption(currentId, "${currentId.drop(1)} 分钟（自定义）"))
}

/**
 * 由真实状态构建 9 个分类的完整目录。
 * hub 用它出状态摘要与搜索结果，详情页用它的 groups 出分组与行 —— **唯一数据源**。
 */
fun buildSettingsCatalog(state: SettingsState, handlers: SettingsHandlers): List<SettingsCategory> {
    val palette = state.palette
    val quality = state.quality

    val activeScriptName = state.scripts.firstOrNull { it.id == state.activeScriptId }?.name
    val source = SettingsCategory(
        key = SettingsCategoryKeys.SOURCE,
        name = "音源管理",
        summary = activeScriptName?.let { "当前脚本：$it" } ?: "未设置取流脚本",
        icon = LxIcons.Link,
        groups = listOf(
            SettingsGroup(
                header = "音乐平台（搜索与歌词）",
                rows = state.platforms.map { entry ->
                    SettingsInfoRow(
                        title = entry.name,
                        subtitle = "${entry.qualityCount} 档音质 · 内置搜索与歌词",
                    )
                },
            ),
            SettingsGroup(
                header = "取流脚本（单选）",
                rows = buildList {
                    if (state.scripts.isEmpty()) {
                        add(
                            SettingsActionRow(
                                title = "还没有导入脚本",
                                subtitle = "从本地文件导入 lx 2.0 音源脚本，用于在线播放取流",
                                onClick = handlers.onImportScript,
                            ),
                        )
                    } else {
                        state.scripts.forEach { script ->
                            add(
                                SettingsScriptRow(
                                    title = script.name +
                                        if (script.version.isBlank()) "" else " v${script.version}",
                                    subtitle = buildString {
                                        if (script.author.isNotBlank()) append(script.author).append(" · ")
                                        if (script.loadError != null) {
                                            append("加载失败：").append(script.loadError)
                                        } else if (script.sourcesDesc.isNotBlank()) {
                                            append(script.sourcesDesc)
                                        } else if (!script.enabled) {
                                            append("已停用")
                                        } else {
                                            append("加载中…")
                                        }
                                        if (script.id == state.activeScriptId) append(" · 当前脚本")
                                    },
                                    scriptId = script.id,
                                    isActive = script.id == state.activeScriptId,
                                    onPick = { action ->
                                        when (action) {
                                            "active" -> handlers.onScriptActive(script.id)
                                            "test" -> handlers.onScriptTest(script.id)
                                            "remove" -> handlers.onScriptRemove(script.id)
                                        }
                                    },
                                ),
                            )
                        }
                    }
                },
            ),
            SettingsGroup(
                header = "导入与维护",
                rows = buildList {
                    add(SettingsActionRow("导入音源脚本", "从本地文件导入 JS 脚本（lx 2.0 协议）", handlers.onImportScript))
                },
            ),
        ),
    )

    val local = SettingsCategory(
        key = SettingsCategoryKeys.LOCAL,
        name = "本地音乐",
        summary = "${state.localCount} 首 · ${state.localDirNames.size} 个扫描目录",
        icon = LxIcons.Folder,
        groups = listOf(
            SettingsGroup(
                header = "索引",
                rows = listOf(
                    SettingsActionRow("扫描文件夹", "全盘搜索音频文件", handlers.onScanFolder),
                    SettingsActionRow("导入音乐", "单曲 / 整张专辑", handlers.onImportMusic),
                    SettingsToggleRow(
                        title = "开机自动扫描",
                        subtitle = "开机后自动刷新本地曲库索引",
                        checked = state.autoScanOnBoot,
                        onToggle = handlers.onAutoScanOnBoot,
                    ),
                ),
            ),
            SettingsGroup(
                header = "扫描范围",
                rows = buildList {
                    if (state.localDirNames.isEmpty()) {
                        add(SettingsInfoRow("还没有授权目录", "去本地音乐页添加，扫描与导入都在那里"))
                    } else {
                        state.localDirNames.forEach { add(SettingsInfoRow(it, "授权目录 · 在本地音乐页管理")) }
                    }
                    add(
                        SettingsToggleRow(
                            title = "忽略短于 30 秒的文件",
                            subtitle = "关闭时只过滤 10 秒以内的提示音",
                            checked = state.ignoreShortAudio,
                            onToggle = handlers.onIgnoreShortAudio,
                        ),
                    )
                },
            ),
        ),
    )

    val look = SettingsCategory(
        key = SettingsCategoryKeys.LOOK,
        name = "外观",
        summary = "六套情绪配色 · 当前 ${palette.displayName}" +
            if (state.dynamicBg) " · 动态背景开" else "",
        icon = LxIcons.Palette,
        groups = listOf(
            SettingsGroup(header = "配色（六套实测）", rows = listOf(SettingsThemesRow())),
            SettingsGroup(
                header = "动态背景（封面模糊铺满）",
                rows = buildList {
                    add(
                        SettingsToggleRow(
                            title = "使用动态背景",
                            subtitle = "全局背景跟随当前播放封面（模糊 + 当前配色遮罩）",
                            checked = state.dynamicBg,
                            onToggle = handlers.onDynamicBg,
                        ),
                    )
                    if (state.dynamicBg) {
                        add(
                            SettingsSliderRow(
                                title = "模糊强度",
                                subtitle = null,
                                value = state.dynamicBgBlur,
                                range = 0..40,
                                onChange = handlers.onDynamicBgBlur,
                            ),
                        )
                        add(
                            SettingsSliderRow(
                                title = "遮罩不透明度",
                                subtitle = "越高封面越淡、文字越清晰",
                                value = state.dynamicBgMaskOpacity,
                                range = 0..100,
                                unit = "%",
                                onChange = handlers.onDynamicBgMask,
                            ),
                        )
                    }
                },
            ),
            SettingsGroup(
                header = null,
                rows = listOf(
                    SettingsToggleRow(
                        title = "氛围呼吸",
                        subtitle = "背景光源缓慢漂移，可关闭省电",
                        checked = state.ambientEnabled,
                        onToggle = handlers.onAmbient,
                    ),
                    SettingsToggleRow(
                        title = "跟随系统深浅色",
                        subtitle = "系统深色时，浅色配色回落到 ① 暗绿",
                        checked = state.followSystemDark,
                        onToggle = handlers.onFollowSystemDark,
                    ),
                ),
            ),
        ),
    )

    val play = SettingsCategory(
        key = SettingsCategoryKeys.PLAY,
        name = "播放与音效",
        summary = "默认音质 ${qualityLabel(quality)} · " +
            (if (!state.eqBypass && state.eqPresetName != null) "EQ：${state.eqPresetName}" else "EQ 直通"),
        icon = LxIcons.Noise,
        groups = listOf(
            SettingsGroup(
                header = "后台保活（厂商电池策略）",
                rows = listOf(
                    SettingsInfoRow(
                        title = "电池优化白名单",
                        subtitle = if (state.batteryOptimizationIgnored) {
                            "✓ 已加入白名单，系统不会限制后台运行"
                        } else {
                            "⚠ 未加入白名单，${state.batteryVendorHint}可能在锁屏/省电时杀后台播放"
                        },
                    ),
                    SettingsActionRow(
                        title = "加入电池优化白名单",
                        subtitle = "系统弹对话框让你选「允许」；部分 ROM 直接跳设置页",
                        onClick = handlers.onRequestBatteryOptimization,
                    ),
                    SettingsActionRow(
                        title = "打开自启动 / 后台管理",
                        subtitle = "跳转${state.batteryVendorHint}的自启动设置页，打开后应用被杀能自恢复",
                        onClick = handlers.onOpenAutoStartSettings,
                    ),
                ),
            ),
            SettingsGroup(
                header = "音效（只作用于歌曲）",
                rows = listOf(
                    SettingsActionRow(
                        title = "均衡器",
                        subtitle = "耳机校正 · 142 条曲线 · 直通时为关",
                        onClick = handlers.onOpenEq,
                    ),
                    SettingsInfoRow(
                        "当前校正曲线",
                        if (!state.eqBypass && state.eqPresetName != null) {
                            "已套用：${state.eqPresetName}"
                        } else {
                            "正常（直通）· 信号原样输出"
                        },
                    ),
                    SettingsInfoRow("歌曲切换淡入淡出", "白噪音不参与 · 播放页「更多」可调时长"),
                ),
            ),
            SettingsGroup(
                header = "播放",
                rows = listOf(
                    SettingsSelectRow(
                        title = "默认音质",
                        subtitle = "高于可用音质时自动降级",
                        options = qualityOptions(),
                        currentId = quality.value,
                        onPick = { id -> SELECTABLE_QUALITIES.firstOrNull { it.value == id }?.let(handlers.onQuality) },
                    ),
                    SettingsToggleRow(
                        title = "记忆上次播放位置",
                        subtitle = "同一首再次播放时从上次停下的位置继续",
                        checked = state.rememberPlaybackPosition,
                        onToggle = handlers.onRememberPosition,
                    ),
                    SettingsToggleRow(
                        title = "耳机拔出时暂停",
                        subtitle = "仅在插线耳机拔出时生效",
                        checked = state.pauseOnHeadphoneUnplug,
                        onToggle = handlers.onPauseOnHeadphone,
                    ),
                    SettingsToggleRow(
                        title = "与其他应用同时播放",
                        subtitle = "开启后不抢占音频焦点：其他应用播放时本应用不暂停，两边同时出声",
                        checked = state.playWithOtherApps,
                        onToggle = handlers.onPlayWithOtherApps,
                    ),
                    // ── 省流量模式（#125）：总开关只在计费网络下生效 ──
                    SettingsToggleRow(
                        title = "省流量模式",
                        subtitle = "默认仅在非 Wi-Fi（蜂窝/热点）时生效；可用下方开关扩展到 Wi-Fi",
                        checked = state.dataSaverEnabled,
                        onToggle = handlers.onDataSaverEnabled,
                    ),
                    SettingsToggleRow(
                        title = "Wi-Fi 下也启用省流",
                        subtitle = "开启后任何网络环境都按省流规则播放与加载图片",
                        checked = state.dataSaverApplyOnWifi,
                        onToggle = handlers.onDataSaverApplyOnWifi,
                    ),
                    SettingsToggleRow(
                        title = "省流时自动降音质",
                        subtitle = "省流生效时播放取流固定 128k，回到 Wi-Fi 自动恢复默认音质",
                        checked = state.dataSaverAutoQuality,
                        onToggle = handlers.onDataSaverAutoQuality,
                    ),
                    SettingsToggleRow(
                        title = "省流时全无图",
                        subtitle = "省流生效时彻底不渲染网络图片（不留占位）；关闭则仅显示已缓存图片、不联网拉取",
                        checked = state.dataSaverNoImage,
                        onToggle = handlers.onDataSaverNoImage,
                    ),
                ),
            ),
            SettingsGroup(
                header = "歌词",
                rows = buildList {
                    add(
                        SettingsToggleRow(
                            title = "桌面歌词",
                            subtitle = "悬浮窗跟随播放显示当前歌词行 · 需悬浮窗权限，可拖动调位置",
                            checked = state.desktopLyricEnabled,
                            onToggle = handlers.onDesktopLyric,
                        ),
                    )
                    add(
                        SettingsToggleRow(
                            title = "状态栏歌词",
                            subtitle = "把当前歌词行提供给系统侧歌词模块（如「墨·状态栏歌词」）",
                            checked = state.statusBarLyricEnabled,
                            onToggle = handlers.onStatusBarLyric,
                        ),
                    )
                    add(
                        SettingsToggleRow(
                            title = "繁体歌词",
                            subtitle = "简体自动转繁体显示（对齐 LX Music 的繁体风格，同时作用于系统媒体卡片标题）",
                            checked = state.traditionalLyricEnabled,
                            onToggle = handlers.onTraditionalLyric,
                        ),
                    )
                },
            ),
            SettingsGroup(
                header = "分享邀请语",
                rows = listOf(
                    SettingsInfoRow(
                        title = "当前模板",
                        subtitle = state.shareTemplate,
                    ),
                    SettingsActionRow(
                        title = "自定义分享邀请语",
                        subtitle = "占位符：{title} 歌名 {artist} 艺术家 {link} 软链接",
                        onClick = handlers.onEditShareTemplate,
                    ),
                ),
            ),
            SettingsGroup(
                header = "桌面歌词外观（关闭桌面歌词时不生效）",
                rows = listOf(
                    SettingsToggleRow(
                        title = "锁定位置",
                        subtitle = "开启后禁止拖动悬浮窗，防误触",
                        checked = state.desktopLyricLock,
                        onToggle = handlers.onDesktopLyricLock,
                    ),
                    SettingsSliderRow(
                        title = "字号",
                        subtitle = null,
                        value = state.desktopLyricFontSize,
                        range = 10..36,
                        unit = "sp",
                        onChange = handlers.onDesktopLyricFontSize,
                    ),
                    SettingsSelectRow(
                        title = "对齐",
                        subtitle = "悬浮窗文本 + 位置",
                        options = listOf(
                            SettingsOption("0", "左"),
                            SettingsOption("1", "中"),
                            SettingsOption("2", "右"),
                        ),
                        currentId = state.desktopLyricAlignment.toString(),
                        onPick = handlers.onDesktopLyricAlignment,
                    ),
                    SettingsSliderRow(
                        title = "透明度",
                        subtitle = null,
                        value = state.desktopLyricOpacity,
                        range = 10..100,
                        unit = "%",
                        onChange = handlers.onDesktopLyricOpacity,
                    ),
                    SettingsSelectRow(
                        title = "已播放行颜色",
                        subtitle = "当前高亮行",
                        options = COLOR_PRESETS_PLAYED.map { SettingsOption(it.id, it.label) },
                        currentId = state.desktopLyricPlayedColor,
                        onPick = handlers.onDesktopLyricPlayedColor,
                    ),
                    SettingsSelectRow(
                        title = "未播放行颜色",
                        subtitle = "翻译行 / 占位",
                        options = COLOR_PRESETS_PENDING.map { SettingsOption(it.id, it.label) },
                        currentId = state.desktopLyricPendingColor,
                        onPick = handlers.onDesktopLyricPendingColor,
                    ),
                ),
            ),
        ),
    )

    val noise = SettingsCategory(
        key = SettingsCategoryKeys.NOISE,
        name = "白噪音",
        summary = "${NoiseCatalog.presetScenes.size} 场景 · 独立音轨 · 与歌曲互不打断",
        icon = LxIcons.Noise,
        groups = listOf(
            SettingsGroup(
                header = "播放",
                rows = listOf(
                    SettingsToggleRow(
                        title = "与歌曲同时播放",
                        subtitle = "关掉时，一放歌白噪音就暂停",
                        checked = state.noiseWithMusic,
                        onToggle = handlers.onNoiseWithMusic,
                    ),
                    SettingsSelectRow(
                        title = "默认场景",
                        subtitle = "进入白噪音页时预置的混音",
                        options = noiseSceneOptions(),
                        currentId = state.noiseDefaultSceneId ?: "last",
                        onPick = { id -> handlers.onNoiseDefaultScene(id.takeUnless { it == "last" }) },
                    ),
                    SettingsSelectRow(
                        title = "睡眠定时",
                        subtitle = "只管白噪音，与播放页定时器独立",
                        options = noiseTimerOptions(state.noiseTimerId),
                        currentId = state.noiseTimerId,
                        onPick = handlers.onNoiseSleepTimer,
                    ),
                ),
            ),
            SettingsGroup(
                header = "引擎（17 全篇）",
                rows = listOf(
                    SettingsInfoRow("三段式循环", "30s 素材拼成不重复的长循环"),
                    SettingsInfoRow("平方音量曲线", "低音量更细腻，符合听感"),
                    SettingsInfoRow("切场景 1.5s 淡变", "移出图层淡出 1.5s · 新图层淡入 5s"),
                ),
            ),
        ),
    )

    val down = SettingsCategory(
        key = SettingsCategoryKeys.DOWN,
        name = "下载",
        summary = "默认音质 ${qualityLabel(quality)} · 同时 ${state.concurrency} 个",
        icon = LxIcons.Download,
        groups = listOf(
            SettingsGroup(
                header = "下载",
                rows = listOf(
                    SettingsSelectRow(
                        title = "默认音质",
                        subtitle = "超出可用时自动降级",
                        options = qualityOptions(),
                        currentId = quality.value,
                        onPick = { id -> SELECTABLE_QUALITIES.firstOrNull { it.value == id }?.let(handlers.onQuality) },
                    ),
                    SettingsSelectRow(
                        title = "同时下载数",
                        subtitle = "超出的任务留在排队中",
                        options = (1..5).map { SettingsOption(it.toString(), it.toString()) },
                        currentId = state.concurrency.toString(),
                        onPick = { id -> id.toIntOrNull()?.let(handlers.onConcurrency) },
                    ),
                    SettingsSelectRow(
                        title = "失败自动重试",
                        subtitle = "取流失败或网络中断时自动重试的次数",
                        options = (0..3).map {
                            SettingsOption(it.toString(), if (it == 0) "不重试" else "$it 次")
                        },
                        currentId = state.retryLimit.toString(),
                        onPick = { id -> id.toIntOrNull()?.let(handlers.onRetryLimit) },
                    ),
                    SettingsToggleRow(
                        title = "完成后自动入本地库",
                        subtitle = "下完即可在「本地音乐」里看到，不用等下次扫描",
                        checked = state.autoImport,
                        onToggle = handlers.onAutoImport,
                    ),
                    SettingsToggleRow(
                        title = "仅 Wi-Fi 下载",
                        subtitle = "移动网络下不开始新任务（已开始的会跑完）",
                        checked = state.wifiOnlyDownload,
                        onToggle = handlers.onWifiOnlyDownload,
                    ),
                ),
            ),
            SettingsGroup(
                header = "存储",
                rows = listOf(
                    SettingsInfoRow("本地音乐", "${state.localCount} 首 · ${state.localDirNames.size} 个目录"),
                    SettingsInfoRow("清空已完成记录", "只删记录，不删文件 · 在下载管理页执行"),
                ),
            ),
        ),
    )

    val list = SettingsCategory(
        key = SettingsCategoryKeys.LIST,
        name = "歌单与列表",
        summary = "双列网格 · 视图切换在歌单页",
        icon = LxIcons.ViewList,
        groups = listOf(
            SettingsGroup(
                header = "展示",
                rows = listOf(
                    SettingsInfoRow("歌单页默认视图", "双列网格 / 单列列表 · 在歌单页顶部切换"),
                    SettingsToggleRow(
                        title = "歌曲行显示封面",
                        subtitle = "本地音乐页默认显示封面；页内仍可临时切换",
                        checked = state.listShowCover,
                        onToggle = handlers.onShowCover,
                    ),
                ),
            ),
            SettingsGroup(
                header = "歌曲行操作（7 项）",
                rows = listOf(
                    SettingsInfoRow("重命名歌曲 / 作者", "改作者会同步影响统计归并"),
                    SettingsInfoRow("换源", "多源聚合时可一键换"),
                    SettingsInfoRow("添加到歌单 / 移除 / 分享", "其余 4 项 · 都在歌曲操作弹层里"),
                ),
            ),
        ),
    )

    // —— 文案自定义（文案组全量自定义：预览 → 组列表 → 时段 → 展示）——
    // 展示回落：DataStore 首读前几帧组列表为空，先用内置 6 组出摘要，不闪「0 组」
    val verseGroups = state.verseGroups.ifEmpty { builtinVerseGroups() }
    val versePool = VersePicker.pool(
        LocalTime.now().hour,
        state.verseGroups,
        state.enabledVerseGroupIds,
        state.enabledVerseSlots,
    )
    val versePreview = versePool[FlowRandom.nextIndex(versePool.size, previous = -1)].text
    val enabledGroupCount = verseGroups.count {
        state.enabledVerseGroupIds.isEmpty() || it.id in state.enabledVerseGroupIds
    }
    val totalVerseCount = verseGroups.sumOf { it.verses.size }
    val verse = SettingsCategory(
        key = SettingsCategoryKeys.VERSE,
        name = "文案自定义",
        summary = "$enabledGroupCount/${verseGroups.size} 组启用 · 共 $totalVerseCount 条 · 每 ${state.verseIntervalSec} 秒轮换",
        icon = LxIcons.Quote,
        groups = buildList {
            add(
                SettingsGroup(
                    header = "当前轮换池",
                    rows = listOf(
                        SettingsInfoRow(
                            title = "此刻预览",
                            subtitle = "$versePreview\n轮换池 ${versePool.size} 条 · 共 ${verseGroups.size} 组 $totalVerseCount 条",
                        ),
                    ),
                ),
            )
            add(
                SettingsGroup(
                    header = "文案组（${verseGroups.size}）",
                    rows = verseGroups.map { group ->
                        SettingsVerseGroupRow(
                            title = group.name,
                            subtitle = "${group.verses.size} 条 · " + if (group.builtin) "内置" else "自建",
                            groupId = group.id,
                            checked = state.enabledVerseGroupIds.isEmpty() ||
                                group.id in state.enabledVerseGroupIds,
                            onToggle = { on -> handlers.onVerseGroupToggle(group.id, on) },
                            onClick = { handlers.onOpenVerseGroup(group.id) },
                        )
                    } + SettingsActionRow(
                        title = "添加文案组",
                        subtitle = "自建组启用后始终参与轮换，不受时段收窄影响（组数上限 $VERSE_GROUP_LIMIT）",
                        onClick = handlers.onAddVerseGroupClick,
                    ),
                ),
            )
            add(
                SettingsGroup(
                    header = "时段分组 · 此刻用哪几类",
                    rows = listOf(
                        Triple(DaySlot.NIGHT, "夜深", "0–5"),
                        Triple(DaySlot.MORNING, "清晨", "5–11"),
                        Triple(DaySlot.DAY, "日间", "11–18"),
                        Triple(DaySlot.EVENING, "夜晚", "18–23"),
                    ).map { (slot, label, range) ->
                        val preferred = VersePicker.SLOT_CATEGORIES.getValue(slot).joinToString(" / ")
                        SettingsToggleRow(
                            title = label,
                            subtitle = "$range 时 · 偏好内置组：$preferred",
                            checked = state.enabledVerseSlots.isEmpty() ||
                                slot.name in state.enabledVerseSlots,
                            onToggle = { on -> handlers.onVerseSlot(slot, on) },
                        )
                    },
                ),
            )
            add(
                SettingsGroup(
                    header = "展示",
                    rows = listOf(
                        SettingsSelectRow(
                            title = "轮换间隔",
                            subtitle = "首页 / 歌单 / 统计三处共用",
                            options = listOf(5, 9, 12, 15, 20).map { SettingsOption(it.toString(), "$it 秒") },
                            currentId = state.verseIntervalSec.toString(),
                            onPick = { id -> id.toIntOrNull()?.let(handlers.onVerseInterval) },
                        ),
                        SettingsInfoRow(
                            title = "轮换规则",
                            subtitle = "轮换池 = 当前时段偏好的内置组 ∩ 已启用的组 + 已启用的自建组；" +
                                "组全都关掉时自动回落到全部组的全部文案，不出现空屏",
                        ),
                    ),
                ),
            )
        },
    )

    val data = SettingsCategory(
        key = SettingsCategoryKeys.DATA,
        name = "数据与备份",
        summary = "全量备份 / 新手教程 · 导入与扫描",
        icon = LxIcons.Merge,
        // 两个真实导入入口由详情页渲染（要文件选择器与进度回执），目录里不重复占一行
        groups = emptyList(),
    )

    val about = SettingsCategory(
        key = SettingsCategoryKeys.ABOUT,
        name = "关于",
        summary = "版本 ${BuildConfig.VERSION_NAME} · 开源许可",
        icon = LxIcons.Info,
        groups = listOf(
            SettingsGroup(
                header = null,
                rows = listOf(
                    SettingsInfoRow("版本", "${BuildConfig.VERSION_NAME}（Layer 1 · 平面导航）"),
                    SettingsActionRow(
                        title = "构建信息与合规声明",
                        subtitle = "LX-Music 外置音源协议 · GPL-3.0",
                        onClick = handlers.onOpenAbout,
                    ),
                    SettingsInfoRow("设计规格", "12-UI规格总览 ~ 17-白噪音与音效"),
                ),
            ),
        ),
    )

    return listOf(source, local, look, play, noise, down, list, verse, data, about)
}