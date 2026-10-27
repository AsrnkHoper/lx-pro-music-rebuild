package com.lxpro.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXMotion

/**
 * 列表项入场（16 §4）：淡入 + 上移 14dp，逐项 `stagger 40ms`。
 *
 * ⚠️ 延迟按 index 递增但**封顶**：几十项的列表若线性累加，最后一项要等两秒才出现。
 *
 * ⚠️ **每页只播一次**：离开页面时目标页的组合会被销毁，回来时若重新播一遍，
 * 「返回」看起来就像「又进了一次」（2026-10-02 真机反馈）。
 * `rememberSaveable` 能跨导航存活 —— NavHost 为每个 BackStackEntry 保存状态。
 */
@Composable
fun LXStaggerItem(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val enterOffset = with(LocalDensity.current) { ENTER_RISE.roundToPx() }
    val delay = index.coerceAtMost(MAX_STAGGER_ITEMS) * LXMotion.staggerStep

    var played by rememberSaveable { mutableStateOf(false) }
    val visibleState = remember { MutableTransitionState(played) }
    if (!played) {
        LaunchedEffect(Unit) {
            visibleState.targetState = true
            played = true
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(animationSpec = tween(LXMotion.quick, delayMillis = delay)) +
            slideInVertically(
                animationSpec = tween(LXMotion.quick, delayMillis = delay),
                initialOffsetY = { enterOffset },
            ),
        modifier = modifier,
        label = "staggerItem",
    ) {
        content()
    }
}

private val ENTER_RISE = 14.dp
private const val MAX_STAGGER_ITEMS = 8