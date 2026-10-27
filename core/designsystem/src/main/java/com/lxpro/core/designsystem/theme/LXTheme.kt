package com.lxpro.core.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalLXColors = staticCompositionLocalOf { LXPalette.default.colors }
private val LocalLXScalars = staticCompositionLocalOf { LXPalette.default.scalars }
private val LocalLXPalette = staticCompositionLocalOf { LXPalette.default }

/** 动态背景（封面铺满+模糊+遮罩）是否正在显示；由 [LXTheme] 的参数提供 */
val LocalDynamicBgActive = staticCompositionLocalOf { false }

/** 动态背景激活时页面级容器的 surface 透明度（封面色透进容器的强度，调这里） */
internal const val PAGE_SURFACE_ALPHA = 0.45f

/** 设计系统入口：全程从 token 取色取距，**禁止硬编码颜色**（14 §5 无游离颜色） */
object LXTheme {
    val colors: LXColorScheme
        @Composable @ReadOnlyComposable get() = LocalLXColors.current

    val scalars: LXScalars
        @Composable @ReadOnlyComposable get() = LocalLXScalars.current

    val palette: LXPalette
        @Composable @ReadOnlyComposable get() = LocalLXPalette.current

    /** 动态背景是否正在显示（封面铺满+模糊+遮罩） */
    val dynamicBgActive: Boolean
        @Composable @ReadOnlyComposable get() = LocalDynamicBgActive.current

    /**
     * 页面级容器（卡片、全局栏）的底色：动态背景激活时半透明，让封面色透进所有容器；
     * 模态层（弹层/弹窗/浮标）仍用 [LXColorScheme.surface] 保持不透明，不透出下层内容。
     */
    val pageSurface: Color
        @Composable @ReadOnlyComposable get() {
            val s = colors.surface
            return if (dynamicBgActive) s.copy(alpha = PAGE_SURFACE_ALPHA) else s
        }

    /**
     * 凹槽块 / 控件轨道底色。
     *
     * ⚠️ **不能对这个色做 `copy(alpha = ...)`**：`inset` token 本身就是**半透明叠色**
     * （各配色 alpha 只有 0x0B–0x0F，约 4–6%），它天然就是"底下是什么就透什么"。
     * 把它改写成 0.45 会从 6% 叠色变成 45% 实色，凹槽瞬间变成比卡片深得多的实心板，
     * 与所在卡片形成两截色（2026-10-25 真机反馈的根因）。
     * 所以动态背景激活与否，这里都原样返回 —— 它本来就跟着背景走。
     */
    val pageInset: Color
        @Composable @ReadOnlyComposable get() = colors.inset

    /** [pageSurface] 的 bg2 版 */
    val pageBg2: Color
        @Composable @ReadOnlyComposable get() {
            val c = colors.bg2
            return if (dynamicBgActive) c.copy(alpha = PAGE_SURFACE_ALPHA) else c
        }
}

/**
 * 配色不是换色卡 —— 颜色与六个联动标量**同时**过渡，
 * 用 `animateColorAsState` / `animateDpAsState`，时长 `LXMotion.ambient`（13 §8 #2）。
 */
@Composable
fun LXTheme(palette: LXPalette, dynamicBgActive: Boolean = false, content: @Composable () -> Unit) {
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
        danger = animateColorAsState(source.danger, colorSpec, label = "danger").value,
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
        LocalDynamicBgActive provides dynamicBgActive,
    ) {
        // M3 组件必须吃到同一套颜色，否则会出现"字看不清"（见 LXMaterialBridge 注释）。
        // ⚠️ 动态背景激活时把 pageAlpha 一并喂给桥接层：否则开关/滑杆/复选框这些
        // M3 默认色仍是全不透明 surfaceVariant + outline，叠在透色卡片上就是两截色。
        MaterialTheme(
            colorScheme = remember(animatedColors, dynamicBgActive) {
                animatedColors.toMaterialColorScheme(
                    pageAlpha = if (dynamicBgActive) PAGE_SURFACE_ALPHA else 1f,
                )
            },
            typography = LxMaterialTypography,
            content = content,
        )
    }
}