package com.lxpro.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 七套配色（六套实测 + 第 7 套封面动态取色占位）。
 *
 * 数据来源：`DesignerDocs/LX/UI/research/derive_tokens.py` → `13-色彩系统.md` §4/§5。
 * 默认 = ④ 白·留白极简（12 §2 #4 已取代原「深色优先」）。
 */
sealed class LXPalette(
    val id: String,
    val displayName: String,
    val colors: LXColorScheme,
    val scalars: LXScalars,
) {

    /** ① 暗绿·园林硬边 */
    data object GreenHard : LXPalette(
        id = "green_hard",
        displayName = "① 暗绿·园林硬边",
        colors = LXColorScheme(
            bg = Color(0xFF12130F),
            bg2 = Color(0xFF191B15),
            surface = Color(0xFF23251E),
            ink1 = Color(0xFFDDE0D6),
            ink2 = Color(0xFF969E8D),
            ink3 = Color(0xFF61665B),
            accent = Color(0xFF939E7E),
            hairline = Color(0x17DDE0D6),
            inset = Color(0x0FDDE0D6),
            leak1 = Color(0x4D788462),
            leak2 = Color(0x4246503A),
        ),
        scalars = LXScalars(
            sectionGap = 18.dp,
            sidePadding = 13.dp,
            grainAlpha = 0.028f,
            grainBlend = LXGrainBlend.OVERLAY,
            shadowStyle = LXShadowStyle.HARD,
        ),
    )

    /** ①b 暗绿·园林软化（黑位抬到 ~.10） */
    data object GreenSoft : LXPalette(
        id = "green_soft",
        displayName = "①b 暗绿·园林软化",
        colors = LXColorScheme(
            bg = Color(0xFF1B1D17),
            bg2 = Color(0xFF24261E),
            surface = Color(0xFF292C23),
            ink1 = Color(0xFFE2E6DB),
            ink2 = Color(0xFFA4AD9B),
            ink3 = Color(0xFF6F7569),
            accent = Color(0xFF9DA886),
            hairline = Color(0x17E2E6DB),
            inset = Color(0x0FE2E6DB),
            leak1 = Color(0x478C9874),
            leak2 = Color(0x3D545E48),
        ),
        scalars = LXScalars(
            sectionGap = 21.dp,
            sidePadding = 15.dp,
            grainAlpha = 0.038f,
            grainBlend = LXGrainBlend.OVERLAY,
            shadowStyle = LXShadowStyle.DIFFUSE,
        ),
    )

    /** ② 冷蓝·天光柔雾 */
    data object BlueMist : LXPalette(
        id = "blue_mist",
        displayName = "② 冷蓝·天光柔雾",
        colors = LXColorScheme(
            bg = Color(0xFFDEE6EA),
            bg2 = Color(0xFFD6DFE5),
            surface = Color(0xFFEFF4F7),
            ink1 = Color(0xFF232D36),
            // ⚠️ ink-2 已按 13 §7 修正为 #556373（原 #5D6C7D 仅 4.25:1）
            ink2 = Color(0xFF556373),
            ink3 = Color(0xFF8A99A6),
            accent = Color(0xFF4F6B84),
            hairline = Color(0x14232D36),
            inset = Color(0x0D232D36),
            leak1 = Color(0xF2D4E6F3),
            leak2 = Color(0x73F0F5EE),
        ),
        scalars = LXScalars(
            sectionGap = 22.dp,
            sidePadding = 15.dp,
            grainAlpha = 0.080f,
            grainBlend = LXGrainBlend.MULTIPLY,
            shadowStyle = LXShadowStyle.DIFFUSE,
        ),
    )

    /** ④ 白·留白极简（**默认**） */
    data object WhiteMinimal : LXPalette(
        id = "white_minimal",
        displayName = "④ 白·留白极简",
        colors = LXColorScheme(
            bg = Color(0xFFF4F1EA),
            bg2 = Color(0xFFEEEADF),
            surface = Color(0xFFFBF9F4),
            ink1 = Color(0xFF2E2A23),
            ink2 = Color(0xFF6E6D68),
            ink3 = Color(0xFFA39C90),
            accent = Color(0xFF87755C),
            hairline = Color(0x122E2A23),
            inset = Color(0x0B2E2A23),
            leak1 = Color(0xF2FAF2E2),
            leak2 = Color(0x8CF0F6F4),
        ),
        scalars = LXScalars(
            sectionGap = 34.dp,
            sidePadding = 22.dp,
            grainAlpha = 0.055f,
            grainBlend = LXGrainBlend.MULTIPLY,
            shadowStyle = LXShadowStyle.DIFFUSE,
        ),
    )

    /** ⑤ 灰·心灰意冷 */
    data object GrayLight : LXPalette(
        id = "gray_light",
        displayName = "⑤ 灰·心灰意冷",
        colors = LXColorScheme(
            bg = Color(0xFFEDEDED),
            bg2 = Color(0xFFE2E2E2),
            surface = Color(0xFFF9F9F9),
            ink1 = Color(0xFF222222),
            ink2 = Color(0xFF6B6B6B),
            ink3 = Color(0xFFA6A6A6),
            accent = Color(0xFF575757),
            hairline = Color(0x14222222),
            inset = Color(0x0D222222),
            leak1 = Color(0xF2FFFFFF),
            leak2 = Color(0x99ECECEC),
        ),
        scalars = LXScalars(
            sectionGap = 32.dp,
            sidePadding = 21.dp,
            grainAlpha = 0.100f,
            grainBlend = LXGrainBlend.MULTIPLY,
            shadowStyle = LXShadowStyle.DIFFUSE,
        ),
    )

    /** ⑤b 灰·万念俱灰 */
    data object GrayDeep : LXPalette(
        id = "gray_deep",
        displayName = "⑤b 灰·万念俱灰",
        colors = LXColorScheme(
            bg = Color(0xFF101010),
            bg2 = Color(0xFF171717),
            surface = Color(0xFF202020),
            ink1 = Color(0xFFE6E6E6),
            ink2 = Color(0xFF949494),
            ink3 = Color(0xFF575757),
            accent = Color(0xFFBDBDBD),
            hairline = Color(0x17E6E6E6),
            inset = Color(0x0FE6E6E6),
            leak1 = Color(0x33969696),
            leak2 = Color(0x2E5A5A5A),
        ),
        scalars = LXScalars(
            sectionGap = 20.dp,
            sidePadding = 14.dp,
            grainAlpha = 0.035f,
            grainBlend = LXGrainBlend.OVERLAY,
            shadowStyle = LXShadowStyle.HARD,
        ),
    )

    /**
     * 第 7 套 · 封面动态取色。
     * ⚠️ M0 阶段为**占位**：算法见 13 §6（在六顶点间插值），M3 落地；
     * 在算法落地前回落 ④ 白，符合 13 §6.3 的降级链。
     */
    data object Dynamic : LXPalette(
        id = "dynamic",
        displayName = "封面动态取色",
        colors = WhiteMinimal.colors,
        scalars = WhiteMinimal.scalars,
    )

    companion object {
        /** 六套实测配色（不含第 7 套占位） */
        val measured: List<LXPalette> = listOf(
            GreenHard, GreenSoft, BlueMist, WhiteMinimal, GrayLight, GrayDeep,
        )

        val all: List<LXPalette> = measured + Dynamic

        val default: LXPalette = WhiteMinimal

        fun fromId(id: String?): LXPalette = all.firstOrNull { it.id == id } ?: default
    }
}

/** 供代码里少写一点 dp 后缀 */
private val Int.dp: Dp get() = Dp(this.toFloat())