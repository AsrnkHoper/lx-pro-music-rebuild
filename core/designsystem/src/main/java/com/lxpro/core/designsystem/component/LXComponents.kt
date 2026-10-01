package com.lxpro.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXShadowStyle
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType

/**
 * 卡片：平面材质 = 纯色 + 发丝线 + 弥散影（12 §2 #5 已取代液态玻璃）。
 * 阴影性格随配色变化（13 §5）。
 */
@Composable
fun LXCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val elevation = when (scalars.shadowStyle) {
        LXShadowStyle.DIFFUSE -> 2.dp
        LXShadowStyle.HARD -> 6.dp
    }
    Surface(
        modifier = modifier,
        color = colors.surface,
        shape = RoundedCornerShape(LXDimens.radiusMedium),
        border = BorderStroke(1.dp, colors.hairline),
        shadowElevation = elevation,
    ) {
        Column(content = content)
    }
}

/** 分区标题 + 发丝线（14 §2.3：标题下间距 11dp） */
@Composable
fun LXSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LXTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = LXType.titleSmall, color = colors.ink1)
        trailing?.invoke()
    }
}

/** 胶囊标签 / 筛选 chip */
@Composable
fun LXPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = if (selected) colors.accent else colors.inset,
    ) {
        Text(
            text = label,
            style = LXType.labelLarge,
            color = if (selected) colors.bg else colors.ink2,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}