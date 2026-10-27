package com.lxpro.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song

/**
 * 歌曲操作项（`mockup_playlist.html` 的 7 项 + 播放页/搜索页的裁剪版本）。
 *
 * 三处共用同一套动作，按上下文裁剪：
 * - 歌单详情：全部 8 项
 * - 搜索结果 / 播放页：去掉「移除」「移动位置」（没有歌单上下文），见 mockup_search `data-more` 的说明
 */
enum class LXSongAction {
    /** 播放页专用：立体歌词开关（原来是歌词页头上的一颗按钮，2026-10-03 用户要求收进「更多」） */
    LYRIC_3D,
    /** 播放页专用：歌词参数面板 */
    LYRIC_SETTINGS,
    /** 播放页专用：歌曲切换淡变时长（17 §6.1：即切/1/2/3/4/5/6/8 秒） */
    CROSSFADE,

    /** 播放页专用：倍速播放（2026-10-27：0.5x ~ 2.0x 六档） */
    SPEED,

    /** 播放页专用：定时关闭（17 §7：到点先淡出 3 秒再停） */
    TIMER,
    /** 播放页专用：跳转白噪音页（17 §4；2026-10-20 用户要求加进「更多」菜单） */
    NOISE,
    /** 播放页专用：跳转均衡器页（12 §4：从播放页「更多」与设置两处可达） */
    EQUALIZER,
    DOWNLOAD,
    ADD_TO_PLAYLIST,
    SWAP_SOURCE,
    RENAME_SONG,
    RENAME_SINGER,
    MOVE_POSITION,
    /** 歌曲标签（2026-10-26 第 8 项）：软件自贴 + 用户文字标签，点开由 app 层弹标签页 */
    TAGS,
    SHARE,
    REMOVE_FROM_PLAYLIST,
}

/** 弹层里的一个可选目标（歌单 / 音源），id 由调用方解释 */
data class LXSheetOption(val id: String, val label: String, val hint: String? = null)

/**
 * 「换源」面板状态（04 · 换源，对齐 LX MusicToggleModal）。
 * 纯 UI 载体：搜索由调用方 ViewModel 发起（面板打开时回调 [LXSongActionSheet] 的
 * `onOpenSwapPanel`），结果填进这里驱动界面。
 */
data class SwapUiState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** sourceId → 该源搜到的同名候选（原始搜索结果，不做匹配过滤，由用户自己挑） */
    val candidates: Map<String, List<Song>> = emptyMap(),
    /** 已钉住的换源目标（null = 未换源，播原源） */
    val pinned: Song? = null,
)

/** 音质档位的展示名（原型用「无损 / Hi-Res」这类短语，不是裸的 flac/hires） */
object LXQuality {
    fun label(quality: Quality): String = when (quality) {
        Quality.K128 -> "128k"
        Quality.K320 -> "320k"
        Quality.FLAC -> "无损"
        Quality.HIRES -> "Hi-Res"
        Quality.ATMOS -> "全景声"
        Quality.ATMOS_PLUS -> "全景声+"
        Quality.MASTER -> "母带"
    }
}

private enum class SheetPanel { MENU, DOWNLOAD, ADD, SWAP, RENAME_SONG, RENAME_SINGER, MOVE, CROSSFADE, SPEED, TIMER }

/**
 * 歌曲定时关闭的目标选项（17 §7）。
 *
 * 放在设计层是为了不让 designsystem 反向依赖 core:database 的
 * `TimerStateEntity.TargetKind` —— 由 app 层做双向映射。
 */
enum class LXSongTimerTarget(val label: String) {
    OFF("不开启"),
    MIN_15("15 分钟后"),
    MIN_30("30 分钟后"),
    MIN_45("45 分钟后"),
    MIN_60("60 分钟后"),
    MIN_90("90 分钟后"),
    CURRENT_TRACK("播完当前歌曲"),
    HOUR_7("到早上 7:00"),
    CUSTOM("自定义…"),
}

/** 定时菜单项上显示的当前状态短标签 */
fun LXSongTimerTarget.shortLabel(customMinutes: Int? = null): String = when (this) {
    LXSongTimerTarget.OFF -> "关"
    LXSongTimerTarget.MIN_15 -> "15 分钟"
    LXSongTimerTarget.MIN_30 -> "30 分钟"
    LXSongTimerTarget.MIN_45 -> "45 分钟"
    LXSongTimerTarget.MIN_60 -> "60 分钟"
    LXSongTimerTarget.MIN_90 -> "90 分钟"
    LXSongTimerTarget.CURRENT_TRACK -> "播完当前"
    LXSongTimerTarget.HOUR_7 -> "到 7:00"
    LXSongTimerTarget.CUSTOM -> customMinutes?.let { "$it 分钟" } ?: "自定义"
}

/**
 * 歌曲操作弹层（原型 `.sheet`）——**纯 UI**，动作通过回调交给各页面的 ViewModel。
 *
 * 「下载」的落地在「下载管理」里程碑（这里只按音源能力列出可选音质），
 * 「换源」需要跨源同名检索，也在后续里程碑 —— 两项都如实告知，不做假成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LXSongActionSheet(
    song: Song,
    sourceLabel: String,
    actions: List<LXSongAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    playlistOptions: List<LXSheetOption> = emptyList(),
    moveRange: Int? = null,
    onRemoveFromPlaylist: () -> Unit = {},
    onAddToPlaylist: (String) -> Unit = {},
    onCreatePlaylistAndAdd: (String) -> Unit = {},
    /** 「换源」面板状态；面板每次打开时回调 [onOpenSwapPanel] 触发跨源搜索 */
    swapState: SwapUiState = SwapUiState(),
    /** sourceId → 展示名（候选行与源 Tab 的源标记） */
    swapSourceNames: Map<String, String> = emptyMap(),
    onOpenSwapPanel: () -> Unit = {},
    /** 钉住选中的换源目标（面板保持打开，对照卡即时更新，对齐 LX） */
    onPickSwap: (Song) -> Unit = {},
    /** 取消换源，恢复原源 */
    onClearSwap: () -> Unit = {},
    /** 试听：正在预览的候选 key（"source:id"）；null = 未在试听 */
    swapPreviewKey: String? = null,
    /** 试听取流/起播中（key 命中的候选行显示转圈） */
    swapPreviewLoading: Boolean = false,
    /** 点候选行的试听按钮（同一候选再点 = 停止试听） */
    onPreviewToggle: (Song) -> Unit = {},
    onDownload: (Quality) -> Unit = {},
    onRenameSong: (String) -> Unit = {},
    onRenameSinger: (String) -> Unit = {},
    onMovePosition: (Int) -> Unit = {},
    /** 打开歌曲标签页（第 8 项：软件自贴标签 + 用户文字标签） */
    onOpenTags: () -> Unit = {},
    /** 这首歌已有的用户标签数（trailing 展示；null=不显示） */
    tagCount: Int? = null,
    onShare: () -> Unit = {},
    /** 立体歌词当前是否开启（只有播放页会传） */
    lyric3dOn: Boolean? = null,
    onToggleLyric3d: () -> Unit = {},
    onOpenLyricSettings: () -> Unit = {},
    /** 当前切换淡变时长（只有播放页会传；null=不显示该项） */
    crossfadeMs: Long? = null,
    onSetCrossfadeMs: (Long) -> Unit = {},
    /** 当前倍速（只有播放页会传；null=不显示该项；默认 1.0f） */
    playbackSpeed: Float? = null,
    onSetPlaybackSpeed: (Float) -> Unit = {},
    /** 当前歌曲定时目标（只有播放页会传；OFF=未开启） */
    songTimerTarget: LXSongTimerTarget = LXSongTimerTarget.OFF,
    /** 自定义分钟数（target=CUSTOM 时显示）；null=无自定义 */
    songTimerCustomMinutes: Int? = null,
    /** 「同时停止白噪音」开关初值（17 §7 默认开；有活动定时时取定时自带值） */
    songTimerStopNoise: Boolean = true,
    /** 「等当前歌曲播完再停止」开关初值（问题2；有活动定时时取定时自带值） */
    songTimerFinishSongFirst: Boolean = false,
    /** 设定定时；target=OFF 表示取消；customMinutes 仅在 CUSTOM 时有值 */
    onSetSongTimer: (LXSongTimerTarget, Boolean, Int?, Boolean) -> Unit = { _, _, _, _ -> },
    /** 白噪音当前是否在响（只有播放页会传；null=不显示「白噪音」项） */
    noiseActive: Boolean? = null,
    /** 跳转白噪音页（17 §4；只有播放页会传） */
    onOpenNoise: () -> Unit = {},
    /** 跳转均衡器页（12 §4；只有播放页会传） */
    onOpenEqualizer: () -> Unit = {},
) {
    val colors = LXTheme.colors
    var panel by remember { mutableStateOf(SheetPanel.MENU) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLocal = song.source.value == LOCAL_SOURCE_ID.value

    /** 立即收起并执行——这些操作都会改变歌单内容或需要外部界面 */
    fun finish(action: () -> Unit) {
        action()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = colors.surface,
        scrimColor = colors.ink1.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 9.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.hairline),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (panel) {
                SheetPanel.MENU -> {
                    SheetTitle(
                        title = song.name,
                        subtitle = listOf(song.singer, sourceLabel)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = SHEET_BODY_MAX)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 「歌词」组：只有播放页会带上这两项（立体档开关 / 参数面板）
                        if (LXSongAction.LYRIC_3D in actions || LXSongAction.LYRIC_SETTINGS in actions) {
                            SheetGroupLabel("歌词")
                        }
                        if (LXSongAction.LYRIC_3D in actions) {
                            SheetAction(
                                icon = LxIcons.CoverBox,
                                label = "立体歌词",
                                hint = "整块歌词板倾斜入深",
                                trailing = if (lyric3dOn == true) "开" else "关",
                                onClick = { finish(onToggleLyric3d) },
                            )
                        }
                        if (LXSongAction.LYRIC_SETTINGS in actions) {
                            SheetAction(
                                icon = LxIcons.LyricLines,
                                label = "歌词参数",
                                hint = "对齐 / 字号 / 景深 / 动效",
                                onClick = { finish(onOpenLyricSettings) },
                            )
                        }
                        if (LXSongAction.LYRIC_3D in actions || LXSongAction.LYRIC_SETTINGS in actions) {
                            SheetSeparator()
                        }
                        if (LXSongAction.CROSSFADE in actions) {
                            SheetAction(
                                icon = LxIcons.Repeat,
                                label = "切换淡变",
                                hint = "两首歌渐弱渐强的重叠时长",
                                trailing = when (crossfadeMs) {
                                    null -> "4 秒"
                                    0L -> "即切"
                                    else -> "${crossfadeMs / 1000} 秒"
                                },
                                onClick = { panel = SheetPanel.CROSSFADE },
                            )
                        }
                        if (LXSongAction.SPEED in actions) {
                            SheetAction(
                                icon = LxIcons.PlaySpeed,
                                label = "倍速播放",
                                hint = "0.5x ~ 2.0x 六档",
                                trailing = when (playbackSpeed) {
                                    null -> null
                                    1.0f -> "1.0x"
                                    else -> "${playbackSpeed}x"
                                },
                                onClick = { panel = SheetPanel.SPEED },
                            )
                        }
                        if (LXSongAction.TIMER in actions) {
                            SheetAction(
                                icon = LxIcons.Clock,
                                label = "定时关闭",
                                hint = "到点先淡出 3 秒再停",
                                trailing = songTimerTarget.shortLabel(),
                                onClick = { panel = SheetPanel.TIMER },
                            )
                        }
                        // 白噪音（17 §4：白噪音是第 4 个一级 Tab，也是与歌曲平行的独立音轨）
                        // —— 播放页在这里给一个直达入口，不再往页面里塞白噪音控件
                        if (LXSongAction.NOISE in actions) {
                            SheetAction(
                                icon = LxIcons.Noise,
                                label = "白噪音",
                                hint = "雨声 / 海浪 / 壁炉… 混音助眠",
                                trailing = if (noiseActive == true) "在响" else "关",
                                onClick = { finish(onOpenNoise) },
                            )
                        }
                        // 均衡器（12 §4：播放页「更多」与设置两处可达）
                        if (LXSongAction.EQUALIZER in actions) {
                            SheetAction(
                                icon = LxIcons.Equalizer,
                                label = "均衡器",
                                hint = "耳机校正曲线 · 只对歌曲生效",
                                onClick = { finish(onOpenEqualizer) },
                            )
                        }

                        if (LXSongAction.DOWNLOAD in actions) {
                            SheetAction(
                                icon = LxIcons.Download,
                                label = "下载",
                                hint = "按音源可选音质 · 128k → Hi-Res",
                                trailing = if (isLocal) {
                                    "已在本地"
                                } else {
                                    song.qualitys.maxByOrNull { it.ordinal }?.let(LXQuality::label)
                                },
                                onClick = { panel = SheetPanel.DOWNLOAD },
                            )
                        }
                        if (LXSongAction.ADD_TO_PLAYLIST in actions) {
                            SheetAction(
                                icon = LxIcons.Plus,
                                label = "添加到",
                                hint = "添加到其它歌单",
                                onClick = { panel = SheetPanel.ADD },
                            )
                        }
                        if (LXSongAction.SWAP_SOURCE in actions) {
                            SheetAction(
                                icon = LxIcons.Swap,
                                label = "换源",
                                hint = "当前 $sourceLabel · 从其它源找同首歌",
                                trailing = swapState.pinned?.let {
                                    "已换 ${swapSourceNames[it.source.value] ?: it.source.value}"
                                },
                                onClick = {
                                    panel = SheetPanel.SWAP
                                    onOpenSwapPanel()
                                },
                            )
                        }

                        val editing = actions.filter {
                            it == LXSongAction.RENAME_SONG ||
                                it == LXSongAction.RENAME_SINGER ||
                                it == LXSongAction.MOVE_POSITION
                        }
                        if (editing.isNotEmpty()) {
                            SheetSeparator()
                            SheetGroupLabel("编辑")
                            if (LXSongAction.RENAME_SONG in actions) {
                                SheetAction(
                                    icon = LxIcons.Pencil,
                                    label = "重命名歌曲",
                                    hint = "只改本地显示，不改动音源",
                                    trailing = if (song.name.length > 12) "标题偏长" else null,
                                    onClick = { panel = SheetPanel.RENAME_SONG },
                                )
                            }
                            if (LXSongAction.RENAME_SINGER in actions) {
                                SheetAction(
                                    icon = LxIcons.Pencil,
                                    label = "重命名作者",
                                    hint = "改正后可修正听歌统计",
                                    onClick = { panel = SheetPanel.RENAME_SINGER },
                                )
                            }
                            if (LXSongAction.MOVE_POSITION in actions) {
                                SheetAction(
                                    icon = LxIcons.MoveTo,
                                    label = "移动位置",
                                    hint = "输入序号精确排序",
                                    onClick = { panel = SheetPanel.MOVE },
                                )
                            }
                        }

                        SheetSeparator()
                        if (LXSongAction.TAGS in actions) {
                            SheetAction(
                                icon = LxIcons.Tag,
                                label = "标签",
                                hint = "软件记录 · 我的文字标签",
                                trailing = tagCount?.takeIf { it > 0 }?.let { "$it 个" },
                                onClick = { finish(onOpenTags) },
                            )
                        }
                        if (LXSongAction.SHARE in actions) {
                            SheetAction(
                                icon = LxIcons.Share,
                                label = "分享",
                                hint = if (isLocal) "直接分享音频文件" else "复制软链接，搜索框粘上即播",
                                onClick = { finish(onShare) },
                            )
                        }
                        if (LXSongAction.REMOVE_FROM_PLAYLIST in actions) {
                            SheetAction(
                                icon = LxIcons.Trash,
                                label = "从歌单移除",
                                tint = colors.danger,
                                onClick = { finish(onRemoveFromPlaylist) },
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                SheetPanel.DOWNLOAD -> {
                    SheetTitle(
                        title = "下载 · ${song.name}",
                        subtitle = "音源 $sourceLabel · 可选音质由该音源能力决定",
                    )
                    QualityPanel(song = song, onPick = { finish { onDownload(it) } })
                }

                SheetPanel.ADD -> {
                    SheetTitle(title = "添加到", subtitle = "${song.name} · 选一个歌单放进去")
                    AddPanel(
                        playlists = playlistOptions,
                        onPick = { finish { onAddToPlaylist(it) } },
                        onCreate = { finish { onCreatePlaylistAndAdd(it) } },
                    )
                }

                SheetPanel.SWAP -> {
                    SheetTitle(
                        title = "换源 · ${song.name}",
                        subtitle = "原 ${song.singer} · $sourceLabel",
                    )
                    SwapPanel(
                        song = song,
                        state = swapState,
                        sourceLabel = sourceLabel,
                        sourceNames = swapSourceNames,
                        onPick = onPickSwap,
                        onClear = onClearSwap,
                        onRetry = onOpenSwapPanel,
                        previewKey = swapPreviewKey,
                        previewLoading = swapPreviewLoading,
                        onPreviewToggle = onPreviewToggle,
                    )
                }

                SheetPanel.RENAME_SONG -> {
                    SheetTitle(title = "重命名歌曲", subtitle = "只改本地显示，不改动音源")
                    InputPanel(
                        label = "歌曲名",
                        initial = song.name,
                        hint = "搬运号的标题常是引流长串，改成干净的歌名即可",
                        confirmLabel = "保存",
                        onConfirm = { finish { onRenameSong(it) } },
                        onBack = { panel = SheetPanel.MENU },
                    )
                }

                SheetPanel.RENAME_SINGER -> {
                    SheetTitle(title = "重命名作者", subtitle = "改正后，听歌统计按新名字归并")
                    InputPanel(
                        label = "作者名",
                        initial = song.singer,
                        hint = "例：把搬运号的「熊猫先生」改回真实作者「DJ Okawari」",
                        confirmLabel = "保存",
                        onConfirm = { finish { onRenameSinger(it) } },
                        onBack = { panel = SheetPanel.MENU },
                    )
                }

                SheetPanel.MOVE -> {
                    SheetTitle(
                        title = "移动位置",
                        subtitle = buildString {
                            append(song.name)
                            moveRange?.let { append(" · 当前共 ").append(it).append(" 首") }
                        },
                    )
                    MovePanel(
                        onConfirm = { finish { onMovePosition(it) } },
                        onBack = { panel = SheetPanel.MENU },
                    )
                }

                SheetPanel.CROSSFADE -> {
                    SheetTitle(
                        title = "切换淡变",
                        subtitle = "上一首渐弱、下一首渐强的重叠时长",
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = SHEET_BODY_MAX)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) {
                        listOf(
                            0L to "即切（无淡变）",
                            1_000L to "1 秒",
                            2_000L to "2 秒",
                            3_000L to "3 秒",
                            4_000L to "4 秒（推荐）",
                            5_000L to "5 秒",
                            6_000L to "6 秒",
                            8_000L to "8 秒（最长）",
                        ).forEach { (ms, label) ->
                            SheetAction(
                                icon = LxIcons.Repeat,
                                label = label,
                                trailing = if (crossfadeMs == ms) "当前" else null,
                                onClick = { finish { onSetCrossfadeMs(ms) } },
                            )
                        }
                    }
                }

                SheetPanel.SPEED -> {
                    SheetTitle(
                        title = "倍速播放",
                        subtitle = "慢放重温、快听精华 · 全局生效 · 支持 0.5–2.0 自定义",
                    )
                    var showCustomDialog by remember { mutableStateOf(false) }
                    // 预设六档；当前值为自定义值时，「自定义…」行显示它
                    val presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    Column(
                        modifier = Modifier
                            .heightIn(max = SHEET_BODY_MAX)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) {
                        listOf(
                            0.5f to "0.5x（慢放）",
                            0.75f to "0.75x",
                            1.0f to "1.0x（正常）",
                            1.25f to "1.25x",
                            1.5f to "1.5x",
                            2.0f to "2.0x（快进）",
                        ).forEach { (speed, label) ->
                            SheetAction(
                                icon = LxIcons.PlaySpeed,
                                label = label,
                                trailing = if (playbackSpeed == speed) "当前" else null,
                                onClick = { finish { onSetPlaybackSpeed(speed) } },
                            )
                        }
                        SheetAction(
                            icon = LxIcons.PlaySpeed,
                            label = "自定义…",
                            trailing = playbackSpeed
                                ?.takeIf { it !in presets }
                                ?.let { "${it}x（当前）" },
                            onClick = { showCustomDialog = true },
                        )
                    }

                    if (showCustomDialog) {
                        LXCustomSpeedDialog(
                            initialSpeed = playbackSpeed ?: 1.0f,
                            onConfirm = { speed ->
                                showCustomDialog = false
                                finish { onSetPlaybackSpeed(speed) }
                            },
                            onDismiss = { showCustomDialog = false },
                        )
                    }
                }

                SheetPanel.TIMER -> {
                    SheetTitle(
                        title = "定时关闭",
                        subtitle = "到点先淡出 3 秒再停 · 提示条上可一键取消",
                    )
                    // 开关是「设定时」的附带参数：先拨开关再选目标，选目标时一并提交
                    var stopNoise by remember(songTimerStopNoise) { mutableStateOf(songTimerStopNoise) }
                    var finishSongFirst by remember(songTimerFinishSongFirst) { mutableStateOf(songTimerFinishSongFirst) }
                    var showCustomDialog by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .heightIn(max = SHEET_BODY_MAX)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) {
                        LXSongTimerTarget.entries.filter { it != LXSongTimerTarget.CUSTOM }.forEach { target ->
                            SheetAction(
                                icon = LxIcons.Clock,
                                label = target.label,
                                trailing = if (songTimerTarget == target) "当前" else null,
                                onClick = { finish { onSetSongTimer(target, stopNoise, null, finishSongFirst) } },
                            )
                        }
                        // 自定义分钟数（问题1）
                        SheetAction(
                            icon = LxIcons.Clock,
                            label = "自定义…",
                            trailing = when {
                                songTimerTarget == LXSongTimerTarget.CUSTOM -> songTimerCustomMinutes?.let { "$it 分钟" }
                                else -> null
                            },
                            onClick = { showCustomDialog = true },
                        )
                        SheetSeparator()
                        // 「同时停止白噪音」（17 §7：默认开——定时关闭通常意味着要睡了）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "同时停止白噪音",
                                    style = LXType.labelLarge,
                                    color = colors.ink1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "到点歌曲淡出后，白噪音也一起停下",
                                    style = LXType.labelSmall,
                                    color = colors.ink3,
                                )
                            }
                            Switch(
                                checked = stopNoise,
                                onCheckedChange = { stopNoise = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = colors.accent,
                                    checkedThumbColor = colors.bg,
                                ),
                            )
                        }
                        // 「等当前歌曲播完再停止」（问题2）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "等当前歌曲播完再停止",
                                    style = LXType.labelLarge,
                                    color = colors.ink1,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "时间到后不立刻停，等歌曲自然结束再停止",
                                    style = LXType.labelSmall,
                                    color = colors.ink3,
                                )
                            }
                            Switch(
                                checked = finishSongFirst,
                                onCheckedChange = { finishSongFirst = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = colors.accent,
                                    checkedThumbColor = colors.bg,
                                ),
                            )
                        }
                    }

                    // 自定义分钟数输入对话框
                    if (showCustomDialog) {
                        LXCustomMinutesDialog(
                            initialMinutes = songTimerCustomMinutes ?: 30,
                            onConfirm = { mins ->
                                showCustomDialog = false
                                finish { onSetSongTimer(LXSongTimerTarget.CUSTOM, stopNoise, mins, finishSongFirst) }
                            },
                            onDismiss = { showCustomDialog = false },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 子面板 ───────────────────────────

@Composable
private fun QualityPanel(song: Song, onPick: (Quality) -> Unit) {
    val colors = LXTheme.colors
    // 音源没声明档位时至少给出 320k，避免出现空面板
    val qualities = song.qualitys.ifEmpty { listOf(Quality.K320) }

    Column(
        modifier = Modifier
            .heightIn(max = SHEET_BODY_MAX)
            .verticalScroll(rememberScrollState()),
    ) {
        qualities.forEach { quality ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(quality) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(colors.inset),
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    text = LXQuality.label(quality),
                    style = LXType.labelLarge,
                    color = colors.ink1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = song.qualitySizes[quality.value]?.let(::formatSize).orEmpty(),
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
        }
        SheetGroupLabel("实际音质由音源能力决定 · 下载落地在「下载管理」里程碑")
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddPanel(
    playlists: List<LXSheetOption>,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    val colors = LXTheme.colors
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        InputPanel(
            label = "新歌单名",
            initial = "",
            hint = "留空则自动命名",
            confirmLabel = "保存",
            allowEmpty = true,
            onConfirm = onCreate,
            onBack = { creating = false },
        )
    } else {
        Column(
            modifier = Modifier
                .heightIn(max = SHEET_BODY_MAX)
                .verticalScroll(rememberScrollState()),
        ) {
            if (playlists.isEmpty()) {
                Text(
                    text = "还没有其它歌单",
                    style = LXType.bodyMedium,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                playlists.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option.id) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LxIcons.Playlist,
                            contentDescription = null,
                            tint = colors.ink2,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = option.label,
                            style = LXType.labelLarge,
                            color = colors.ink1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            SheetSeparator()
            SheetAction(
                icon = LxIcons.Plus,
                label = "新建歌单",
                hint = "建好之后直接放进去",
                onClick = { creating = true },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 换源面板（04 · 换源，对齐 LX MusicToggleModal）：
 * 顶部「原歌曲 → 已钉目标」对照卡（可取消换源）；
 * 中间按源分 Tab 的候选列表（原始搜索结果，点一条即钉住，面板不关闭）；
 * 加载中 / 失败重试 / 无结果三态。
 */
@Composable
private fun SwapPanel(
    song: Song,
    state: SwapUiState,
    sourceLabel: String,
    sourceNames: Map<String, String>,
    onPick: (Song) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    previewKey: String?,
    previewLoading: Boolean,
    onPreviewToggle: (Song) -> Unit,
) {
    val colors = LXTheme.colors
    val tabs = state.candidates.keys.toList()
    var tab by remember { mutableStateOf<String?>(null) }
    val activeTab = tab?.takeIf { it in state.candidates } ?: tabs.firstOrNull()

    Column(
        modifier = Modifier
            .heightIn(max = SHEET_BODY_MAX)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 对照卡：原歌曲 →（已钉住时）换源目标 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bg)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SwapSongLine(
                name = song.name,
                detail = listOfNotNull(
                    song.singer.ifBlank { null },
                    sourceLabel,
                    song.interval?.let(::formatSeconds),
                ).joinToString(" · "),
                detailAccent = false,
            )
            state.pinned?.let { pinned ->
                Text(
                    text = "↓",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 2.dp),
                )
                SwapSongLine(
                    name = pinned.name,
                    detail = listOfNotNull(
                        pinned.singer.ifBlank { null },
                        sourceNames[pinned.source.value] ?: pinned.source.value,
                        pinned.interval?.let(::formatSeconds),
                    ).joinToString(" · "),
                    detailAccent = true,
                )
            }
        }
        if (state.pinned != null) {
            TextButton(
                onClick = onClear,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .fillMaxWidth(),
            ) {
                Text(text = "取消换源（恢复原 $sourceLabel）", color = colors.accent, style = LXType.labelLarge)
            }
        }

        Spacer(Modifier.height(6.dp))

        when {
            tabs.isEmpty() && state.loading -> {
                Text(
                    text = "正在各源搜索同名歌曲…",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
            tabs.isEmpty() && state.failed -> {
                Text(
                    text = "搜索失败，点按重试",
                    style = LXType.labelLarge,
                    color = colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
            tabs.isEmpty() -> {
                Text(
                    text = "各源都没搜到这首歌",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
            else -> {
                // ── 源 Tab（横向滚动，附结果数）──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    tabs.forEach { sourceId ->
                        val selected = sourceId == activeTab
                        Text(
                            text = "${sourceNames[sourceId] ?: sourceId} ${state.candidates[sourceId]?.size ?: 0}",
                            style = LXType.labelLarge,
                            color = if (selected) colors.accent else colors.ink2,
                            modifier = Modifier
                                .clickable { tab = sourceId }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
                SheetSeparator()
                // ── 候选行：试听按钮 + 点一条即钉住 ──
                state.candidates[activeTab].orEmpty().forEach { candidate ->
                    val candidateKey = "${candidate.source.value}:${candidate.id}"
                    val previewing = previewKey == candidateKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate) }
                            .padding(start = 6.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 试听（2026-10-27 用户要求「换源应该能预览播放」）：
                        // 取流中显示转圈；播放中显示 ⏸；其余 ▶。点它不切源，只预览。
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onPreviewToggle(candidate) },
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                previewing && previewLoading -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent,
                                )
                                previewing -> Icon(
                                    imageVector = LxIcons.Pause,
                                    contentDescription = "停止试听",
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                else -> Icon(
                                    imageVector = LxIcons.Play,
                                    contentDescription = "试听",
                                    tint = colors.ink3,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.name,
                                style = LXType.labelLarge,
                                color = colors.ink1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(
                                    candidate.singer.ifBlank { null },
                                    candidate.albumName?.takeIf { it.isNotBlank() }?.let { "($it)" },
                                ).joinToString(" "),
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = candidate.interval?.let(::formatSeconds).orEmpty(),
                            style = LXType.labelSmall,
                            color = colors.ink3,
                        )
                    }
                }
                // 逐源流式上屏：还有源没回时尾部提示，不把已到的结果换成转圈
                if (state.loading) {
                    Text(
                        text = "正在搜其它平台…",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 对照卡里的一行歌曲（名 + 详情）；[detailAccent] 用于区分「目标」行 */
@Composable
private fun SwapSongLine(
    name: String,
    detail: String,
    detailAccent: Boolean,
) {
    val colors = LXTheme.colors
    Column {
        Text(
            text = name,
            style = LXType.labelLarge,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = LXType.labelSmall,
                color = if (detailAccent) colors.accent else colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatSeconds(total: Long): String = "%d:%02d".format(total / 60, total % 60)

@Composable
private fun InputPanel(
    label: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
    allowEmpty: Boolean = false,
) {
    val colors = LXTheme.colors
    var value by remember { mutableStateOf(initial) }
    val normalized = value.trim()
    val canConfirm = normalized.isNotEmpty() || allowEmpty

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = hint,
            style = LXType.labelSmall,
            color = colors.ink2,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LXDimens.radiusSmall))
                .background(colors.inset)
                .padding(horizontal = 13.dp, vertical = 11.dp),
        )
        Spacer(Modifier.height(15.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(text = label, style = LXType.labelSmall, color = colors.ink3) },
            singleLine = true,
            textStyle = LXType.bodyMedium,
            shape = RoundedCornerShape(LXDimens.radiusSmall),
            modifier = Modifier.fillMaxWidth(),
            colors = sheetFieldColors(),
        )
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TextButton(
                onClick = { if (canConfirm) onConfirm(normalized) },
                enabled = canConfirm,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = confirmLabel, style = LXType.labelLarge, color = colors.accent)
            }
            TextButton(onClick = onBack, modifier = Modifier.width(88.dp)) {
                Text(text = "返回", style = LXType.labelLarge, color = colors.ink2)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MovePanel(onConfirm: (Int) -> Unit, onBack: () -> Unit) {
    val colors = LXTheme.colors
    var value by remember { mutableStateOf("") }
    val target = value.trim().toIntOrNull()

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "序号从 1 开始；超出范围会被夹到首尾",
            style = LXType.labelSmall,
            color = colors.ink2,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LXDimens.radiusSmall))
                .background(colors.inset)
                .padding(horizontal = 13.dp, vertical = 11.dp),
        )
        Spacer(Modifier.height(15.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { input -> value = input.filter { it.isDigit() }.take(4) },
            label = { Text(text = "序号", style = LXType.labelSmall, color = colors.ink3) },
            singleLine = true,
            textStyle = LXType.bodyMedium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(LXDimens.radiusSmall),
            modifier = Modifier.fillMaxWidth(),
            colors = sheetFieldColors(),
        )
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TextButton(
                onClick = { if (target != null && target > 0) onConfirm(target) },
                enabled = target != null && target > 0,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "移动", style = LXType.labelLarge, color = colors.accent)
            }
            TextButton(onClick = onBack, modifier = Modifier.width(88.dp)) {
                Text(text = "返回", style = LXType.labelLarge, color = colors.ink2)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ─────────────────────────── 通用零件 ───────────────────────────

@Composable
private fun sheetFieldColors() = LXTheme.colors.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.ink1,
        unfocusedTextColor = colors.ink1,
        focusedContainerColor = colors.bg2,
        unfocusedContainerColor = colors.bg2,
        cursorColor = colors.accent,
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.hairline,
    )
}

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Text(
            text = title,
            style = LXType.labelLarge,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    SheetSeparator()
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    hint: String? = null,
    trailing: String? = null,
    tint: Color? = null,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: colors.ink2,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = LXType.labelLarge, color = tint ?: colors.ink1)
            if (hint != null) {
                Spacer(Modifier.height(3.dp))
                Text(text = hint, style = LXType.labelSmall, color = colors.ink3)
            }
        }
        if (trailing != null) {
            Text(text = trailing, style = LXType.labelSmall, color = colors.ink3)
        }
    }
}

@Composable
private fun SheetGroupLabel(text: String) {
    Text(
        text = text,
        style = LXType.labelSmall,
        color = LXTheme.colors.ink3,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 5.dp),
    )
}

@Composable
private fun SheetSeparator() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LXTheme.colors.hairline))
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}

private val SHEET_BODY_MAX = 480.dp

/**
 * 自定义定时分钟数输入对话框（2026-10-20 用户要求：歌曲定时与白噪音定时都支持自定义）。
 *
 * 两个入口共用：歌曲定时（[LXSongActionSheet] 的 TIMER 面板）与白噪音睡眠定时（feature:noise）。
 * 跨模块复用，故为 public。
 *
 * @param hint 有效输入时显示的辅助说明（歌曲="到点后淡出 3 秒停"；白噪音="到点白噪音淡出 3 秒后暂停"）
 */
@Composable
fun LXCustomMinutesDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    hint: String = "到点后淡出 3 秒停",
) {
    val colors = LXTheme.colors
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    val minutes = text.toIntOrNull()?.takeIf { it in 1..720 }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = "自定义时间", style = LXType.titleSmall, color = colors.ink1)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("分钟", style = LXType.labelSmall) },
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = minutes == null,
                supportingText = {
                    Text(
                        text = if (minutes == null) "请输入 1–720 之间的整数" else hint,
                        style = LXType.labelSmall,
                        color = colors.ink3,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.hairline,
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = minutes != null,
                onClick = { minutes?.let { onConfirm(it) } },
            ) {
                Text("确定", color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.ink3)
            }
        },
    )
}

/**
 * 自定义倍速输入对话框（播放页「更多 → 倍速播放 → 自定义…」）。
 * 允许 0.5–2.0 之间任意值（最多两位小数），非法输入禁用确定键。
 */
@Composable
fun LXCustomSpeedDialog(
    initialSpeed: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    var text by remember { mutableStateOf(initialSpeed.toString().trimEnd('0').trimEnd('.')) }
    val speed = text.toFloatOrNull()?.takeIf { it in 0.5f..2.0f }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(text = "自定义倍速", style = LXType.titleSmall, color = colors.ink1)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    // 只允许数字 + 至多一个小数点；最长 4 字符（如 "1.75"）
                    val filtered = input.filter { c -> c.isDigit() || c == '.' }
                    if (filtered.count { it == '.' } <= 1 && filtered.length <= 4) text = filtered
                },
                label = { Text("倍速", style = LXType.labelSmall) },
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = speed == null,
                supportingText = {
                    Text(
                        text = if (speed == null) "请输入 0.5–2.0 之间的数值" else "全局生效，切歌不重置",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.hairline,
                ),
            )
        },
        confirmButton = {
            TextButton(
                enabled = speed != null,
                onClick = { speed?.let { onConfirm(it) } },
            ) {
                Text("确定", color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.ink3)
            }
        },
    )
}