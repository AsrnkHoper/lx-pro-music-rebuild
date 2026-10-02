package com.lxpro.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 极简自绘图标。
 *
 * 不引入 material-icons-extended：那会往 APK 里塞几千个图标（体积硬指标 < 20MB）。
 */
object LxIcons {

    private fun icon(name: String, build: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(build).build()

    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.triangle(
        x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
    ) {
        path(fill = SolidColor(Color.White)) {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x3, y3)
            close()
        }
    }

    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.bar(x: Float, w: Float = 4f) {
        path(fill = SolidColor(Color.White)) {
            moveTo(x, 5f)
            lineTo(x + w, 5f)
            lineTo(x + w, 19f)
            lineTo(x, 19f)
            close()
        }
    }

    /** 实心矩形（自绘图标的基础形状） */
    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.rect(
        x: Float, y: Float, w: Float, h: Float,
    ) {
        path(fill = SolidColor(Color.White)) {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }
    }

    /** 描边折线（波浪、滑杆轨道这类） */
    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.stroke(
        width: Float = 2f,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) {
        path(stroke = SolidColor(Color.White), strokeLineWidth = width, pathBuilder = block)
    }

    val Play: ImageVector by lazy { icon("Play") { triangle(7f, 5f, 19f, 12f, 7f, 19f) } }

    val Pause: ImageVector by lazy { icon("Pause") { bar(7f); bar(13f) } }

    val Next: ImageVector by lazy {
        icon("Next") {
            triangle(6f, 5f, 16f, 12f, 6f, 19f)
            bar(17f)
        }
    }

    val Previous: ImageVector by lazy {
        icon("Previous") {
            triangle(18f, 5f, 8f, 12f, 18f, 19f)
            bar(3f)
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
                moveTo(15f, 4f)
                lineTo(7f, 12f)
                lineTo(15f, 20f)
            }
        }
    }

    // ─────────────── 底部 Tab（5 项，见 14 §4.1）───────────────

    /** 首页：屋顶 + 屋身 */
    val Home: ImageVector by lazy {
        icon("Home") {
            triangle(2.5f, 11.5f, 12f, 3f, 21.5f, 11.5f)
            rect(5.5f, 11f, 13f, 9.5f)
        }
    }

    /** 歌单：三条书签线 + 播放三角 */
    val Playlist: ImageVector by lazy {
        icon("Playlist") {
            rect(3f, 5f, 18f, 2f)
            rect(3f, 10f, 18f, 2f)
            rect(3f, 15f, 10f, 2f)
            triangle(15f, 12.5f, 21f, 15.5f, 15f, 18.5f)
        }
    }

    /** 统计：三根递增柱 */
    val Stats: ImageVector by lazy {
        icon("Stats") {
            rect(4f, 13f, 3.5f, 7f)
            rect(10.25f, 9f, 3.5f, 11f)
            rect(16.5f, 5f, 3.5f, 15f)
        }
    }

    /** 白噪音：波形折线 */
    val Noise: ImageVector by lazy {
        icon("Noise") {
            stroke(width = 2f) {
                moveTo(2f, 12f)
                lineTo(5.5f, 7f)
                lineTo(9f, 16f)
                lineTo(12f, 8.5f)
                lineTo(15f, 15.5f)
                lineTo(18.5f, 6.5f)
                lineTo(22f, 12f)
            }
        }
    }

    /** 设置：两条滑杆 + 旋钮 */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroke(width = 2f) {
                moveTo(3f, 8f)
                lineTo(21f, 8f)
                moveTo(3f, 16f)
                lineTo(21f, 16f)
            }
            rect(6.5f, 5.5f, 2.5f, 5f)
            rect(14.5f, 13.5f, 2.5f, 5f)
        }
    }
}