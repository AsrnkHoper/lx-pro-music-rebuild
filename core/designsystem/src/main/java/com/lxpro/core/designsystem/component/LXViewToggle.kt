package com.lxpro.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme

/**
 * 视图模式切换按钮（**图标式**，非文字按钮）。
 *
 * 样式取自「我的歌单」页的 `.vtoggle`：34×28 的格子、选中用 `surface` 底 + `ink-1` 图标、
 * 未选中 `inset` 底 + `ink-3` 图标。
 *
 * 用户 2026-10-03 明确要求：**所有涉及列表的地方都用这种图标式选项，不要用文字做按钮**
 * （此前统计页的「有图 / 无图」是文字按钮，被指出不合格）。成组使用见 [LXViewToggleGroup]。
 */
@Composable
fun LXViewToggle(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 28.dp)
            // 选中片与外壳（inset 叠色）必须同时透气，否则"灰壳 + 白片"两截色
            .background(if (selected) LXTheme.pageSurface else colors.inset)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) colors.ink1 else colors.ink3,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 把若干 [LXViewToggle] 拼成一枚分段控件（整组一个圆角，中间不留缝） */
@Composable
fun LXViewToggleGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier = modifier.clip(RoundedCornerShape(LXDimens.radiusSmall)), content = content)
}