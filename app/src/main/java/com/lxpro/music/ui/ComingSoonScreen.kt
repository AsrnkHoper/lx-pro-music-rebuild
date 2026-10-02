package com.lxpro.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding

/**
 * 尚未落地的 Tab 占位页。
 *
 * ⚠️ 明写「属于哪个里程碑」而不是留一个空页面 —— 让「现在到哪一步了」一眼可见，
 * 避免把未完成误当成坏掉。
 */
@Composable
fun ComingSoonScreen(
    title: String,
    milestone: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .padding(horizontal = scalars.sidePadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = LXType.headlineLarge, color = colors.ink1)
        Spacer(Modifier.height(LXDimens.space12))
        Text(
            text = "$milestone · 待落地",
            style = LXType.labelLarge,
            color = colors.accent,
        )
        Spacer(Modifier.height(LXDimens.space8))
        Text(
            text = description,
            style = LXType.bodyMedium,
            color = colors.ink3,
            textAlign = TextAlign.Center,
        )
    }
}