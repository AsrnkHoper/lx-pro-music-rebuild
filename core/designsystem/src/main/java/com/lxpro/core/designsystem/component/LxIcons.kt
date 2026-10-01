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
}