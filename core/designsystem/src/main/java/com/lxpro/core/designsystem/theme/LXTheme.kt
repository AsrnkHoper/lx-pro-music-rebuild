package com.lxpro.core.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalLXColors = staticCompositionLocalOf { LXPalette.default.colors }
private val LocalLXScalars = staticCompositionLocalOf { LXPalette.default.scalars }
private val LocalLXPalette = staticCompositionLocalOf { LXPalette.default }

/** 设计系统入口：全程从 token 取色取距，**禁止硬编码颜色**（14 §5 无游离颜色） */
object LXTheme {
    val colors: LXColorScheme
        @Composable @ReadOnlyComposable get() = LocalLXColors.current

    val scalars: LXScalars
        @Composable @ReadOnlyComposable get() = LocalLXScalars.current

    val palette: LXPalette
        @Composable @ReadOnlyComposable get() = LocalLXPalette.current
}

/**
 * 配色不是换色卡 —— 颜色与六个联动标量**同时**过渡，
 * 用 `animateColorAsState` / `animateDpAsState`，时长 `LXMotion.ambient`（13 §8 #2）。
 */
@Composable
fun LXTheme(palette: LXPalette, content: @Composable () -> Unit) {
    val colorSpec = tween<Color>(durationMillis = LXMotion.ambient)
    val source = palette.colors

    val animatedColors = LXColorScheme(
        bg = animateColorAsState(source.bg, colorSpec, label = "bg").value,
        bg2 = animateColorAsState(source.bg2, colorSpec, label = "bg2").value,
        surface = animateColorAsState(source.surface, colorSpec, label = "surface").value,
        ink1 = animateColorAsState(source.ink1, colorSpec, label = "ink1").value,
        ink2 = animateColorAsState(source.ink2, colorSpec, label = "ink2").value,
        ink3 = animateColorAsState(source.ink3, colorSpec, label = "ink3").value,
        accent = animateColorAsState(source.accent, colorSpec, label = "accent").value,
        hairline = animateColorAsState(source.hairline, colorSpec, label = "hairline").value,
        inset = animateColorAsState(source.inset, colorSpec, label = "inset").value,
        leak1 = animateColorAsState(source.leak1, colorSpec, label = "leak1").value,
        leak2 = animateColorAsState(source.leak2, colorSpec, label = "leak2").value,
    )

    val sourceScalars = palette.scalars
    val animatedScalars = LXScalars(
        sectionGap = animateDpAsState(
            sourceScalars.sectionGap,
            tween(LXMotion.ambient),
            label = "sectionGap",
        ).value,
        sidePadding = animateDpAsState(
            sourceScalars.sidePadding,
            tween(LXMotion.ambient),
            label = "sidePadding",
        ).value,
        grainAlpha = animateFloatAsState(
            sourceScalars.grainAlpha,
            tween(LXMotion.ambient),
            label = "grainAlpha",
        ).value,
        grainBlend = sourceScalars.grainBlend,
        shadowStyle = sourceScalars.shadowStyle,
    )

    CompositionLocalProvider(
        LocalLXColors provides animatedColors,
        LocalLXScalars provides animatedScalars,
        LocalLXPalette provides palette,
        content = content,
    )
}