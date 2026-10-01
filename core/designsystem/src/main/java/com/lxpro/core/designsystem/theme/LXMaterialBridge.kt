package com.lxpro.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * LX token → Material3 的桥接。
 *
 * ⚠️ **不桥接的后果**（2026-10-02 真机验收反馈）：Material3 组件
 * （`OutlinedTextField` / `Switch` / `CircularProgressIndicator` / `Button` …）
 * 走的是 M3 自己的 `ColorScheme`，与 LX 情绪配色无关 —— 在 ④ 白这类浅底上会变成
 * 默认紫调，在暗底上则"深字压深底"，用户看到的就是**字看不清**。
 *
 * 设计意图：LX 的 token 是唯一色彩来源，M3 只是组件实现细节。
 */
internal fun LXColorScheme.toMaterialColorScheme(): ColorScheme {
    val isLight = bg.luminance() > 0.5f
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    val errorColor = if (isLight) Color(0xFFB3261E) else Color(0xFFF2B8B5)
    val onErrorColor = if (isLight) Color(0xFFFFFFFF) else Color(0xFF601410)

    return base.copy(
        primary = accent,
        onPrimary = bg,
        primaryContainer = accent,
        onPrimaryContainer = bg,
        secondary = accent,
        onSecondary = bg,
        secondaryContainer = bg2,
        onSecondaryContainer = ink1,
        tertiary = accent,
        onTertiary = bg,
        background = bg,
        onBackground = ink1,
        surface = surface,
        onSurface = ink1,
        surfaceVariant = bg2,
        onSurfaceVariant = ink2,
        surfaceContainer = surface,
        surfaceContainerLow = surface,
        surfaceContainerLowest = bg,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = bg2,
        outline = ink3,
        outlineVariant = hairline,
        error = errorColor,
        onError = onErrorColor,
        scrim = Color(0x99000000),
        inverseSurface = ink1,
        inverseOnSurface = bg,
        inversePrimary = accent,
        surfaceTint = accent,
    )
}

/** LX 的 11 档字号即 M3 的字体阶梯，避免两套字号体系并存（14 §1 无游离字号） */
internal val LxMaterialTypography: Typography = Typography(
    displaySmall = LXType.displaySmall,
    headlineLarge = LXType.headlineLarge,
    headlineSmall = LXType.headlineSmall,
    titleLarge = LXType.titleLarge,
    titleMedium = LXType.titleMedium,
    titleSmall = LXType.titleSmall,
    bodyLarge = LXType.bodyLarge,
    bodyMedium = LXType.bodyMedium,
    bodySmall = LXType.bodySmall,
    labelLarge = LXType.labelLarge,
    labelSmall = LXType.labelSmall,
)