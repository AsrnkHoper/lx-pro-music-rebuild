package com.lxpro.feature.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXSectionHeader
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding

/** 集合详情页路由参数（id 是 UUID，单个路径段里没有斜杠，安全） */
const val ARG_EQ_COLLECTION_ID = "collectionId"
const val ROUTE_EQ_COLLECTION = "eq/collection/{$ARG_EQ_COLLECTION_ID}"

fun eqCollectionRoute(collectionId: String) = "eq/collection/$collectionId"

/**
 * 收藏集合详情页（17 §5.7：**网格 → 详情两级**，与「我的歌单」同一套交互，不做同屏）。
 *
 * 页面职责：列出集合内曲线 · 直接「应用」· 移除单条 · 重命名 / 删除集合。
 */
@Composable
fun EqCollectionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EqCollectionViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    // 重命名 / 删除集合（删除必须确认，17 §5.7）
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    if (renameOpen) {
        EqCollectionNameDialog(
            title = "重命名集合",
            initial = ui.name,
            existingNames = ui.existingNames,
            onConfirm = { name ->
                viewModel.rename(name)
                renameOpen = false
            },
            onDismiss = { renameOpen = false },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            containerColor = colors.surface,
            title = { Text("删除集合", style = LXType.titleSmall, color = colors.ink1) },
            text = {
                Text(
                    "「${ui.name}」将被删除，里面的收藏条目一并移除（曲线本身不受影响）。",
                    style = LXType.bodySmall, color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text("删除", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("取消", color = colors.ink3) }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize().lxSafeDrawingPadding()) {
        // ── 头部（返回 + 集合名 + 重命名 / 删除）──
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
                Text(
                    ui.name.ifBlank { "收藏集合" },
                    style = LXType.titleSmall, color = colors.ink1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${ui.rows.size} 条曲线 · 只作用于歌曲",
                    style = LXType.labelSmall, color = colors.ink3,
                )
            }
            IconButton(onClick = { renameOpen = true }) {
                Icon(LxIcons.Pencil, contentDescription = "重命名", tint = colors.ink2)
            }
            IconButton(onClick = { deleteOpen = true }) {
                Icon(LxIcons.Trash, contentDescription = "删除集合", tint = colors.ink2)
            }
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
            item(key = "header") {
                LXSectionHeader(title = "集合内曲线", trailing = {
                    if (ui.rows.isNotEmpty()) {
                        Text(
                            "点行即套用",
                            style = LXType.bodySmall, color = colors.ink3,
                        )
                    }
                })
            }

            items(items = ui.rows, key = { it.preset.name }) { row ->
                CollectionItemRow(
                    preset = row.preset,
                    alias = row.alias,
                    active = !ui.bypass && ui.currentName == row.preset.name,
                    onClick = { viewModel.apply(row.preset) },
                    onRemove = { viewModel.remove(row.preset.name) },
                )
                Spacer(Modifier.height(4.dp))
            }

            if (ui.rows.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("这个集合还是空的", style = LXType.bodyMedium, color = colors.ink2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "回均衡器页，在曲线行右侧点 ☆ 把曲线加进来",
                            style = LXType.bodySmall, color = colors.ink3,
                        )
                    }
                }
            }
        }
    }
}

/** 集合内一条曲线：别名（或原型号）· 品牌/目录 · 迷你曲线 · 应用态 · 移除 */
@Composable
private fun CollectionItemRow(
    preset: com.lxpro.core.media.eq.EqPreset,
    alias: String?,
    active: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LXTheme.colors
    LXCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    alias ?: preset.name, style = LXType.bodyMedium,
                    color = if (active) colors.accent else colors.ink1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 17 §5.6：有别名时副标题让位给「原型号」
                    Text(
                        alias?.let { preset.name } ?: preset.brand,
                        style = LXType.labelSmall, color = colors.ink3,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        preset.layer.layerKey, style = LXType.labelSmall, color = colors.ink3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(LXTheme.pageInset)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text("已启用", style = LXType.labelSmall, color = colors.accent)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            EqSpark(gains = preset.gains, modifier = Modifier.size(width = 56.dp, height = 22.dp))
            Spacer(Modifier.width(10.dp))
            // 移除单条（17 §5.7）；点它不会触发行套用（内层 clickable 先消费）
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LxIcons.Close, contentDescription = "从集合移除",
                    tint = colors.ink3, modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
