package com.lxpro.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxpro.core.common.LyricLine
import com.lxpro.core.common.LyricParser
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 歌词区（平面档 / 立体档）。
 *
 * 平面档（14 §4.4:165-176）：
 * - 四态连续淡化：l1 = accent 加粗不透明（当前句）→ l2 = ink-1/0.52 → l3 = ink-2/0.24 → l4 = ink-3/0.13
 * - 上下 34dp 渐隐遮罩
 * - 当前句（行中心）落在**可视区正中**；点任意一行 = 跳到该句并开始播放
 *
 * 立体档：整块歌词板旋转（详见 [Lyric3dBoard]）。
 */
@Composable
fun LyricsArea(
    lyrics: LyricsUiState,
    positionMs: Long,
    playing: Boolean,
    pageState: PlayerPageState,
    dragging: Boolean,
    onSeekToLine: (Long) -> Unit,
    /** 长按歌词行 → 分享弹窗，默认空实现（封面模式可能不用） */
    onLongPressLineShare: (LyricLine) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors

    when {
        lyrics.loading -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }

        lyrics.lines.isEmpty() -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = lyrics.message ?: "暂无歌词",
                style = LXType.bodyMedium,
                color = colors.ink3,
                textAlign = TextAlign.Center,
            )
        }

        else -> {
            // ⚠️ 当前句用**插值后的歌曲时钟**判定，不用内核 500ms 一跳的 positionMs ——
            // 否则换行最多会晚半秒（2026-10-03 的细节优化）。
            // 读值放在 derivedStateOf 里：只有「当前句真的换了」才重组，不逐帧重算。
            val clock = rememberSongClock(positionMs, playing)
            val current by remember(lyrics.lines, clock) {
                derivedStateOf { LyricParser.indexAt(lyrics.lines, clock.value) }
            }
            if (pageState.lyric3d) {
                Lyric3dBoard(
                    lines = lyrics.lines,
                    current = current,
                    params = pageState.params,
                    align = pageState.align,
                    translate = pageState.translate,
                    sheetOpen = pageState.p3dSheetOpen,
                    onSeekToLine = onSeekToLine,
                    onLongPressLineShare = onLongPressLineShare,
                    modifier = modifier,
                )
            } else {
                LyricFlatList(
                    lines = lyrics.lines,
                    current = current,
                    dragging = dragging,
                    sheetOpen = pageState.p3dSheetOpen,
                    align = pageState.align,
                    translate = pageState.translate,
                    params = pageState.params,
                    onSeekToLine = onSeekToLine,
                    onLongPressLineShare = onLongPressLineShare,
                    modifier = modifier,
                )
            }
        }
    }
}

/**
 * 歌曲时钟：播放中逐帧推进的毫秒位置。
 *
 * ⚠️ 内核 `positionMs` 每 500ms 才更新一次，直接拿它驱动「当前句判定」会晚半秒、
 * 拿它驱动逐字填充会一跳一顿。这里维护一个本地单调时钟，只在落后/超前太多时与内核对齐。
 * 写入每帧一次，但**读值都在 derivedStateOf / measure / 绘制阶段**，不会逐帧重组。
 */
@Composable
private fun rememberSongClock(positionMs: Long, playing: Boolean): State<Long> {
    val clock = remember { mutableLongStateOf(positionMs) }
    val latest by rememberUpdatedState(positionMs)
    LaunchedEffect(playing) {
        clock.longValue = latest
        if (!playing) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val advanced = clock.longValue + (now - lastFrame) / 1_000_000L
            lastFrame = now
            val kernel = latest
            clock.longValue = when {
                advanced < kernel - 450L -> kernel
                advanced > kernel + 1_500L -> kernel
                else -> advanced
            }
        }
    }
    return clock
}

// ─────────────────────────── 平面档 ───────────────────────────

@Composable
private fun LyricFlatList(
    lines: List<LyricLine>,
    current: Int,
    dragging: Boolean,
    sheetOpen: Boolean,
    align: LyricAlign,
    translate: Boolean,
    params: Lyric3dParams,
    onSeekToLine: (Long) -> Unit,
    onLongPressLineShare: (LyricLine) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val density = LocalDensity.current

    /**
     * 「浏览」态：用户手动拖动歌词时进入。
     *
     * ⚠️ 2026-10-03 用户反馈：翻动歌词找人时，四态淡化会把远处行压到几乎看不见
     * （第四态只有 0.13），根本看不清要找哪一行。所以**手动翻动 = 取消高亮、所有行同样亮**，
     * 翻完再回到跟唱态（点某一行即恢复）。
     */
    var browsing by remember { mutableStateOf(false) }

    // 用 interactionSource 精确区分「用户拖动」与「程序自动跟随滚动」——
    // isScrollInProgress 两者都算，会把自己的吸底动画误判成浏览
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) browsing = true
        }
    }

    // 换歌就回到跟唱态（否则新歌一进来是「无高亮 + 不跟随」的浏览态，用户会以为坏了）
    LaunchedEffect(lines) { browsing = false }

    // ⚠️ 翻阅后**静止一会儿自动回到跟唱**（2026-10-03 用户要求）：任何一次滑动都会把定时器推后
    LaunchedEffect(browsing) {
        if (!browsing) return@LaunchedEffect
        var lastPosition = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        var idle = 0L
        while (true) {
            // 用 delay 而不是逐帧轮询：静止时不该继续出帧
            delay(BROWSE_IDLE_POLL_MS)
            val now = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            if (now == lastPosition) {
                idle += BROWSE_IDLE_POLL_MS
                if (idle >= BROWSE_IDLE_MS) break
            } else {
                lastPosition = now
                idle = 0
            }
        }
        browsing = false
    }

    // ── 实测几何（2026-10-24 第三次修定位的最终方案）──
    // 之前两次用 LazyListItemInfo.offset 推算屏幕位置，连修两版仍「偏低贴底」——
    // offset 的坐标原点、与 contentPadding 的换算关系在不同 foundation 版本有语义差异，
    // 继续在这条路上修只会再错。改为**实测**：容器与当前行各挂 onGloballyPositioned，
    // 直接拿真实渲染出来的窗口坐标求差滚动，绕开全部 offset 语义。
    var containerWindowY by remember { mutableFloatStateOf(Float.NaN) }
    var containerHeightPx by remember { mutableFloatStateOf(Float.NaN) }
    val currentLineCenterY = remember { mutableFloatStateOf(Float.NaN) }

    BoxWithConstraints(
        modifier = modifier.lyricFadeMask(flat = true).onGloballyPositioned { coords ->
            containerWindowY = coords.positionInRoot().y
            containerHeightPx = coords.size.height.toFloat()
        },
    ) {
        val viewport = maxHeight
        // 参数面板打开时它挡住下半屏 —— 基准挪到没被挡住的那块区域（原型 `lyricFocusCenterY`）
        val ratio = if (sheetOpen) FOCUS_RATIO * (1f - SHEET_HEIGHT_FRACTION) else FOCUS_RATIO

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 上下留出与基准比例等高的空白：滚到第 i 行即把它放在 42% 处
            contentPadding = PaddingValues(top = viewport * ratio, bottom = viewport * (1f - ratio)),
            horizontalAlignment = when (align) {
                LyricAlign.LEFT -> Alignment.Start
                LyricAlign.CENTER -> Alignment.CenterHorizontally
                LyricAlign.RIGHT -> Alignment.End
            },
        ) {
            itemsIndexed(
                items = lines,
                key = { index, line -> "$index:${line.timeMs}" },
            ) { index, line ->
                FlatLyricLine(
                    line = line,
                    distance = index - current,
                    align = align,
                    translate = translate,
                    isCurrent = index == current,
                    browsing = browsing,
                    // 共用参数：基准字号按 15.5/24 的比例跟随立体档基字号（默认值下与规格一致）
                    fontSp = FLAT_LYRIC_FONT_SP * (params.fontSize / 24f),
                    dimBase = params.dimBase,
                    opDecay = params.opDecay,
                    blurStep = params.blurStep,
                    fadeMs = (params.blurDur * 1000f).roundToInt(),
                    // 只有当前行回报自己的真实渲染中心（窗口坐标）
                    reportCenter = if (index == current) {
                        { y -> currentLineCenterY.floatValue = y }
                    } else {
                        null
                    },
                    onClick = {
                        browsing = false
                        onSeekToLine(line.timeMs)
                    },
                    onLongPress = { onLongPressLineShare(line) },
                )
            }
        }

        // ⚠️ 拖动进度条时**不跟随**（15 §4.4:144：拖动中暂停自动跟随，松手恢复）；
        // 浏览态下也不跟随（否则用户刚找到那一行就被拽回去）。
        //
        // 对齐口径：**当前行中心 = 歌词区实测高度 × ratio（默认 0.5 = 正中）**。
        // 全部用实测窗口坐标，不碰 LazyListItemInfo.offset 的语义换算。
        LaunchedEffect(current, dragging, ratio, browsing, lines) {
            if (dragging || browsing || current < 0 || current >= lines.size) return@LaunchedEffect
            // 目标行还没被布局（换歌 / 跳到远处 / 快速翻页）→ 先滚进窗口
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == current }) {
                listState.scrollToItem(current)
            }
            val spec = tween<Float>(
                durationMillis = (params.scrollDur * 1000f).roundToInt().coerceIn(80, 2000),
                easing = CubicBezierEasing(0.22f, 0.7f, 0.3f, 1f),
            )
            // 收敛校正：每轮先等一帧让 onGloballyPositioned 回报最新坐标，再求差滚动
            repeat(4) {
                withFrameNanos { }
                val containerH = containerHeightPx
                val containerY = containerWindowY
                val center = currentLineCenterY.floatValue
                if (containerH.isNaN() || containerY.isNaN() || center.isNaN()) return@LaunchedEffect
                val target = containerY + containerH * ratio
                val delta = center - target
                if (abs(delta) <= 1f) return@LaunchedEffect
                listState.animateScrollBy(delta, spec)
            }
        }

        // 浏览态的出口：不 seek 也能回到跟唱（点某一行当然也可以）
        if (browsing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.surface)
                    .clickable { browsing = false }
                    .padding(horizontal = scalars.sidePadding, vertical = 6.dp),
            ) {
                Text(
                    text = "回到当前句",
                    style = LXType.labelSmall,
                    color = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun FlatLyricLine(
    line: LyricLine,
    distance: Int,
    align: LyricAlign,
    translate: Boolean,
    isCurrent: Boolean,
    browsing: Boolean,
    fontSp: Float,
    dimBase: Float,
    opDecay: Float,
    blurStep: Float,
    fadeMs: Int,
    reportCenter: ((Float) -> Unit)? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val colors = LXTheme.colors
    val absolute = abs(distance)
    // 浏览态：所有行同样亮、不高亮 —— 翻找时看得清每一行（用户 2026-10-03 要求）
    val textColor = when {
        browsing -> colors.ink1
        isCurrent -> colors.accent
        absolute == 1 -> colors.ink1
        absolute <= 3 -> colors.ink2
        else -> colors.ink3
    }
    // 透明度：四态阶梯（14 §4.4 定稿的层次）为基底，两个共用参数叠加——
    // · dimBase 缩放整体亮度（默认 0.30 时与原阶梯一致）；
    // · opDecay 缩放阶梯的**陡峭程度**（以默认 0.18 归一：调大远处行更快变暗，调小阶梯趋于平坦），
    //   默认值下与原四态视觉完全一致（2026-10-24 用户反馈连续衰减版「塑料感」后的回退方案）。
    val dimScale = (dimBase / 0.30f).coerceIn(0.2f, 3.4f)
    val decayFactor = (opDecay / 0.18f).coerceAtLeast(0f)
    fun tier(base: Float) = ((1f - (1f - base) * decayFactor) * dimScale).coerceIn(0.02f, 1f)
    val op = when {
        browsing -> 1f
        isCurrent -> 1f
        absolute == 1 -> tier(LYR_2)
        absolute <= 3 -> tier(LYR_3)
        else -> tier(LYR_4)
    }
    // 模糊强度共用：近处三行按距离加深（与立体档同一上限，避免整列表离屏图层压垮 GPU）
    val lineBlur = (absolute * blurStep).takeIf { !browsing && absolute in 1..3 && it > 0.05f }
    val textAlign = when (align) {
        LyricAlign.LEFT -> TextAlign.Start
        LyricAlign.CENTER -> TextAlign.Center
        LyricAlign.RIGHT -> TextAlign.End
    }
    val origin = when (align) {
        LyricAlign.LEFT -> TransformOrigin(0f, 0.5f)
        LyricAlign.CENTER -> TransformOrigin(0.5f, 0.5f)
        LyricAlign.RIGHT -> TransformOrigin(1f, 0.5f)
    }

    // 「模糊过渡时长」是共用参数：平面档用它驱动颜色/透明度/模糊的过渡动画（原型 .35s）
    val animatedAlpha by animateFloatAsState(op, tween(durationMillis = fadeMs), label = "lyrAlpha")
    val animatedColor by animateColorAsState(textColor, tween(durationMillis = fadeMs), label = "lyrColor")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // detectTapGestures 而不是 combinedClickable：
            // LazyColumn 的滚动手势会先一步消费掉 combinedClickable 的 onLongClick，
            // 导致长按在可滚动歌词列表里经常不触发。pointerInput 的 onLongPress 更可靠，
            // 且长按不会顺带触发 onTap（保证"长按不被判定为选取歌词跳转进度"）。
            .pointerInput(line.timeMs) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
            .then(if (lineBlur != null) Modifier.blur(lineBlur.dp) else Modifier)
            .then(
                if (reportCenter != null) {
                    Modifier.onGloballyPositioned { coords ->
                        reportCenter(coords.positionInRoot().y + coords.size.height / 2f)
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                // 居中对齐时当前句轻微放大（原型 `[data-align=center] .lline.l1{scale 1.06}`）
                val currentScale = if (!browsing && isCurrent && align == LyricAlign.CENTER) 1.06f else 1f
                scaleX = currentScale
                scaleY = currentScale
                transformOrigin = origin
                alpha = animatedAlpha
            }
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = line.text,
            style = LXType.titleSmall.copy(
                // 字号与过渡时长都来自共用参数（基准字号默认 24 → 平面 15.5，与规格一致）
                fontSize = fontSp.sp,
                lineHeight = (fontSp * 2.05f).sp,
                letterSpacing = LYRIC_LETTER_SPACING,
                fontWeight = if (isCurrent && !browsing) FontWeight.SemiBold else null,
            ),
            color = animatedColor,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        val translation = line.translation
        if (translate && !translation.isNullOrBlank()) {
            Text(
                text = translation,
                // 原型 `.tr`：主行的 0.8 倍（14 §4.4:174：翻译行可关）
                style = LXType.bodySmall.copy(
                    fontSize = (fontSp * 0.8f).sp,
                    lineHeight = (fontSp * 2.05f * 0.8f).sp,
                ),
                color = textColor,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp).alpha(0.72f),
            )
        }
    }
}

// ─────────────────────────── 立体档 ───────────────────────────

/**
 * 立体歌词：**整块歌词板**当刚体旋转（权威稿 `#lyrCard` / `#lyrMover`，3160-3345 行）。
 *
 * 与「逐行位移」那套的根本区别：空间感来自板的 rotateX/rotateY，居中是外层 mover 的平移；
 * 板宽要按旋转角重算（转过去后远端在透视里收窄），否则文字铺不满屏宽。
 *
 * ⚠️ 与原型的一处实现差异（更简单且更准）：原型的透视挂在容器上，mover 的位移也会被透视
 * 缩放，所以要「双采样测量 + 牛顿法」反推；这里透视只挂在板自己身上（`cameraDistance`），
 * mover 的 offset 在层外，屏幕位移与布局位移**精确 1:1**，量一次即可收敛。
 */
@Composable
private fun Lyric3dBoard(
    lines: List<LyricLine>,
    current: Int,
    params: Lyric3dParams,
    align: LyricAlign,
    translate: Boolean,
    sheetOpen: Boolean,
    onSeekToLine: (Long) -> Unit,
    onLongPressLineShare: (LyricLine) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colors = LXTheme.colors
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    /** 浏览态（手动翻看）：暂停跟随、取消高亮与填充 —— 与平面档同一套语义 */
    var browsing by remember { mutableStateOf(false) }
    /**
     * 手指拖动 + 惯性位移。用 FloatState 而不是 Animatable：`scrollable` 的 delta 回调
     * 是同步的（含 fling 阶段每帧回调），不能在里面跑挂起的 snapTo；退出浏览态时
     * 再用挂起的 `animate` 平滑归零。
     */
    val dragOffset = remember { mutableFloatStateOf(0f) }
    /** 内容总高（含上下缓冲），由 measurePolicy 写入；用于限制惯性翻页的边界 */
    val contentHeightPx = remember { mutableFloatStateOf(0f) }
    /** 跟唱时「当前句居中」的目标位移；由 Layout 量完行高后写入（见 measurePolicy） */
    var targetShift by remember { mutableFloatStateOf(0f) }

    // ⚠️ 让「滚动时长」真的生效：位移走补间动画（原型 `#lyrCard` 的
    //    `transition: transform .55s cubic-bezier(.22,.7,.3,1)`）。
    //    它的读值在 measure 里 → 只触发重排版，不触发重组。
    val shiftAnim by animateFloatAsState(
        targetValue = targetShift,
        animationSpec = tween(
            durationMillis = (params.scrollDur * 1000f).roundToInt().coerceIn(80, 2000),
            easing = CubicBezierEasing(0.22f, 0.7f, 0.3f, 1f),
        ),
        label = "lyric3dShift",
    )

    // 参数改动走「模糊过渡时长」的补间：拖滑块时画面**渐变**过去，而不是瞬跳
    // （2026-10-24 用户反馈「参数改了不生效」——直读瞬变不易感知，动画化后变化过程可见）
    val paramFadeMs = (params.blurDur * 1000f).roundToInt().coerceIn(80, 2000)
    val tiltDeg by animateFloatAsState(
        targetValue = if (params.tiltOn) params.tilt else 0f,
        animationSpec = tween(paramFadeMs),
        label = "lyric3dTilt",
    )
    val pitchDeg by animateFloatAsState(
        targetValue = params.pitch,
        animationSpec = tween(paramFadeMs),
        label = "lyric3dPitch",
    )
    val perspAnim by animateFloatAsState(
        targetValue = params.persp,
        animationSpec = tween(paramFadeMs),
        label = "lyric3dPersp",
    )

    /**
     * 切句焦点拉动（17 §8）：切句瞬间远处行的模糊在稳态值上按比例加强，640ms 内收敛回
     * 按距离的稳态值 —— 视觉上像「焦点从旧句拉到新句」。
     * 行内读取这个 State，只有带模糊的近处三行随动画重组，其余行不订阅。
     */
    val focusPull = remember { Animatable(0f) }
    LaunchedEffect(current) {
        focusPull.snapTo(1f)
        focusPull.animateTo(0f, tween(FOCUS_PULL_MS, easing = LinearOutSlowInEasing))
    }

    // 换歌回到跟唱态
    LaunchedEffect(lines) {
        browsing = false
        dragOffset.floatValue = 0f
    }

    // 浏览态：静止一会儿自动回跟唱；退出时手指位移按「滚动时长」平滑归零（不会跳一下）
    LaunchedEffect(browsing) {
        if (!browsing) {
            if (abs(dragOffset.floatValue) > 0.5f) {
                animate(
                    initialValue = dragOffset.floatValue,
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = (params.scrollDur * 1000f).roundToInt().coerceIn(80, 2000),
                        easing = CubicBezierEasing(0.22f, 0.7f, 0.3f, 1f),
                    ),
                ) { value, _ -> dragOffset.floatValue = value }
            } else {
                dragOffset.floatValue = 0f
            }
            return@LaunchedEffect
        }
        var lastOffset = dragOffset.floatValue
        var idle = 0L
        while (true) {
            delay(BROWSE_IDLE_POLL_MS)
            val now = dragOffset.floatValue
            if (abs(now - lastOffset) < 0.5f) {
                idle += BROWSE_IDLE_POLL_MS
                if (idle >= BROWSE_IDLE_MS) break
            } else {
                lastOffset = now
                idle = 0
            }
        }
        browsing = false
    }

    BoxWithConstraints(
        modifier = modifier
            // 立体档的行会越出容器上下（板整体在平移），必须裁掉，否则会盖到迷你封面那一行
            .clipToBounds()
            .lyricFadeMask(flat = false)
            .onGloballyPositioned { boxSize = it.size }
            // 立体档也能翻阅：拖动即进入浏览态（暂停跟随、取消高亮）。
            // ⚠️ 用 `scrollable` 而不是 detectDragGestures：自带松手惯性（fling 衰减），
            // 与平面档 LazyColumn 的滑动手感一致（2026-10-24 用户要求）。
            .scrollable(
                orientation = Orientation.Vertical,
                state = rememberScrollableState { delta ->
                    browsing = true
                    val contentH = contentHeightPx.floatValue
                    val viewportH = boxSize.height.toFloat()
                    // 惯性边界：总位移（跟唱位移 + 手指位移）不许把板拖出内容区太多
                    val newShift = if (contentH > 0f && viewportH > 0f) {
                        (shiftAnim + dragOffset.floatValue - delta)
                            .coerceIn(-viewportH * 0.4f, contentH - viewportH * 0.4f)
                    } else {
                        shiftAnim + dragOffset.floatValue - delta
                    }
                    dragOffset.floatValue = newShift - shiftAnim
                    delta
                },
            ),
        contentAlignment = Alignment.TopStart,
    ) {
        val viewportHeightPx = boxSize.height
        val boardWidthDp = with(density) { c3dBoardWidthPx(boxSize.width.toFloat(), params.tiltOn, tiltDeg, perspAnim).toDp() }
        // 字号按容器宽 /360 缩放（原型 `applyFontSize`）：容器宽先换算回 dp，与设计单位一致
        val containerDp = boxSize.width / density.density
        val sizeFactor = if (containerDp > 0f) containerDp / 360f else 1f
        val lineFontSize = (params.fontSize * sizeFactor).coerceAtLeast(8f)
        val ratio = if (sheetOpen) FOCUS_RATIO * (1f - SHEET_HEIGHT_FRACTION) else FOCUS_RATIO
        // 上下缓冲：板转起来后首尾行会离开视口，给足 padding（原型 `c3dApplyPadding`）
        // 上翘角为负（反向翘）时这一项会变小，至少要留住一段缓冲，否则首尾句会被裁掉
        val basePad = with(density) { 100.dp.toPx() }
        val verticalPadPx = (viewportHeightPx * 0.6f +
            (if (params.tiltOn) viewportHeightPx * (pitchDeg * Math.PI / 180.0).toFloat() * 2f else 0f) +
            basePad).coerceAtLeast(basePad)

        Layout(
            content = {
                lines.forEachIndexed { index, line ->
                    Lyric3dLine(
                        line = line,
                        distance = index - current,
                        params = params,
                        align = align,
                        translate = translate,
                        fontDp = lineFontSize,
                        focusPull = focusPull,
                        browsing = browsing,
                        onClick = {
                            browsing = false
                            onSeekToLine(line.timeMs)
                        },
                        onLongPress = { onLongPressLineShare(line) },
                    )
                }
            },
            modifier = Modifier
                .width(boardWidthDp)
                .graphicsLayer {
                    rotationX = -pitchDeg
                    rotationY = when {
                        align == LyricAlign.RIGHT -> -tiltDeg
                        align == LyricAlign.CENTER -> 0f
                        else -> tiltDeg
                    }
                    cameraDistance = perspAnim * PERSPECTIVE_SCALE
                    transformOrigin = when (align) {
                        LyricAlign.LEFT -> TransformOrigin(0f, 0.5f)
                        LyricAlign.CENTER -> TransformOrigin(0.5f, 0.5f)
                        LyricAlign.RIGHT -> TransformOrigin(1f, 0.5f)
                    }
                }
                // 板的对齐边贴着容器对应边（原型 `left/right` 分别贴 0）
                .align(
                    when (align) {
                        LyricAlign.LEFT -> Alignment.TopStart
                        LyricAlign.CENTER -> Alignment.TopCenter
                        LyricAlign.RIGHT -> Alignment.TopEnd
                    },
                ),
            measurePolicy = { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                // 累计高度：每行的布局 y 自己算，所以「当前句居中」是确定性的，不依赖测量
                val cumulative = IntArray(placeables.size + 1)
                placeables.forEachIndexed { i, placeable -> cumulative[i + 1] = cumulative[i] + placeable.height }
                val index = current.coerceIn(0, (placeables.size - 1).coerceAtLeast(0))
                val pad = verticalPadPx.roundToInt()
                val currentCenterY = pad + cumulative[index] + (placeables.getOrNull(index)?.height ?: 0) / 2f
                val base = currentCenterY - viewportHeightPx * ratio
                // 浏览态不跟随（用户正在自己翻看）；跟唱态把目标交给补间动画
                if (!browsing && abs(base - targetShift) > 0.5f) targetShift = base
                // 内容总高（含上下 pad）回报给 scrollable 的惯性边界
                val totalContent = (cumulative.last() + pad * 2).toFloat()
                if (abs(contentHeightPx.floatValue - totalContent) > 0.5f) {
                    contentHeightPx.floatValue = totalContent
                }
                // dragOffset.floatValue 在 measure 里读 → 拖动只触发重排版，不触发重组
                val shift = shiftAnim + dragOffset.floatValue

                layout(constraints.maxWidth, viewportHeightPx.coerceAtLeast(1)) {
                    placeables.forEachIndexed { i, placeable ->
                        placeable.placeRelative(0, (pad + cumulative[i] - shift).roundToInt())
                    }
                }
            },
        )

        // 浏览态的出口（与平面档同一套）：不 seek 也能回到跟唱
        if (browsing) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.surface)
                    .clickable { browsing = false }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(text = "回到当前句", style = LXType.labelSmall, color = colors.accent)
            }
        }
    }
}

/**
 * 透视强度的映射系数（原型教训的定稿值）。
 *
 * ⚠️ CSS 的 `perspective: 1200px` 与 Compose 的 `cameraDistance` **不是同一量纲**：
 * Compose 用一个很小的数表达「镜头近」（默认值量级是 8×density），直接塞 1200 等于把镜头
 * 拉到极远 —— 表现出来就是「透视距离调了几乎没变化」（2026-10-03 用户反馈）。
 * 按 0.15 缩放映射：滑块 400~3000 → 60~450；默认 1200 → 180，透视明显强于 1/4 那版
 * （用户 2026-10-03：「效果更强一点」）。
 */
private const val PERSPECTIVE_SCALE = 0.15f

/**
 * 立体档的一行：越远越模糊 / 越淡；当前句整行提为 accent（不做逐字填充）。
 *
 * ⚠️ 只给**近处三行**上模糊：`Modifier.blur` 每行都要一张离屏图层，
 * 给整首歌词（上百行）都加会直接压垮 GPU。
 */
@Composable
private fun Lyric3dLine(
    line: LyricLine,
    distance: Int,
    params: Lyric3dParams,
    align: LyricAlign,
    translate: Boolean,
    fontDp: Float,
    focusPull: Animatable<Float, *>,
    browsing: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val colors = LXTheme.colors
    val absolute = abs(distance)
    val isCurrent = distance == 0
    val depth = absolute.coerceAtMost(Lyric3dParams.MAX_DEPTH)
    // 浏览态：所有行同样亮、不模糊、不填充 —— 翻找时看得清每一行（与平面档同一套语义）
    val lineAlpha = when {
        browsing -> 1f
        isCurrent -> 1f
        else -> (params.dimBase * (1f - depth * params.opDecay)).coerceAtLeast(0.02f)
    }
    // 切句焦点拉动（17 §8）：切句瞬间模糊在稳态值上加强一倍，640ms 内收敛回稳态。
    // ⚠️ 只有带模糊的近处三行才读这个 State —— 其余行不订阅，不随动画逐帧重组
    val pull = if (!browsing && absolute in 1..3) focusPull.value else 0f
    val lineBlur = (depth * params.blurStep * (1f + pull)).takeIf { !browsing && absolute in 1..3 && it > 0.05f }
    val textAlign = when (align) {
        LyricAlign.LEFT -> TextAlign.Start
        LyricAlign.CENTER -> TextAlign.Center
        LyricAlign.RIGHT -> TextAlign.End
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (lineBlur != null) Modifier.blur(lineBlur.dp) else Modifier)
            .alpha(lineAlpha)
            .pointerInput(line.timeMs) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (isCurrent && !browsing) {
                // 柔光投影（17 §8「当前行另加一层柔光投影」）：同一份文字 accent 垫在底下，
                // 大模糊 + 低透明度 → 当前句从版面上「浮起发光」。
                Text(
                    text = line.text,
                    style = LYRIC_3D_TEXT.copy(
                        fontSize = fontDp.sp,
                        lineHeight = (fontDp * 1.6f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = colors.accent,
                    textAlign = textAlign,
                    modifier = Modifier
                        .fillMaxWidth()
                        .blur(GLOW_BLUR_DP)
                        .alpha(GLOW_ALPHA),
                )
            }
            Text(
                text = line.text,
                style = LYRIC_3D_TEXT.copy(
                    fontSize = fontDp.sp,
                    lineHeight = (fontDp * 1.6f).sp,
                    fontWeight = if (isCurrent && !browsing) FontWeight.Bold else FontWeight.Normal,
                ),
                // 当前句整行 accent，其余行 ink-1（不做逐字填充）
                color = if (isCurrent && !browsing) colors.accent else colors.ink1,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val translation = line.translation
        if (translate && !translation.isNullOrBlank()) {
            Text(
                text = translation,
                style = LYRIC_3D_TEXT.copy(
                    fontSize = (fontDp * 0.78f).sp,
                    lineHeight = (fontDp * 1.6f * 0.78f).sp,
                ),
                color = colors.ink1,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 板宽的解析解（原型 `c3dCardWidth`）：转过去以后远端会在透视里收窄，
 * 要按旋转角把板加宽到「转完之后仍铺满屏宽」。
 * 入参用动画后的角度与透视距离，与 graphicsLayer 的旋转保持同步。
 */
private fun c3dBoardWidthPx(containerWidth: Float, tiltOn: Boolean, tiltDeg: Float, persp: Float): Float {
    if (containerWidth <= 0f) return 0f
    val safe = containerWidth * 0.92f
    if (!tiltOn || tiltDeg < 0.5f) return safe
    val distance = persp * PERSPECTIVE_SCALE
    val radians = tiltDeg * Math.PI.toFloat() / 180f
    val denominator = kotlin.math.cos(radians) * distance - safe * kotlin.math.sin(radians)
    if (denominator <= 1f) return containerWidth * 2.2f
    return kotlin.math.min(safe * distance / denominator, containerWidth * 2.2f)
}

// ─────────────────────────── 共用 ───────────────────────────

/**
 * 上下渐隐：平面档 34dp，立体档 14%（原型 `.lyrics` 与 `body.lyr3d .lyrics` 的 mask）。
 * DstIn + 离屏合成 = 按透明度把内容擦掉。
 */
private fun Modifier.lyricFadeMask(flat: Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val stops = if (flat) {
            val edge = 34.dp.toPx() / size.height.coerceAtLeast(1f)
            arrayOf(0f to Color.Transparent, edge to Color.Black, (1f - edge) to Color.Black, 1f to Color.Transparent)
        } else {
            arrayOf(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent)
        }
        drawRect(brush = Brush.verticalGradient(colorStops = stops), blendMode = BlendMode.DstIn)
    }

private suspend fun awaitFrame() = withFrameNanos { }

/** 立体档主文字：15.5px/1.6 行高（原型 `body.lyr3d .lline`） */
private val LYRIC_3D_TEXT = TextStyle(
    fontSize = 24.sp,
    lineHeight = 38.sp,
    letterSpacing = 0.02.sp,
)

/** 平面档主文字行高：15.5px × 2.05（原型 `.lline`） */
private val LYRIC_LINE_HEIGHT = 31.8.sp

/** 平面档字距：.03em（原型 `.lline`） */
private val LYRIC_LETTER_SPACING = 0.47.sp

/** 当前句落在可视区的比例（2026-10-24 用户裁决：**垂直居中**，上下各留一半） */
private const val FOCUS_RATIO = 0.5f

/** 参数面板高度占屏比（14 §4.4:186：面板半高 48%，且不允许完全遮挡歌词） */
internal const val SHEET_HEIGHT_FRACTION = 0.48f

/** 四态中的二/三/四档透明度（14 §4.4:165-171 的 .46-.52 / .20-.24 / .10-.13） */
private const val LYR_2 = 0.52f
private const val LYR_3 = 0.24f
private const val LYR_4 = 0.13f

/** 切句焦点拉动的收敛时长（17 §8：切句时远处短暂加强、640ms 内收敛） */
private const val FOCUS_PULL_MS = 640

/** 当前句柔光投影的模糊半径与强度（17 §8「另加一层柔光投影」） */
private val GLOW_BLUR_DP = 7.dp
private const val GLOW_ALPHA = 0.45f

/** 翻阅后静止多久自动回到跟唱（2026-10-03 用户要求） */
private const val BROWSE_IDLE_MS = 3_500L

/** 判断「静止」的轮询间隔（用 delay 而不是逐帧，静止时不白出帧） */
private const val BROWSE_IDLE_POLL_MS = 100L

/** 平面档歌词基准字号（原型 `.lline` 15.5px；立体档基准 24 → 平面 = 15.5 × fontSize/24） */
private const val FLAT_LYRIC_FONT_SP = 15.5f