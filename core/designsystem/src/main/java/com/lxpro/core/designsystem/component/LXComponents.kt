package com.lxpro.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXShadowStyle
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.model.SearchMode

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
    // ⚠️ 动态背景激活时卡片是半透明的，**投影会从卡片内部透出来**（实测反推为 22% 黑色），
    // 在卡片四周形成一圈灰带，与透出来的封面色构成「外框一种色、内部另一种色」的两截色。
    // 所以透色模式下不打投影、不描发丝边：卡片与背景靠亮度差分层，不必再叠一圈线框。
    val translucent = LXTheme.dynamicBgActive
    val elevation = if (translucent) 0.dp else when (scalars.shadowStyle) {
        LXShadowStyle.DIFFUSE -> 2.dp
        LXShadowStyle.HARD -> 6.dp
    }
    Surface(
        modifier = modifier,
        color = LXTheme.pageSurface,
        shape = RoundedCornerShape(LXDimens.radiusMedium),
        border = if (translucent) null else BorderStroke(1.dp, colors.hairline),
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

/**
 * 搜索范围三档切换（全部 / 在线音源 / 本地音乐）。
 *
 * 「全部」是默认档：统一搜索，本地结果区在前 + 在线结果区接后；
 * 「在线」「本地」只是把同一份结果过滤到对应部分。
 *
 * ⚠️ 15 §4.2 #2：它必须**紧贴搜索框正下方** —— 这是「在线 + 本地」双模式定位的 UI 体现。
 * 首页与搜索页共用同一枚组件。
 *
 * 批四（2026-10-10）单行化：旧版每档主副两行文字（副文字如「本地 + 在线」「N 个已装」），
 * 用户嫌「一团东西…看着好累」—— 副文字全部拿掉，只留单行主文字，weight(1f) 等宽均分。
 */
@Composable
fun LXModeSwitch(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(LXTheme.pageInset)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeButton(
            label = "全部",
            selected = mode == SearchMode.ALL,
            onClick = { onModeChange(SearchMode.ALL) },
            modifier = Modifier.weight(1f),
        )
        ModeButton(
            label = "在线音源",
            selected = mode == SearchMode.ONLINE,
            onClick = { onModeChange(SearchMode.ONLINE) },
            modifier = Modifier.weight(1f),
        )
        ModeButton(
            label = "本地音乐",
            selected = mode == SearchMode.LOCAL,
            onClick = { onModeChange(SearchMode.LOCAL) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 单行文字、居中、单行省略；选中 = surface 底 + 微阴影（原型 `.mode.on`） */
@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    // 选中片必须跟外壳一起透气：用不透明 surface 会在透色模式下变成"灰壳 + 白片"的两截色
    val translucent = LXTheme.dynamicBgActive
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) LXTheme.pageSurface else Color.Transparent)
            .then(
                if (selected && !translucent) {
                    Modifier.shadow(elevation = 1.dp, shape = RoundedCornerShape(9.dp), clip = false)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = LXType.bodySmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else LXType.bodySmall.fontWeight,
            ),
            color = if (selected) colors.ink1 else colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
        color = if (selected) colors.accent else LXTheme.pageInset,
    ) {
        Text(
            text = label,
            style = LXType.labelLarge,
            color = if (selected) colors.bg else colors.ink2,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * 开关：LX 配色统一版（裸 M3 `Switch()` 默认 surfaceVariant 轨道 + outline 描边，两截色且不透动态背景）。
 * 轨道走 [LXTheme.pageInset]：动态背景激活时透出封面色。
 */
@Composable
fun LXSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.bg,
            checkedTrackColor = colors.accent,
            uncheckedThumbColor = colors.ink3,
            uncheckedTrackColor = LXTheme.pageInset,
            uncheckedBorderColor = colors.hairline,
        ),
    )
}

/** 滑杆：LX 配色统一版；非激活轨道走 [LXTheme.pageInset]（动态背景下透出封面色） */
@Composable
fun LXSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val colors = LXTheme.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = LXTheme.pageInset,
        ),
    )
}