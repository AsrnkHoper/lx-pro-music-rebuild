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

    /** 实心圆点（⋮ / ⋮⋮ 这类靠点组成） */
    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.dot(
        cx: Float, cy: Float, r: Float,
    ) {
        path(fill = SolidColor(Color.White)) {
            moveTo(cx - r, cy)
            arcToRelative(r, r, 0f, true, true, r * 2, 0f)
            arcToRelative(r, r, 0f, true, true, -r * 2, 0f)
            close()
        }
    }

    /** 描边圆 */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.strokeCircle(
        cx: Float, cy: Float, r: Float,
    ) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, r * 2, 0f)
        arcToRelative(r, r, 0f, true, true, -r * 2, 0f)
        close()
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

    /** 均衡器：三条竖向滑杆 + 旋钮（播放页「更多」入口） */
    val Equalizer: ImageVector by lazy {
        icon("Equalizer") {
            stroke(width = 1.5f) { moveTo(5f, 4f); lineTo(5f, 20f) }
            stroke(width = 1.5f) { moveTo(12f, 4f); lineTo(12f, 20f) }
            stroke(width = 1.5f) { moveTo(19f, 4f); lineTo(19f, 20f) }
            dot(5f, 13.5f, 1.8f)
            dot(12f, 8f, 1.8f)
            dot(19f, 15.5f, 1.8f)
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

    // ─────────────── 列表 / 歌单操作（mockup_playlist.html）───────────────

    /** 更多：竖排三点 */
    val More: ImageVector by lazy {
        icon("More") {
            dot(12f, 5f, 1.7f)
            dot(12f, 12f, 1.7f)
            dot(12f, 19f, 1.7f)
        }
    }

    /** 拖动把手：两列三点 */
    val DragHandle: ImageVector by lazy {
        icon("DragHandle") {
            listOf(6f, 12f, 18f).forEach { y ->
                dot(9f, y, 1.5f)
                dot(15f, y, 1.5f)
            }
        }
    }

    /** 网格视图：2×2 方块（描边） */
    val ViewGrid: ImageVector by lazy {
        icon("ViewGrid") {
            stroke(width = 1.8f) {
                moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
                moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
                moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
                moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
            }
        }
    }

    /** 紧凑列表：三行，末行短 */
    val ViewList: ImageVector by lazy {
        icon("ViewList") {
            rect(3.5f, 5f, 17f, 2f)
            rect(3.5f, 11f, 17f, 2f)
            rect(3.5f, 17f, 10f, 2f)
        }
    }

    /** 有封面 / 无封面：相框 + 山 + 太阳 */
    val CoverBox: ImageVector by lazy {
        icon("CoverBox") {
            stroke(width = 1.8f) {
                moveTo(4f, 5f); lineTo(20f, 5f)
                lineTo(20f, 19f); lineTo(4f, 19f); close()
                moveTo(4f, 15.5f); lineTo(9f, 10.5f); lineTo(12.5f, 14f)
                lineTo(15f, 12f); lineTo(20f, 16.5f)
                strokeCircle(8.5f, 9f, 1.4f)
            }
        }
    }

    /** 搜索：圆 + 手柄 */
    val Search: ImageVector by lazy {
        icon("Search") {
            stroke(width = 2f) {
                strokeCircle(11f, 11f, 6.5f)
                moveTo(20f, 20f)
                lineTo(16.2f, 16.2f)
            }
        }
    }

    /** 下载：向下箭头 + 底座 */
    val Download: ImageVector by lazy {
        icon("Download") {
            stroke(width = 1.8f) {
                moveTo(12f, 3.5f); lineTo(12f, 15f)
                moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
                moveTo(4.5f, 19.5f); lineTo(19.5f, 19.5f)
            }
        }
    }

    /** 添加：加号 */
    val Plus: ImageVector by lazy {
        icon("Plus") {
            stroke(width = 1.8f) {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }
    }

    /** 换源：双向箭头 */
    val Swap: ImageVector by lazy {
        icon("Swap") {
            stroke(width = 1.8f) {
                moveTo(4f, 8.5f); lineTo(19f, 8.5f)
                moveTo(16f, 5.5f); lineTo(19f, 8.5f); lineTo(16f, 11.5f)
                moveTo(20f, 15.5f); lineTo(5f, 15.5f)
                moveTo(8f, 12.5f); lineTo(5f, 15.5f); lineTo(8f, 18.5f)
            }
        }
    }

    /** 移除：垃圾桶 */
    val Trash: ImageVector by lazy {
        icon("Trash") {
            stroke(width = 1.8f) {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
                moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
            }
        }
    }

    /** 分享：三个节点 + 连线 */
    val Share: ImageVector by lazy {
        icon("Share") {
            stroke(width = 1.8f) {
                strokeCircle(18f, 5f, 2.6f)
                strokeCircle(6f, 12f, 2.6f)
                strokeCircle(18f, 19f, 2.6f)
                moveTo(8.3f, 10.7f); lineTo(15.7f, 6.3f)
                moveTo(8.3f, 13.3f); lineTo(15.7f, 17.7f)
            }
        }
    }

    /** 编辑：铅笔 */
    val Pencil: ImageVector by lazy {
        icon("Pencil") {
            stroke(width = 1.8f) {
                moveTo(4f, 20f); lineTo(8f, 20f); lineTo(19f, 9f); lineTo(15f, 5f); lineTo(4f, 16f); close()
                moveTo(14f, 6f); lineTo(18f, 10f)
            }
        }
    }

    /** 移动位置：目标行 + 左侧指示箭头 */
    val MoveTo: ImageVector by lazy {
        icon("MoveTo") {
            stroke(width = 1.8f) {
                moveTo(9f, 9f); lineTo(20f, 9f)
                moveTo(9f, 15f); lineTo(20f, 15f)
                moveTo(5f, 6f); lineTo(5f, 18f)
                moveTo(5f, 6f); lineTo(2.5f, 8.5f)
                moveTo(5f, 6f); lineTo(7.5f, 8.5f)
                moveTo(5f, 18f); lineTo(2.5f, 15.5f)
                moveTo(5f, 18f); lineTo(7.5f, 15.5f)
            }
        }
    }

    /** 随机播放：交叉箭头 */
    val Shuffle: ImageVector by lazy {
        icon("Shuffle") {
            stroke(width = 1.8f) {
                moveTo(16f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 8.5f)
                moveTo(20f, 4.5f); lineTo(4f, 20f)
                moveTo(16f, 19.5f); lineTo(20f, 19.5f); lineTo(20f, 15.5f)
                moveTo(14f, 14f); lineTo(20f, 19.5f)
                moveTo(4f, 4.5f); lineTo(8f, 8.5f)
            }
        }
    }

    /** 排序：向上箭头 + 长度递减的行 */
    val SortAsc: ImageVector by lazy {
        icon("SortAsc") {
            stroke(width = 1.8f) {
                moveTo(6f, 20f); lineTo(6f, 5f)
                moveTo(6f, 5f); lineTo(3.5f, 7.5f)
                moveTo(6f, 5f); lineTo(8.5f, 7.5f)
                moveTo(12f, 7f); lineTo(20.5f, 7f)
                moveTo(12f, 12f); lineTo(18f, 12f)
                moveTo(12f, 17f); lineTo(15.5f, 17f)
            }
        }
    }

    /** 归并：两路来源汇成一路（统计页「歌手归并」开关） */
    val Merge: ImageVector by lazy {
        icon("Merge") {
            stroke(width = 1.8f) {
                moveTo(3f, 6f); lineTo(11f, 12f); lineTo(3f, 18f)
                moveTo(11f, 12f); lineTo(21f, 12f)
            }
        }
    }

    /** 关闭：叉 */
    val Close: ImageVector by lazy {
        icon("Close") {
            stroke(width = 1.8f) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }
    }

    // ─────────────── 播放页（14 §4.4 / mockup 播放页部分）───────────────

    /** 喜欢：心形（原型 `#btnLike` 的 `abtn` 图标） */
    val Heart: ImageVector by lazy {
        icon("Heart") {
            stroke(width = 1.8f) {
                moveTo(12f, 20.5f)
                curveTo(4.6f, 15f, 3.2f, 10.4f, 6.4f, 7.6f)
                curveTo(8.8f, 5.5f, 11.4f, 6.3f, 12f, 8.7f)
                curveTo(12.6f, 6.3f, 15.2f, 5.5f, 17.6f, 7.6f)
                curveTo(20.8f, 10.4f, 19.4f, 15f, 12f, 20.5f)
                close()
            }
        }
    }

    /** 歌词：长度递减的三行（原型封面模式 `#btnLyric` 的图标） */
    val LyricLines: ImageVector by lazy {
        icon("LyricLines") {
            stroke(width = 1.8f) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 11.5f); lineTo(16f, 11.5f)
                moveTo(4f, 17f); lineTo(13f, 17f)
            }
        }
    }

    /** 评论：气泡 + 三个点（批十九） */
    val Comment: ImageVector by lazy {
        icon("Comment") {
            stroke(width = 1.8f) {
                moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 15f); lineTo(9f, 15f); lineTo(5.5f, 18.5f); lineTo(5.5f, 15f); lineTo(4f, 15f); close()
            }
            stroke(width = 1.6f) {
                moveTo(8f, 10f); lineTo(8f, 10f)
                moveTo(12f, 10f); lineTo(12f, 10f)
                moveTo(16f, 10f); lineTo(16f, 10f)
            }
        }
    }

    /** 循环模式：两条带回勾的环线（原型 `#btnMode`） */
    val Repeat: ImageVector by lazy {
        icon("Repeat") {
            stroke(width = 1.8f) {
                moveTo(17f, 4.5f); lineTo(20.5f, 8f); lineTo(17f, 11.5f)
                moveTo(20.5f, 8f); lineTo(7f, 8f)
                moveTo(7f, 19.5f); lineTo(3.5f, 16f); lineTo(7f, 12.5f)
                moveTo(3.5f, 16f); lineTo(17f, 16f)
            }
        }
    }

    /** 单曲循环：循环环线 + 中间的「1」（用户 2026-10-03 反馈：按钮图标必须随模式变） */
    val RepeatOne: ImageVector by lazy {
        icon("RepeatOne") {
            stroke(width = 1.8f) {
                moveTo(17f, 4.5f); lineTo(20.5f, 8f); lineTo(17f, 11.5f)
                moveTo(20.5f, 8f); lineTo(7f, 8f)
                moveTo(7f, 19.5f); lineTo(3.5f, 16f); lineTo(7f, 12.5f)
                moveTo(3.5f, 16f); lineTo(17f, 16f)
            }
            stroke(width = 1.6f) {
                moveTo(11f, 10.6f); lineTo(12.6f, 9.6f); lineTo(12.6f, 15f)
            }
        }
    }

    /** 顺序播放：循环环线 + 一道斜杠 */
    val RepeatOff: ImageVector by lazy {
        icon("RepeatOff") {
            stroke(width = 1.8f) {
                moveTo(17f, 4.5f); lineTo(20.5f, 8f); lineTo(17f, 11.5f)
                moveTo(20.5f, 8f); lineTo(7f, 8f)
                moveTo(7f, 19.5f); lineTo(3.5f, 16f); lineTo(7f, 12.5f)
                moveTo(3.5f, 16f); lineTo(17f, 16f)
                moveTo(4.5f, 4.5f); lineTo(19.5f, 19.5f)
            }
        }
    }

    /** 播放队列：三行清单 + 音符（原型 `#btnQueue`） */
    val Queue: ImageVector by lazy {
        icon("Queue") {
            stroke(width = 1.8f) {
                moveTo(4f, 6f); lineTo(14.5f, 6f)
                moveTo(4f, 12f); lineTo(14.5f, 12f)
                moveTo(4f, 18f); lineTo(10.5f, 18f)
            }
            stroke(width = 1.6f) {
                moveTo(18f, 9.5f); lineTo(18f, 18f)
                moveTo(18f, 9.5f); lineTo(21f, 8.6f); lineTo(21f, 11.4f)
            }
            dot(18f, 19.6f, 2f)
        }
    }

    /** 定时关闭：表盘 + 指针（17 §7） */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            stroke(width = 1.8f) {
                strokeCircle(12f, 12f, 8.5f)
                moveTo(12f, 6.8f)
                lineTo(12f, 12f)
                lineTo(15.6f, 14.2f)
            }
        }
    }

    /** 倍速播放：表盘描边 + 向右加速三角（2026-10-27 新增） */
    val PlaySpeed: ImageVector by lazy {
        icon("PlaySpeed") {
            stroke(width = 1.8f) { strokeCircle(12f, 12f, 8.5f) }
            // 内部加速三角：比 Play 小一号，中心偏右一点
            path(fill = SolidColor(Color.White)) {
                moveTo(8.5f, 7.5f)
                lineTo(16.5f, 12f)
                lineTo(8.5f, 16.5f)
                close()
            }
        }
    }

    // ─────────────── 设置分类图标（9 分类，见 mockup_app.html 的 SET_ICON）───────────────

    /** 音源管理：两个相扣的链环 */
    val Link: ImageVector by lazy {
        icon("Link") {
            stroke(width = 1.8f) {
                moveTo(10f, 13f)
                arcToRelative(5f, 5f, 0f, false, false, 7.5f, 0.5f)
                lineTo(19.5f, 11.5f)
                arcToRelative(5f, 5f, 0f, false, false, -7f, -7f)
                lineTo(11.5f, 5.5f)
                moveTo(14f, 11f)
                arcToRelative(5f, 5f, 0f, false, false, -7.5f, -0.5f)
                lineTo(4.5f, 12.5f)
                arcToRelative(5f, 5f, 0f, false, false, 7f, 7f)
                lineTo(12.5f, 18.5f)
            }
        }
    }

    /** 本地音乐：文件夹 */
    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke(width = 1.8f) {
                moveTo(3f, 7f); lineTo(10f, 7f); lineTo(12f, 5f); lineTo(19f, 5f)
                lineTo(19f, 19f); lineTo(3f, 19f); close()
            }
        }
    }

    /** 外观：调色盘（外环 + 三个颜料点） */
    val Palette: ImageVector by lazy {
        icon("Palette") {
            stroke(width = 1.8f) { strokeCircle(12f, 12f, 8.5f) }
            dot(8.5f, 10.5f, 1.3f)
            dot(12f, 7.8f, 1.3f)
            dot(15.5f, 10.5f, 1.3f)
        }
    }

    /** 文案自定义：一对引号 */
    val Quote: ImageVector by lazy {
        icon("Quote") {
            stroke(width = 1.8f) {
                moveTo(6f, 6f); lineTo(11f, 6f); lineTo(11f, 11f); lineTo(8f, 11f)
                arcToRelative(4f, 4f, 0f, false, true, -4f, 4f)
                moveTo(14f, 6f); lineTo(19f, 6f); lineTo(19f, 11f); lineTo(16f, 11f)
                arcToRelative(4f, 4f, 0f, false, true, -4f, 4f)
            }
        }
    }

    /** 关于：圆圈 + 信息「i」 */
    val Info: ImageVector by lazy {
        icon("Info") {
            stroke(width = 1.8f) { strokeCircle(12f, 12f, 8.5f) }
            stroke(width = 1.8f) { moveTo(12f, 11f); lineTo(12f, 16f) }
            dot(12f, 8.2f, 1.1f)
        }
    }

    /** 勾选（多选菜单 / 下拉里标「已选」） */
    val Check: ImageVector by lazy {
        icon("Check") {
            stroke(width = 2.2f) { moveTo(5.5f, 12.5f); lineTo(10f, 17f); lineTo(18.5f, 7.5f) }
        }
    }

    /** 标签：吊牌轮廓 + 穿孔圆点（歌曲标签页 / 操作弹层入口） */
    val Tag: ImageVector by lazy {
        icon("Tag") {
            stroke(width = 1.8f) {
                moveTo(4f, 4f)
                lineTo(10.5f, 4f)
                lineTo(20f, 13.5f)
                lineTo(13.5f, 20f)
                lineTo(4f, 10.5f)
                close()
            }
            dot(7.8f, 7.8f, 1.3f)
        }
    }

    // ─────────────── 均衡器（17 §5.7：收藏星标）───────────────

    /** 收藏：空心五角星（未收藏态） */
    val Star: ImageVector by lazy {
        icon("Star") {
            stroke(width = 1.8f) { starPath() }
        }
    }

    /** 收藏：实心五角星（已收藏态） */
    val StarFilled: ImageVector by lazy {
        icon("StarFilled") {
            path(fill = SolidColor(Color.White)) { starPath() }
        }
    }

    /** 五角星几何（外接半径 8.5、内接 3.25，中心 (12,12)），描边/填充共用 */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.starPath() {
        moveTo(12f, 3.5f)
        lineTo(13.91f, 9.37f)
        lineTo(20.08f, 9.37f)
        lineTo(15.09f, 13.0f)
        lineTo(17.0f, 18.88f)
        lineTo(12f, 15.25f)
        lineTo(7.0f, 18.88f)
        lineTo(8.91f, 13.0f)
        lineTo(3.92f, 9.37f)
        lineTo(10.09f, 9.37f)
        close()
    }
}