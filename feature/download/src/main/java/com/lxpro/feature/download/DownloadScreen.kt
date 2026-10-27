@file:OptIn(ExperimentalLayoutApi::class)

package com.lxpro.feature.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXPillChip
import com.lxpro.core.designsystem.component.LXSwitch
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.download.DownloadItem
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 下载管理页（M3 任务 8）。
 *
 * 版面依据 `mockup_download.html`（同 `mockup_app.html` 的 `#pg-dl`）：
 * 存储占用 → 下载中 → 已完成（含音质筛选）→ 下载设置。
 *
 * 与原型的差别只有一处：这里的任务**都是真的**（真实下载引擎），不是 mock 数据。
 */
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    // 删除要二次确认（用户 2026-10-03 要求）：删任务会连带删文件
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmClearDone by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            onNotice(text)
            viewModel.onMessageShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding(),
    ) {
        // ── 顶栏 ──
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
                Text(text = "下载管理", style = LXType.titleSmall, color = colors.ink1)
                Text(
                    text = "${state.active.count { it.isRunning }} 个下载中 · " +
                        "${state.active.size} 个任务 · ${state.completedTotal} 个已完成",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
            // 全部暂停 ⇄ 全部继续：图标按钮（不用文字按钮）
            IconButton(onClick = viewModel::onToggleAll) {
                Icon(
                    imageVector = if (state.hasActiveWork) LxIcons.Pause else LxIcons.Play,
                    contentDescription = if (state.hasActiveWork) "全部暂停" else "全部继续",
                    tint = if (state.hasActiveWork) colors.ink1 else colors.accent,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = scalars.sidePadding)
                .padding(bottom = scalars.sectionGap),
        ) {
            UsageCard(state)

            // ── 下载中 ──
            SectionHeader(title = "下载中", count = state.active.size.toString())
            if (state.active.isEmpty()) {
                EmptyHint("没有进行中的下载")
            } else {
                LXCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 15.dp)) {
                        state.active.forEach { item ->
                            ActiveRow(
                                item = item,
                                concurrency = state.concurrency,
                                onPause = { viewModel.onPause(item) },
                                onResume = { viewModel.onResume(item) },
                                onRetry = { viewModel.onRetry(item) },
                                onRemove = { pendingDelete = item },
                            )
                        }
                    }
                }
            }

            // ── 已完成 ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = scalars.sectionGap, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "已完成", style = LXType.titleSmall, color = colors.ink1)
                Text(
                    text = "${state.completed.size} / ${state.completedTotal} 条",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 7.dp),
                )
                Spacer(Modifier.weight(1f))
                if (state.completedTotal > 0) {
                    TextButton(onClick = { confirmClearDone = true }) {
                        Text(text = "清空已完成", style = LXType.labelSmall, color = colors.ink3)
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LXDimens.space8),
                verticalArrangement = Arrangement.spacedBy(LXDimens.space8),
            ) {
                QualityFilter.entries.forEach { option ->
                    LXPillChip(
                        label = option.label,
                        selected = option == state.filter,
                        onClick = { viewModel.onFilterChange(option) },
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            if (state.completed.isEmpty()) {
                EmptyHint("该音质下没有已完成的下载")
            } else {
                LXCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 15.dp)) {
                        state.completed.forEach { item ->
                            CompletedRow(
                                item = item,
                                onPlay = {
                                    val (song, queue) = viewModel.playQueue(item)
                                    onPlaySong(song, queue)
                                },
                                onRemove = { pendingDelete = item },
                            )
                        }
                    }
                }
            }

            // ── 下载设置 ──
            SectionHeader(title = "下载设置")
            LXCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(LXDimens.space16)) {
                    SettingSelect(
                        title = "同时下载数",
                        description = "超出上限的任务留在排队中（15 §6.2）",
                        options = (1..5).map { it to "$it" },
                        current = state.concurrency,
                        onPick = viewModel::onConcurrencyChange,
                    )
                    SettingSelect(
                        title = "失败自动重试",
                        description = "取流失败或网络中断时自动重试的次数",
                        options = (0..3).map { it to if (it == 0) "不重试" else "$it 次" },
                        current = state.retryLimit,
                        onPick = viewModel::onRetryLimitChange,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "完成后自动入本地库", style = LXType.labelLarge, color = colors.ink1)
                            Text(
                                text = "下完即可在「本地音乐」里看到，不用等下次扫描",
                                style = LXType.labelSmall,
                                color = colors.ink3,
                            )
                        }
                        LXSwitch(
                            checked = state.autoImport,
                            onCheckedChange = viewModel::onAutoImportChange,
                        )
                    }
                    Text(
                        text = "存放位置：应用私有目录 files/downloads（免分区存储授权；删除任务会一并删文件）",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // 删除二次确认：任务删掉会连带删文件与本地库索引行，不能一点就没
        pendingDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = colors.surface,
                title = { Text(text = "删除下载", style = LXType.titleLarge, color = colors.ink1) },
                text = {
                    Text(
                        text = if (item.isDone) {
                            "《${item.task.name}》的下载文件会被删除，本地音乐里也会一并移除。"
                        } else {
                            "《${item.task.name}》尚未下载完成，已下载的部分会被丢弃。"
                        },
                        style = LXType.bodyMedium,
                        color = colors.ink2,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onRemove(item)
                        pendingDelete = null
                    }) {
                        Text(text = "删除", style = LXType.labelLarge, color = colors.danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(text = "取消", style = LXType.labelLarge, color = colors.ink3)
                    }
                },
            )
        }
    // 清空已完成也是删文件，同样要确认
        if (confirmClearDone) {
            AlertDialog(
                onDismissRequest = { confirmClearDone = false },
                containerColor = colors.surface,
                title = { Text(text = "清空已完成", style = LXType.titleLarge, color = colors.ink1) },
                text = {
                    Text(
                        text = "${state.completedTotal} 个已完成任务的下载文件都会被删除，" +
                            "本地音乐里也会一并移除。",
                        style = LXType.bodyMedium,
                        color = colors.ink2,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.onClearCompleted()
                        confirmClearDone = false
                    }) {
                        Text(text = "清空", style = LXType.labelLarge, color = colors.danger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClearDone = false }) {
                        Text(text = "取消", style = LXType.labelLarge, color = colors.ink3)
                    }
                },
            )
        }
    }
}

// ── 存储占用 ──

@Composable
private fun UsageCard(state: DownloadUiState) {
    val colors = LXTheme.colors
    val used = state.localBytes + state.cacheBytes
    val total = state.deviceTotalBytes.coerceAtLeast(1L)
    LXCard(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatBytes(used),
                    style = LXType.headlineSmall,
                    color = colors.ink1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "共 ${formatBytes(state.deviceTotalBytes)} · 已用 ${(used * 100 / total)}%",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
            // 双段条：本地音乐 / 下载缓存（两段 + 剩余 spacer，按字节比例分配）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(LXTheme.pageInset),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(state.localBytes.coerceAtLeast(1L).toFloat())
                        .fillMaxSize()
                        .background(colors.accent),
                )
                Box(
                    modifier = Modifier
                        .weight(state.cacheBytes.coerceAtLeast(1L).toFloat())
                        .fillMaxSize()
                        .background(colors.ink3.copy(alpha = 0.55f)),
                )
                Spacer(Modifier.weight((state.deviceFreeBytes).coerceAtLeast(1L).toFloat()))
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                LegendDot("本地音乐", colors.accent)
                LegendDot("下载缓存", colors.ink3.copy(alpha = 0.55f))
                LegendDot("剩余", colors.inset)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .background(color),
        )
        Text(
            text = label,
            style = LXType.labelSmall,
            color = LXTheme.colors.ink2,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

// ── 任务行 ──

@Composable
private fun ActiveRow(
    item: DownloadItem,
    concurrency: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverBox(item.task.coverUrl, 44)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.task.name,
                style = LXType.bodySmall,
                color = colors.ink1,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Badge(SourceLabels.of(item.task.source))
                Badge(item.quality?.let { qualityLabel(it) } ?: item.task.quality, accent = isLossless(item.quality))
                Text(
                    text = metaOf(item, concurrency),
                    style = LXType.labelSmall,
                    color = if (item.isFailed) colors.danger else colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProgressBar(
                progress = item.task.progress,
                color = if (item.isRunning) colors.accent else colors.ink3,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                item.isRunning -> OpsButton(LxIcons.Pause, "暂停", onPause)
                item.isFailed -> OpsButton(LxIcons.Play, "重新下载", onRetry)
                item.isPaused -> OpsButton(LxIcons.Play, "续传", onResume)
                // 排队中：只能删
                else -> Unit
            }
            OpsButton(LxIcons.Trash, "删除任务", onRemove, danger = true)
        }
    }
}

@Composable
private fun CompletedRow(item: DownloadItem, onPlay: () -> Unit, onRemove: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverBox(item.task.coverUrl, 44)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.task.name,
                style = LXType.bodySmall,
                color = colors.ink1,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Badge(SourceLabels.of(item.task.source))
                Badge(item.quality?.let { qualityLabel(it) } ?: item.task.quality, accent = isLossless(item.quality))
                Text(
                    text = "${formatBytes(item.task.totalBytes)} · ${formatDate(item.task.updatedAt)}",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OpsButton(LxIcons.Trash, "删除下载", onRemove, danger = true)
    }
}

@Composable
private fun CoverBox(url: String?, size: Int) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LXTheme.pageInset),
    ) {
        AsyncArtwork(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Badge(text: String, accent: Boolean = false) {
    val colors = LXTheme.colors
    Text(
        text = text,
        style = LXType.labelSmall,
        color = if (accent) colors.accent else colors.ink2,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (accent) colors.accent.copy(alpha = 0.13f) else colors.inset)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    )
}

@Composable
private fun ProgressBar(progress: Int, color: Color, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(LXTheme.pageInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.001f))
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

@Composable
private fun OpsButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, danger: Boolean = false) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(LXTheme.pageInset)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (danger) colors.danger else colors.ink2,
            modifier = Modifier.size(15.dp),
        )
    }
}

// ── 通用小件 ──

@Composable
private fun SectionHeader(title: String, count: String? = null) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = scalars.sectionGap, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = LXType.titleSmall, color = colors.ink1)
        if (count != null) {
            Text(
                text = count,
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = LXType.bodySmall, color = colors.ink3)
    }
}

@Composable
private fun SettingSelect(
    title: String,
    description: String,
    options: List<Pair<Int, String>>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = title, style = LXType.labelLarge, color = colors.ink1)
        Text(text = description, style = LXType.labelSmall, color = colors.ink3)
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(LXDimens.space8),
            verticalArrangement = Arrangement.spacedBy(LXDimens.space8),
        ) {
            options.forEach { (value, label) ->
                LXPillChip(
                    label = label,
                    selected = value == current,
                    onClick = { onPick(value) },
                )
            }
        }
    }
}

// ── 格式化 ──

private fun metaOf(item: DownloadItem, concurrency: Int): String = when {
    item.isFailed -> "下载失败 · ${item.task.error ?: "可换源后重试"}"
    item.isPaused -> "已暂停 · ${item.task.progress}%"
    item.isQueued -> "排队中（并发上限 $concurrency）"
    item.task.totalBytes > 0 ->
        "${item.task.progress}% · ${formatBytes(item.task.downloadedBytes)}/${formatBytes(item.task.totalBytes)}"

    else -> "${formatBytes(item.task.downloadedBytes)}"
}

private fun isLossless(quality: Quality?): Boolean =
    quality in setOf(Quality.FLAC, Quality.HIRES, Quality.MASTER)

private fun qualityLabel(quality: Quality): String = when (quality) {
    Quality.K128 -> "128K"
    Quality.K320 -> "320K"
    Quality.FLAC -> "FLAC"
    Quality.HIRES -> "Hi-Res"
    Quality.ATMOS -> "Atmos"
    Quality.ATMOS_PLUS -> "Atmos+"
    Quality.MASTER -> "Master"
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

private fun formatDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormatter)

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}