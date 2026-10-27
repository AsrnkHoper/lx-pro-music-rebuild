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
 *
 * @param pageAlpha 页面级表面的不透明度（1f = 原样）。动态背景激活时传
 *   [PAGE_SURFACE_ALPHA]，让**所有** M3 组件（开关轨道 / 滑杆非激活轨 / 复选框描边 /
 *   输入框 / 分隔线）都跟着透出封面色 —— 否则这些组件默认色是不透明 `surfaceVariant`
 *   与 `outline`，叠在透色卡片上就成了「外边框一种颜色、内部另一种颜色」的两截色。
 *   模态层（AlertDialog / ModalBottomSheet / DropdownMenu）各自显式传了
 *   `containerColor = colors.surface`，不受此参数影响，仍保持不透明。
 */
internal fun LXColorScheme.toMaterialColorScheme(pageAlpha: Float = 1f): ColorScheme {
    val isLight = bg.luminance() > 0.5f
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    // 危险色沿用各主题的 `--danger`（不再吃 M3 默认红，那是游离颜色）
    val errorColor = danger
    val onErrorColor = if (danger.luminance() > 0.5f) Color(0xFF1A1313) else Color(0xFFF7EFEC)

    // 页面级表面统一透气；hairline 本身就是低 alpha 叠色，不再二次压暗
    val surf = surface.copy(alpha = pageAlpha)
    val surfVar = bg2.copy(alpha = pageAlpha)
    val pageBg = bg.copy(alpha = pageAlpha)

    return base.copy(
        primary = accent,
        onPrimary = bg,
        primaryContainer = accent,
        onPrimaryContainer = bg,
        secondary = accent,
        onSecondary = bg,
        secondaryContainer = surfVar,
        onSecondaryContainer = ink1,
        tertiary = accent,
        onTertiary = bg,
        background = pageBg,
        onBackground = ink1,
        surface = surf,
        onSurface = ink1,
        surfaceVariant = surfVar,
        onSurfaceVariant = ink2,
        surfaceContainer = surf,
        surfaceContainerLow = surf,
        surfaceContainerLowest = surf,
        surfaceContainerHigh = surf,
        surfaceContainerHighest = surfVar,
        // ⚠️ 只有 surface 家族是**不透明实色**，动态背景时要按 pageAlpha 透气；
        // `hairline` / `inset` 本身就是低 alpha 叠色，绝不能再次改 alpha（会变实心）。
        outline = ink3.copy(alpha = pageAlpha),
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