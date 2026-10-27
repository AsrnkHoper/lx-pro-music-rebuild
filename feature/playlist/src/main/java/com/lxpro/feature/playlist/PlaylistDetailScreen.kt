package com.lxpro.feature.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXQuality
import com.lxpro.core.designsystem.component.LXSheetOption
import com.lxpro.core.designsystem.component.LXSongAction
import com.lxpro.core.designsystem.component.LXSongActionSheet
import com.lxpro.core.designsystem.component.LXToast
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.component.shareSong
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SongTagBus
import com.lxpro.core.playlist.songOverrideKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/**
 * 歌单详情页 —— 两级跳转的第二级（`mockup_playlist.html`）。
 *
 * 本页**不显示底部 Tab**（14 §4.1：详情页全屏沉浸），返回靠左上的返回键。
 * 歌曲行右侧「更多」调起 7 项操作面板。
 */
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onPlayShuffle: (List<Song>) -> Unit,
    playingKey: String?,
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 省流「全无图」：强制等同右上角按钮切到无封面布局（#128，不改用户自己的选择记忆）
    val dataSaverNoImage by com.lxpro.core.model.DataSaver.imagesBlockedFlow.collectAsState()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val context = LocalContext.current

    var sheetSong by remember { mutableStateOf<Song?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var showCoverSongSheet by remember { mutableStateOf(false) }

    // 「从本地图片选择」封面：OpenDocument 授权后取持久读权限，否则重启后读不到
    val pickCoverImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setCoverFromImage(uri.toString())
        }
    }

    val density = LocalDensity.current
    val listState = rememberLazyListState()

    // ── 拖动排序：长按任意行 → 该行浮起 → 上下拖动实时换位 → 松手落位并持久化 ──
    // 批十三（2026-10-12 第十轮，结构性重写，参照成熟开源方案 ComposeReorderable
    // 的核心思路）：**拖拽期间 items 数据永不重排** —— 布局（槽位）恒定，被拖行
    // 用 graphicsLayer translation 跟手、其他行用 translation「视觉让位」，松手才
    // 把最终顺序一次性提交给仓储。前十二轮的病根全部源于「拖拽中实时重排
    // dragOrder」：LazyLayout 的布局更新与手势事件/状态写入之间存在不可控时序
    // （快照滞后一帧、measure 异步、头部插入瞬态、滚动同步生效……），补偿矩阵
    // 每轮膨胀且修一个症状冒一个新症状。本方案下拖拽期间**没有任何换位类布局
    // 变化**（唯一布局变化是自动滚动，其补偿只有 baseSlot 一处且与滚动同帧），
    // 渲染位置 = 固定槽位 + 自家 translation，全部确定值。
    /** 被拖行的 key；null 表示当前没有拖动 */
    var draggingKey by remember { mutableStateOf<String?>(null) }
    /** 被拖行在**原列表**里的 index（长按命中；拖拽期间列表不重排，恒有效） */
    var fromIndex by remember { mutableIntStateOf(-1) }
    /** 被拖行当前的插入目标 index（拖动中实时推导；松手提交 add(to, removeAt(from))） */
    var toIndex by remember { mutableIntStateOf(-1) }
    /** 被拖行**原槽位**的视口 offset（px）：长按 = 命中快照、自动滚动 −= scrolled ——
     *  全方案唯一需要滚动补偿的量（跟手 translation = 视觉顶边 − 本值） */
    var baseSlot by remember { mutableFloatStateOf(0f) }
    /** 长按那一刻手指在行内的偏移（行局部坐标），拖动期间不变 */
    var grabOffsetY by remember { mutableFloatStateOf(0f) }
    /** 手指当前在视口里的 y（绝对坐标；跟手渲染 + 贴边自动滚动判定都用它） */
    var dragPointerY by remember { mutableFloatStateOf(-1f) }
    /** 本次拖动累计的手指移动量（px）：自动滚动要等手指**真的动过**才启用（批八） */
    var dragMovedPx by remember { mutableFloatStateOf(0f) }
    /** 行高（px）：长按时从命中快照记录一次（等高列表，拖拽期间恒定） */
    var dragRowH by remember { mutableFloatStateOf(0f) }
    /** 最近一次有效垂直拖动方向：-1 上 / +1 下 / 0 未动（批十：滚动方向判定，
     *  行真到边缘且方向一致才滚，停住保留最近方向 —— 经典边缘体验不变） */
    var lastDragDir by remember { mutableIntStateOf(0) }
    val dragScope = rememberCoroutineScope()
    val autoScrollSlopPx = with(density) { AUTOSCROLL_SLOP.toPx() }
    /** 行间距（px）：remember 稳定引用 —— pointerInput 协程闭包捕获安全（值恒定） */
    val songGapPx = remember { with(density) { SONG_ROW_GAP.toPx() } }
    // pointerInput(Unit) 协程闭包捕获首次组合值（批七教训）：songs/draggable 用
    // rememberUpdatedState 让 onDragStart/applyDrag/commit 读到实时值
    val songsNow = rememberUpdatedState(state.songs)

    /**
     * 拖拽开始时杀掉列表可能还在跑的惯性滚动（fling）：容器级手势不经过列表自己
     * 的 scrollable，长按**不会**打断 fling —— 不杀的话列表带着被拖行继续滑走
     * （批八症状①）。PreventUserInput 优先级拿一次滚动锁即可，空块无副作用。
     */
    val killFlingAtDragStart: () -> Unit = {
        dragScope.launch {
            listState.scroll(MutatePriority.PreventUserInput) { }
        }
    }

    // 有页内搜索时不开放拖动：列表被筛短，重排会与未展示的条目撞 position
    val draggable = state.query.isBlank()
    val draggableNow = rememberUpdatedState(draggable)
    // 拖拽期间 items 恒用仓储原序（**永不重排** —— 让位全部由 translation 推导）
    val visibleSongs = state.songs

    /**
     * 拖动核心（每次 onDrag / 每帧自动滚动都调用）：更新插入目标 toIndex。
     * 被拖行视觉中心相对**原槽位中心**偏移 k 个 stride（round → 中线滞回，
     * 过半才换、天然不抖动），toIndex = fromIndex + k，clamp 到列表界内。
     * 让位渲染由 toIndex 驱动（渲染处纯推导，见 itemsIndexed）——**不写任何
     * 会改变布局的状态**（items 顺序恒定），无布局时序问题。
     *
     * ⚠️ stride 必须**在 lambda 内部**用状态值计算（dragRowH 是 mutableStateOf，
     * 读到实时值）：拖拽手势的 pointerInput(Unit) 协程闭包捕获**首帧**的 lambda
     * 实例，composition 级的派生 val（如 dragStride = dragRowH + gap）在首帧
     * dragRowH=0 → stride=7px，被定格后换位目标放大 30 倍（批十三真机日志锤死：
     * vC=115 → to=2、vC=383 → to=43）。闭包内只信「状态对象 + remember 值」。
     */
    val applyDrag: (Float) -> Unit = { pointerY ->
        dragPointerY = pointerY
        val from = fromIndex
        val songs = songsNow.value
        val stride = dragRowH + songGapPx
        if (draggingKey != null && from in songs.indices && stride > 0f) {
            val visualCenter = pointerY - grabOffsetY + dragRowH / 2f
            val k = ((visualCenter - (baseSlot + dragRowH / 2f)) / stride).roundToInt()
            val newTo = (from + k).coerceIn(0, songs.lastIndex)
            if (newTo != toIndex) {
                toIndex = newTo
            }
        }
    }

    /** 松手提交：一次性把 from 行插到 to 位（items 在拖拽期间从未重排，这里才重排） */
    val commitDrag: () -> Unit = {
        val from = fromIndex
        val to = toIndex
        val moved = draggingKey
        val songs = songsNow.value
        draggingKey = null
        fromIndex = -1
        toIndex = -1
        dragPointerY = -1f
        if (moved != null && from in songs.indices && to in songs.indices && to != from) {
            val order = songs.toMutableList().apply { add(to, removeAt(from)) }
            viewModel.reorder(order)
        }
    }

    // 拖到列表上下边缘时自动滚动（长歌单必需，否则拖不动几十格）。
    // 批八：手指**真的移动过**（≥ AUTOSCROLL_SLOP）才启用；批十：滚动方向由
    // 「被拖行的渲染中心 + 最近拖动方向」共同决定（行真到边缘且方向一致才滚，
    // 停住保留最近方向 —— 经典边缘体验不变）。
    val autoScrollDelta = if (
        draggingKey == null ||
        dragPointerY < 0f ||
        dragMovedPx < autoScrollSlopPx ||
        lastDragDir == 0
    ) {
        0f
    } else {
        val viewport = listState.layoutInfo.viewportSize.height.toFloat()
        val edge = with(density) { AUTOSCROLL_EDGE.toPx() }
        val step = with(density) { AUTOSCROLL_STEP.toPx() }
        val rowVisualCenter = dragPointerY - grabOffsetY + dragRowH / 2f
        when {
            lastDragDir < 0 && rowVisualCenter < edge -> -step
            lastDragDir > 0 && rowVisualCenter > viewport - edge -> step
            else -> 0f
        }
    }

    LaunchedEffect(draggingKey, autoScrollDelta) {
        if (draggingKey != null && autoScrollDelta != 0f) {
            while (true) {
                val scrolled = listState.scrollBy(autoScrollDelta)
                if (scrolled != 0f) {
                    // 唯一滚动补偿：baseSlot 随滚动更新（scrollBy 返回实际滚动量）；
                    // 跟手 translation = 视觉顶边 − baseSlot 在渲染处重算，
                    // 布局滚动与 translation 更新同帧 → 被拖行恒贴手指；
                    // 让位行的 translation 只依赖 from/to（与滚动无关）不变
                    baseSlot -= scrolled
                    // 滚动改变相对位置 → 重推插入目标
                    applyDrag(dragPointerY)
                }
                delay(AUTOSCROLL_FRAME_MS)
            }
        }
    }

    // ── 批六 #2：滚动收起头部（与歌单列表页同一套）──
    // 上滑浏览歌曲时收起 Hero/操作行/搜索行（返回栏保留 —— 导航必需），下滑展开。
    /** 头部内容自然高度（px） */
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    /** 头部已收起的像素数：0 = 全展开 */
    var headerCollapse by remember { mutableFloatStateOf(0f) }

    val headerConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 拖拽排序的自动滚动也走 nested scroll：排序期间别让头部跟着伸缩
                if (draggingKey != null) return Offset.Zero
                val dy = available.y
                if (headerHeightPx <= 0f || dy == 0f) return Offset.Zero
                val before = headerCollapse
                headerCollapse = if (dy < 0f) {
                    (before - dy).coerceAtMost(headerHeightPx)
                } else {
                    (before - dy).coerceAtLeast(0f)
                }
                return Offset(0f, before - headerCollapse)
            }
        }
    }

    // 停手后吸附：收过半 → 全收；没过半 → 全展
    val headerSettled by remember {
        derivedStateOf { !listState.isScrollInProgress }
    }
    LaunchedEffect(headerSettled, draggingKey) {
        if (headerSettled && draggingKey == null &&
            headerCollapse > 0f && headerCollapse < headerHeightPx
        ) {
            animate(
                initialValue = headerCollapse,
                targetValue = if (headerCollapse > headerHeightPx / 2f) headerHeightPx else 0f,
            ) { v, _ -> headerCollapse = v }
        }
    }

    // 轻提示到点自动收起（弹层关闭、改名完成这类一次性回执）
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(TOAST_DURATION_MS)
            viewModel.onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .lxSafeDrawingPadding()
                .nestedScroll(headerConnection),
        ) {
            // ── 返回 + 面包屑 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scalars.sidePadding, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(LxIcons.Back, contentDescription = "返回", tint = colors.ink1)
                }
                Text(
                    text = "我的歌单 / ${state.name}",
                    style = LXType.labelLarge,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // 系统歌单（收藏）不可改名/删除，但支持自定义封面（批次三 #3）
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = LxIcons.More,
                            contentDescription = "歌单操作",
                            tint = colors.ink3,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = colors.surface,
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = "设置封面", style = LXType.labelLarge, color = colors.ink1) },
                            onClick = {
                                showMenu = false
                                showCoverSheet = true
                            },
                        )
                        if (!state.isSystem) {
                            DropdownMenuItem(
                                text = { Text(text = "重命名歌单", style = LXType.labelLarge, color = colors.ink1) },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(text = "删除歌单", style = LXType.labelLarge, color = colors.danger) },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                }
            }

            // ── 可收起头部：Hero + 操作行 + 搜索行（返回栏保留）──
            // 布局高度 = 自然高度 - 已收起量，内容上移并裁掉；配合 nestedScroll 消费，
            // 收起过程列表内容贴着手指走（与歌单列表页同一套）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val collapsed = headerCollapse.roundToInt().coerceIn(0, placeable.height)
                        layout(placeable.width, placeable.height - collapsed) {
                            placeable.placeRelative(0, -collapsed)
                        }
                    },
            ) {
                Column(modifier = Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height.toFloat() }) {
                    // ── Hero：封面 + 歌单名 + 统计 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = scalars.sidePadding, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(PLAYLIST_COVER_SIZE)
                                .clip(RoundedCornerShape(LXDimens.radiusMedium))
                                .background(LXTheme.pageInset),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.coverUrl != null) {
                                AsyncArtwork(
                                    model = state.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    imageVector = LxIcons.Playlist,
                                    contentDescription = null,
                                    tint = colors.ink3,
                                    modifier = Modifier.size(30.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.name,
                                style = LXType.titleLarge,
                                color = colors.ink1,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = heroSubtitle(state),
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // ── 播放全部 / 随机 / 排序 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = scalars.sidePadding, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onPlaySong(visibleSongs.first(), visibleSongs) },
                            enabled = visibleSongs.isNotEmpty(),
                            shape = RoundedCornerShape(LXDimens.radiusMedium),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.bg,
                            ),
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) {
                            Icon(LxIcons.Play, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(text = "播放全部", style = LXType.labelLarge)
                        }
                        SquareIconButton(
                            icon = LxIcons.Shuffle,
                            description = "随机播放",
                            enabled = visibleSongs.isNotEmpty(),
                            onClick = { onPlayShuffle(visibleSongs) },
                        )
                        SquareIconButton(
                            icon = LxIcons.SortAsc,
                            description = "排序",
                            enabled = visibleSongs.isNotEmpty(),
                            onClick = { showSortSheet = true },
                        )
                    }

                    // ── 歌单内搜索 + 有/无封面切换 ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = scalars.sidePadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            placeholder = {
                                Text(text = "在歌单内搜索", style = LXType.labelLarge, color = colors.ink3)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = LxIcons.Search,
                                    contentDescription = null,
                                    tint = colors.ink3,
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(LXDimens.radiusSmall),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.ink1,
                                unfocusedTextColor = colors.ink1,
                                focusedContainerColor = colors.inset,
                                unfocusedContainerColor = colors.inset,
                                cursorColor = colors.accent,
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.hairline,
                            ),
                        )
                        SquareIconButton(
                            icon = LxIcons.CoverBox,
                            description = if (state.layout == SongLayout.COVER) "切换为无封面" else "切换为有封面",
                            enabled = true,
                            highlighted = state.layout == SongLayout.COVER,
                            onClick = {
                                viewModel.onLayoutChange(
                                    if (state.layout == SongLayout.COVER) SongLayout.COMPACT else SongLayout.COVER,
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ⚠️ 首帧还在读库时别把它显示成「歌单是空的」，否则会闪一下空态
            if (state.loading) {
                Box(Modifier.fillMaxSize())
            } else if (state.songs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.query.isBlank()) "这个歌单还是空的" else "歌单内没有匹配的歌曲",
                        style = LXType.bodyMedium,
                        color = colors.ink3,
                    )
                }
            } else {
                // ── 容器级拖拽手势（批六改容器级；批十三视觉让位架构）──
                // 手势挂列表容器：change.position 即视口绝对坐标；命中检测只用
                // hit.key + hit.index（当场读快照，长按时刻必新鲜），items 原序
                // 拖拽期间不变 → index 恒有效，不存在闭包捕获旧列表问题
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(
                            if (draggable) {
                                Modifier.pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { local ->
                                            // 命中检测：local 相对容器（= 列表视口原点）
                                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { row ->
                                                local.y >= row.offset && local.y < row.offset + row.size
                                            }
                                            val key = hit?.key as? String
                                            if (draggableNow.value && key != null) {
                                                draggingKey = key
                                                fromIndex = hit.index
                                                toIndex = hit.index
                                                baseSlot = hit.offset.toFloat()
                                                grabOffsetY = local.y - hit.offset.toFloat()
                                                dragPointerY = local.y
                                                dragRowH = hit.size.toFloat()
                                                dragMovedPx = 0f
                                                lastDragDir = 0
                                                killFlingAtDragStart()
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            if (draggingKey != null) {
                                                // 手指移动量累计（自动滚动门槛）+ 方向记录（批十）
                                                dragMovedPx += abs(dragAmount.x) + abs(dragAmount.y)
                                                if (abs(dragAmount.y) > 2f) {
                                                    lastDragDir = if (dragAmount.y > 0f) 1 else -1
                                                }
                                                // 更新跟手位置 + 重推插入目标（纯状态，无布局副作用）
                                                applyDrag(change.position.y)
                                            }
                                        },
                                        onDragEnd = { commitDrag() },
                                        onDragCancel = { commitDrag() },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = scalars.sidePadding,
                            end = scalars.sidePadding,
                            bottom = scalars.sectionGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(SONG_ROW_GAP),
                    ) {
                        itemsIndexed(
                            items = visibleSongs,
                            key = { _, song -> keyOf(song) },
                            contentType = { _, _ -> "playlist-song" },
                        ) { index, song ->
                            val key = keyOf(song)
                            val dragging = key == draggingKey
                            // 被拖行无需 zIndex 置顶：让位架构下被拖行的目标槽位必然
                            // 已被让空（让位方向就是腾出目标槽），中线切换瞬间的 ±gap/2
                            // 边缘重叠可忽略；foundation 1.11 的 LazyItemScope 也无 zIndex
                            // ── 让位/跟手 translation（纯推导，与布局状态完全无关）──
                            // 被拖行：视觉顶边 − 原槽位（原槽位 baseSlot 随滚动补偿）
                            // 让位行：from→to 区间内的行各让一个 stride（线性推移），
                            //         松手提交后让位位置 == 新布局位置 → 无跳变
                            val dragStrideNow = dragRowH + songGapPx
                            val translateY = when {
                                dragging -> dragPointerY - grabOffsetY - baseSlot
                                draggingKey != null && fromIndex in state.songs.indices &&
                                    toIndex in state.songs.indices -> when {
                                    fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> -dragStrideNow
                                    fromIndex > toIndex && index in toIndex until fromIndex -> dragStrideNow
                                    else -> 0f
                                }
                                else -> 0f
                            }
                            PlaylistSongRow(
                                index = index,
                                song = song,
                                sourceLabel = state.sourceNames[song.source.value] ?: song.source.value,
                                corrected = songOverrideKey(song.id, song.source.value) in state.correctedKeys,
                                compact = state.layout == SongLayout.COMPACT || dataSaverNoImage,
                                playing = key == playingKey,
                                dragging = dragging,
                                translateY = translateY,
                                // draggingKey 压制空长按松手的误触点击（点击先于拖拽收尾派发，此刻仍是拖拽中）
                                onClick = { if (draggingKey == null) onPlaySong(song, visibleSongs) },
                                onMore = {
                                    sheetSong = song
                                    viewModel.resetSwap(song)
                                },
                            )
                        }
                    }
                }
            }
        }

        LXToast(
            message = state.message,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        )
    }

    sheetSong?.let { song ->
        val swapState by viewModel.swapState.collectAsStateWithLifecycle()
        val swapPreview by viewModel.swapPreviewState.collectAsStateWithLifecycle()
        LXSongActionSheet(
            song = song,
            sourceLabel = state.sourceNames[song.source.value] ?: song.source.value,
            // 歌单上下文：不再给「移动位置」入口 —— 排序改为长按行直接拖动
            actions = ALL_PLAYLIST_SONG_ACTIONS,
            playlistOptions = state.otherPlaylists.map { LXSheetOption(it.id, it.name) },
            swapState = swapState,
            swapSourceNames = state.sourceNames,
            onOpenSwapPanel = { viewModel.loadSwapCandidates() },
            swapPreviewKey = swapPreview.key,
            swapPreviewLoading = swapPreview.loading,
            onPreviewToggle = { viewModel.toggleSwapPreview(it) },
            onPickSwap = { target ->
                viewModel.pinSwap(target)
                // 正在播放这首歌：立即用新源重播使其生效（对齐 LX「正在播放则立即重播」）
                if (playingKey == keyOf(song)) {
                    val toggled = song.copy(toggleSong = target)
                    onPlaySong(
                        toggled,
                        visibleSongs.map { if (keyOf(it) == keyOf(song)) toggled else it },
                    )
                }
            },
            onClearSwap = {
                viewModel.clearSwap()
                if (playingKey == keyOf(song)) {
                    val restored = song.copy(toggleSong = null)
                    onPlaySong(
                        restored,
                        visibleSongs.map { if (keyOf(it) == keyOf(song)) restored else it },
                    )
                }
            },
            onDismiss = {
                viewModel.stopSwapPreview()
                sheetSong = null
            },
            onRemoveFromPlaylist = { viewModel.removeSong(song) },
            onAddToPlaylist = { viewModel.addToPlaylist(it, song) },
            onCreatePlaylistAndAdd = { viewModel.createPlaylistAndAdd(song, it) },
            onDownload = { quality -> viewModel.notifyDownloadPending(song, quality) },
            onRenameSong = { viewModel.renameSong(song, it) },
            onRenameSinger = { viewModel.renameSinger(song, it) },
            // 标签页（第 8 项）：本模块不依赖 app，经总线交给 app 级宿主渲染
            onOpenTags = { SongTagBus.emit(song) },
            onShare = { shareSong(context, song) },
        )
    }

    if (showSortSheet) {
        SortSheet(
            current = state.sort,
            onPick = {
                viewModel.onSortChange(it)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showCoverSheet) {
        CoverSheet(
            hasCustomCover = state.hasCustomCover,
            canPickFromSongs = state.coverSongs.isNotEmpty(),
            onPickSong = {
                showCoverSheet = false
                showCoverSongSheet = true
            },
            onPickImage = {
                showCoverSheet = false
                pickCoverImage.launch(arrayOf("image/*"))
            },
            onClear = {
                showCoverSheet = false
                viewModel.clearCover()
            },
            onDismiss = { showCoverSheet = false },
        )
    }

    if (showCoverSongSheet) {
        CoverSongSheet(
            songs = state.coverSongs,
            onPick = {
                viewModel.setCoverFromSong(it)
                showCoverSongSheet = false
            },
            onDismiss = { showCoverSongSheet = false },
        )
    }

    if (showRenameDialog) {
        PlaylistNameDialog(
            title = "重命名歌单",
            initialValue = state.name,
            confirmLabel = "保存",
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                viewModel.rename(name)
                showRenameDialog = false
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.surface,
            title = { Text(text = "删除歌单", style = LXType.titleLarge, color = colors.ink1) },
            text = {
                Text(
                    text = "「${state.name}」及其中的 ${state.totalCount} 首歌都会从这个歌单里移除。",
                    style = LXType.bodyMedium,
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onBack)
                }) {
                    Text(text = "删除", style = LXType.labelLarge, color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(text = "取消", style = LXType.labelLarge, color = colors.ink3)
                }
            },
        )
    }
}

private fun heroSubtitle(state: PlaylistDetailUiState): String = buildString {
    append("${state.totalCount} 首")
    if (state.query.isNotBlank()) append(" · 匹配 ${state.songs.size} 首")
    val corrected = state.songs.count { songOverrideKey(it.id, it.source.value) in state.correctedKeys }
    if (corrected > 0) append(" · $corrected 首已修正")
    val origins = state.songs
        .mapNotNull { state.sourceNames[it.source.value] }
        .distinct()
    if (origins.isNotEmpty()) append(" · ").append(origins.joinToString("/"))
}

@Composable
private fun PlaylistSongRow(
    index: Int,
    song: Song,
    sourceLabel: String,
    corrected: Boolean,
    compact: Boolean,
    playing: Boolean,
    dragging: Boolean,
    translateY: Float,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = LXTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = translateY
                if (dragging) {
                    // 浮起：轻微放大即可，别夸张
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
            }
            // 被拖起的那一行：抬升阴影 + 底色提亮（原型 `.song.dragging`）
            .then(
                if (dragging) {
                    Modifier
                        .shadow(6.dp, RoundedCornerShape(LXDimens.radiusSmall))
                        .background(colors.surface)
                } else {
                    Modifier
                },
            )
            // 点按播放；长按完全交给列表容器级的拖拽手势（批六）。
            // ⚠️ 不可传非空 onLongClick：combinedClickable 内部长按检测器会在超时竞争里取胜并
            // 消费事件，把 detectDragGesturesAfterLongPress 的长按永久取消（拖拽失效的根因）。
            // 空长按误触点击由调用方的 draggingKey 压制。
            .combinedClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 有封面 → 42dp 封面；无封面 → 26dp 的序号/均衡条（mockup `.song.nocover .lead`）
        Box(
            modifier = Modifier
                .size(if (compact) 26.dp else 42.dp)
                .clip(RoundedCornerShape(LXDimens.radiusSmall)),
            contentAlignment = Alignment.Center,
        ) {
            if (compact) {
                if (playing) {
                    EqIndicator()
                } else {
                    Text(
                        text = "${index + 1}",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                    )
                }
            } else {
                AsyncArtwork(
                    model = song.picUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.name,
                    style = LXType.labelLarge,
                    color = if (playing) colors.accent else colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (corrected) {
                    Spacer(Modifier.width(6.dp))
                    Text(text = "已修正", style = LXType.labelSmall, color = colors.danger)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                LXBadge(text = sourceLabel)
                val topQuality = song.qualitys.maxByOrNull { it.ordinal }
                LXBadge(
                    text = if (song.source.value == LOCAL_SOURCE_ID.value) {
                        "本地"
                    } else {
                        topQuality?.let(LXQuality::label) ?: "标准"
                    },
                    // 原型只给无损及以上高亮（`.bdg.hq`）
                    accent = topQuality != null && topQuality.ordinal >= Quality.FLAC.ordinal,
                )
                Text(
                    text = song.singer,
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            text = song.interval?.let(::formatDuration).orEmpty(),
            style = LXType.labelSmall,
            color = colors.ink3,
        )

        IconButton(onClick = onMore) {
            Icon(
                imageVector = LxIcons.More,
                contentDescription = "更多",
                tint = colors.ink3,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun LXBadge(text: String, accent: Boolean = false) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (accent) colors.accent.copy(alpha = 0.13f) else colors.inset)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = LXType.labelSmall,
            color = if (accent) colors.accent else colors.ink2,
        )
    }
}

/** 播放中的跳动均衡条（16 §5：820ms 循环，三根错相位） */
@Composable
private fun EqIndicator() {
    val colors = LXTheme.colors
    val transition = rememberInfiniteTransition(label = "eq")
    val scales = listOf(0L, 170L, 340L).mapIndexed { index, delayMs ->
        transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 820, delayMillis = delayMs.toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "eq$index",
        ).value
    }
    val heights = listOf(6.dp, 12.dp, 8.dp)

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(13.dp),
    ) {
        scales.forEachIndexed { index, scale ->
            Box(
                Modifier
                    .width(2.dp)
                    .height(heights[index])
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(colors.accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun SquareIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(LXDimens.radiusMedium))
            .background(colors.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                !enabled -> colors.ink3.copy(alpha = 0.45f)
                highlighted -> colors.accent
                else -> colors.ink1
            },
            modifier = Modifier.size(17.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(current: SongSort, onPick: (SongSort) -> Unit, onDismiss: () -> Unit) {
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        scrimColor = colors.ink1.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "排序",
                style = LXType.labelLarge,
                color = colors.ink1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            SongSort.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(option) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = option.label,
                        style = LXType.labelLarge,
                        color = if (option == current) colors.accent else colors.ink1,
                        modifier = Modifier.weight(1f),
                    )
                    if (option == current) {
                        Text(text = "当前", style = LXType.labelSmall, color = colors.ink3)
                    }
                }
            }
        }
    }
}

/** 「设置封面」面板：与排序面板同一套弹窗语言（ModalBottomSheet + 文字选项行） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(
    hasCustomCover: Boolean,
    canPickFromSongs: Boolean,
    onPickSong: () -> Unit,
    onPickImage: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        scrimColor = colors.ink1.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "设置封面",
                style = LXType.labelLarge,
                color = colors.ink1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            SheetOptionRow(text = "从歌单歌曲选择", enabled = canPickFromSongs, onClick = onPickSong)
            SheetOptionRow(text = "从本地图片选择", enabled = true, onClick = onPickImage)
            // 只有设过自定义封面才给「恢复默认」
            if (hasCustomCover) {
                SheetOptionRow(text = "恢复默认封面", enabled = true, onClick = onClear)
            }
        }
    }
}

/** 「从歌单歌曲选择封面」：封面缩略图 + 歌名 + 歌手，点选即设为封面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSongSheet(songs: List<Song>, onPick: (Song) -> Unit, onDismiss: () -> Unit) {
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        scrimColor = colors.ink1.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "选择一首歌作为封面",
                style = LXType.labelLarge,
                color = colors.ink1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(songs, key = { keyOf(it) }) { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(song) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(LXDimens.radiusSmall))
                                .background(LXTheme.pageInset),
                        ) {
                            AsyncArtwork(
                                model = song.picUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.name,
                                style = LXType.labelLarge,
                                color = colors.ink1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.singer,
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 底部弹层里的文字选项行（与排序面板的行样式一致） */
@Composable
private fun SheetOptionRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = LXType.labelLarge,
            color = if (enabled) colors.ink1 else colors.ink3.copy(alpha = 0.45f),
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** 歌曲在列表里的稳定键，与 LazyColumn 的 key 一致 */
private fun keyOf(song: Song): String = "${song.source.value}:${song.id}"

private fun List<Song>.keys(): List<String> = map(::keyOf)

private val PLAYLIST_COVER_SIZE = 96.dp
private const val TOAST_DURATION_MS = 2_400L

/** 歌曲行之间的间距（LazyColumn 的 spacedBy） */
private val SONG_ROW_GAP = 2.dp

private val AUTOSCROLL_EDGE = 72.dp
private val AUTOSCROLL_STEP = 6.dp
/** 自动滚动的启用门槛：手指累计移动超过这个量才算「真的在拖」（批八症状①） */
private val AUTOSCROLL_SLOP = 8.dp
private const val AUTOSCROLL_FRAME_MS = 16L

/** 歌单上下文下的歌曲操作：不含「移动位置」—— 排序改为长按行直接拖动 */
private val ALL_PLAYLIST_SONG_ACTIONS = listOf(
    LXSongAction.DOWNLOAD,
    LXSongAction.ADD_TO_PLAYLIST,
    LXSongAction.SWAP_SOURCE,
    LXSongAction.RENAME_SONG,
    LXSongAction.RENAME_SINGER,
    LXSongAction.TAGS,
    LXSongAction.SHARE,
    LXSongAction.REMOVE_FROM_PLAYLIST,
)