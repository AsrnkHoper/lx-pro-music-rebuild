package com.lxpro.core.designsystem.ambient

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.lxpro.core.designsystem.theme.LXGrainBlend
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import java.util.Random

/**
 * 氛围层：底色 + 双呼吸光斑 + 颗粒（16 §2）。
 *
 * ⚠️ 光斑周期 15s : 19s，肉眼不应看出规律；[enabled] 关闭后完全不绘制（省电）。
 */
@Composable
fun LXAmbientBackground(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        if (enabled) {
            val transition = rememberInfiniteTransition(label = "ambient")
            val phasePrimary by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(LXMotion.leakPrimaryPeriod, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "leakPrimary",
            )
            val phaseSecondary by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(LXMotion.leakSecondaryPeriod, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "leakSecondary",
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLeak(colors.leak1, phasePrimary)
                drawLeak(colors.leak2, phaseSecondary)
                drawGrain(scalars.grainAlpha, scalars.grainBlend)
            }
        }
        content()
    }
}

private fun DrawScope.drawLeak(color: Color, phase: Float) {
    val centerX = size.width * (0.22f + 0.56f * phase)
    val centerY = size.height * (0.16f + 0.26f * phase)
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = Offset(centerX, centerY),
            radius = size.minDimension * 0.78f,
        ),
    )
}

private fun DrawScope.drawGrain(alpha: Float, blend: LXGrainBlend) {
    if (alpha <= 0f) return
    drawRect(
        brush = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated)),
        alpha = alpha,
        blendMode = when (blend) {
            LXGrainBlend.OVERLAY -> BlendMode.Overlay
            LXGrainBlend.MULTIPLY -> BlendMode.Multiply
        },
    )
}

private val grainTile: ImageBitmap by lazy { createNoiseTile(96) }

private fun createNoiseTile(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val random = Random(20261001L)
    val pixels = IntArray(size * size) { index ->
        if (index % 7 == 0) {
            val value = random.nextInt(256)
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        } else {
            0
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}