package com.lxpro.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType

/**
 * 轻提示（`mockup_playlist.html` 的 `.toast`）。
 *
 * 不承担任何交互，只回报「刚刚做了什么」。由调用方在展示结束后把 message 置回 null。
 */
@Composable
fun LXToast(message: String?, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(LXMotion.quick)) + slideInVertically(tween(LXMotion.quick)) { it / 2 },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(LXDimens.radiusSmall),
            color = colors.ink1,
            contentColor = colors.bg,
        ) {
            Text(
                text = message.orEmpty(),
                style = LXType.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}