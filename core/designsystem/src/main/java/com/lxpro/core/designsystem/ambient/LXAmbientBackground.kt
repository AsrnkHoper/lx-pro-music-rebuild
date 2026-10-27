package com.lxpro.core.designsystem.ambient

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.graphicsLayer
import com.lxpro.core.designsystem.theme.LXGrainBlend
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import java.util.Random
import kotlinx.coroutines.delay

/**
 * 氛围层：底色 + 双呼吸光斑 + 颗粒（16 §2）。
 *
 * ⚠️ 光斑周期 15s : 19s，肉眼不应看出规律；[enabled] 关闭后完全不绘制（省电）。
 *
 * ⚠️ **光斑按 [AMBIENT_FPS] 帧率量化重建，不逐帧重绘**（2026-10-02 真机性能实测）：
 * 原先用 `rememberInfiniteTransition` 让整块氛围层跟着屏幕刷新率重绘 —— 15s / 19s 的漂移
 * 逐帧重绘肉眼毫无差别，却让「什么都不做」的页面也每秒重绘 90~120 次（实测静态页 8s 内
 * 画了 742 帧、CPU 帧耗时中位数 14ms），主线程被持续占满，一滚动就掉帧。
 * 量化后每秒只重建 12 次，运动轨迹（三角波 = 原型 `.leak` 的往返呼吸）完全一致。
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
            val primaryPeriod = LXMotion.leakPrimaryPeriod / 1000f
            val secondaryPeriod = LXMotion.leakSecondaryPeriod / 1000f

            // 主相位移 0→1→0（等价于原 `repeatMode = Reverse` 的 15s 往返）
            var phasePrimary by remember { mutableFloatStateOf(0f) }
            // 次相位 1→0→1（原实现从 1 起、Reverse 回 1）
            var phaseSecondary by remember { mutableFloatStateOf(1f) }

            LaunchedEffect(primaryPeriod, secondaryPeriod) {
                val start = SystemClock.elapsedRealtimeNanos()
                while (true) {
                    val seconds = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000_000f
                    phasePrimary = triangle(seconds, primaryPeriod)
                    phaseSecondary = 1f - triangle(seconds, secondaryPeriod)
                    // ⚠️ 用 delay 而不是 withFrameNanos：后者会**每帧向编舞器要一次帧**，
                    // 页面即使没变化也持续出帧（实测静止页每秒上百帧）。delay 只在自己醒来的
                    // 那一刻写状态，没有状态变化就一帧都不画 —— 这才是真的静下来。
                    delay(AMBIENT_FRAME_MS)
                }
            }

            // 颗粒是静态的：单独成节点（自带一层 RenderNode），随光斑重绘时不重复执行
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
                drawGrain(scalars.grainAlpha, scalars.grainBlend)
            }

            // 光斑同样单独成节点：别的地方一动（如播放中的均衡条）场面会整块重录，
            // 独立层只是被「引用」一次，不会跟着重跑两个全屏径向渐变
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer()) {
                drawLeak(colors.leak1, phasePrimary)
                drawLeak(colors.leak2, phaseSecondary)
            }
        }
        content()
    }
}

/** 0 → 1 → 0 的三角波，`period` 秒走一半（与 `animateFloat` + `RepeatMode.Reverse` 等价） */
private fun triangle(seconds: Float, period: Float): Float {
    val x = (seconds / period) % 2f
    return if (x <= 1f) x else 2f - x
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
        brush = grainBrush,
        alpha = alpha,
        blendMode = when (blend) {
            LXGrainBlend.OVERLAY -> BlendMode.Overlay
            LXGrainBlend.MULTIPLY -> BlendMode.Multiply
        },
    )
}

/** 颗粒刷只建一次（原实现在每帧的 draw 里 new 一个 ShaderBrush） */
private val grainBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
}

/** 氛围层的重建间隔（83ms ≈ 12fps；光斑 15s/19s 漂移，这个台阶肉眼看不出） */
private const val AMBIENT_FRAME_MS = 83L

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