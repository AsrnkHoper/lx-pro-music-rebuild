package com.lxpro.feature.noise

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXCustomMinutesDialog
import com.lxpro.core.designsystem.component.LXPillChip
import com.lxpro.core.designsystem.component.LXSectionHeader
import com.lxpro.core.designsystem.component.LXSlider
import com.lxpro.core.designsystem.component.LXSwitch
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.media.noise.NoiseCatalog
import com.lxpro.core.media.noise.NoiseCategory
import com.lxpro.core.media.noise.NoiseScene
import com.lxpro.core.media.noise.NoiseSource
import com.lxpro.core.media.timer.NoiseSleepTimer
import com.lxpro.core.model.Song
import kotlinx.coroutines.delay

/**
 * 白噪音页（第 4 个底部 Tab，17 §4）。
 *
 * 一级页面：无返回键，自上而下——
 * 当前混音卡（竖条混音器）→ 预设场景 2 列 8 卡 → 我的组合 → 我的白噪音（批二占位）→ 音源库。
 */
@Composable
fun NoiseScreen(
    onNotice: (String) -> Unit = {},
    viewModel: NoiseViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    var showSleepTimer by remember { mutableStateOf(false) }
    var showCustomTimer by remember { mutableStateOf(false) }
    var showOnlineSearch by remember { mutableStateOf(false) }

    // ── 17 §3 本地导入：SAF 选文件 → 命名确认 → 入库 → 自动合并进 catalog ──
    val context = LocalContext.current
    var importDraft by remember { mutableStateOf<ImportDraft?>(null) }
    var deleteSourceId by remember { mutableStateOf<String?>(null) }
    var deletePackId by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // 持久化读权限（SAF 选中的文件重启后还能播）
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = displayNameOf(context, uri) ?: "本地白噪音"
            importDraft = ImportDraft(name = stripExt(name), uri = uri.toString())
        }
    }

    // 远端音源包：SAF 选 zip → 解压到私有目录 → 离线可用（17 §3）
    val packLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importPack(uri)
    }

    // 导入命名确认对话框
    importDraft?.let { draft ->
        ImportNameDialog(
            draft = draft,
            onConfirm = { name, cat ->
                viewModel.importUserSource(name, draft.uri, cat)
                importDraft = null
            },
            onDismiss = { importDraft = null },
        )
    }
    // 删除本地导入音源确认
    deleteSourceId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteSourceId = null },
            containerColor = colors.surface,
            title = { Text("删除白噪音", style = LXType.titleSmall, color = colors.ink1) },
            text = {
                Text(
                    "「${ui.userSources.firstOrNull { it.id == id }?.name.orEmpty()}」将从「我的白噪音」移除（内置 CDN 音源不受影响）。",
                    style = LXType.bodySmall, color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUserSource(id)
                    deleteSourceId = null
                }) { Text("删除", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSourceId = null }) { Text("取消", color = colors.ink3) }
            },
        )
    }

    // 删除远端音源包确认（连同私有目录里的音频一起删）
    deletePackId?.let { id ->
        AlertDialog(
            onDismissRequest = { deletePackId = null },
            containerColor = colors.surface,
            title = { Text("删除音源包", style = LXType.titleSmall, color = colors.ink1) },
            text = {
                Text(
                    "「${ui.userPacks.firstOrNull { it.id == id }?.name.orEmpty()}」及其解压出的音频将从本机删除，此操作不可恢复。",
                    style = LXType.bodySmall, color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePack(id)
                    deletePackId = null
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deletePackId = null }) { Text("取消", color = colors.ink3) }
            },
        )
    }
    // 音源包导入回执
    ui.packMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearPackMessage,
            containerColor = colors.surface,
            title = { Text("远端音源包", style = LXType.titleSmall, color = colors.ink1) },
            text = { Text(message, style = LXType.bodySmall, color = colors.ink2) },
            confirmButton = {
                TextButton(onClick = viewModel::clearPackMessage) {
                    Text("知道了", color = colors.accent)
                }
            },
        )
    }

    // 在线搜索（17 §3 第三种获取方式：复用音源聚合；默认流式，点缓存才落盘）
    if (showOnlineSearch) {
        OnlineSearchDialog(
            query = ui.onlineQuery,
            searching = ui.onlineSearching,
            results = ui.onlineResults,
            error = ui.onlineError,
            onQueryChange = viewModel::setOnlineQuery,
            onSearch = viewModel::searchOnline,
            onPlay = { song ->
                viewModel.playOnline(song)
                showOnlineSearch = false
            },
            onCache = viewModel::cacheOnline,
            onDismiss = { showOnlineSearch = false },
        )
    }
    // 在线取流 / 缓存回执
    ui.onlineMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearOnlineMessage,
            containerColor = colors.surface,
            title = { Text("在线白噪音", style = LXType.titleSmall, color = colors.ink1) },
            text = { Text(message, style = LXType.bodySmall, color = colors.ink2) },
            confirmButton = {
                TextButton(onClick = viewModel::clearOnlineMessage) {
                    Text("知道了", color = colors.accent)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 不铺不透明底色：动态背景开启时封面要能透过本页（与首页/歌单/统计保持一致）
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LXTheme.scalars.sidePadding),
    ) {
        Spacer(Modifier.height(LXDimens.space12))

        // ── 标题行（一级页无返回键；睡眠定时 17 §7 右列）──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("白噪音", style = LXType.headlineSmall, color = colors.ink1)
                Text("预设场景 · 自建组合", style = LXType.bodySmall, color = colors.ink3)
            }
            LXPillChip(
                label = if (ui.noiseTimer != null) "睡眠定时 · 开" else "睡眠定时",
                selected = ui.noiseTimer != null,
                onClick = { showSleepTimer = true },
            )
        }
        Spacer(Modifier.height(LXDimens.space16))

        // ── 当前混音卡 ──
        NowMixCard(ui = ui, viewModel = viewModel)
        Spacer(Modifier.height(LXDimens.space24))

        // ── 预设场景（2 列 8 卡）──
        LXSectionHeader(title = "预设场景", trailing = {
            Text("${NoiseCatalog.presetScenes.size} 个", style = LXType.bodySmall, color = colors.ink3)
        })
        NoiseCatalog.presetScenes.chunked(2).forEach { rowItems ->
            // 等高布局：同一行两张卡按较高者对齐，文案换行不再让卡片高低参差
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(LXDimens.space8),
            ) {
                rowItems.forEach { scene ->
                    SceneCard(
                        scene = scene,
                        active = ui.mixSceneId == scene.id && ui.activeSourceIds.isNotEmpty(),
                        sources = ui.sources,
                        onClick = { viewModel.applyScene(scene) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(LXDimens.space8))
        }
        Spacer(Modifier.height(LXDimens.space16))

        // ── 我的组合 ──
        MyCombosSection(ui = ui, viewModel = viewModel)
        Spacer(Modifier.height(LXDimens.space24))

        // ── 我的白噪音（17 §3）──────────────────────────────────
        LXSectionHeader(title = "我的白噪音")
        Row(horizontalArrangement = Arrangement.spacedBy(LXDimens.space8)) {
            GetTile("本地导入", "选音频文件", Modifier.weight(1f)) {
                importLauncher.launch(arrayOf("audio/*"))
            }
            GetTile("在线搜索", "复用音源检索", Modifier.weight(1f)) { showOnlineSearch = true }
            GetTile("远端音源包", "导入 zip 离线用", Modifier.weight(1f)) {
                packLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
        }
        // 已导入的本地音源 + 远端音源包（行点击 = 选中/取消选中；行尾「删除」= 从本机移除）
        // 17 §3：导入之后与内置音源完全同权
        val activeMixIds = ui.mixLayers.map { it.first }.toSet()
        if (ui.userSources.isNotEmpty()) {
            Spacer(Modifier.height(LXDimens.space12))
            UserSourceList(
                sources = ui.userSources,
                activeIds = activeMixIds,
                onTap = { id -> viewModel.tapSource(id) },
                onDelete = { id -> deleteSourceId = id },
            )
        }
        if (ui.userPacks.isNotEmpty()) {
            Spacer(Modifier.height(if (ui.userSources.isEmpty()) LXDimens.space12 else LXDimens.space8))
            UserPackList(
                packs = ui.userPacks,
                activeIds = activeMixIds,
                onTap = { id -> viewModel.tapSource(id) },
                onDelete = { id -> deletePackId = id },
            )
        }
        Spacer(Modifier.height(LXDimens.space24))

        // ── 音源库 ──
        SourceLibrarySection(ui = ui, viewModel = viewModel)

        Spacer(Modifier.height(LXDimens.space32))
    }

    // 睡眠定时选项（17 §7 右列：不开启 / 15/30/45/60/90/120 分钟 / 到早上 7:00；只作用白噪音）
    if (showSleepTimer) {
        AlertDialog(
            onDismissRequest = { showSleepTimer = false },
            title = { Text("睡眠定时") },
            text = {
                Column {
                    Text(
                        "到点白噪音淡出 3 秒后自动暂停，歌曲照常播放",
                        style = LXType.bodySmall, color = colors.ink3,
                    )
                    Spacer(Modifier.height(LXDimens.space8))
                    SleepTimerOptionRow(
                        label = "不开启",
                        selected = ui.noiseTimer == null,
                        onClick = { viewModel.cancelSleepTimer(); showSleepTimer = false },
                    )
                    NoiseSleepTimer.MINUTE_OPTIONS.forEach { min ->
                        SleepTimerOptionRow(
                            label = "$min 分钟",
                            selected = ui.noiseTimerMinutes == min,
                            onClick = { viewModel.setSleepTimerMinutes(min); showSleepTimer = false },
                        )
                    }
                    SleepTimerOptionRow(
                        label = "到早上 7:00",
                        selected = ui.noiseTimerUntilMorning,
                        onClick = { viewModel.setSleepTimerUntilMorning(); showSleepTimer = false },
                    )
                    // 自定义分钟数（2026-10-20 用户要求：白噪音定时也要能与歌曲定时一样自定义）
                    // —— 预设档之外的分钟数（如 25 分钟）自动归到这一项，并显性标出当前值
                    val customActive = ui.noiseTimerMinutes?.let { it !in NoiseSleepTimer.MINUTE_OPTIONS } == true
                    SleepTimerOptionRow(
                        label = if (customActive) "${ui.noiseTimerMinutes} 分钟（自定义）" else "自定义…",
                        selected = customActive,
                        onClick = {
                            showSleepTimer = false
                            showCustomTimer = true
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimer = false }) { Text("关闭") }
            },
        )
    }

    // 自定义分钟数输入（复用设计层对话框：歌曲定时与白噪音定时共用同一个组件）
    if (showCustomTimer) {
        LXCustomMinutesDialog(
            initialMinutes = ui.noiseTimerMinutes ?: 30,
            hint = "到点白噪音淡出 3 秒后暂停",
            onConfirm = { min ->
                showCustomTimer = false
                viewModel.setSleepTimerMinutes(min)
            },
            onDismiss = { showCustomTimer = false },
        )
    }
}

/** 睡眠定时选项行（单选语义） */
@Composable
private fun SleepTimerOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = LXType.bodyMedium, color = LXTheme.colors.ink1)
    }
}

/* ════════════════ 当前混音卡 ════════════════ */

@Composable
private fun NowMixCard(ui: NoiseViewModel.UiState, viewModel: NoiseViewModel) {
    val colors = LXTheme.colors
    var showRename by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<String?>(null) }

    // 睡眠定时剩余时间轮询（17 §7：混音卡副标题显示剩余分钟数；分钟级显示，20s tick 足够）
    val timerRemainMs by produceState<Long?>(initialValue = null, ui.noiseTimer) {
        while (true) {
            val remain = viewModel.sleepTimerRemainingMs()
            value = remain
            if (remain == null) break
            delay(20_000)
        }
    }

    LXCard {
        Column(Modifier.padding(LXDimens.space16)) {
            // 名称行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "当前混音 · ${if (ui.playing) "播放中" else if (ui.activeSourceIds.isNotEmpty()) "已暂停" else "未播放"}",
                        style = LXType.labelSmall, color = colors.ink3,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        ui.mixName, style = LXType.titleLarge, color = colors.ink1,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(if (ui.mixLayers.isEmpty()) "还没有图层" else "${ui.mixLayers.size} 个图层")
                            timerRemainMs?.let { append(" · ${(it + 59_999) / 60_000} 分钟后自动暂停") }
                        },
                        style = LXType.bodySmall, color = colors.ink3,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showRename = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LxIcons.Pencil, contentDescription = "重命名", tint = colors.accent, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑", style = LXType.labelLarge, color = colors.accent)
                }
            }
            Spacer(Modifier.height(LXDimens.space16))

            // 竖条混音器（17 §4：竖条高度 = 该层音量；垂直拖动调音量）
            if (ui.mixLayers.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(11.dp)).background(LXTheme.pageInset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "从下方「音源库」点选音源，叠进当前混音",
                        style = LXType.bodySmall, color = colors.ink3,
                    )
                }
            } else {
                MixerBars(ui = ui, viewModel = viewModel)
            }
            Spacer(Modifier.height(LXDimens.space12))

            // 图层标签（含百分比；点击可移除该层）
            if (ui.mixLayers.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ui.mixLayers.forEach { (sid, vol) ->
                        val src = ui.sources[sid] ?: return@forEach
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(LXTheme.pageInset)
                                .clickable { removeTarget = sid }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${src.displayName} ${NoiseViewModel.percentOf(vol)}%",
                                style = LXType.labelSmall, color = colors.ink2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(LXDimens.space12))
            }

            // 「与歌曲同时播放」开关（17 §4）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("与歌曲同时播放", style = LXType.bodyMedium, color = colors.ink1, fontWeight = FontWeight.Medium)
                    Text("开启后白噪音与歌曲各自独立调节，互不打断", style = LXType.bodySmall, color = colors.ink3)
                }
                LXSwitch(
                    checked = ui.withMusic,
                    onCheckedChange = { viewModel.setWithMusic(it) },
                )
            }
            Spacer(Modifier.height(LXDimens.space8))

            // 播放键 + 双轨独立音量（17 §1 硬约束③：歌曲/白噪音各有自己的音量）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (ui.mixLayers.isEmpty() && !ui.playing) colors.inset else colors.accent)
                        .clickable(enabled = (ui.mixLayers.isNotEmpty() || ui.playing) && !ui.loading) { viewModel.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (ui.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = colors.bg,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            if (ui.playing) LxIcons.Pause else LxIcons.Play,
                            contentDescription = if (ui.playing) "暂停" else "播放",
                            tint = if (ui.mixLayers.isEmpty() && !ui.playing) colors.ink3 else colors.bg,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(LXDimens.space12))
                Column(Modifier.weight(1f)) {
                    // 歌曲轨音量（上）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("歌曲", style = LXType.bodySmall, color = colors.ink3, modifier = Modifier.width(44.dp))
                        LXSlider(
                            value = ui.songVolume,
                            onValueChange = { viewModel.setSongVolume(it) },
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        )
                        Text(
                            "${NoiseViewModel.percentOf(ui.songVolume)}",
                            style = LXType.bodySmall, color = colors.ink3,
                            modifier = Modifier.width(30.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 白噪音轨总音量（下）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("白噪音", style = LXType.bodySmall, color = colors.ink2, modifier = Modifier.width(44.dp))
                        LXSlider(
                            value = ui.totalVolume,
                            onValueChange = { viewModel.setTotalVolume(it) },
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        )
                        Text(
                            "${NoiseViewModel.percentOf(ui.totalVolume)}",
                            style = LXType.bodySmall, color = colors.ink2,
                            modifier = Modifier.width(30.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(LXDimens.space8))

            // 参数小标（17 §4）
            Row(horizontalArrangement = Arrangement.spacedBy(LXDimens.space12)) {
                ParamTag("淡入 ${ui.fadeInMs / 1000}s")
                ParamTag("淡出 ${"%.1f".format(ui.fadeOutMs / 1000f)}s")
                ParamTag("音量曲线 平方")
                ParamTag("图层音量 1.5s 淡变")
            }
        }
    }

    // 重命名对话框
    if (showRename) {
        var name by remember { mutableStateOf(ui.mixName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名混音") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameMix(name); showRename = false }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }

    // 移除图层确认（用户偏好：删除类操作需确认，防误触）
    removeTarget?.let { sid ->
        val src = ui.sources[sid]
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除图层") },
            text = { Text("把「${src?.displayName ?: sid}」从当前混音中移除？") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeLayer(sid); removeTarget = null }) {
                    Text("移除", color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } },
        )
    }
}

/** 竖条混音器：每层一条，高度 = 音量；垂直拖动调音量（增量回调，规避闭包旧值，批十四铁律） */
@Composable
private fun MixerBars(ui: NoiseViewModel.UiState, viewModel: NoiseViewModel) {
    val colors = LXTheme.colors
    val trackHeight = 88.dp
    val trackHeightPx = with(LocalDensity.current) { trackHeight.toPx() }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ui.mixLayers.forEach { (sid, vol) ->
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(9.dp))
                    .background(LXTheme.pageInset)
                    .pointerInput(sid) {
                        detectVerticalDragGestures(
                            onDragEnd = { viewModel.commitLayerVolumes() },
                            onDragCancel = { viewModel.commitLayerVolumes() },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                viewModel.nudgeLayerVolume(sid, -dragAmount / trackHeightPx)
                            },
                        )
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(vol.coerceIn(0f, 1f))
                        .background(colors.accent),
                )
            }
        }
    }
}

@Composable
private fun ParamTag(text: String) {
    Text(text = text, style = LXType.labelSmall, color = LXTheme.colors.ink3)
}

/* ════════════════ 预设场景卡 ════════════════ */

@Composable
private fun SceneCard(
    scene: NoiseScene,
    active: Boolean,
    sources: Map<String, NoiseSource>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(LXDimens.radiusMedium),
        color = LXTheme.pageSurface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (active) 1.5.dp else 1.dp,
            color = if (active) colors.accent else colors.hairline,
        ),
        // 不给阴影：pageSurface 在动态背景下是半透明的，阴影会在卡背压出一块
        // 可见的深色板（2026-10-25 真机反馈「容器透明 + 边框」问题根因）
    ) {
        Column(Modifier.fillMaxHeight().padding(LXDimens.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scene.name, style = LXType.titleSmall, color = colors.ink1,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    // 在响指示：小跳动条（简化实现：accent 圆点）
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${scene.layers.size} 层 · " + scene.layers.mapNotNull { sources[it.sourceId]?.displayName }.joinToString(" / "),
                style = LXType.bodySmall, color = colors.ink3,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ════════════════ 我的组合 ════════════════ */

@Composable
private fun MyCombosSection(ui: NoiseViewModel.UiState, viewModel: NoiseViewModel) {
    val colors = LXTheme.colors
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LXSectionHeader(title = "我的组合", trailing = {
        Row(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(enabled = ui.mixLayers.isNotEmpty()) { viewModel.saveCurrentAsCombo() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                LxIcons.Plus, contentDescription = "存为组合",
                tint = if (ui.mixLayers.isEmpty()) colors.ink3 else colors.accent,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "存为组合", style = LXType.labelLarge,
                color = if (ui.mixLayers.isEmpty()) colors.ink3 else colors.accent,
            )
        }
    })

    if (ui.combos.isEmpty()) {
        Text(
            "还没有组合 —— 调好一套混音后点右上角「存为组合」",
            style = LXType.bodySmall, color = colors.ink3,
        )
    } else {
        ui.combos.forEach { combo ->
            val bound = ui.mixComboId == combo.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = LXDimens.space8)
                    .clickable { viewModel.applyCombo(combo) },
                shape = RoundedCornerShape(LXDimens.radiusMedium),
                color = LXTheme.pageSurface,
                border = androidx.compose.foundation.BorderStroke(
                    width = if (bound) 1.5.dp else 1.dp,
                    color = if (bound) colors.accent else colors.hairline,
                ),
            ) {
                Row(
                    Modifier.padding(LXDimens.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(combo.name, style = LXType.titleSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${NoiseViewModel.parseLayers(combo.layersJson).size} 个图层",
                            style = LXType.bodySmall, color = colors.ink3,
                        )
                    }
                    Icon(
                        LxIcons.Trash, contentDescription = "删除组合",
                        tint = colors.ink3, modifier = Modifier
                            .size(18.dp)
                            .clickable { deleteTarget = combo.id },
                    )
                }
            }
        }
    }

    // 删除组合确认（用户偏好：删除类操作需确认）
    deleteTarget?.let { id ->
        val combo = ui.combos.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除组合") },
            text = { Text("删除「${combo?.name ?: ""}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCombo(id); deleteTarget = null }) {
                    Text("删除", color = colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/* ════════════════ 我的白噪音 · 获取方块（批二占位）════════════════ */

@Composable
private fun GetTile(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(LXDimens.radiusMedium),
        color = LXTheme.pageSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairline),
    ) {
        Column(Modifier.padding(LXDimens.space12)) {
            Text(title, style = LXType.labelLarge, color = colors.ink1)
            Spacer(Modifier.height(2.dp))
            Text(sub, style = LXType.labelSmall, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ════════════════ 我的白噪音 · 本地导入（17 §3）════════════════ */

private data class ImportDraft(val name: String, val uri: String)

@Composable
private fun ImportNameDialog(
    draft: ImportDraft,
    onConfirm: (name: String, categoryLabel: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    var text by remember(draft.name) { mutableStateOf(draft.name) }
    // 默认归为「生活」类；若文件名包含明显天气词则自动归「天气」
    var category by remember(draft.name) {
        mutableStateOf(
            when {
                draft.name.any { listOf('雨', '雷', '风', '雪').contains(it) } -> com.lxpro.core.media.noise.NoiseCategory.WEATHER.label
                else -> com.lxpro.core.media.noise.NoiseCategory.LIFE.label
            }
        )
    }
    val trimmed = text.trim()
    val canConfirm = trimmed.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("导入白噪音", style = LXType.titleSmall, color = colors.ink1) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(48) },
                    label = { Text("名称", style = LXType.labelSmall) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("分类", style = LXType.labelLarge, color = colors.ink2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    com.lxpro.core.media.noise.NoiseCategory.entries.forEach { cat ->
                        LXPillChip(
                            label = cat.label,
                            selected = category == cat.label,
                            onClick = { category = cat.label },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "导入后与内置音源同权：可叠进混音、可存组合、不经过 EQ",
                    style = LXType.labelSmall, color = colors.ink3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(trimmed, category) },
                enabled = canConfirm,
            ) { Text("导入", color = if (canConfirm) colors.accent else colors.ink3) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.ink3) } },
    )
}

@Composable
private fun UserSourceList(
    sources: List<com.lxpro.core.database.entity.NoiseUserSourceEntity>,
    activeIds: Set<String>,
    onTap: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = LXTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEach { entity ->
            val isActive = activeIds.contains(entity.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LXDimens.radiusSmall))
                    .background(if (isActive) colors.accent.copy(alpha = 0.08f) else LXTheme.pageSurface)
                    .border(
                        1.dp,
                        if (isActive) colors.accent.copy(alpha = 0.4f) else colors.hairline,
                        RoundedCornerShape(LXDimens.radiusSmall),
                    )
                    .clickable { onTap(entity.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entity.name, style = LXType.bodyMedium, color = colors.ink1,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        if (isActive) "已叠进混音 · 点击取消"
                        else "${entity.category} · 整段循环 · 点击叠进",
                        style = LXType.labelSmall, color = colors.ink3,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 行尾「删除」= 从本机移除该导入音源（需确认）；取消叠进走上方的行点击
                TextButton(onClick = { onDelete(entity.id) }) {
                    Text("删除", color = colors.accent, style = LXType.labelSmall)
                }
            }
        }
    }
}

/**
 * 远端音源包列表（17 §3：zip 声音包解压到私有目录，离线可用）。
 *
 * 行点击 = 选中/取消选中（与本地导入同权）；行尾「删除」= 连目录一并删除。
 */
@Composable
private fun UserPackList(
    packs: List<com.lxpro.core.database.entity.NoiseUserPackEntity>,
    activeIds: Set<String>,
    onTap: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = LXTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        packs.forEach { pack ->
            val isActive = activeIds.contains(pack.id)
            val segments = packSegmentCount(pack.segmentsJson)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LXDimens.radiusSmall))
                    .background(if (isActive) colors.accent.copy(alpha = 0.08f) else LXTheme.pageSurface)
                    .border(
                        1.dp,
                        if (isActive) colors.accent.copy(alpha = 0.4f) else colors.hairline,
                        RoundedCornerShape(LXDimens.radiusSmall),
                    )
                    .clickable { onTap(pack.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pack.name, style = LXType.bodyMedium, color = colors.ink1,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        // 「音源包」徽章，与本地导入区分来源
                        Text(
                            "音源包", style = LXType.labelSmall, color = colors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(colors.accent.copy(alpha = 0.10f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        buildString {
                            if (isActive) append("已叠进混音 · 点击取消")
                            else append("${pack.category} · $segments 段")
                            if (!isActive && pack.maxSilenceSeconds > 0) {
                                append(" · 间隔 ${pack.minSilenceSeconds}–${pack.maxSilenceSeconds}s")
                            }
                        },
                        style = LXType.labelSmall, color = colors.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDelete(pack.id) }) {
                    Text("删除", color = colors.accent, style = LXType.labelSmall)
                }
            }
        }
    }
}

private fun packSegmentCount(json: String): Int =
    runCatching { org.json.JSONArray(json).length() }.getOrDefault(0)

private fun stripExt(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.length - 1 &&
        name.substring(dot + 1).length in 2..4 &&
        name.substring(dot + 1).all { it.isLetter() }
    ) name.substring(0, dot) else name
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

/* ════════════════ 音源库 ════════════════ */

@Composable
private fun SourceLibrarySection(ui: NoiseViewModel.UiState, viewModel: NoiseViewModel) {
    val colors = LXTheme.colors
    val categories = listOf<NoiseCategory?>(null) + NoiseCategory.entries
    val filtered = NoiseCatalog.entries.filter { ui.category == null || it.category == ui.category }

    LXSectionHeader(title = "音源库", trailing = {
        Text("${NoiseCatalog.entries.size} 个", style = LXType.bodySmall, color = colors.ink3)
    })

    // 分类 chips（全部 / 天气 / 自然 / 生活 / 交通 / 机械 / 噪音）
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categories.forEach { cat ->
            LXPillChip(
                label = cat?.label ?: "全部",
                selected = ui.category == cat,
                onClick = { viewModel.selectCategory(cat) },
            )
        }
    }
    Spacer(Modifier.height(LXDimens.space12))

    when {
        ui.catalogLoading -> Row(
            Modifier.fillMaxWidth().padding(vertical = LXDimens.space24),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = colors.accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(LXDimens.space12))
            Text("正在加载白噪音库…", style = LXType.bodySmall, color = colors.ink3)
        }
        ui.catalogError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "目录加载失败：${ui.catalogError}",
                style = LXType.bodySmall, color = colors.danger,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.loadCatalog(forceRefresh = true) }) { Text("重试") }
        }
        else -> filtered.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(LXDimens.space8)) {
                rowItems.forEach { entry ->
                    val src = ui.sources[entry.id]
                    val inMix = ui.mixLayers.any { it.first == entry.id }
                    SourceTile(
                        name = entry.displayName,
                        kindLabel = src?.let { NoiseViewModel.kindLabel(it) } ?: "",
                        inMix = inMix,
                        onClick = { viewModel.tapSource(entry.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(LXDimens.space8))
        }
    }
}

/** 音源瓦片（3 列）：首字圆标 + 名称 + 副标（无缝循环 / 间隔 X–Ys）；已叠进混音的右下角 accent 点 */
@Composable
private fun SourceTile(
    name: String,
    kindLabel: String,
    inMix: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(LXDimens.radiusMedium),
        color = LXTheme.pageSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (inMix) colors.accent else colors.hairline),
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().padding(LXDimens.space12),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(LXTheme.pageInset),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1), style = LXType.titleSmall, color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(name, style = LXType.labelLarge, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel, style = LXType.labelSmall, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inMix) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
    }
}

/**
 * 在线搜索白噪音（17 §3 第三种获取方式）。
 *
 * 复用「已启用音源」的检索能力，不另做一套；结果默认**流式播放**，
 * 只有点「缓存」才下载到应用私有目录并落库成「我的白噪音」。
 */
@Composable
private fun OnlineSearchDialog(
    query: String,
    searching: Boolean,
    results: List<Song>,
    error: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPlay: (Song) -> Unit,
    onCache: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("在线搜索白噪音", style = LXType.titleSmall, color = colors.ink1) },
        text = {
            Column {
                Text(
                    "复用已启用音源的检索结果；默认流式播放，点「缓存」才落盘到我的白噪音。",
                    style = LXType.labelSmall, color = colors.ink3,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = {
                            Text("雨声 / 篝火 / 咖啡厅", style = LXType.bodySmall, color = colors.ink3)
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onSearch, enabled = query.isNotBlank() && !searching) {
                        Text(if (searching) "搜索中" else "搜索", color = colors.accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    searching -> Box(
                        Modifier.fillMaxWidth().height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = colors.accent,
                            strokeWidth = 2.dp,
                        )
                    }
                    error != null -> Text(error, style = LXType.bodySmall, color = colors.ink2)
                    results.isEmpty() -> Text(
                        "输入关键词后点「搜索」",
                        style = LXType.bodySmall, color = colors.ink3,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(results, key = { "${it.source.value}_${it.id}" }) { song ->
                            OnlineResultRow(
                                song = song,
                                onPlay = { onPlay(song) },
                                onCache = { onCache(song) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = colors.ink3) }
        },
    )
}

@Composable
private fun OnlineResultRow(song: Song, onPlay: () -> Unit, onCache: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                song.name, style = LXType.bodyMedium, color = colors.ink1,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(song.singer.ifBlank { "未知" })
                    append(" · ")
                    append(song.source.value)
                    song.interval?.let { sec ->
                        append(" · ")
                        append(sec / 60)
                        append(":")
                        append((sec % 60).toString().padStart(2, '0'))
                    }
                },
                style = LXType.labelSmall, color = colors.ink3,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onPlay) {
            Text("播放", color = colors.accent, style = LXType.labelSmall)
        }
        TextButton(onClick = onCache) {
            Text("缓存", color = colors.accent, style = LXType.labelSmall)
        }
    }
}
