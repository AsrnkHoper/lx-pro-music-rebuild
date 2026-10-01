package com.lxpro.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 一套情绪配色的颜色令牌。
 *
 * 命名映射（13 §4）：`bg`→background、`surface`→surface、
 * `ink1/2/3`→onBackground/onSurfaceVariant/outline、`accent`→primary。
 *
 * ⚠️ 所有取值均为 `research/derive_tokens.py` 的实测输出，**不得肉眼估色**（13 §5）。
 */
@Immutable
data class LXColorScheme(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val hairline: Color,
    val inset: Color,
    val leak1: Color,
    val leak2: Color,
)

/** 颗粒混合模式：深色底必须用 OVERLAY，否则噪点被压没（13 §5） */
enum class LXGrainBlend { MULTIPLY, OVERLAY }

/** 阴影性格：柔光比高 → 弥散；低 → 紧而深 */
enum class LXShadowStyle { DIFFUSE, HARD }

/**
 * 与配色**同时切换**的联动标量。
 * ⚠️ 只换颜色不换标量 = 实现错误（13 §5）。
 */
@Immutable
data class LXScalars(
    val sectionGap: Dp,
    val sidePadding: Dp,
    val grainAlpha: Float,
    val grainBlend: LXGrainBlend,
    val shadowStyle: LXShadowStyle,
)

/** 固定的间距底座（14 §2.1），不随配色变化 */
object LXDimens {
    val space2 = 2.dp
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space48 = 48.dp

    val radiusSmall = 8.dp
    val radiusMedium = 16.dp
    val radiusLarge = 24.dp

    /** 迷你播放条 / 底部 Tab（14 §3.2） */
    val miniBarHeight = 56.dp
    val tabBarHeight = 64.dp
    val bottomInset = 158.dp

    val coverList = 44.dp
    val coverFull = 270.dp
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
private val Float.dp get() = androidx.compose.ui.unit.Dp(this)