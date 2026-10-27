package com.lxpro.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXShadowStyle
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.media.PlayerState
import com.lxpro.core.media.RepeatMode
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song

/**
 * 播放页完整版（权威稿 `mockup_app.html` 的 `#player`，1549-1677 行）。
 *
 * 结构自上而下：`.topbar`（返回 / 歌名·歌手 / 更多）→ `.stage`（**封面模式 ⇄ 歌词模式**）→
 * `.bottom`（进度条 + 控制排）。弹层：立体歌词参数面板（半高 48%）、播放队列。
 *
 * ⚠️ 与 M2 版本的差别（14 §4.4 的要求）：
 * - 封面 ⇄ 歌词 双模式（点封面 / 底栏「歌词」/ 歌词页「封面」三处都能切）
 * - 歌词四态连续淡化 l1→l4、上下 34dp 渐隐、当前句落在可视区 42%
 * - 点句跳转并起播；进度条拖动中**暂停歌词自动跟随**
 * - 立体歌词档（整块板旋转）+ 参数面板；**默认关**（14 §4.4:183）
 *
 * ⚠️ 本页不管：白噪音独立音轨与定时关闭（M4）、下载/换源的落地（占位提示，见 08 篇进度）。
 */
@Composable
fun PlayerScreen(
    state: PlayerState,
    lyrics: LyricsUiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onMoreSong: (Song) -> Unit,
    /** 封面模式的「下载」快捷动作：按当前音质入队（M3 任务 8） */
    onDownloadSong: (Song, Quality) -> Unit,
    /** 页面级轻提示（循环模式轮换等） */
    onNotice: (String) -> Unit,
    /** 歌词长按分享：把歌曲 + 被长按行抛给上层弹分享弹窗 */
    onShareLine: ((Song, com.lxpro.core.common.LyricLine) -> Unit)? = null,
    /** 歌曲定时提示条文案（17 §7：进度条上方，可一键取消）；null=不显示 */
    songTimerText: String? = null,
    onCancelSongTimer: () -> Unit = {},
    /** 切歌淡变时长（ms，0=即切）：进度条两端渐变区宽度与中间徽章（17 §6.1） */
    crossfadeMs: Long = 0L,
    /** 页面状态由调用方持有（app 级作用域）—— 顶部「更多」弹层也要能操作它 */
    pageViewModel: PlayerPageViewModel,
    modifier: Modifier = Modifier,
) {
    val pageState by pageViewModel.state.collectAsStateWithLifecycle()
    val liked by pageViewModel.liked.collectAsStateWithLifecycle()
    val queue by pageViewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by pageViewModel.currentIndex.collectAsStateWithLifecycle()
    val scalars = LXTheme.scalars

    // 拖动中的进度用本地状态：既不打断 500ms 的进度回调，也避免每动一下就去 seek 播放器
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L)

    Box(modifier = modifier.fillMaxSize().lxSafeDrawingPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                song = state.current,
                onBack = onBack,
                onMore = { state.current?.let(onMoreSong) },
            )

            // ── .stage：两种模式各自 320ms fade + scale(.98)（原型 `@keyframes fade`）──
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = pageState.mode,
                    transitionSpec = {
                        (fadeIn(tween(MODE_FADE_MS)) + scaleIn(tween(MODE_FADE_MS), initialScale = 0.98f))
                            .togetherWith(fadeOut(tween(MODE_FADE_MS)) + scaleOut(tween(MODE_FADE_MS), targetScale = 0.98f))
                    },
                    label = "playerStage",
                    modifier = Modifier.fillMaxSize(),
                ) { mode ->
                    when (mode) {
                        PlayerPageMode.COVER -> CoverMode(
                            state = state,
                            lyricsAvailable = lyrics.lines.isNotEmpty(),
                            liked = liked,
                            onOpenLyrics = { pageViewModel.onModeChange(PlayerPageMode.LYRICS) },
                            onToggleLike = { state.current?.let(pageViewModel::onToggleLike) },
                            onDownload = { state.current?.let { song -> onDownloadSong(song, state.quality ?: Quality.K320) } },
                            onMore = { state.current?.let(onMoreSong) },
                            onComments = { pageViewModel.toggleCommentSheet() },
                        )

                        PlayerPageMode.LYRICS -> LyricMode(
                            state = state,
                            lyrics = lyrics,
                            pageState = pageState,
                            dragging = dragging,
                            onBackToCover = { pageViewModel.onModeChange(PlayerPageMode.COVER) },
                            onSeekToLine = { timeMs ->
                                onSeek(timeMs)
                                if (!state.isPlaying) onTogglePlay()
                            },
                            onShareLine = { song, line -> onShareLine?.invoke(song, line) },
                        )
                    }
                }
            }

            // ── 定时关闭提示条（17 §7：进度条上方，可一键取消）──
            if (songTimerText != null) {
                SongTimerBar(
                    text = songTimerText,
                    onCancel = onCancelSongTimer,
                    modifier = Modifier
                        .padding(horizontal = scalars.sidePadding)
                        .padding(bottom = 8.dp),
                )
            }

            PlayerBottom(
                state = state,
                repeatMode = state.repeatMode,
                dragging = dragging,
                dragFraction = dragFraction,
                duration = duration,
                onDragStart = { fraction ->
                    dragging = true
                    dragFraction = fraction
                },
                onDrag = { fraction -> dragFraction = fraction },
                onDragEnd = {
                    dragging = false
                    onSeek((dragFraction * duration).toLong())
                },
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onPickRepeat = { mode ->
                    pageViewModel.onPickRepeat(mode)
                    onNotice(mode.displayName())
                },
                onOpenQueue = { pageViewModel.onQueueSheetOpenChange(true) },
                crossfadeMs = crossfadeMs,
                modifier = Modifier.padding(horizontal = scalars.sidePadding, vertical = 0.dp),
            )
        }

        // 弹层打开时，返回键先关弹层（否则会直接把整个播放页退掉 —— 2026-10-03 用户反馈）
        BackHandler(enabled = pageState.queueSheetOpen || pageState.p3dSheetOpen || pageState.commentSheetOpen) {
            when {
                pageState.p3dSheetOpen -> pageViewModel.onP3dSheetOpenChange(false)
                pageState.queueSheetOpen -> pageViewModel.onQueueSheetOpenChange(false)
                pageState.commentSheetOpen -> pageViewModel.toggleCommentSheet()
            }
        }

        // ── 立体歌词参数面板（半高 48%；`c3dReset` 等都在面板里）──
        if (pageState.p3dSheetOpen) {
            Lyric3dSheet(
                pageState = pageState,
                onDismiss = { pageViewModel.onP3dSheetOpenChange(false) },
                onToggle3d = pageViewModel::onToggle3d,
                onToggleTilt = pageViewModel::onToggleTilt,
                onAlignChange = pageViewModel::onAlignChange,
                onParamChange = pageViewModel::onParamChange,
                onReset = {
                    pageViewModel.onReset3d()
                    onNotice("已恢复立体歌词的默认参数")
                },
            )
        }

        // ── 播放队列 ──
        if (pageState.queueSheetOpen) {
            QueueSheet(
                queue = queue,
                currentIndex = currentIndex,
                onDismiss = { pageViewModel.onQueueSheetOpenChange(false) },
                onPlayAt = pageViewModel::onPlayAt,
            )
        }

        // ── 评论 ──
        if (pageState.commentSheetOpen) {
            val commentState by pageViewModel.commentState.collectAsStateWithLifecycle()
            CommentSheet(
                state = commentState,
                onDismiss = { pageViewModel.toggleCommentSheet() },
                onTabChange = pageViewModel::switchCommentTab,
                onLoadMore = pageViewModel::loadMoreComments,
            )
        }
    }
}

// ─────────────────────────── 顶栏 ───────────────────────────

@Composable
private fun PlayerTopBar(song: Song?, onBack: () -> Unit, onMore: () -> Unit) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = scalars.sidePadding)
            .padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBtn(icon = LxIcons.Back, description = "返回", onClick = onBack)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = song?.name ?: "未在播放",
                style = LXType.labelLarge,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song?.singer ?: "正在播放",
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        IconBtn(
            icon = LxIcons.More,
            description = "更多",
            onClick = onMore,
            enabled = song != null,
        )
    }
}

@Composable
private fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(LXTheme.pageInset)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = colors.ink1,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ─────────────────────────── 封面模式 ───────────────────────────

@Composable
private fun CoverMode(
    state: PlayerState,
    lyricsAvailable: Boolean,
    liked: Boolean,
    onOpenLyrics: () -> Unit,
    onToggleLike: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
    onComments: () -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = scalars.sidePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // .cover：270dp、圆角 20、投影；点它切歌词
        // 投影随配色联动（13 §5 的 shadowStyle）：弥散（浅色系）= 大而淡，硬边（深色系）= 紧而深
        val coverShape = RoundedCornerShape(20.dp)
        val diffuse = scalars.shadowStyle == LXShadowStyle.DIFFUSE
        // 封面按压反馈（16 §5：250ms scale 0.975）
        val coverInteraction = remember { MutableInteractionSource() }
        val coverPressed by coverInteraction.collectIsPressedAsState()
        val coverScale by animateFloatAsState(
            targetValue = if (coverPressed) 0.975f else 1f,
            animationSpec = tween(250),
            label = "coverPress",
        )
        Box(
            modifier = Modifier
                .size(COVER_SIZE)
                .graphicsLayer { scaleX = coverScale; scaleY = coverScale }
                .shadow(
                    elevation = if (diffuse) 26.dp else 10.dp,
                    shape = coverShape,
                    ambientColor = Color.Black.copy(alpha = if (diffuse) 0.10f else 0.30f),
                    spotColor = Color.Black.copy(alpha = if (diffuse) 0.22f else 0.45f),
                )
                .clip(coverShape)
                .background(LXTheme.pageInset)
                .clickable(
                    interactionSource = coverInteraction,
                    indication = null,
                    onClick = onOpenLyrics,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AsyncArtwork(
                model = state.current?.picUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // .cover::after 的那层高光
            Box(
                modifier = Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.32f), Color.Transparent),
                            center = Offset(size.width * 0.22f, size.height * 0.12f),
                            radius = size.width * 1.2f,
                        ),
                    )
                },
            )
            if (state.isBuffering) {
                CircularProgressIndicator(color = colors.accent)
            }
            Text(
                text = if (lyricsAvailable) "点击查看歌词" else "点击查看歌词（这首暂无歌词）",
                style = LXType.labelSmall,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.current?.name ?: "未在播放",
                style = LXType.titleLarge,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = state.current?.singer.orEmpty(),
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(text = message, style = LXType.labelSmall, color = colors.accent)
        }

        Spacer(Modifier.height(22.dp))

        // .acts：喜欢 / 下载 / 歌词 / 音源
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                icon = LxIcons.Heart,
                label = "喜欢",
                highlighted = liked,
                onClick = onToggleLike,
            )
            ActionButton(icon = LxIcons.Download, label = "下载", onClick = onDownload)
            ActionButton(icon = LxIcons.LyricLines, label = "歌词", onClick = onOpenLyrics)
            ActionButton(icon = LxIcons.Comment, label = "评论", onClick = onComments)
            ActionButton(
                label = state.current?.let { sourceLabelOf(it.source.value) } ?: "音源",
                onClick = onMore,
            )
        }
    }
}

/** `.abtn`：高 34、圆角 11、发丝线；`highlighted` = 原型 `.abtn.on` */
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    highlighted: Boolean = false,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(LXTheme.pageSurface)
            .border(
                width = 1.dp,
                color = if (highlighted) colors.accent else colors.hairline,
                shape = RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = if (highlighted) colors.accent else colors.ink2,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = label,
            style = LXType.labelSmall,
            color = if (highlighted) colors.accent else colors.ink2,
            maxLines = 1,
        )
    }
}

// ─────────────────────────── 歌词模式 ───────────────────────────

@Composable
private fun LyricMode(
    state: PlayerState,
    lyrics: LyricsUiState,
    pageState: PlayerPageState,
    dragging: Boolean,
    onBackToCover: () -> Unit,
    onSeekToLine: (Long) -> Unit,
    onShareLine: ((Song, com.lxpro.core.common.LyricLine) -> Unit)? = null,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = scalars.sidePadding)) {
        // .lyrhead：迷你封面（**点它切回封面页**）+ 歌名/歌手。
        // 「立体」「歌词参数」已收进右上角「更多」，「封面」按钮取消（2026-10-03 用户要求）
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LXTheme.pageInset)
                    .clickable(onClick = onBackToCover),
            ) {
                AsyncArtwork(
                    model = state.current?.picUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.current?.name ?: "未在播放",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.current?.singer.orEmpty(),
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        LyricsArea(
            lyrics = lyrics,
            positionMs = state.positionMs,
            playing = state.isPlaying,
            pageState = pageState,
            dragging = dragging,
            onSeekToLine = onSeekToLine,
            onLongPressLineShare = { line -> state.current?.let { song -> onShareLine?.invoke(song, line) } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

// ─────────────────────────── 定时关闭提示条（17 §7）───────────────────────────

@Composable
private fun SongTimerBar(
    text: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LXDimens.radiusSmall))
            .background(LXTheme.pageInset)
            .padding(start = 11.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LxIcons.Clock,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = LXType.labelSmall,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 一键取消（17 §11 验收项 #14）
        Text(
            text = "取消",
            style = LXType.labelSmall,
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

// ─────────────────────────── 进度 + 控制 ───────────────────────────

@Composable
private fun PlayerBottom(
    state: PlayerState,
    repeatMode: RepeatMode,
    dragging: Boolean,
    dragFraction: Float,
    duration: Long,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    /** 点选档位后立即设定的模式 */
    onPickRepeat: (RepeatMode) -> Unit,
    onOpenQueue: () -> Unit,
    crossfadeMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val displayPosition = if (dragging) (dragFraction * duration).toLong() else state.positionMs

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        ProgressBar(
            fraction = if (duration > 0) (displayPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            fadeFraction = if (duration > 0) {
                (crossfadeMs.toFloat() / duration).coerceIn(0f, 0.45f)
            } else 0f,
            dragging = dragging,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatMs(displayPosition), style = LXType.labelSmall, color = colors.ink3)
            // 淡变徽章（17 §6.1：中间显示当前秒数；0 = 即切时不显示）
            if (crossfadeMs > 0L) {
                Text(
                    "淡入淡出 ${crossfadeMs / 1000}s",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(formatMs(state.durationMs), style = LXType.labelSmall, color = colors.ink3)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ 图标必须随模式变（用户 2026-10-03 反馈：以前恒为 Repeat，看不出切换）
            //    点按弹出档位列表直接选（2026-10-05 用户要求：不再长按上滑）
            ModeControl(
                repeatMode = repeatMode,
                onPick = onPickRepeat,
            )
            CtrlButton(icon = LxIcons.Previous, description = "上一首", onClick = onPrevious)
            // .cbtn.play：58dp accent 实心
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.isPlaying) LxIcons.Pause else LxIcons.Play,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = colors.bg,
                    modifier = Modifier.size(22.dp),
                )
            }
            CtrlButton(icon = LxIcons.Next, description = "下一首", onClick = onNext)
            CtrlButton(icon = LxIcons.Queue, description = "播放列表", onClick = onOpenQueue, size = 38.dp)
        }
    }
}

// ── 播放模式：点按弹出档位列表 ──

/** 选择器里每一项的高度 */
private val MODE_ITEM_HEIGHT = 38.dp

/** 选择器整体高度 = 5 项 + 上下各 4dp 内边距（Popup 定位到按钮上方要用它） */
private val MODE_PICKER_HEIGHT = 38.dp * PLAY_MODE_ORDER.size + 8.dp

private fun modeIcon(mode: RepeatMode): androidx.compose.ui.graphics.vector.ImageVector =
    when (mode) {
        RepeatMode.ALL -> LxIcons.Repeat
        RepeatMode.ONE -> LxIcons.RepeatOne
        RepeatMode.OFF -> LxIcons.RepeatOff
        // 两档随机共用 Shuffle，靠角标区分：1 = 顺序固定（打乱一次），∞ = 每次不同
        RepeatMode.SHUFFLE_ORDER, RepeatMode.SHUFFLE_RANDOM -> LxIcons.Shuffle
    }

private fun modeBadge(mode: RepeatMode): String? = when (mode) {
    RepeatMode.SHUFFLE_ORDER -> "1"
    RepeatMode.SHUFFLE_RANDOM -> "∞"
    else -> null
}

/**
 * 播放模式按钮（用户 2026-10-05 要求改交互）。
 *
 * **点按按钮 → 在按钮正上方弹出全部档位 → 点某一档立即设定并收起**；
 * 点列表以外的地方或按返回只收起、不改模式。
 *
 * 档位列表放在 [Popup] 里：它是一个独立的窗口层，**完全不参与父布局测量**，
 * 所以弹出/收起不会撑开底部控制排、也就不会把其它按钮挤走
 * （旧实现把列表当普通子项画在 Box 里，弹出后整排按钮错位）。
 * 按钮图标由 [modeIcon] 跟随当前模式，切换后立即变化。
 */
@Composable
private fun ModeControl(
    repeatMode: RepeatMode,
    onPick: (RepeatMode) -> Unit,
) {
    val colors = LXTheme.colors
    var expanded by remember { mutableStateOf(false) }
    // 让浮层底边正好贴住按钮顶边：从父布局上边再往上挪「浮层高度」
    val pickerHeightPx = with(LocalDensity.current) { MODE_PICKER_HEIGHT.roundToPx() }

    Box {
        CtrlButton(
            icon = modeIcon(repeatMode),
            description = "播放模式：${repeatMode.displayName()}（点按选择）",
            onClick = { expanded = true },
            size = 38.dp,
            tint = if (repeatMode == RepeatMode.OFF) colors.ink3 else colors.accent,
            badge = modeBadge(repeatMode),
        )

        if (expanded) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -pickerHeightPx),
                onDismissRequest = { expanded = false },
                // focusable：点外部 / 返回键都会回调 onDismissRequest，只收起不改模式
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        .width(168.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.hairline, RoundedCornerShape(14.dp))
                        .padding(vertical = 4.dp),
                ) {
                    PLAY_MODE_ORDER.forEach { mode ->
                        val active = mode == repeatMode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MODE_ITEM_HEIGHT)
                                .background(if (active) colors.accent.copy(alpha = 0.13f) else Color.Transparent)
                                .clickable {
                                    expanded = false
                                    onPick(mode)
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = modeIcon(mode),
                                contentDescription = null,
                                tint = if (active) colors.accent else colors.ink3,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = mode.displayName(),
                                style = LXType.labelSmall,
                                color = if (active) colors.ink1 else colors.ink2,
                                modifier = Modifier.weight(1f),
                            )
                            // 当前档位的选中标记
                            if (active) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(colors.accent),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    tint: Color? = null,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Box(
        modifier = modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint ?: if (size == 44.dp) colors.ink1 else colors.ink2,
            modifier = Modifier.size(if (size == 44.dp) 22.dp else 19.dp),
        )
        // 角标：两档随机的区分（1 = 顺序固定 / ∞ = 每次不同）
        if (badge != null) {
            Text(
                text = badge,
                style = LXType.labelSmall,
                color = tint ?: colors.ink2,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 1.dp),
            )
        }
    }
}

/**
 * `.prog`：高 20 的命中区、3dp 轨道与已播、拖动时才露出的 10dp 拇指。
 *
 * 17 §6.1：两端按 [fadeFraction]（淡变秒数 / 本曲时长）铺渐变区，
 * 让「切换时会淡掉的那一段」在进度条上直接可见；[fadeFraction] = 0 时不画。
 *
 * ⚠️ 未播放时长（直播/未知）时轨道全灰 —— 不假装知道总长。
 */
@Composable
private fun ProgressBar(
    fraction: Float,
    fadeFraction: Float,
    dragging: Boolean,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    // 16 §5「250ms linear 匀速推进」的 Compose 等价：内核位置每 500ms 一跳，
    // 补间时长对齐数据节拍，每拍到达即续下一拍 → 视觉匀速；拖动时跟手不插值（同规格「拖动进度 无过渡」）
    val fillFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (dragging) snap() else tween(PROGRESS_SMOOTH_MS, easing = LinearEasing),
        label = "progressFill",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                // 按下即跳到该位置（原型 pointerdown → seek），拖动过程中只更新本地状态
                fun fractionAt(x: Float) = (x / size.width.toFloat()).coerceIn(0f, 1f)
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        onDragStart(fractionAt(down.position.x))
                        drag(down.id) { change ->
                            onDrag(fractionAt(change.position.x))
                            change.consume()
                        }
                        onDragEnd()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LXTheme.pageInset),
        )
        // 已播
        Box(
            modifier = Modifier
                .fillMaxWidth(fillFraction)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent),
        )
        // 两端淡变区（17 §6.1）：左端由淡到实、右端由实到淡
        if (fadeFraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fadeFraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(colors.accent.copy(alpha = 0.45f), colors.accent.copy(alpha = 0f)),
                        )
                    ),
            )
            Box(
                Modifier
                    .fillMaxWidth(fadeFraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(colors.bg.copy(alpha = 0f), colors.bg.copy(alpha = 0.75f)),
                        )
                    )
                    .align(Alignment.CenterEnd),
            )
        }
        // 拇指（仅拖动中出现；原型 `.prog.dragging .progfill::after`）
        if (dragging) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .height(3.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
            }
        }
    }
}

// ─────────────────────────── 小工具 ───────────────────────────

/** 音源展示名（播放页「音源」按钮上只放名字，与原型 `#btnSrc` 一致） */
internal fun sourceLabelOf(sourceId: String): String = when (sourceId) {
    "bi" -> "B 站"
    "local" -> "本地"
    else -> sourceId
}

internal fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 封面尺寸（原型 `.cover` 270×270） */
private val COVER_SIZE = 270.dp

/** 进度条补间时长：与内核 500ms 的位置更新节拍一致（16 §5 的 linear 匀速） */
private const val PROGRESS_SMOOTH_MS = 500

/** 封面 ⇄ 歌词 的过渡时长（原型 `@keyframes fade .32s`） */
private const val MODE_FADE_MS = 320