package com.lxpro.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXMotion

/**
 * 列表项入场（16 §4）：淡入 + 上移 14dp，逐项 `stagger 40ms`。
 *
 * ⚠️ 延迟按 index 递增但**封顶**：几十项的列表若线性累加，最后一项要等两秒才出现。
 */
@Composable
fun LXStaggerItem(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val enterOffset = with(LocalDensity.current) { ENTER_RISE.roundToPx() }
    val delay = index.coerceAtMost(MAX_STAGGER_ITEMS) * LXMotion.staggerStep

    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

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