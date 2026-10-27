package com.lxpro.music.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXSlider
import com.lxpro.core.designsystem.component.LXSwitch
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.stats.ImportKind
import com.lxpro.music.BackupImportViewModel
import com.lxpro.music.ScanUiState
import com.lxpro.music.ScannedBackup

/**
 * 设置分类详情（M3 任务 9 的第二级）。
 *
 * 依据 `mockup_app.html` 的 `#pg-set`（`renderSet` / `setRow`）：9 个分类共用一页，
 * 按 group 出「分区标题 + 卡片」，行只有四型：`toggle` / `select` / `static` / `themes`。
 * 返回键回 hub（15 §6.5）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    categoryKey: String,
    onBack: () -> Unit,
    onScanFolder: () -> Unit,
    onImportMusic: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenEq: () -> Unit,
    onReplayTutorial: () -> Unit,
    onOpenVerseGroup: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupImportViewModel = hiltViewModel(),
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importState by backupViewModel.state.collectAsStateWithLifecycle()
    val exportState by backupViewModel.export.collectAsStateWithLifecycle()
    val scanState by backupViewModel.scan.collectAsStateWithLifecycle()

    // 用户从系统设置页（电池优化白名单 / 自启动设置）回来时刷新状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshBatteryStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current

    /** 当前弹着选项面板的 select 行（null = 没弹） */
    var selectRow by remember { mutableStateOf<SettingsSelectRow?>(null) }

    // 外部回调用 rememberUpdatedState 包一层：handlers / catalog 用 remember 固定，
    // 既不让每次重组都重建整棵目录，也不会抓到过期的回调
    val scanFolder by rememberUpdatedState(onScanFolder)
    val importMusic by rememberUpdatedState(onImportMusic)
    val openAbout by rememberUpdatedState(onOpenAbout)
    val openEq by rememberUpdatedState(onOpenEq)
    val openVerseGroup by rememberUpdatedState(onOpenVerseGroup)
    val notice by rememberUpdatedState(onNotice)

    // 删除脚本确认弹窗（批十九：用户要求删除前确认）
    var pendingDeleteScript by remember { mutableStateOf<com.lxpro.source.script.ScriptEntry?>(null) }

    // 「添加文案组」弹窗（输入框放弹窗里，edge-to-edge 下键盘不会顶乱列表）
    var showAddVerseGroupDialog by remember { mutableStateOf(false) }

    // 全量备份：一个导出、一个导入（.lxmc 没注册 MIME，导入用 */* 让系统选择器能选中）
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(backupViewModel::exportTo) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(backupViewModel::importAny)
    }

    // 音源脚本导入（.js 同样没注册稳定的 MIME，用 */*；导入结果如实提示）
    val pickScript = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importScript(uri) { error ->
                notice(error ?: "脚本导入成功，已加载")
            }
        }
    }

    // 分享邀请语编辑框状态（必须在 handlers 之前声明——handlers.onEditShareTemplate 里引用它）
    var showEditTemplateDialog by remember { mutableStateOf(false) }
    var templateInput by remember { mutableStateOf(state.shareTemplate) }

    val handlers = remember(viewModel) {
        SettingsHandlers(
            onPalette = viewModel::selectPalette,
            onAmbient = viewModel::setAmbient,
            onDynamicBg = viewModel::setDynamicBg,
            onDynamicBgBlur = viewModel::setDynamicBgBlur,
            onDynamicBgMask = viewModel::setDynamicBgMaskOpacity,
            onQuality = viewModel::setQuality,
            onImportScript = { pickScript.launch(arrayOf("*/*")) },
            onScriptActive = { scriptId ->
                viewModel.setScriptActive(scriptId) { stopped ->
                    val script = state.scripts.firstOrNull { it.id == scriptId }
                    val msg = buildString {
                        append("已设为当前脚本")
                        if (!stopped.isNullOrEmpty()) {
                            append("，已停用：")
                            append(stopped.joinToString("、"))
                        }
                    }
                    notice(msg)
                }
            },
            onScriptRemove = { scriptId ->
                pendingDeleteScript = state.scripts.firstOrNull { it.id == scriptId }
            },
            onScriptTest = { scriptId ->
                viewModel.testScript(scriptId) { msg -> notice(msg) }
            },
            onConcurrency = viewModel::setConcurrency,
            onAutoImport = viewModel::setAutoImport,
            onRetryLimit = viewModel::setRetryLimit,
            onFollowSystemDark = viewModel::setFollowSystemDark,
            onRememberPosition = viewModel::setRememberPlaybackPosition,
            onPauseOnHeadphone = viewModel::setPauseOnHeadphoneUnplug,
            onPlayWithOtherApps = viewModel::setPlayWithOtherApps,
            onDesktopLyric = { on ->
                // 无悬浮窗权限时先引导、不写入开关（不做「开了但没效果」的假开关）；
                // 授权回来后用户再点一次即生效
                if (on && !android.provider.Settings.canDrawOverlays(context)) {
                    notice("请先允许「悬浮窗」权限，再回来打开桌面歌词")
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ),
                    )
                } else {
                    viewModel.setDesktopLyricEnabled(on)
                }
            },
            onStatusBarLyric = viewModel::setStatusBarLyricEnabled,
            onTraditionalLyric = viewModel::setTraditionalLyricEnabled,
            onDesktopLyricLock = viewModel::setDesktopLyricLock,
            onDesktopLyricFontSize = viewModel::setDesktopLyricFontSize,
            onDesktopLyricAlignment = viewModel::setDesktopLyricAlignment,
            onDesktopLyricOpacity = viewModel::setDesktopLyricOpacity,
            onDesktopLyricPlayedColor = viewModel::setDesktopLyricPlayedColor,
            onDesktopLyricPendingColor = viewModel::setDesktopLyricPendingColor,
            onIgnoreShortAudio = viewModel::setIgnoreShortAudio,
            onAutoScanOnBoot = viewModel::setAutoScanOnBoot,
            onWifiOnlyDownload = viewModel::setWifiOnlyDownload,
            onDataSaverEnabled = viewModel::setDataSaverEnabled,
            onDataSaverAutoQuality = viewModel::setDataSaverAutoQuality,
            onDataSaverNoImage = viewModel::setDataSaverNoImage,
            onDataSaverApplyOnWifi = viewModel::setDataSaverApplyOnWifi,
            onShowCover = viewModel::setListShowCover,
            onNoiseWithMusic = viewModel::setNoiseWithMusic,
            onNoiseDefaultScene = viewModel::setNoiseDefaultScene,
            onNoiseSleepTimer = viewModel::setNoiseSleepTimer,
            onVerseGroupToggle = viewModel::setVerseGroupEnabled,
            onOpenVerseGroup = { openVerseGroup(it) },
            onAddVerseGroupClick = { showAddVerseGroupDialog = true },
            onVerseSlot = viewModel::setVerseSlot,
            onVerseInterval = viewModel::setVerseIntervalSec,
            onScanFolder = { scanFolder() },
            onImportMusic = { importMusic() },
            onOpenAbout = { openAbout() },
            onOpenEq = { openEq() },
            onNotice = { notice(it) },
            onRequestBatteryOptimization = { viewModel.requestBatteryOptimization() },
            onOpenAutoStartSettings = { viewModel.openAutoStartSettings() },
            onRefreshBatteryStatus = { viewModel.refreshBatteryStatus() },
            onEditShareTemplate = { showEditTemplateDialog = true; templateInput = state.shareTemplate },
            onSetShareTemplate = { viewModel.setShareTemplate(it) },
        )
    }

    val catalog = remember(state, handlers) { buildSettingsCatalog(state, handlers) }
    val category = catalog.firstOrNull { it.key == categoryKey }

    // 「扫描 Download 导入」弹层。Manifest 里 READ_EXTERNAL_STORAGE 只注册到 API 32，
    // 且 33+ 没有任何运行时权限能看到其它应用放进 Download 的非媒体文件，
    // 所以只在 API ≤ 32 申请，33+ 直接查、查不到走空态文案兜底（不引 MANAGE_EXTERNAL_STORAGE）
    var showScanSheet by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val legacyReadPermission = Manifest.permission.READ_EXTERNAL_STORAGE
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showScanSheet = true
            backupViewModel.scanDownload()
        } else {
            onNotice("没有存储读取权限，扫不到 Download 目录；可改用上方手动导入")
        }
    }
    val startScan: () -> Unit = {
        @Suppress("DEPRECATION")
        val needRequest = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(context, legacyReadPermission) !=
            PackageManager.PERMISSION_GRANTED
        if (needRequest) {
            scanPermissionLauncher.launch(legacyReadPermission)
        } else {
            showScanSheet = true
            backupViewModel.scanDownload()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding(),
    ) {
        // 删除脚本确认弹窗
        pendingDeleteScript?.let { script ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDeleteScript = null },
                title = { Text("删除脚本", style = LXType.titleMedium) },
                text = { Text("确认删除「${script.name}」？将卸载脚本并删除文件与记录。", style = LXType.bodyMedium) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.removeScript(script.id)
                        pendingDeleteScript = null
                    }) { Text("删除", color = colors.danger) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingDeleteScript = null }) {
                        Text("取消")
                    }
                },
            )
        }
        // 添加文案组弹窗（trim / 重名 / 上限由仓库层保证，结果如实提示）
        if (showAddVerseGroupDialog) {
            var nameInput by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAddVerseGroupDialog = false },
                title = { Text("添加文案组", style = LXType.titleMedium) },
                text = {
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            placeholder = { Text("给这组文案起个名字…") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "建好后点进组里添加文案 · 组数上限 " +
                                "${com.lxpro.core.datastore.VERSE_GROUP_LIMIT}" +
                                "（现有 ${state.verseGroups.size}）",
                            style = LXType.labelSmall,
                            color = colors.ink3,
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            viewModel.addVerseGroup(nameInput) { added ->
                                notice(if (added) "已建组，点进组里添加文案" else "未添加：组名为空、重名或已达上限")
                            }
                            showAddVerseGroupDialog = false
                        },
                        enabled = nameInput.isNotBlank(),
                    ) { Text("创建") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showAddVerseGroupDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }

        // ── 分享邀请语编辑框 ──
        if (showEditTemplateDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showEditTemplateDialog = false },
                title = { Text("自定义分享邀请语", style = LXType.titleMedium) },
                text = {
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = templateInput,
                            onValueChange = { templateInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 5,
                        )
                        Text(
                            text = "占位符：{title} 歌名 {artist} 艺术家 {link} 软链接",
                            style = LXType.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            viewModel.setShareTemplate(templateInput)
                            showEditTemplateDialog = false
                        },
                    ) { Text("保存") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showEditTemplateDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }
        // ── 顶栏：返回 + crumb「设置 / 分类名」 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = scalars.sidePadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(LxIcons.Back, contentDescription = "返回", tint = colors.ink1)
            }
            Text(
                text = "设置 / ${category?.name.orEmpty()}",
                style = LXType.titleSmall,
                color = colors.ink1,
            )
        }

        if (category == null) {
            Text(
                text = "未知的设置分类：$categoryKey",
                style = LXType.bodyMedium,
                color = colors.ink3,
                modifier = Modifier.padding(scalars.sidePadding),
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = scalars.sidePadding)
                .padding(bottom = scalars.sectionGap),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(text = category.name, style = LXType.headlineLarge, color = colors.ink1)
            Spacer(Modifier.height(6.dp))
            Text(text = category.summary, style = LXType.labelSmall, color = colors.ink3)

            category.groups.forEach { group ->
                if (group.header != null) {
                    Text(
                        text = group.header,
                        style = LXType.titleSmall,
                        color = colors.ink1,
                        modifier = Modifier.padding(top = 20.dp, bottom = 11.dp),
                    )
                } else {
                    Spacer(Modifier.height(20.dp))
                }
                if (group.rows.singleOrNull() is SettingsThemesRow) {
                    ThemeGrid(
                        current = state.palette,
                        onPick = handlers.onPalette,
                    )
                } else {
                    LXCard(modifier = Modifier.fillMaxWidth()) {
                        group.rows.forEachIndexed { index, row ->
                            SettingsRowView(row = row, onSelect = { selectRow = it })
                            if (index != group.rows.lastIndex) RowDivider()
                        }
                    }
                }
            }

            // 「数据与备份」独立分类（用户 2026-10-05 要求从「关于」里拿出来）：
            // 2026-10-27 起合并为**一个全量备份入口**——所有用户数据装进同一个人 .lxmc 文件
            if (categoryKey == SettingsCategoryKeys.DATA) {
                Text(
                    text = "全量备份",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                    modifier = Modifier.padding(top = 20.dp, bottom = 11.dp),
                )
                Text(
                    text = "一个文件带走全部数据；导入时按文件类型自动识别，旧版备份也能直接导入。",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(bottom = 11.dp),
                )
                BackupCard(
                    fileName = "lxpro_backup.lxmc",
                    description = "听歌统计、播放历史、歌单与收藏、歌曲标签、白噪音账本、" +
                        "歌曲修正、常听歌手图标、搜索记录，以及全部设置偏好（主题、播放、下载、" +
                        "统计口径、白噪音、EQ、歌词、桌面歌词、分享模板…），全部装在这一个文件里。" +
                        "账本与设置覆盖导入，歌单按名字合并，标签/修正按内容去重合并。",
                    exportRunning = exportState.running,
                    importRunning = importState.running,
                    progress = when {
                        exportState.running -> exportState.progress
                        importState.running -> importState.progress
                        else -> ""
                    },
                    message = importState.receiptOf(ImportKind.UNIFIED) ?: exportState.message,
                    failed = importState.resultOf(ImportKind.UNIFIED)?.failed == true ||
                        exportState.failed,
                    onExport = { createBackup.launch("lxpro_backup.lxmc") },
                    onImport = { pickBackup.launch(arrayOf("*/*")) },
                    onDismissMessage = {
                        backupViewModel.consumeResult()
                        backupViewModel.consumeExport()
                    },
                )
                Spacer(Modifier.height(LXDimens.space12))
                // 一键扫描 Download：旧版 LX 会把 .lxmc 备份放进系统 Download 目录，
                // 免去用户用文件选择器手动找
                LXCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = startScan)
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        RowText(
                            title = "扫描 Download 导入",
                            subtitle = "在系统 Download 目录（含子目录）查找 .lxmc 备份，" +
                                "自动识别类型后勾选导入",
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = "›", style = LXType.titleSmall, color = colors.ink3)
                    }
                }

                // 教程（2026-10-27）：随时可重看
                Text(
                    text = "教程",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                )
                LXCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onReplayTutorial)
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        RowText(
                            title = "重看新手教程",
                            subtitle = "偏好问答 + 实机引导，沿着高亮把功能走一遍",
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = "›", style = LXType.titleSmall, color = colors.ink3)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    selectRow?.let { row ->
        OptionSheet(
            title = row.title,
            subtitle = row.subtitle,
            options = row.options,
            currentId = row.currentId,
            onPick = { id ->
                row.onPick(id)
                selectRow = null
                val label = row.options.firstOrNull { it.id == id }?.label ?: id
                onNotice("${row.title} 已设为 $label")
            },
            onDismiss = { selectRow = null },
        )
    }

    if (showScanSheet) {
        ScanDownloadSheet(
            state = scanState,
            onImport = backupViewModel::importScanned,
            onDismiss = {
                showScanSheet = false
                backupViewModel.dismissScan()
            },
        )
    }
}

// ─────────────────────────── 脚本操作弹层 ───────────────────────────

/** 脚本行的操作面板：设为当前 / 测试 / 删除（与设置页 OptionSheet 同一交互语言） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptActionSheet(
    scriptName: String,
    isActive: Boolean,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val actions = listOf(
        "active" to if (isActive) "设为当前脚本（已是当前）" else "设为当前脚本",
        "test" to "测试脚本",
        "remove" to "删除脚本",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
                Text(text = scriptName, style = LXType.labelLarge, color = colors.ink1)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isActive) "当前取流脚本" else "选择一个操作",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

            actions.forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = id != "active" || !isActive) { onAction(id) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = LXType.labelLarge,
                        color = if (id == "remove") colors.danger else colors.ink1,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────── 行视图（五型 + 脚本操作弹层） ───────────────────────────

@Composable
private fun SettingsRowView(
    row: SettingsRow,
    onSelect: (SettingsSelectRow) -> Unit,
) {
    when (row) {
        is SettingsToggleRow -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            RowText(row.title, row.subtitle, Modifier.weight(1f))
            LXSwitch(checked = row.checked, onCheckedChange = row.onToggle)
        }

        is SettingsSelectRow -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(row) }
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            RowText(row.title, row.subtitle, Modifier.weight(1f))
            Text(text = row.currentLabel, style = LXType.bodySmall, color = LXTheme.colors.ink3)
            Text(text = "›", style = LXType.titleSmall, color = LXTheme.colors.ink3)
        }

        is SettingsActionRow -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = row.onClick)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            RowText(row.title, row.subtitle, Modifier.weight(1f))
            Text(text = "›", style = LXType.titleSmall, color = LXTheme.colors.ink3)
        }

        is SettingsInfoRow -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowText(row.title, row.subtitle, Modifier.weight(1f))
        }

        // 文案组：整行点击进组详情，右侧开关直接启停（互不打断）
        is SettingsVerseGroupRow -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = row.onClick)
                .padding(start = 15.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RowText(row.title, row.subtitle, Modifier.weight(1f))
            LXSwitch(checked = row.checked, onCheckedChange = row.onToggle)
        }

        is SettingsSliderRow -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowText(row.title, row.subtitle, Modifier.weight(1f))
                Text(
                    text = "${row.value}${row.unit}",
                    style = LXType.bodySmall,
                    color = LXTheme.colors.ink3,
                )
            }
            LXSlider(
                value = row.value.toFloat(),
                onValueChange = { row.onChange(it.roundToInt()) },
                valueRange = row.range.first.toFloat()..row.range.last.toFloat(),
            )
        }

        is SettingsScriptRow -> {
            var showSheet by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSheet = true }
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                RowText(
                    title = row.title,
                    subtitle = row.subtitle,
                    modifier = Modifier.weight(1f),
                    isActive = row.isActive,
                )
                Text(text = "⋯", style = LXType.titleSmall, color = LXTheme.colors.ink3)
            }
            if (showSheet) {
                ScriptActionSheet(
                    scriptName = row.title,
                    isActive = row.isActive,
                    onAction = { action ->
                        showSheet = false
                        row.onPick(action)
                    },
                    onDismiss = { showSheet = false },
                )
            }
        }

        is SettingsThemesRow -> Unit // 由 ThemeGrid 单独渲染
    }
}

@Composable
private fun RowText(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
) {
    val colors = LXTheme.colors
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = LXType.labelLarge, color = colors.ink1)
            if (isActive) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "当前",
                    style = LXType.labelSmall,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent.copy(alpha = 0.13f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, style = LXType.labelSmall, color = colors.ink3)
        }
    }
}

/** 配色卡网格（原型 `.themes`：双列、4 色块、当前标记；六套实测配色） */
@Composable
private fun ThemeGrid(current: LXPalette, onPick: (LXPalette) -> Unit) {
    val colors = LXTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LXDimens.radiusMedium))
            .background(LXTheme.pageSurface)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        LXPalette.all.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                pair.forEach { palette ->
                    ThemeCard(
                        palette = palette,
                        selected = palette.id == current.id,
                        onClick = { onPick(palette) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeCard(
    palette: LXPalette,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val swatches = listOf(palette.colors.bg, palette.colors.bg2, palette.colors.ink1, palette.colors.accent)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.13f) else LXTheme.pageBg2)
            .clickable(onClick = onClick)
            .padding(9.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            swatches.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(19.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = palette.displayName,
            style = LXType.labelSmall,
            color = if (selected) colors.accent else colors.ink1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 选项面板（原型 `setRow` 的 select → `openSheet`） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(
    title: String,
    subtitle: String?,
    options: List<SettingsOption>,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
                Text(text = title, style = LXType.labelLarge, color = colors.ink1)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = subtitle, style = LXType.labelSmall, color = colors.ink3)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    val isCurrent = option.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCurrent) { onPick(option.id) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isCurrent) colors.accent else colors.ink3),
                        )
                        Text(
                            text = option.label,
                            style = LXType.labelLarge,
                            color = if (isCurrent) colors.accent else colors.ink1,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(text = "当前", style = LXType.labelSmall, color = colors.ink3)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LXTheme.colors.hairline),
    )
}

// ─────────────────────────── 全量备份卡 ───────────────────────────

/**
 * 全量备份卡：一个文件（`lxpro_backup.lxmc`）+ 两个动作（导出 / 导入）。
 *
 * 2026-10-27 起取代原来的「听歌统计」「歌单备份」两张卡：用户要求所有数据装进同一个文件，
 * 导入端因此只保留一个文件选择器（按 `type` 自动分流）。
 */
@Composable
private fun BackupCard(
    fileName: String,
    description: String,
    exportRunning: Boolean,
    importRunning: Boolean,
    progress: String,
    message: String?,
    failed: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val colors = LXTheme.colors
    val busy = exportRunning || importRunning
    LXCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(LXDimens.space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "全量备份", style = LXType.titleSmall, color = colors.ink1)
                Text(
                    text = "本应用",
                    style = LXType.labelSmall,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accent.copy(alpha = 0.13f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                text = "$fileName · $description",
                style = LXType.labelLarge,
                color = colors.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onExport, enabled = !busy) {
                    Text(
                        text = if (exportRunning) "导出中…" else "导出备份",
                        style = LXType.labelLarge,
                        color = colors.accent,
                    )
                }
                Text(text = "·", style = LXType.labelLarge, color = colors.ink3)
                TextButton(onClick = onImport, enabled = !busy) {
                    Text(
                        text = if (importRunning) "导入中…" else "导入备份",
                        style = LXType.labelLarge,
                        color = colors.accent,
                    )
                }
            }
            if (busy && progress.isNotEmpty()) {
                Text(text = progress, style = LXType.labelSmall, color = colors.ink3)
            }
            message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = it,
                        style = LXType.labelLarge,
                        color = if (failed) colors.danger else colors.ink1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    )
                    TextButton(onClick = onDismissMessage) {
                        Text(text = "知道了", style = LXType.labelSmall, color = colors.ink3)
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 扫描 Download 导入弹层 ───────────────────────────

/** 识别类型的展示文案；null（未知格式）不可勾选 */
private fun scanKindText(kind: ImportKind?): String = when (kind) {
    ImportKind.UNIFIED -> "全量备份"
    ImportKind.STATS -> "统计备份（旧）"
    ImportKind.BACKUP -> "歌单备份（旧）"
    null -> "未知格式（不可导入）"
}

/**
 * 「扫描 Download 导入」的底部弹层：扫描中 → 勾选列表 →「导入所选」→ 汇总回执。
 * 行样式与进度/结果反馈沿用本页导入卡与 [OptionSheet] 的交互语言。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanDownloadSheet(
    state: ScanUiState,
    onImport: (List<ScannedBackup>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 默认勾选所有能识别的；扫到新一批文件就重置勾选
    var checked by remember(state.files) {
        mutableStateOf(state.files.filter { it.kind != null }.map { it.uri }.toSet())
    }

    ModalBottomSheet(
        onDismissRequest = { if (!state.importing) onDismiss() },
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
                Text(text = "扫描 Download 导入", style = LXType.labelLarge, color = colors.ink1)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "在系统 Download 目录（含子目录）查找 .lxmc 备份，识别后勾选导入。",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

            when {
                state.scanning -> Text(
                    text = state.progress.ifEmpty { "扫描中…" },
                    style = LXType.labelLarge,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )

                state.files.isEmpty() -> Text(
                    text = "Download 目录里没找到 .lxmc 备份。部分机型（尤其 Android 13+）" +
                        "看不到其它应用放进 Download 的文件，可改用上方「导入听歌统计 / 导入歌单备份」" +
                        "手动选择文件。",
                    style = LXType.labelLarge,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )

                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.files.forEach { file ->
                        val selectable = file.kind != null && !state.importing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = selectable) {
                                    checked = if (file.uri in checked) checked - file.uri else checked + file.uri
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = file.uri in checked,
                                enabled = selectable,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + file.uri else checked - file.uri
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = LXType.labelLarge,
                                    color = colors.ink1,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${scanKindText(file.kind)} · ${file.path}",
                                    style = LXType.labelSmall,
                                    color = colors.ink3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 底部：导入按钮 + 进度 / 汇总回执
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                if (state.files.isNotEmpty()) {
                    val selected = state.files.filter { it.uri in checked && it.kind != null }
                    TextButton(
                        onClick = { onImport(selected) },
                        enabled = selected.isNotEmpty() && !state.importing,
                    ) {
                        Text(
                            text = if (state.importing) "导入中…" else "导入所选（${selected.size}）",
                            style = LXType.labelLarge,
                            color = colors.accent,
                        )
                    }
                }
                if (state.importing && state.progress.isNotEmpty()) {
                    Text(text = state.progress, style = LXType.labelSmall, color = colors.ink3)
                }
                state.receipt?.let {
                    Text(
                        text = it,
                        style = LXType.labelLarge,
                        color = if (state.failed) colors.danger else colors.ink1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(LXDimens.space24))
            }
        }
    }
}