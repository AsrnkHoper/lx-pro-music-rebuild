package com.lxpro.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType

/** 一个底部 Tab 项 */
data class LXTabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 底部 5 Tab（14 §4.1）。
 *
 * - 高 [LXDimens.tabBarHeight]（64dp），**不透明**背景 —— 半透明会让滚动内容透出来（原型实测踩坑）
 * - 选中 `accent`，未选中 `ink3`
 * - 平面语言下靠 1dp 发丝线分界，不用阴影
 * - 自身吃掉导航栏安全区（背景铺到底，图标不被系统手势条压住）
 */
@Composable
fun LXBottomBar(
    items: List<LXTabItem>,
    selectedRoute: String?,
    onSelect: (LXTabItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors

    Column(modifier = modifier.fillMaxWidth().background(colors.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

        Row(
            modifier = Modifier.fillMaxWidth().height(LXDimens.tabBarHeight),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.route == selectedRoute
                val tint by animateColorAsState(
                    targetValue = if (selected) colors.accent else colors.ink3,
                    animationSpec = tween(LXMotion.quick),
                    label = "tabTint",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(text = item.label, style = LXType.labelSmall, color = tint)
                }
            }
        }

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}