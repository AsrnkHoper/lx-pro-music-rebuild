package com.lxpro.music.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.component.LXQuality
import com.lxpro.core.designsystem.component.LXSwitch
import com.lxpro.core.designsystem.theme.LXPalette
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.tutorial.TutorialTargets
import com.lxpro.core.media.noise.NoiseCatalog
import com.lxpro.core.model.Quality
import com.lxpro.core.model.SearchMode
import com.lxpro.music.TUTORIAL_STEPS
import com.lxpro.music.TutorialStep
import com.lxpro.music.TutorialUiState
import com.lxpro.music.TutorialViewModel
import kotlin.math.roundToInt

/**
 * 游戏式新手引导（2026-10-27 用户要求，取代文本分页教程）。
 *
 * 覆盖在**真实界面**之上：
 * - 半透明遮罩 + 按目标控件的实际位置挖洞（[TutorialTargets]），控件原样露出来、还能点
 * - 挖洞以外的区域被四块透明拦截层吃掉，用户只能操作被高亮的那一个控件
 * - 「点到了才前进」：点高亮区即推进；Tab 类步骤由路由变化自动推进（用户点的是真 Tab）
 * - 气泡卡贴着目标上下摆放：目标在屏幕下半就把卡片放上方，反之放下方
 *
 * 前 5 步是偏好问答（居中卡），选一项立刻写 DataStore，整个界面马上跟着变色。
 */
@Composable
fun TutorialOverlay(
    state: TutorialUiState,
    viewModel: TutorialViewModel,
    onFinish: () -> Unit,
) {
    if (!state.active) return
    val step = TUTORIAL_STEPS.getOrNull(state.stepIndex) ?: return
    val colors = LXTheme.colors
    val density = LocalDensity.current

    // 遮罩层自己在窗口里的原点：目标用窗口坐标上报，这里换算成本地坐标
    var origin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val gapPx = with(density) { 12.dp.toPx() }
        val holePadPx = with(density) { 7.dp.toPx() }
        val cornerPx = with(density) { 16.dp.toPx() }

        val tour = step as? TutorialStep.Tour
        val targetRect = tour?.targetId?.let { id ->
            TutorialTargets.rectOf(id)?.let { rect ->
                Rect(
                    rect.left - origin.x,
                    rect.top - origin.y,
                    rect.right - origin.x,
                    rect.bottom - origin.y,
                ).inflate(holePadPx)
            }
        }

        // 高亮圈呼吸：不动位置，只动描边透明度，避免干扰点击
        val pulse by rememberInfiniteTransition(label = "tutorialPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "tutorialPulseAlpha",
        )

        CanvasOverlay(hole = targetRect, cornerPx = cornerPx, ringAlpha = pulse)

        if (targetRect == null) {
            // 没有具体目标（偏好问答 / 页面说明）：整屏挡住，只留气泡卡可点
            Box(Modifier.fillMaxSize().consumeAllPointerEvents())
        } else {
            HoleBlockers(hole = targetRect, screenW = screenW, screenH = screenH)
            if (tour?.tapToAdvance == true) {
                // 高亮区盖一层透明接收器：点击被教程吃掉，不会真的打开输入框 / 弹窗
                Box(
                    modifier = Modifier
                        .offset { IntOffset(targetRect.left.roundToInt(), targetRect.top.roundToInt()) }
                        .size(
                            with(density) { targetRect.width.toDp() },
                            with(density) { targetRect.height.toDp() },
                        )
                        .pointerInput(state.stepIndex) {
                            detectTapGestures { viewModel.next() }
                        },
                )
            }
        }

        // ── 气泡卡定位 ──
        val bubbleModifier: Modifier = when {
            targetRect == null ->
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = LXTheme.scalars.sidePadding)
            targetRect.center.y > screenH * 0.55f -> {
                val bottomPad =
                    with(density) { (screenH - targetRect.top).toDp() } + 10.dp + with(density) { gapPx.toDp() }
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = LXTheme.scalars.sidePadding)
                    .padding(bottom = bottomPad)
            }
            else -> {
                val topPad = with(density) { targetRect.bottom.toDp() } + with(density) { gapPx.toDp() }
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = LXTheme.scalars.sidePadding)
                    .padding(top = topPad)
            }
        }

        val bubbleMaxHeight = maxHeight * 0.62f
        val bodyMaxHeight = maxHeight * 0.44f
        BubbleCard(
            modifier = bubbleModifier,
            maxHeight = bubbleMaxHeight,
        ) {
            BubbleHeader(
                stepIndex = state.stepIndex,
                stepCount = TUTORIAL_STEPS.size,
                onSkip = { viewModel.finish(onFinish) },
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    TutorialStep.Welcome -> WelcomeStep()
                    TutorialStep.Palette -> PaletteStep(state, viewModel::pickPalette)
                    TutorialStep.Sound -> SoundStep(state, viewModel)
                    TutorialStep.Habits -> HabitsStep(state, viewModel)
                    TutorialStep.NoiseScene -> NoiseSceneStep(state, viewModel::pickNoiseScene)
                    is TutorialStep.Tour -> TourStep(step)
                    TutorialStep.Done -> DoneStep()
                }
            }

            Spacer(Modifier.height(14.dp))
            BubbleActions(
                stepIndex = state.stepIndex,
                finishing = state.finishing,
                onBack = viewModel::back,
                onNext = {
                    if (state.stepIndex >= TUTORIAL_STEPS.lastIndex) {
                        viewModel.finish(onFinish)
                    } else {
                        viewModel.next()
                    }
                },
            )
        }
    }
}

// ─────────────────────────── 遮罩 / 拦截 ───────────────────────────

@Composable
private fun CanvasOverlay(hole: Rect?, cornerPx: Float, ringAlpha: Float) {
    val accent = LXTheme.colors.accent
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val path = Path().apply {
            // 整屏先填满，再把洞当成「反向矩形」挖掉（EvenOdd）
            addRect(Rect(0f, 0f, size.width, size.height))
            hole?.let { addRoundRect(RoundRect(it, CornerRadius(cornerPx))) }
            fillType = PathFillType.EvenOdd
        }
        drawPath(path, Color.Black.copy(alpha = 0.62f))
        hole?.let {
            drawRoundRect(
                color = accent.copy(alpha = ringAlpha),
                topLeft = it.topLeft,
                size = it.size,
                cornerRadius = CornerRadius(cornerPx),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** 四块透明拦截层把「洞」以外的点击全部吃掉，洞里的真实控件照常接收 */
@Composable
private fun HoleBlockers(hole: Rect, screenW: Float, screenH: Float) {
    Blocker(0f, 0f, screenW, hole.top)
    Blocker(0f, hole.bottom, screenW, screenH - hole.bottom)
    Blocker(0f, hole.top, hole.left, hole.height)
    Blocker(hole.right, hole.top, screenW - hole.right, hole.height)
}

@Composable
private fun Blocker(left: Float, top: Float, width: Float, height: Float) {
    if (width <= 0f || height <= 0f) return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .consumeAllPointerEvents(),
    )
}

/** 吃掉落在上面的所有指针事件（包括拖动），让下层页面完全收不到 */
private fun Modifier.consumeAllPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

// ─────────────────────────── 气泡卡 ───────────────────────────

@Composable
private fun BubbleCard(
    modifier: Modifier,
    maxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LXTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
private fun BubbleHeader(stepIndex: Int, stepCount: Int, onSkip: () -> Unit) {
    val colors = LXTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "新手教程 · ${stepIndex + 1} / $stepCount",
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.weight(1f),
            )
            if (stepIndex < TUTORIAL_STEPS.lastIndex) {
                Text(
                    text = "跳过",
                    style = LXType.labelLarge,
                    color = colors.ink3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.hairline),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((stepIndex + 1f) / stepCount)
                    .height(3.dp)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
private fun BubbleActions(
    stepIndex: Int,
    finishing: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stepIndex > 0) {
            Text(
                text = "上一步",
                style = LXType.labelLarge,
                color = colors.ink3,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val label = when {
            finishing -> "正在整理…"
            stepIndex >= TUTORIAL_STEPS.lastIndex -> "开始使用"
            else -> "下一步"
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (finishing) colors.ink3 else colors.accent)
                .clickable(enabled = !finishing, onClick = onNext)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        ) {
            Text(text = label, style = LXType.labelLarge, color = colors.bg)
        }
    }
}

// ─────────────────────────── 各步内容 ───────────────────────────

@Composable
private fun WelcomeStep() {
    StepTitle(eyebrow = "阿玉音乐", title = "先调成你的，再逛一遍真实界面")
    StepLines(
        listOf(
            "接下来问 5 个小问题：配色、音质、搜索、播放习惯、白噪音场景 —— 选完立即生效。",
            "随后遮罩会亮起真实控件，你点到哪，就学到哪。",
            "随时可以点右上角「跳过」。",
        ),
    )
}

@Composable
private fun PaletteStep(state: TutorialUiState, onPick: (String) -> Unit) {
    StepTitle(
        eyebrow = "偏好 1 / 5",
        title = "挑一套配色",
        subtitle = "六套情绪配色，点一下整个界面立刻变色；之后在「设置 → 外观」也能改。",
    )
    LXPalette.all.forEach { palette ->
        val colors = LXTheme.colors
        val selected = palette.id == state.paletteId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) colors.bg2 else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (selected) colors.accent else colors.hairline,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable { onPick(palette.id) }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            PaletteSwatch(palette)
            Text(
                text = palette.displayName,
                style = LXType.bodyMedium,
                color = if (selected) colors.accent else colors.ink1,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(text = "已选", style = LXType.labelSmall, color = colors.accent)
            }
        }
    }
}

@Composable
private fun PaletteSwatch(palette: LXPalette) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(palette.colors.bg)
            .border(1.dp, palette.colors.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(palette.colors.accent),
        )
    }
}

@Composable
private fun SoundStep(state: TutorialUiState, viewModel: TutorialViewModel) {
    StepTitle(
        eyebrow = "偏好 2 / 5",
        title = "音质与流量",
        subtitle = "音源给不到这个档位时会自动降级，不会放不出来。",
    )
    QuestionLabel("默认音质")
    OptionChips(
        options = QUALITY_OPTIONS.map { it.value to LXQuality.label(it) },
        selectedId = state.quality,
        onPick = viewModel::pickQuality,
    )
    Spacer(Modifier.height(16.dp))
    SwitchCard(
        title = "省流量模式",
        subtitle = "仅在蜂窝 / 热点下生效：播放自动降到 128k，并且不加载网络图片。Wi-Fi 下一切照旧。",
        checked = state.dataSaver,
        onToggle = viewModel::setDataSaver,
    )
}

@Composable
private fun HabitsStep(state: TutorialUiState, viewModel: TutorialViewModel) {
    StepTitle(
        eyebrow = "偏好 3 / 5",
        title = "搜索与播放习惯",
        subtitle = "搜索模式决定首页与搜索页默认看哪一边。",
    )
    QuestionLabel("搜索模式")
    OptionChips(
        options = listOf(
            SearchMode.ALL.name to "全部",
            SearchMode.ONLINE.name to "在线音源",
            SearchMode.LOCAL.name to "本地音乐",
        ),
        selectedId = state.searchMode.name,
        onPick = { id -> viewModel.pickSearchMode(SearchMode.fromId(id)) },
    )
    Spacer(Modifier.height(16.dp))
    SwitchCard(
        title = "记忆上次播放位置",
        subtitle = "同一首歌再次播放时，从上次停下的位置继续，而不是从头开始。",
        checked = state.rememberPosition,
        onToggle = viewModel::setRememberPosition,
    )
}

@Composable
private fun NoiseSceneStep(state: TutorialUiState, onPick: (String?) -> Unit) {
    StepTitle(
        eyebrow = "偏好 4 / 5",
        title = "白噪音默认场景",
        subtitle = "选中后进入白噪音页会直接铺好这套混音；不预置则沿用上次的混音。",
    )
    OptionChips(
        options = NoiseCatalog.presetScenes.map { it.id to it.name } + (NO_PRESET_ID to "不预置"),
        selectedId = state.noiseSceneId ?: NO_PRESET_ID,
        onPick = { id -> onPick(if (id == NO_PRESET_ID) null else id) },
    )
}

@Composable
private fun TourStep(step: TutorialStep.Tour) {
    val colors = LXTheme.colors
    StepTitle(title = step.title, subtitle = step.body)
    step.hint?.let { hint ->
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = hint, style = LXType.labelSmall, color = colors.accent)
        }
    }
}

@Composable
private fun DoneStep() {
    StepTitle(
        eyebrow = "最后",
        title = "都设好了，开始听吧",
        subtitle = "偏好已全部保存。",
    )
    StepLines(
        listOf(
            "设置都在底部「设置」Tab 里，随时能改。",
            "所有数据都留在本机，不上传。",
        ),
    )
}

// ─────────────────────────── 通用小件 ───────────────────────────

@Composable
private fun StepTitle(eyebrow: String? = null, title: String, subtitle: String? = null) {
    val colors = LXTheme.colors
    eyebrow?.let {
        Text(text = it, style = LXType.labelLarge, color = colors.accent)
        Spacer(Modifier.height(7.dp))
    }
    Text(text = title, style = LXType.titleLarge, color = colors.ink1)
    subtitle?.let {
        Spacer(Modifier.height(8.dp))
        Text(text = it, style = LXType.bodyMedium, color = colors.ink2)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun StepLines(lines: List<String>) {
    val colors = LXTheme.colors
    lines.forEach { line ->
        Text(
            text = line,
            style = LXType.bodyMedium,
            color = colors.ink2,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun QuestionLabel(text: String) {
    Text(
        text = text,
        style = LXType.titleSmall,
        color = LXTheme.colors.ink1,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(
    options: List<Pair<String, String>>,
    selectedId: String,
    onPick: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            val colors = LXTheme.colors
            val selected = id == selectedId
            val shape = RoundedCornerShape(percent = 50)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) colors.accent else colors.surface)
                    .border(1.dp, if (selected) colors.accent else colors.hairline, shape)
                    .clickable { onPick(id) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    style = LXType.labelLarge,
                    color = if (selected) colors.bg else colors.ink2,
                )
            }
        }
    }
}

@Composable
private fun SwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = LXType.bodyMedium, color = colors.ink1)
            Spacer(Modifier.height(3.dp))
            Text(text = subtitle, style = LXType.labelSmall, color = colors.ink3)
        }
        LXSwitch(checked = checked, onCheckedChange = onToggle)
    }
}

private const val NO_PRESET_ID = "__none__"

private val QUALITY_OPTIONS = listOf(Quality.K128, Quality.K320, Quality.FLAC, Quality.HIRES)
