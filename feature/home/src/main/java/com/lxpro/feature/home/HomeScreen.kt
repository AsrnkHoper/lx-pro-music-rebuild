package com.lxpro.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXPillChip
import com.lxpro.core.designsystem.component.LXSectionHeader
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding

/**
 * M0 的验证落地页：证明「工程骨架 + 七套配色 + 氛围层 + DI + DataStore」这条链路可用。
 * 正式首页（问候 + 文案 + 双模式搜索 + 三货架）在 M3 按 `mockup_home.html` 落地。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    palette: LXPalette,
    onPaletteSelected: (LXPalette) -> Unit,
    ambientEnabled: Boolean,
    onAmbientChange: (Boolean) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = scalars.sidePadding, vertical = scalars.sectionGap),
    ) {
        Text(text = "LX Pro", style = LXType.headlineLarge, color = colors.ink1)
        Spacer(Modifier.height(LXDimens.space8))
        Text(
            text = "M0 地基验证 · 平面材质 + 七选一情绪配色",
            style = LXType.bodyMedium,
            color = colors.ink2,
        )

        Spacer(Modifier.height(scalars.sectionGap))

        LXSectionHeader(title = "配色")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LXDimens.space8),
            verticalArrangement = Arrangement.spacedBy(LXDimens.space8),
        ) {
            LXPalette.all.forEach { candidate ->
                LXPillChip(
                    label = candidate.displayName,
                    selected = candidate.id == palette.id,
                    onClick = { onPaletteSelected(candidate) },
                )
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))

        LXCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(LXDimens.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "氛围动效", style = LXType.titleSmall, color = colors.ink1)
                    Text(
                        text = "呼吸光斑 + 颗粒；关闭后完全不绘制",
                        style = LXType.labelLarge,
                        color = colors.ink2,
                    )
                }
                Switch(checked = ambientEnabled, onCheckedChange = onAmbientChange)
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))

        LXSectionHeader(title = "搜索")
        TextButton(onClick = onOpenSearch) {
            Text(text = "搜索歌曲（内置小哔音乐）", style = LXType.labelLarge, color = colors.accent)
        }

        Spacer(Modifier.height(scalars.sectionGap))

        LXSectionHeader(title = "关于")
        TextButton(onClick = onOpenAbout) {
            Text(text = "构建信息与合规声明", style = LXType.labelLarge, color = colors.accent)
        }
    }
}