package com.lxpro.feature.eq

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXPillChip
import com.lxpro.core.designsystem.component.LXSectionHeader
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.media.eq.EQ_PEAK_FREQS
import com.lxpro.core.media.eq.EqLayer
import com.lxpro.core.media.eq.EqOutputDevice
import com.lxpro.core.media.eq.EqPreset
import com.lxpro.core.media.eq.EqPresetRepository
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 均衡器页（17 §5，定稿原型 mockup_eq.html）。
 *
 * 批次范围（m4b3-4）：当前音效卡（大曲线 + 参数小标）· 搜索 + 品牌/目录筛选 ·
 * 142 条校正曲线列表（迷你曲线）·「正常（直通）」置顶。
 * 别名 / 收藏集合 / 自动匹配 / 导入预设 在后续批次（Room 表批一已备好）。
 */
@Composable
fun EqScreen(
    onBack: () -> Unit,
    onOpenCollection: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: EqViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    // 别名编辑目标（17 §5.6；null=未打开）
    var aliasTarget by remember { mutableStateOf<String?>(null) }
    // 「选集合」目标曲线（17 §5.7：列表行右侧 ☆ → 可多选）
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    // 「新建集合」对话框（我的收藏区块 +）
    var newCollectionOpen by remember { mutableStateOf(false) }
    // 删除导入的曲线（17 §5.3；内置 142 条不可删，删前确认）
    var deleteTarget by remember { mutableStateOf<EqPreset?>(null) }
    // 导入预设：解析成功的草稿（确认命名后才入库）+ 解析失败提示
    var importDraft by remember { mutableStateOf<EqPresetFileParser.Result.Success?>(null) }
    var importFailure by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    // SAF 选文件（17 §5.3：AutoEQ / PowerAMP 曲线文件；不限 MIME，文本按内容嗅探）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            when (val result = readEqPresetFile(context, uri)) {
                is EqPresetFileParser.Result.Success -> importDraft = result
                is EqPresetFileParser.Result.Failure -> importFailure = result.reason
            }
        }
    }

    // 删除导入曲线确认
    deleteTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.surface,
            title = { Text("删除导入的曲线", style = LXType.titleSmall, color = colors.ink1) },
            text = {
                Text(
                    "「${ui.aliases[preset.name] ?: preset.name}」将从本机移除（内置的 142 条不受影响）。",
                    style = LXType.bodySmall, color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUserPreset(preset.name)
                    deleteTarget = null
                }) { Text("删除", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = colors.ink3) }
            },
        )
    }
    // 解析成功 → 命名确认（文件名的型号部分是默认名，可改）
    importDraft?.let { draft ->
        EqImportDialog(
            draft = draft,
            existingNames = ui.rows.map { it.name },
            onConfirm = { name ->
                viewModel.importPreset(name, draft.gains, draft.source)
                importDraft = null
            },
            onDismiss = { importDraft = null },
        )
    }
    // 解析失败提示
    importFailure?.let { reason ->
        AlertDialog(
            onDismissRequest = { importFailure = null },
            containerColor = colors.surface,
            title = { Text("无法识别这个文件", style = LXType.titleSmall, color = colors.ink1) },
            text = { Text(reason, style = LXType.bodySmall, color = colors.ink2) },
            confirmButton = {
                TextButton(onClick = { importFailure = null }) { Text("知道了", color = colors.accent) }
            },
        )
    }
    // 导入结果回执（重名 / 成功）
    ui.importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearImportMessage,
            containerColor = colors.surface,
            title = { Text("导入预设", style = LXType.titleSmall, color = colors.ink1) },
            text = { Text(message, style = LXType.bodySmall, color = colors.ink2) },
            confirmButton = {
                TextButton(onClick = viewModel::clearImportMessage) {
                    Text("知道了", color = colors.accent)
                }
            },
        )
    }
    // 别名编辑对话框（独立窗口渲染，与页面层级无关）
    aliasTarget?.let { originalName ->
        EqAliasDialog(
            originalName = originalName,
            currentAlias = ui.aliases[originalName],
            onConfirm = { value ->
                viewModel.setAlias(originalName, value)
                aliasTarget = null
            },
            onClear = {
                viewModel.clearAlias(originalName)
                aliasTarget = null
            },
            onDismiss = { aliasTarget = null },
        )
    }
    // 选集合弹层（可多选；勾选差异写回 17 §5.7）
    pickerTarget?.let { presetName ->
        EqCollectionPickerDialog(
            presetLabel = ui.aliases[presetName] ?: presetName,
            originalName = presetName,
            collections = ui.collections,
            initialSelected = ui.membership[presetName].orEmpty(),
            onCreateAndAdd = { name -> viewModel.createCollectionAndAdd(name, presetName) },
            onConfirm = { selected ->
                viewModel.setMembership(presetName, selected)
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null },
        )
    }
    // 新建集合
    if (newCollectionOpen) {
        EqCollectionNameDialog(
            title = "新建收藏集合",
            initial = "",
            existingNames = ui.collections.map { it.name },
            onConfirm = { name ->
                viewModel.createCollection(name)
                newCollectionOpen = false
            },
            onDismiss = { newCollectionOpen = false },
        )
    }

    Column(modifier = modifier.fillMaxSize().lxSafeDrawingPadding()) {
        // ── 头部（详情页：返回 + 标题，与统计榜单页同一套）──
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
            Column(modifier = Modifier.weight(1f)) {
                Text("均衡器", style = LXType.titleSmall, color = colors.ink1)
                Text(
                    if (ui.bypass) "耳机校正 · 只作用于歌曲" else "已启用 · 只作用于歌曲",
                    style = LXType.labelSmall, color = colors.ink3,
                )
            }
        }

        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = scalars.sidePadding,
                end = scalars.sidePadding,
                top = 8.dp,
                bottom = scalars.sectionGap,
            ),
        ) {
            // ── 当前音效卡 ──
            item(key = "current") {
                CurrentEqCard(ui = ui, onEditAlias = { aliasTarget = it })
                Spacer(Modifier.height(LXDimens.space12))
            }

            // ── 自动匹配（17 §5.3：检测当前输出设备 → 一键套用对应曲线）──
            item(key = "automatch") {
                AutoMatchCard(
                    device = ui.outputDevice,
                    match = ui.match,
                    aliasOf = { name -> ui.aliases[name] },
                    activeName = ui.currentName,
                    bypass = ui.bypass,
                    onApply = { viewModel.apply(it) },
                )
                Spacer(Modifier.height(LXDimens.space24))
            }

            // ── 我的收藏（17 §5.7：集合网格 2 列 + 新建；网格 → 详情两级）──
            item(key = "collections") {
                LXSectionHeader(title = "我的收藏", trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.accent.copy(alpha = 0.12f))
                            .clickable { newCollectionOpen = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            LxIcons.Plus, contentDescription = null,
                            tint = colors.accent, modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("新建", style = LXType.labelLarge, color = colors.accent)
                    }
                })
                if (ui.collections.isEmpty()) {
                    // 空态引导（17 §5.7：收藏是集合，不是扁平喜欢列表）
                    LXCard {
                        Column(Modifier.padding(LXDimens.space16)) {
                            Text("还没有收藏集合", style = LXType.bodyMedium, color = colors.ink1)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "在下方曲线行右侧点 ☆ 选中意的曲线，按场景分组建集合（通勤 / 夜里 / 工作）",
                                style = LXType.bodySmall, color = colors.ink3,
                            )
                        }
                    }
                } else {
                    ui.collections.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LXDimens.space12),
                        ) {
                            row.forEach { collection ->
                                CollectionCard(
                                    collection = collection,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onOpenCollection(collection.id) },
                                )
                            }
                            // 单数行的补位，保证卡片等宽
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(LXDimens.space12))
                    }
                }
                Spacer(Modifier.height(LXDimens.space12))
            }

            // ── 搜索框 ──
            item(key = "search") {
                EqSearchBox(
                    query = ui.query,
                    total = ui.totalCount,
                    onQueryChange = viewModel::setQuery,
                )
                Spacer(Modifier.height(LXDimens.space12))
            }

            // ── 目录筛选 chips（2026-10-16 用户裁决：三层型号零重叠 → 筛选器，默认全部）──
            item(key = "layers") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(LXDimens.space8),
                ) {
                    LXPillChip(
                        label = "全部 ${ui.totalCount}",
                        selected = ui.layer == EqLayer.FILTER_ALL,
                        onClick = { viewModel.setLayer(EqLayer.FILTER_ALL) },
                    )
                    EqLayer.entries.forEach { layer ->
                        // 「自定义」层（导入预设）为空时不占位，避免 chips 上挂一个 0 条的入口
                        if (layer == EqLayer.CUSTOM && (ui.layerCounts[layer] ?: 0) == 0) return@forEach
                        LXPillChip(
                            label = "${layer.label} ${ui.layerCounts[layer] ?: 0}",
                            selected = ui.layer == layer.layerKey,
                            onClick = { viewModel.setLayer(layer.layerKey) },
                        )
                    }
                }
                Spacer(Modifier.height(LXDimens.space8))
            }

            // ── 品牌 chips（按出现次数降序，前置高频品牌；17 §5.4）──
            item(key = "brands") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(LXDimens.space8),
                ) {
                    LXPillChip(
                        label = "全部品牌",
                        selected = ui.brand == EqPresetRepository.BRAND_ALL,
                        onClick = { viewModel.setBrand(EqPresetRepository.BRAND_ALL) },
                    )
                    ui.brands.forEach { (brand, count) ->
                        LXPillChip(
                            label = "$brand $count",
                            selected = ui.brand == brand,
                            onClick = { viewModel.setBrand(brand) },
                        )
                    }
                }
                Spacer(Modifier.height(LXDimens.space16))
            }

            // ── 导入预设（17 §5.3：AutoEQ / PowerAMP 曲线文件；导入后并入下面列表，标「自定义」层）──
            item(key = "import") {
                ImportPresetRow(onClick = { importLauncher.launch(arrayOf("*/*")) })
                Spacer(Modifier.height(LXDimens.space16))
            }

            // ── 校正曲线列表 ──
            item(key = "listHeader") {
                LXSectionHeader(title = "校正曲线", trailing = {
                    Text(
                        "${ui.rows.size} / ${ui.totalCount} 条",
                        style = LXType.bodySmall, color = colors.ink3,
                    )
                })
            }

            // 「正常（直通）」置顶（定稿原型：列表最上方）
            item(key = "bypass") {
                BypassRow(active = ui.bypass, onClick = viewModel::bypass)
                Spacer(Modifier.height(4.dp))
            }

            items(items = ui.rows, key = { it.name }) { preset ->
                EqRow(
                    preset = preset,
                    alias = ui.aliases[preset.name],
                    active = !ui.bypass && ui.currentName == preset.name,
                    starred = !ui.membership[preset.name].isNullOrEmpty(),
                    onClick = { viewModel.apply(preset) },
                    onStar = { pickerTarget = preset.name },
                    onDelete = { deleteTarget = preset },
                )
                Spacer(Modifier.height(4.dp))
            }

            if (ui.rows.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("没有匹配的机型", style = LXType.bodyMedium, color = colors.ink2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "换个关键词，或点「重置筛选」",
                            style = LXType.bodySmall, color = colors.ink3,
                        )
                        Spacer(Modifier.height(LXDimens.space12))
                        LXPillChip(label = "重置筛选", selected = false, onClick = viewModel::resetFilters)
                    }
                }
            }
        }
    }
}

/* ════════════════ 当前音效卡（17 §5.3）════════════════ */

@Composable
private fun CurrentEqCard(ui: EqViewModel.UiState, onEditAlias: (String) -> Unit) {
    val colors = LXTheme.colors
    val current = ui.current
    val active = !ui.bypass && current != null
    // 17 §5.6：有别名时标题显示别名，副标题写原型号
    val alias = ui.currentName?.let { ui.aliases[it] }

    LXCard {
        Column(Modifier.padding(LXDimens.space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("当前音效", style = LXType.labelSmall, color = colors.ink3)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            !active -> "正常（直通）"
                            alias != null -> alias
                            else -> current!!.name
                        },
                        style = LXType.titleLarge, color = colors.ink1,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            !active -> "不加载任何校正曲线，信号原样输出"
                            // 有别名时副标题让位给「原型号」（17 §5.6）
                            alias != null -> "${current!!.name} · 只作用于歌曲音轨"
                            else -> "${current!!.brand} · 只作用于歌曲音轨"
                        },
                        style = LXType.bodySmall, color = colors.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 「别名」按钮（17 §5.3 / §5.6：给正在用的那条改名，可随时还原）
                if (active) {
                    Text(
                        "别名",
                        style = LXType.labelLarge,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.accent.copy(alpha = 0.12f))
                            .clickable { onEditAlias(current!!.name) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // 状态标（已启用 / 直通）
                Text(
                    if (active) "已启用" else "直通",
                    style = LXType.labelLarge,
                    color = if (active) colors.accent else colors.ink3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) colors.accent.copy(alpha = 0.12f) else colors.inset)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(LXDimens.space12))

            // 曲线可视化（17 §5.5：对数频轴 / dB 自适应 / 零线虚线 / accentSoft 填充；直通态只画零线）
            EqCurve(
                gains = if (active) current!!.gains else null,
                modifier = Modifier.fillMaxWidth().height(132.dp),
            )
            Spacer(Modifier.height(LXDimens.space12))

            // 参数小标（17 §5.3：32 段 / 峰值 / 谷值 / 前级 / 显示范围）
            if (active) {
                val gains = current!!.gains
                val range = eqDbRange(gains)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetaChip("32 段 · 20Hz–20kHz")
                    MetaChip("峰值 %+.1f dB".format(gains.max()))
                    MetaChip("谷值 %.1f dB".format(gains.min()))
                    MetaChip("前级 0.0 dB")
                    MetaChip("±$range dB")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(LXDimens.space8)) {
                    MetaChip("不加载曲线")
                    MetaChip("零延迟 · 无相位损失")
                    MetaChip("白噪音同样直通")
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    val colors = LXTheme.colors
    Text(
        text, style = LXType.labelSmall, color = colors.ink3,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LXTheme.pageInset)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/* ════════════════ 搜索框 ════════════════ */

@Composable
private fun EqSearchBox(query: String, total: Int, onQueryChange: (String) -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(LXTheme.pageInset)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LxIcons.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索耳机型号（$total 条）", style = LXType.bodySmall, color = colors.ink3)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LXType.bodySmall.copy(color = colors.ink1),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                LxIcons.Close, contentDescription = "清除", tint = colors.ink3,
                modifier = Modifier.size(14.dp).clip(CircleShape).clickable { onQueryChange("") },
            )
        }
    }
}

/* ════════════════ 列表行 ════════════════ */

/** 「正常（直通）」置顶行（定稿原型：列表最上方；17 §5.2 直通=彻底还原） */
@Composable
private fun BypassRow(active: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LXDimens.radiusSmall))
            .background(LXTheme.pageSurface)
            .then(
                if (active) Modifier.border(1.dp, colors.accent, RoundedCornerShape(LXDimens.radiusSmall))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("正常（直通）", style = LXType.bodyMedium, color = if (active) colors.accent else colors.ink1)
            Text("不加载曲线 · 信号原样输出", style = LXType.labelSmall, color = colors.ink3)
        }
        if (active) {
            Icon(LxIcons.Check, contentDescription = "使用中", tint = colors.accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** 校正曲线行：名称 / 副标题（品牌 · 目录标 · 32 段校正）/ 收藏星标 / 迷你曲线（17 §5.5：56×22） */
@Composable
private fun EqRow(
    preset: EqPreset,
    alias: String?,
    active: Boolean,
    starred: Boolean,
    onClick: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LXTheme.colors
    // 导入的自定义曲线才可删（内置 142 条是只读资产，17 §5.3）
    val deletable = preset.layer == EqLayer.CUSTOM
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LXDimens.radiusSmall))
            .background(LXTheme.pageSurface)
            .then(
                if (active) Modifier.border(1.dp, colors.accent, RoundedCornerShape(LXDimens.radiusSmall))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // 17 §5.6：有别名时行标题显示别名（原型号落到副标题）
            Text(
                alias ?: preset.name, style = LXType.bodyMedium,
                color = if (active) colors.accent else colors.ink1,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 17 §5.6：有别名时副标题写原型号；否则维持「已启用 / 品牌」
                when {
                    alias != null -> Text(
                        preset.name, style = LXType.labelSmall, color = colors.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    active -> Text("已启用", style = LXType.labelSmall, color = colors.accent)
                    else -> Text(preset.brand, style = LXType.labelSmall, color = colors.ink3)
                }
                Spacer(Modifier.width(6.dp))
                // 来源目录小标（2026-10-16 用户裁决：行内带层标记）
                if (deletable) {
                    // 导入的曲线（17 §5.3）：小标本身带删除入口（不额外占位，保持行清爽）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(LXTheme.pageInset)
                            .clickable(onClick = onDelete)
                            .padding(start = 4.dp, end = 3.dp, top = 1.dp, bottom = 1.dp),
                    ) {
                        Text("${preset.layer.label} · 删除", style = LXType.labelSmall, color = colors.ink3)
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            LxIcons.Close, contentDescription = "删除导入的曲线",
                            tint = colors.ink3, modifier = Modifier.size(9.dp),
                        )
                    }
                } else {
                    Text(
                        preset.layer.layerKey, style = LXType.labelSmall, color = colors.ink3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(LXTheme.pageInset)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("32 段校正", style = LXType.labelSmall, color = colors.ink3)
            }
        }
        Spacer(Modifier.width(10.dp))
        // 收藏星标（17 §5.7：列表行右侧 ☆ → 选集合，可多选）
        Icon(
            if (starred) LxIcons.StarFilled else LxIcons.Star,
            contentDescription = if (starred) "已收藏，点此管理集合" else "加入收藏集合",
            tint = if (starred) colors.accent else colors.ink3,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onStar),
        )
        Spacer(Modifier.width(10.dp))
        EqSpark(gains = preset.gains, modifier = Modifier.size(width = 56.dp, height = 22.dp))
    }
}

/* ════════════════ 曲线绘制（17 §5.5 + mockup_eq.html drawCurve/spark）════════════════ */

/** dB 显示范围自适应：最大绝对值向上取整到 3 的倍数，下限 6、上限 18 */
internal fun eqDbRange(gains: FloatArray?): Int {
    val m = gains?.let { g -> g.maxOf { kotlin.math.abs(it) } } ?: 0f
    return min(18, max(6, ceil(m / 3f).toInt() * 3))
}

private const val F_MIN = 20f
private const val F_MAX = 20000f
private val LOG_RANGE = log10(F_MAX) - log10(F_MIN)

/** 大曲线（当前音效卡）：对数频轴 20Hz–20k + dB 网格 + 零线虚线 + 曲线与填充 + 轴标签 */
@Composable
private fun EqCurve(gains: FloatArray?, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    val labelColor = colors.ink3
    val hairline = colors.hairline
    val accent = colors.accent
    val range = eqDbRange(gains)

    Canvas(modifier = modifier) {
        val padTop = 10.dp.toPx()
        val padBottom = 16.dp.toPx()
        val padX = 4.dp.toPx()
        val w = size.width
        val h = size.height
        val midY = padTop + (h - padBottom - padTop) / 2f
        val halfH = (h - padBottom - padTop) / 2f

        fun xOf(f: Float): Float = padX + (log10(f) - log10(F_MIN)) / LOG_RANGE * (w - padX * 2)
        fun yOf(g: Float): Float = midY - (g / range) * halfH

        // dB 网格线（±R、±R/2）
        listOf(-1f, -0.5f, 0.5f, 1f).forEach { k ->
            val y = yOf(k * range)
            drawLine(hairline, Offset(padX, y), Offset(w - padX, y), strokeWidth = 1f)
        }
        // 零线（虚线）
        drawLine(
            hairline, Offset(padX, midY), Offset(w - padX, midY), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
        )
        // 频标 100 / 1k / 10k
        listOf(100f, 1000f, 10000f).forEach { f ->
            val x = xOf(f)
            drawLine(hairline, Offset(x, padTop), Offset(x, h - padBottom), strokeWidth = 1f)
        }

        if (gains != null) {
            // 曲线（32 点折线，对数频轴）+ 下方 accentSoft 填充
            val path = Path()
            gains.forEachIndexed { i, g ->
                val x = xOf(EQ_PEAK_FREQS[i].toFloat())
                val y = yOf(g)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fillPath = Path().apply {
                addPath(path)
                lineTo(xOf(EQ_PEAK_FREQS.last().toFloat()), midY)
                lineTo(xOf(EQ_PEAK_FREQS.first().toFloat()), midY)
                close()
            }
            drawPath(fillPath, accent.copy(alpha = 0.12f))
            drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
        } else {
            // 直通态：一条实线零位（mockup 的 .flat）
            drawLine(hairline, Offset(padX, midY), Offset(w - padX, midY), strokeWidth = 2f)
        }

        // 轴标签（nativeCanvas 画文字）
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                (labelColor.alpha * 255).toInt(),
                (labelColor.red * 255).toInt(),
                (labelColor.green * 255).toInt(),
                (labelColor.blue * 255).toInt(),
            )
            textSize = 9.sp.toPx()
        }
        val c = drawContext.canvas.nativeCanvas
        val labelY = h - 3.dp.toPx()
        c.drawText("20", padX, labelY, paint)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText("100", xOf(100f), labelY, paint)
        c.drawText("1k", xOf(1000f), labelY, paint)
        c.drawText("10k", xOf(10000f), labelY, paint)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        c.drawText("20k Hz", w - padX, labelY, paint)
        c.drawText("+$range dB", w - padX, padTop + 7.dp.toPx(), paint)
        c.drawText("−$range dB", w - padX, h - padBottom - 1.dp.toPx(), paint)
        paint.textAlign = android.graphics.Paint.Align.LEFT
    }
}

/** 列表行迷你曲线（56×22，定稿原型 spark：x 按点序线性，不取对数）—— 集合详情页复用，故 internal */
@Composable
internal fun EqSpark(gains: FloatArray, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    val hairline = colors.hairline
    val accent = colors.accent
    val range = eqDbRange(gains)

    Canvas(modifier = modifier) {
        val m = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val midY = h / 2f
        // 零线
        drawLine(hairline, Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)
        val path = Path()
        gains.forEachIndexed { i, g ->
            val x = m + i.toFloat() / (gains.size - 1) * (w - m * 2)
            val y = midY - (g / range) * (midY - m)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path, accent.copy(alpha = 0.85f),
            style = Stroke(width = 1.3.dp.toPx()),
        )
    }
}

/* ════════════════ 别名编辑（17 §5.6）════════════════ */

/**
 * 别名编辑对话框（17 §5.6 用户明确要求）。
 *
 * **非破坏性**：只写「显示名映射」，原始型号名一个字都不改 ——
 * 输入空串 / 与原名相同、或点「还原原名」都等于删掉映射。
 */
@Composable
private fun EqAliasDialog(
    originalName: String,
    currentAlias: String?,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    var text by remember(originalName) { mutableStateOf(currentAlias ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("自定义别名", style = LXType.titleSmall, color = colors.ink1) },
        text = {
            Column {
                Text(
                    "原型号：$originalName",
                    style = LXType.bodySmall, color = colors.ink3,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    label = { Text("别名", style = LXType.labelSmall) },
                    placeholder = { Text(originalName, style = LXType.labelSmall) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                    singleLine = true,
                    supportingText = {
                        Text(
                            "只改显示名，原始型号名不受影响",
                            style = LXType.labelSmall, color = colors.ink3,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.hairline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定", color = colors.accent) }
        },
        dismissButton = {
            Row {
                if (currentAlias != null) {
                    TextButton(onClick = onClear) { Text("还原原名", color = colors.ink3) }
                }
                TextButton(onClick = onDismiss) { Text("取消", color = colors.ink3) }
            }
        },
    )
}

/* ════════════════ 收藏集合（17 §5.7）════════════════ */

/**
 * 收藏集合卡片（网格 2 列）。
 *
 * ⚠️ 集合卡片上**直接列出里面曲线的别名** —— 不点进去也知道装了啥（17 §5.7）。
 */
@Composable
private fun CollectionCard(
    collection: EqViewModel.CollectionUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    LXCard(modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(LXDimens.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    collection.name, style = LXType.bodyMedium, color = colors.ink1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text("${collection.count}", style = LXType.labelSmall, color = colors.ink3)
            }
            Spacer(Modifier.height(6.dp))
            if (collection.preview.isEmpty()) {
                Text(
                    "空集合 · 点曲线行的 ☆ 往里加",
                    style = LXType.labelSmall, color = colors.ink3,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                collection.preview.forEach { name ->
                    Text(
                        name, style = LXType.labelSmall, color = colors.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                if (collection.count > collection.preview.size) {
                    Text(
                        "等 ${collection.count} 条",
                        style = LXType.labelSmall, color = colors.ink3,
                    )
                }
            }
        }
    }
}

/**
 * 集合名输入对话框（新建 / 重命名共用；17 §5.7）。
 *
 * 用户订正：**不允许同时存在两个同名集合** —— 与 [existingNames] 撞名时内联报错并禁用确定；
 * [initial] 是「当前名」（重命名场景），它与自己同名不算冲突。
 */
@Composable
internal fun EqCollectionNameDialog(
    title: String,
    initial: String,
    existingNames: List<String> = emptyList(),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    var text by remember(initial) { mutableStateOf(initial) }
    val trimmed = text.trim()
    val conflict = trimmed.isNotEmpty() && existingNames.any {
        it.equals(trimmed, ignoreCase = true) && !it.equals(initial.trim(), ignoreCase = true)
    }
    val canConfirm = trimmed.isNotEmpty() && !conflict

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(title, style = LXType.titleSmall, color = colors.ink1) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                label = { Text("集合名", style = LXType.labelSmall) },
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                singleLine = true,
                isError = conflict,
                supportingText = {
                    if (conflict) {
                        Text("已存在同名集合", style = LXType.labelSmall, color = colors.accent)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = if (conflict) colors.accent else colors.hairline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(trimmed) },
                enabled = canConfirm,
            ) {
                Text("确定", color = if (canConfirm) colors.accent else colors.ink3)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.ink3) } },
    )
}

/**
 * 「选集合」弹层（17 §5.7：列表行右侧 ☆ → **可多选**）。
 *
 * 勾选差异在「确定」时一次性写回（[onConfirm]）；弹层底部内置「新建并加入」，
 * 免得用户为了加一个集合先退出去建、再回来勾。
 */
@Composable
private fun EqCollectionPickerDialog(
    presetLabel: String,
    originalName: String,
    collections: List<EqViewModel.CollectionUi>,
    initialSelected: Set<String>,
    onCreateAndAdd: (String) -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    // 以原始型号名为 key：切到另一条曲线时重新取初始勾选，不串味
    var selected by remember(originalName) { mutableStateOf(initialSelected) }
    var newName by remember(originalName) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("加入收藏集合", style = LXType.titleSmall, color = colors.ink1) },
        text = {
            Column {
                Text(
                    presetLabel, style = LXType.bodySmall, color = colors.ink3,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (collections.isEmpty()) {
                    Text("还没有集合，先在下面新建一个", style = LXType.bodySmall, color = colors.ink2)
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 236.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        collections.forEach { c ->
                            val checked = c.id in selected
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(LXDimens.radiusSmall))
                                    .clickable {
                                        selected = if (checked) selected - c.id else selected + c.id
                                    }
                                    .padding(horizontal = 4.dp, vertical = 9.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(if (checked) colors.accent else colors.inset)
                                        .border(
                                            1.dp,
                                            if (checked) colors.accent else colors.hairline,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (checked) {
                                        Icon(
                                            LxIcons.Check, contentDescription = null,
                                            tint = colors.surface, modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        c.name, style = LXType.bodyMedium, color = colors.ink1,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (c.preview.isNotEmpty()) {
                                        Text(
                                            c.preview.joinToString(" · "),
                                            style = LXType.labelSmall, color = colors.ink3,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("${c.count}", style = LXType.labelSmall, color = colors.ink3)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(24) },
                        placeholder = { Text("新建集合…", style = LXType.labelSmall) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.hairline,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "加入",
                        style = LXType.labelLarge,
                        color = if (newName.isNotBlank()) colors.accent else colors.ink3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(LXTheme.pageInset)
                            .clickable {
                                if (newName.isNotBlank()) {
                                    onCreateAndAdd(newName.trim())
                                    newName = ""
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定", color = colors.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.ink3) } },
    )
}

/* ════════════════ 自动匹配 / 导入预设（17 §5.3）════════════════ */

/**
 * 自动匹配卡：显示当前输出设备 + 匹配到的曲线 + 一键套用。
 *
 * 只认外接耳机（蓝牙 / 有线 / USB），外放不参与 —— 耳机校正曲线套到扬声器上没有意义。
 */
@Composable
private fun AutoMatchCard(
    device: EqOutputDevice?,
    match: EqPreset?,
    aliasOf: (String) -> String?,
    activeName: String?,
    bypass: Boolean,
    onApply: (EqPreset) -> Unit,
) {
    val colors = LXTheme.colors
    LXCard {
        Column(Modifier.padding(LXDimens.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动匹配", style = LXType.bodyMedium, color = colors.ink1)
                Spacer(Modifier.width(6.dp))
                Text("按当前输出设备挑曲线", style = LXType.labelSmall, color = colors.ink3)
            }
            Spacer(Modifier.height(6.dp))
            if (device == null) {
                Text(
                    "未检测到外接耳机 · 插上耳机或连上蓝牙后会自动识别",
                    style = LXType.labelSmall, color = colors.ink3,
                )
            } else {
                Text(
                    "当前输出：${device.name.ifBlank { device.kind }}（${device.kind}）",
                    style = LXType.labelSmall, color = colors.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                if (match == null) {
                    Text(
                        "没找到对应曲线 · 可在下方搜索型号名，或点「导入预设」",
                        style = LXType.labelSmall, color = colors.ink3,
                    )
                } else {
                    val applied = !bypass && activeName == match.name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                aliasOf(match.name) ?: match.name,
                                style = LXType.bodyMedium,
                                color = if (applied) colors.accent else colors.ink1,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(1.dp))
                            Text(
                                "匹配到 ${match.name}",
                                style = LXType.labelSmall, color = colors.ink3,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (applied) "已套用" else "套用",
                            style = LXType.labelLarge,
                            color = if (applied) colors.ink3 else colors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (applied) colors.inset else colors.accent.copy(alpha = 0.12f),
                                )
                                .clickable(enabled = !applied) { onApply(match) }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 导入预设入口（17 §5.3）：一行低调入口，点击拉起系统文件选择器 */
@Composable
private fun ImportPresetRow(onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LXDimens.radiusSmall))
            .background(LXTheme.pageSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("导入预设", style = LXType.bodyMedium, color = colors.ink1)
            Spacer(Modifier.height(2.dp))
            Text(
                "AutoEQ / PowerAMP 曲线文件（GraphicEQ 或 ParametricEQ 文本）",
                style = LXType.labelSmall, color = colors.ink3,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            LxIcons.Plus, contentDescription = null,
            tint = colors.accent, modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * 导入确认：显示识别到的格式 + 曲线预览，名称可改（默认取文件名）。
 *
 * 重名与内置 142 条 / 已导入的曲线撞车时内联报错（仓库层同样会拒绝，双保险）。
 */
@Composable
private fun EqImportDialog(
    draft: EqPresetFileParser.Result.Success,
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    var text by remember(draft.name) { mutableStateOf(draft.name) }
    val trimmed = text.trim()
    val conflict = trimmed.isNotEmpty() && existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canConfirm = trimmed.isNotEmpty() && !conflict

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("导入预设", style = LXType.titleSmall, color = colors.ink1) },
        text = {
            Column {
                Text(
                    "识别为 ${draft.format} · 已换算到 32 段",
                    style = LXType.labelSmall, color = colors.ink3,
                )
                Spacer(Modifier.height(8.dp))
                // 导入前先看一眼曲线形状（对不对劲心里有数）
                EqSpark(
                    gains = draft.gains,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(48) },
                    label = { Text("曲线名", style = LXType.labelSmall) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.ink1),
                    singleLine = true,
                    isError = conflict,
                    supportingText = {
                        if (conflict) {
                            Text("已存在同名曲线", style = LXType.labelSmall, color = colors.accent)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = if (conflict) colors.accent else colors.hairline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(trimmed) },
                enabled = canConfirm,
            ) {
                Text("导入", color = if (canConfirm) colors.accent else colors.ink3)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.ink3) } },
    )
}

/** 读 SAF 选中的曲线文件并解析（17 §5.3）；文件名取 DISPLAY_NAME 作为默认预设名 */
private fun readEqPresetFile(context: Context, uri: Uri): EqPresetFileParser.Result {
    val name = displayNameOf(context, uri) ?: "imported.eq"
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
    if (text == null) return EqPresetFileParser.Result.Failure("读不到文件内容（权限或文件已失效）")
    return EqPresetFileParser.parse(name, text)
}

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
