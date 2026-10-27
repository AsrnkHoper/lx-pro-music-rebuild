package com.lxpro.feature.playlist

import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXStaggerItem
import com.lxpro.core.designsystem.component.LXVerseLine
import com.lxpro.core.designsystem.component.LXViewToggle
import com.lxpro.core.designsystem.component.LXViewToggleGroup
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.designsystem.tutorial.tutorialTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 歌单列表页 —— **两级跳转的第一级**（15 §3：点具体歌单后进详情，不做同屏）。
 *
 * ⚠️ 同屏「列表 + 歌曲」在歌多时必然胀满（真实备份里最大一个歌单 213 首），所以拆成两级。
 * 两种排布（网格有封面 / 紧凑列表）与原型的 `.plgrid` / `.pllist` 对应。
 *
 * 长按拖拽排序：与详情页同一套「视口绝对坐标」方案 —— 一律用
 * `pointer = 槽位偏移 + 局部坐标`，绝不累加节点局部 `positionChange`
 *（换位会让节点原点跟着移动，局部增量会把布局位移也算进去，造成双重位移）。
 */
@Composable
fun PlaylistsScreen(
    state: PlaylistsUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (PlaylistFilter) -> Unit,
    onLayoutChange: (PlaylistLayout) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onReorderPlaylists: (List<String>) -> Unit,
    /** 组合播放 · 临时混合：传选中的歌单 id（按展示顺序），路由层加载歌曲并起播 */
    onPlayCombined: (List<String>) -> Unit = {},
    /** 组合播放 · 真合并：新建歌单写入合并曲目，完成经 onCreated 回调新 id */
    onMergePlaylists: (ids: List<String>, name: String, onCreated: (String) -> Unit) -> Unit =
        { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val density = LocalDensity.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var showCombineDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistCard?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistCard?>(null) }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // ── 拖动排序：长按卡片 → 浮起 → 拖动实时换位 → 松手落位并持久化 ──
    // 批十三（2026-10-12 第十轮，结构性重写，参照成熟开源方案 ComposeReorderable
    // 的核心思路）：**拖拽期间 items 数据永不重排** —— 布局（槽位）恒定，被拖卡
    // 用 translation 跟手、其他卡用 translation「视觉让位」，松手才把最终顺序一次
    // 性提交。前十二轮的病根全部源于「拖拽中实时重排 dragOrder」的布局时序不可
    // 控（详见 PlaylistDetailScreen 同款注释）。列表与网格共用同一套状态与判定，
    // 仅 stride/让位的几何推导不同（列表：等高线性；网格：等大格子线性推移）。
    /** 被拖起的歌单 id；null 表示当前没有拖动 */
    var draggingKey by remember { mutableStateOf<String?>(null) }
    /** 被拖卡在**原列表**里的 index（长按命中；拖拽期间列表不重排，恒有效） */
    var fromIndex by remember { mutableIntStateOf(-1) }
    /** 被拖卡当前的插入目标 index（拖动中实时推导；松手提交 add(to, removeAt(from))） */
    var toIndex by remember { mutableIntStateOf(-1) }
    /** 被拖卡**原槽位**左上角（视口坐标）：长按 = 命中快照、自动滚动 y −= scrolled ——
     *  全方案唯一需要滚动补偿的量（跟手 translation = 视觉左上角 − 本值） */
    var baseSlot by remember { mutableStateOf(Offset.Zero) }
    /** 被拖卡尺寸（px）：长按时从命中快照记录一次（等大格子/等高行，恒定） */
    var dragCell by remember { mutableStateOf(IntSize.Zero) }
    /** 列表行距（px）：长按时从相邻行布局差测一次（紧凑列表行距可能非 0） */
    var listStrideY by remember { mutableFloatStateOf(0f) }
    /** 长按那一刻手指在卡片内部的偏移（卡片局部坐标），拖动期间不变 */
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    /** 手指当前在视口里的位置（绝对坐标；跟手渲染 + 贴边自动滚动判定都用它） */
    var dragPointer by remember { mutableStateOf(Offset.Unspecified) }
    /** 本次拖动累计的手指移动量（px）：自动滚动要等手指**真的动过**才启用（批八） */
    var dragMovedPx by remember { mutableFloatStateOf(0f) }
    /** 最近一次有效垂直拖动方向：-1 上 / +1 下 / 0 未动（批十：滚动方向判定，
     *  行真到边缘且方向一致才滚，停住保留最近方向 —— 经典边缘体验不变） */
    var lastDragDir by remember { mutableIntStateOf(0) }
    val dragScope = rememberCoroutineScope()
    val autoScrollSlopPx = with(density) { AUTOSCROLL_SLOP.toPx() }
    /** 网格格距（px）：网格 stride 推导用（等大格子 + 固定间距） */
    val gridGapPx = with(density) { GRID_GAP.toPx() }
    // pointerInput(Unit) 协程闭包捕获首次组合值（批七教训）：playlists 用
    // rememberUpdatedState 让手势回调读到实时值
    val playlistsNow = rememberUpdatedState(state.playlists)

    /**
     * 拖拽开始时杀掉列表可能还在跑的惯性滚动（fling）：容器级手势不经过列表自己的
     * scrollable，长按**不会**打断 fling —— 不杀的话列表带着被拖卡继续滑走
     * （批八症状①）。PreventUserInput 优先级拿一次滚动锁即可，空块无副作用。
     */
    val killFlingAtDragStart: () -> Unit = {
        dragScope.launch {
            if (state.layout == PlaylistLayout.GRID) {
                gridState.scroll(MutatePriority.PreventUserInput) { }
            } else {
                listState.scroll(MutatePriority.PreventUserInput) { }
            }
        }
    }

    // reorderPlaylists 需要全量 id：搜索/筛选把列表筛短时禁止拖拽
    val draggable = state.query.isBlank() && state.filter == PlaylistFilter.ALL
    val draggableNow = rememberUpdatedState(draggable)
    // 拖拽期间 items 恒用仓储原序（**永不重排** —— 让位全部由 translation 推导）
    val visibleCards = state.playlists

    /**
     * 拖动核心（每次 onDrag / 每帧自动滚动都调用）：更新插入目标 toIndex。
     * 列表：视觉中心相对原槽位中心偏移 k 个 stride（round → 中线滞回），
     *       toIndex = fromIndex + k；
     * 网格：视觉中心反推最近的原槽位号（等距网格推导，**不读快照矩形**），
     *       toIndex = row × 列数 + col。
     * 只写纯数据状态（toIndex），**不写任何会改变布局的状态**（items 顺序恒定）。
     */
    val applyDrag: (Offset) -> Unit = drag@{ pointer ->
        dragPointer = pointer
        val from = fromIndex
        val playlists = playlistsNow.value
        if (draggingKey == null || from !in playlists.indices) return@drag
        val minIndex = if (playlists.firstOrNull()?.isSystem == true) 1 else 0
        val newTo = if (state.layout == PlaylistLayout.GRID) {
            val colStride = dragCell.width + gridGapPx
            val rowStride = dragCell.height + gridGapPx
            if (colStride <= 0f || rowStride <= 0f) return@drag
            // 首格原点 = 被拖卡原槽位 − from 的行列位移（baseSlot 随滚动补偿）
            val slot0x = baseSlot.x - (from % GRID_COLUMNS) * colStride
            val slot0y = baseSlot.y - (from / GRID_COLUMNS) * rowStride
            val col = ((pointer.x - grabOffset.x - slot0x) / colStride)
                .roundToInt().coerceIn(0, GRID_COLUMNS - 1)
            val row = ((pointer.y - grabOffset.y - slot0y) / rowStride)
                .roundToInt().coerceAtLeast(0)
            row * GRID_COLUMNS + col
        } else {
            if (listStrideY <= 0f) return@drag
            val visualCenter = pointer.y - grabOffset.y + dragCell.height / 2f
            from + ((visualCenter - (baseSlot.y + dragCell.height / 2f)) / listStrideY).roundToInt()
        }
        val clamped = newTo.coerceIn(minIndex, playlists.lastIndex)
        if (clamped != toIndex) {
            toIndex = clamped
        }
    }

    /** 松手提交：一次性把 from 卡插到 to 位（items 在拖拽期间从未重排，这里才重排） */
    val commitDrag: () -> Unit = {
        val from = fromIndex
        val to = toIndex
        val moved = draggingKey
        val playlists = playlistsNow.value
        draggingKey = null
        fromIndex = -1
        toIndex = -1
        dragPointer = Offset.Unspecified
        if (moved != null && from in playlists.indices && to in playlists.indices && to != from) {
            val order = playlists.toMutableList().apply { add(to, removeAt(from)) }
            onReorderPlaylists(order.ids())
        }
    }

    // 拖到列表上下边缘时自动滚动（与详情页同一套逻辑）。
    // 批八：手指**真的移动过**（≥ AUTOSCROLL_SLOP）才启用；批十：滚动方向由
    // 「被拖卡的渲染中心 y + 最近拖动方向」共同决定（行真到边缘且方向一致才滚，
    // 停住保留最近方向 —— 经典边缘体验不变）。
    val autoScrollDelta = if (
        draggingKey == null ||
        dragPointer == Offset.Unspecified ||
        dragMovedPx < autoScrollSlopPx ||
        lastDragDir == 0
    ) {
        0f
    } else {
        val viewport = (
            if (state.layout == PlaylistLayout.GRID) {
                gridState.layoutInfo.viewportSize.height
            } else {
                listState.layoutInfo.viewportSize.height
            }
            ).toFloat()
        val edge = with(density) { AUTOSCROLL_EDGE.toPx() }
        val step = with(density) { AUTOSCROLL_STEP.toPx() }
        val cardVisualCenterY = dragPointer.y - grabOffset.y + dragCell.height / 2f
        when {
            lastDragDir < 0 && cardVisualCenterY < edge -> -step
            lastDragDir > 0 && cardVisualCenterY > viewport - edge -> step
            else -> 0f
        }
    }

    LaunchedEffect(draggingKey, autoScrollDelta) {
        if (draggingKey != null && autoScrollDelta != 0f) {
            while (true) {
                val scrolled = if (state.layout == PlaylistLayout.GRID) {
                    gridState.scrollBy(autoScrollDelta)
                } else {
                    listState.scrollBy(autoScrollDelta)
                }
                if (scrolled != 0f) {
                    // 唯一滚动补偿：baseSlot.y 随滚动更新（scrollBy 返回实际滚动量）；
                    // 跟手 translation = 视觉左上角 − baseSlot 在渲染处重算，
                    // 布局滚动与 translation 更新同帧 → 被拖卡恒贴手指；
                    // 让位卡的 translation 只依赖 from/to（与滚动无关）不变
                    baseSlot = Offset(baseSlot.x, baseSlot.y - scrolled)
                    // 滚动改变相对位置 → 重推插入目标
                    applyDrag(dragPointer)
                }
                delay(AUTOSCROLL_FRAME_MS)
            }
        }
    }

    // ── 批五 #3：上滑收起头部、下滑展开 ──
    // 浏览歌单（上滑）时把头部「丝滑过渡掉」只看歌单；往回（下滑）要找搜索/筛选时再展开。
    // 实现：nestedScroll 在 onPreScroll 里**消费**手势增量来收/展头部（列表本体不动，
    // 手指-glued 内容稳定不跳）；停手后未收完的半截用动画吸附到全收/全展。
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
                    // 手指上滑（往列表深处浏览）：先收头部
                    (before - dy).coerceAtMost(headerHeightPx)
                } else {
                    // 手指下滑（往回看）：先展开头部
                    (before - dy).coerceAtLeast(0f)
                }
                return Offset(0f, before - headerCollapse)
            }
        }
    }

    // 停手后吸附：收过半 → 全收；没过半 → 全展（半截悬着最难看）
    val headerSettled by remember {
        derivedStateOf { !listState.isScrollInProgress && !gridState.isScrollInProgress }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .nestedScroll(headerConnection)
            .padding(horizontal = scalars.sidePadding),
    ) {
        Spacer(Modifier.height(scalars.sectionGap))

        // 可收起头部：布局高度 = 自然高度 - 已收起量，内容上移并裁掉（配合上面的
        // nestedScroll 消费，收起过程列表内容贴着手指走、不跳变）
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "我的歌单",
                        style = LXType.headlineLarge,
                        color = colors.ink1,
                        modifier = Modifier.weight(1f),
                    )
                    // 组合播放入口（临时混合 / 真合并，2026-10-26 用户要求）
                    TextButton(onClick = { showCombineDialog = true }) {
                        Text(text = "组合", style = LXType.labelLarge, color = colors.accent)
                    }
                    TextButton(
                        onClick = { showCreateDialog = true },
                        // 教程高亮目标：新手引导指到这里，点一下即推进
                        modifier = Modifier.tutorialTarget("playlist_new"),
                    ) {
                        Text(text = "新建", style = LXType.labelLarge, color = colors.accent)
                    }
                }

                Spacer(Modifier.height(LXDimens.space12))

                // inmyfeelings 文案行（原型 `.plverse`：与主屏同一套文案源与轮换间隔，13px 轻量呈现）
                LXVerseLine(
                    style = LXType.labelLarge,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(15.dp))

                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(text = "在歌单里找…", style = LXType.bodyMedium, color = colors.ink3)
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
                    shape = RoundedCornerShape(LXDimens.radiusMedium),
                    modifier = Modifier.fillMaxWidth(),
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

                Spacer(Modifier.height(13.dp))

                // 筛选 chips：全部 / 我创建的 / 收藏的 / 含本地
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PlaylistFilter.entries.forEach { option ->
                        LXFilterChip(
                            filter = option,
                            selected = state.filter == option,
                            onClick = { onFilterChange(option) },
                        )
                    }
                }

                Spacer(Modifier.height(scalars.sectionGap / 2))

                // 结果数 + 排布切换（网格 / 列表）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.filter.label,
                        style = LXType.labelLarge,
                        color = colors.ink1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${state.playlists.size}",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.width(10.dp))
                    LXViewToggleGroup {
                        LXViewToggle(
                            icon = LxIcons.ViewGrid,
                            description = "网格视图",
                            selected = state.layout == PlaylistLayout.GRID,
                            onClick = { onLayoutChange(PlaylistLayout.GRID) },
                        )
                        LXViewToggle(
                            icon = LxIcons.ViewList,
                            description = "紧凑列表",
                            selected = state.layout == PlaylistLayout.LIST,
                            onClick = { onLayoutChange(PlaylistLayout.LIST) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }

        if (state.playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.query.isBlank()) "还没有歌单，点右上角「新建」" else "没有匹配的歌单",
                    style = LXType.bodyMedium,
                    color = colors.ink3,
                )
            }
        } else {
            // ── 容器级拖拽手势（批六改容器级；批十三视觉让位架构）──
            // 手势挂**列表容器**：容器原点永远不动，change.position 就是视口绝对
            // 坐标。命中当场读快照（key + index，长按时刻必新鲜），items 原序拖拽
            // 期间不变 → index 恒有效，无闭包捕获旧列表问题。
            val dragHost = Modifier.pointerInput(state.layout, draggable) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        // 命中检测：local 相对容器（= 列表视口原点）
                        if (state.layout == PlaylistLayout.GRID) {
                            val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { cell ->
                                local.x >= cell.offset.x && local.x < cell.offset.x + cell.size.width &&
                                    local.y >= cell.offset.y && local.y < cell.offset.y + cell.size.height
                            }
                            val cardId = hit?.key as? String
                            val card = cardId?.let { id -> state.playlists.firstOrNull { it.id == id } }
                            if (draggableNow.value && card != null && !card.isSystem) {
                                draggingKey = cardId
                                fromIndex = hit.index
                                toIndex = hit.index
                                baseSlot = Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                                dragCell = hit.size
                                grabOffset = local - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                                dragPointer = local
                                dragMovedPx = 0f
                                lastDragDir = 0
                                killFlingAtDragStart()
                            }
                        } else {
                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { row ->
                                local.y >= row.offset && local.y < row.offset + row.size
                            }
                            val cardId = hit?.key as? String
                            val card = cardId?.let { id -> state.playlists.firstOrNull { it.id == id } }
                            if (draggableNow.value && card != null && !card.isSystem) {
                                draggingKey = cardId
                                fromIndex = hit.index
                                toIndex = hit.index
                                baseSlot = Offset(0f, hit.offset.toFloat())
                                dragCell = IntSize(0, hit.size)
                                // 紧凑列表行距：长按时刻从相邻行布局差测一次（快照新鲜，
                                // 无可见邻居时按行高兜底 —— 行间距 0 的紧凑排列）
                                val neighbor = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == hit.index + 1 }
                                    ?: listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == hit.index - 1 }
                                listStrideY = when {
                                    neighbor != null && neighbor.index > hit.index ->
                                        (neighbor.offset - hit.offset).toFloat().coerceAtLeast(hit.size.toFloat())
                                    neighbor != null ->
                                        (hit.offset - neighbor.offset).toFloat().coerceAtLeast(hit.size.toFloat())
                                    else -> hit.size.toFloat()
                                }
                                grabOffset = Offset(0f, local.y - hit.offset.toFloat())
                                dragPointer = local
                                dragMovedPx = 0f
                                lastDragDir = 0
                                killFlingAtDragStart()
                            }
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
                            applyDrag(change.position)
                        }
                    },
                    onDragEnd = { commitDrag() },
                    onDragCancel = { commitDrag() },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (draggable) dragHost else Modifier),
            ) {
                if (state.layout == PlaylistLayout.GRID) {
                    LazyVerticalGrid(
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        columns = GridCells.Fixed(GRID_COLUMNS),
                        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                        contentPadding = PaddingValues(bottom = scalars.sectionGap),
                    ) {
                        itemsIndexed(
                            items = visibleCards,
                            key = { _, card -> card.id },
                            contentType = { _, _ -> "playlist-card" },
                        ) { index, card ->
                            LXStaggerItem(index = index) {
                                val dragging = card.id == draggingKey
                                // 被拖卡无需 zIndex：让位架构下目标格必然已让空（见详情页注释）
                                // ── 让位/跟手 translation（纯推导，与布局状态完全无关）──
                                // 被拖卡：视觉左上角 − 原槽位（baseSlot 随滚动补偿）；
                                // 让位卡：from→to 区间线性推移一格的行列位移
                                //（等距网格推导，松手提交后让位位置 == 新布局位置）
                                val shift = when {
                                    dragging -> dragPointer - grabOffset - baseSlot
                                    draggingKey != null && fromIndex in state.playlists.indices &&
                                        toIndex in state.playlists.indices -> {
                                        val newIdx = when (index) {
                                            in (fromIndex + 1)..toIndex -> index - 1
                                            in toIndex until fromIndex -> index + 1
                                            else -> index
                                        }
                                        if (newIdx == index || fromIndex == toIndex) Offset.Zero else Offset(
                                            ((newIdx % GRID_COLUMNS) - (index % GRID_COLUMNS)) *
                                                (dragCell.width + gridGapPx),
                                            ((newIdx / GRID_COLUMNS) - (index / GRID_COLUMNS)) *
                                                (dragCell.height + gridGapPx),
                                        )
                                    }
                                    else -> Offset.Zero
                                }
                                PlaylistGridItem(
                                    card = card,
                                    dragging = dragging,
                                    dragOffset = shift,
                                    // 空长按松手时 combinedClickable 仍会补发 onClick（拖拽取消路径不消费 UP），
                                    // 用 draggingKey 压制：点击在 Main pass 先于拖拽收尾派发，此刻仍是拖拽中
                                    onClick = { if (draggingKey == null) onOpenPlaylist(card.id) },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = scalars.sectionGap),
                    ) {
                        itemsIndexed(
                            items = visibleCards,
                            key = { _, card -> card.id },
                            contentType = { _, _ -> "playlist-row" },
                        ) { index, card ->
                            LXStaggerItem(index = index) {
                                val dragging = card.id == draggingKey
                                // 被拖行无需 zIndex：让位架构下目标行槽位必然已让空（见详情页注释）
                                // ── 让位/跟手 translation（纯推导，与布局状态完全无关）──
                                // 被拖卡：视觉顶边 − 原槽位；让位卡：from→to 区间各让一格
                                val translateY = when {
                                    dragging -> dragPointer.y - grabOffset.y - baseSlot.y
                                    draggingKey != null && fromIndex in state.playlists.indices &&
                                        toIndex in state.playlists.indices -> when {
                                        fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> -listStrideY
                                        fromIndex > toIndex && index in toIndex until fromIndex -> listStrideY
                                        else -> 0f
                                    }
                                    else -> 0f
                                }
                                PlaylistListRow(
                                    index = index,
                                    card = card,
                                    dragging = dragging,
                                    translateY = translateY,
                                    // draggingKey 压制空长按松手的误触点击（见网格处注释）
                                    onClick = { if (draggingKey == null) onOpenPlaylist(card.id) },
                                    onRename = { renameTarget = card },
                                    onDelete = { deleteTarget = card },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        PlaylistNameDialog(
            title = "新建歌单",
            initialValue = "",
            confirmLabel = "创建",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            },
        )
    }

    if (showCombineDialog) {
        CombinePlayDialog(
            playlists = state.playlists,
            onDismiss = { showCombineDialog = false },
            onPlayCombined = { ids ->
                showCombineDialog = false
                onPlayCombined(ids)
            },
            onMerge = { ids, name ->
                onMergePlaylists(ids, name) { newId ->
                    showCombineDialog = false
                    onOpenPlaylist(newId)
                }
            },
        )
    }

    renameTarget?.let { card ->
        PlaylistNameDialog(
            title = "重命名歌单",
            initialValue = card.name,
            confirmLabel = "保存",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRenamePlaylist(card.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { card ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.surface,
            title = { Text(text = "删除歌单", style = LXType.titleLarge, color = colors.ink1) },
            text = {
                Text(
                    text = "「${card.name}」及其中的 ${card.itemCount} 首歌都会从这个歌单里移除。",
                    style = LXType.bodyMedium,
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist(card.id)
                    deleteTarget = null
                }) {
                    Text(text = "删除", style = LXType.labelLarge, color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = "取消", style = LXType.labelLarge, color = colors.ink3)
                }
            },
        )
    }
}

@Composable
private fun LXFilterChip(filter: PlaylistFilter, selected: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) colors.inset else colors.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            text = filter.label,
            style = LXType.labelSmall,
            color = if (selected) colors.accent else colors.ink2,
        )
    }
}

@Composable
private fun PlaylistGridItem(
    card: PlaylistCard,
    dragging: Boolean,
    dragOffset: Offset,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    // 封面优先级：本地图片 > 歌曲封面（summary 已解析「指定歌曲 > 第一首有封面的歌」）
    val coverModel = card.coverImageUri ?: card.coverUrl

    Column(
        modifier = Modifier
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                if (dragging) {
                    // 浮起：轻微放大即可，别夸张
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
            }
            // 点按打开；长按完全交给列表容器级的拖拽手势（批六）。
            // ⚠️ 这里绝不能传非空 onLongClick：combinedClickable 的内部长按检测器在 Main pass
            // 先于外层拖拽收到事件，会在长按超时竞争里系统性取胜并消费事件，把
            // detectDragGesturesAfterLongPress 的长按永久取消 —— 这正是拖拽排序失效的根因
            // （2026-10-10 批三真机复验，字节码取证）。空长按误触点击由调用方的 draggingKey 压制。
            .combinedClickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                // 被拖起的那一张：抬升阴影
                .then(
                    if (dragging) {
                        Modifier.shadow(6.dp, RoundedCornerShape(LXDimens.radiusMedium))
                    } else {
                        Modifier
                    },
                )
                .clip(RoundedCornerShape(LXDimens.radiusMedium))
                .background(LXTheme.pageInset),
            contentAlignment = Alignment.Center,
        ) {
            if (coverModel != null) {
                AsyncArtwork(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = LxIcons.Playlist,
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier.size(28.dp),
                )
            }
            // 「收藏」角标（系统歌单）
            if (card.isSystem) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.surface.copy(alpha = 0.75f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(text = "收藏", style = LXType.labelSmall, color = colors.ink1)
                }
            }
            // 曲目数角标
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(colors.ink1.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(text = "${card.itemCount} 首", style = LXType.labelSmall, color = colors.bg)
            }
        }
        Spacer(Modifier.height(LXDimens.space8))
        Text(
            text = card.name,
            style = LXType.titleSmall,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append("${card.itemCount} 首")
                if (card.hasLocal) append(" · 含本地")
            },
            style = LXType.labelSmall,
            color = colors.ink3,
        )
    }
}

@Composable
private fun PlaylistListRow(
    index: Int,
    card: PlaylistCard,
    dragging: Boolean,
    translateY: Float,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LXTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .graphicsLayer {
                translationY = translateY
                if (dragging) {
                    // 浮起：轻微放大即可，别夸张
                    scaleX = 1.04f
                    scaleY = 1.04f
                }
            }
            // 被拖起的那一行：抬升阴影 + 底色提亮
            .then(
                if (dragging) {
                    Modifier
                        .shadow(6.dp, RoundedCornerShape(LXDimens.radiusSmall))
                        .background(colors.surface)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 点按打开；长按完全交给列表容器级的拖拽手势（不可传非空 onLongClick，原因见网格项注释）
                .combinedClickable(onClick = onClick)
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = "${index + 1}",
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.width(15.dp),
            )
            Text(
                text = card.name,
                style = LXType.labelLarge,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (card.hasLocal) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(LXTheme.pageInset)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                ) {
                    Text(text = "本地", style = LXType.labelSmall, color = colors.ink2)
                }
            }
            Text(text = "${card.itemCount} 首", style = LXType.labelSmall, color = colors.ink3)

            // 系统歌单（收藏）不可改名/删除，故不显示入口
            if (!card.isSystem) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = LxIcons.More,
                            contentDescription = "歌单操作",
                            tint = colors.ink3,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = colors.surface,
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = "重命名", style = LXType.labelLarge, color = colors.ink1) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = "删除", style = LXType.labelLarge, color = colors.danger) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

/** 新建 / 重命名共用的命名弹窗 */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = LXTheme.colors
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(text = title, style = LXType.titleLarge, color = colors.ink1) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = {
                    Text(text = "留空则自动命名", style = LXType.bodyMedium, color = colors.ink3)
                },
                singleLine = true,
                shape = RoundedCornerShape(LXDimens.radiusMedium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.ink1,
                    unfocusedTextColor = colors.ink1,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    cursorColor = colors.accent,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.hairline,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(text = confirmLabel, style = LXType.labelLarge, color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = LXType.labelLarge, color = colors.ink3)
            }
        },
    )
}

private fun List<PlaylistCard>.ids(): List<String> = map { it.id }

/**
 * 组合播放弹窗（2026-10-26 用户要求）：勾选 ≥2 个歌单 →
 * - **临时播放**：按展示顺序合并去重成临时队列直接起播，不产生新歌单；
 * - **合并为歌单**：同样合并去重，但落库为新歌单（可命名，留空自动命名）并跳详情。
 *
 * 合并顺序 = 一级页展示顺序（拖拽序），与勾选先后无关——结果可预期、可复现。
 */
@Composable
private fun CombinePlayDialog(
    playlists: List<PlaylistCard>,
    onDismiss: () -> Unit,
    onPlayCombined: (List<String>) -> Unit,
    onMerge: (ids: List<String>, name: String) -> Unit,
) {
    val colors = LXTheme.colors
    var selected by remember { mutableStateOf(setOf<String>()) }
    var name by remember { mutableStateOf("") }
    val enough = selected.size >= 2
    // 合并顺序与展示顺序一致：按 playlists 的顺序过滤选中项
    val orderedIds = playlists.filter { it.id in selected }.map { it.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(text = "组合播放", style = LXType.titleLarge, color = colors.ink1) },
        text = {
            Column {
                Text(
                    text = if (enough) {
                        "已选 ${selected.size} 个歌单，按展示顺序合并去重"
                    } else {
                        "勾选至少 2 个歌单（当前 ${selected.size} 个）"
                    },
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 7.dp),
                ) {
                    playlists.forEach { card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (card.id in selected) {
                                        selected - card.id
                                    } else {
                                        selected + card.id
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = card.id in selected,
                                onCheckedChange = {
                                    selected = if (card.id in selected) {
                                        selected - card.id
                                    } else {
                                        selected + card.id
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = colors.accent,
                                    checkmarkColor = colors.surface,
                                    uncheckedColor = colors.hairline,
                                ),
                            )
                            Text(
                                text = card.name,
                                style = LXType.bodyMedium,
                                color = colors.ink1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 5.dp),
                            )
                            Text(
                                text = "${card.itemCount} 首",
                                style = LXType.labelSmall,
                                color = colors.ink3,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = {
                        Text(
                            text = "合并后的歌单名（留空自动命名）",
                            style = LXType.bodyMedium,
                            color = colors.ink3,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(LXDimens.radiusMedium),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.ink1,
                        unfocusedTextColor = colors.ink1,
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        cursorColor = colors.accent,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.hairline,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp),
                )
            }
        },
        confirmButton = {
            Row {
                // 临时混合：不入库，直接把合并队列交给播放器
                TextButton(enabled = enough, onClick = { onPlayCombined(orderedIds) }) {
                    Text(
                        text = "临时播放",
                        style = LXType.labelLarge,
                        color = if (enough) colors.accent else colors.ink3,
                    )
                }
                Spacer(Modifier.width(7.dp))
                // 真合并：落库为新歌单（回调里跳详情页）
                TextButton(enabled = enough, onClick = { onMerge(orderedIds, name.trim()) }) {
                    Text(
                        text = "合并为歌单",
                        style = LXType.labelLarge,
                        color = if (enough) colors.accent else colors.ink3,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = LXType.labelLarge, color = colors.ink3)
            }
        },
    )
}

private val GRID_GAP = 14.dp

/** 网格列数（`GridCells.Fixed`） */
private const val GRID_COLUMNS = 2

/** 拖拽排序的调试日志开关（真机验证手势链路用） */
private const val TAG = "LXDrag"

private val AUTOSCROLL_EDGE = 72.dp
private val AUTOSCROLL_STEP = 6.dp
/** 自动滚动的启用门槛：手指累计移动超过这个量才算「真的在拖」（批八症状①） */
private val AUTOSCROLL_SLOP = 8.dp
private const val AUTOSCROLL_FRAME_MS = 16L
