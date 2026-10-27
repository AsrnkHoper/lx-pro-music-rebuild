package com.lxpro.feature.download

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType

/** 悬浮球直径 */
private val BALL_SIZE = 58.dp

/**
 * 右下角下载悬浮球（用户 2026-10-03 要求，2026-10-05 追加：**可拖动**）。
 *
 * **只在有任务时出现**（排队中/下载中），任务全部结束自动消失。
 * 外圈进度环显示整体进度，右上角小角标是任务数 —— 圆内图标点一下进下载管理页。
 *
 * @param bounds 父容器尺寸（px）：拖动范围以它为界，不许拖出屏幕
 * @param savedPosition 上次停留的位置（比例 0–1）；null = 从未拖动，默认落在右下角
 * @param onPositionSettled 松手时回报比例位置，由上层落盘（跨会话保持）
 */

@Composable
fun DownloadBall(
    activeCount: Int,
    progress: Int,
    bounds: IntSize,
    savedPosition: Pair<Float, Float>?,
    onClick: () -> Unit,
    onPositionSettled: (xFraction: Float, yFraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val density = LocalDensity.current
    val ballPx = with(density) { BALL_SIZE.toPx() }
    val marginPx = with(density) { 18.dp.toPx() }

    var position by remember { mutableStateOf<Offset?>(null) }
    // 容器尺寸到手后才定位：有存档就还原，没有就默认右下角
    LaunchedEffect(bounds, savedPosition, ballPx) {
        if (bounds.width <= 0 || bounds.height <= 0) return@LaunchedEffect
        if (position != null) return@LaunchedEffect
        position = savedPosition?.let {
            Offset(it.first * bounds.width, it.second * bounds.height)
        } ?: Offset(bounds.width - ballPx - marginPx, bounds.height - ballPx - marginPx)
    }

    val current = position ?: return

    Box(
        modifier = modifier
            .offset { IntOffset(current.x.roundToInt(), current.y.roundToInt()) }
            .size(BALL_SIZE)
            .pointerInput(bounds, ballPx) {
                detectDragGestures(
                    onDragEnd = {
                        val maxX = (bounds.width - ballPx).coerceAtLeast(1f)
                        val maxY = (bounds.height - ballPx).coerceAtLeast(1f)
                        onPositionSettled(current.x / maxX, current.y / maxY)
                    },
                ) { change, drag ->
                    change.consume()
                    val maxX = (bounds.width - ballPx).coerceAtLeast(0f)
                    val maxY = (bounds.height - ballPx).coerceAtLeast(0f)
                    position = Offset(
                        (current.x + drag.x).coerceIn(0f, maxX),
                        (current.y + drag.y).coerceIn(0f, maxY),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = colors.inset,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colors.accent,
                startAngle = -90f,
                sweepAngle = 360f * (progress.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LxIcons.Download,
                contentDescription = "下载任务进行中，点开下载管理",
                tint = colors.bg,
                modifier = Modifier.size(20.dp),
            )
        }
        if (activeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.surface)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(text = "$activeCount", style = LXType.labelSmall, color = colors.ink1)
            }
        }
    }
}