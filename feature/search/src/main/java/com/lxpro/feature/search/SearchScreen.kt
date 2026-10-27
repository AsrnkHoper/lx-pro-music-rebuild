package com.lxpro.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXModeSwitch
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.SearchMode
import com.lxpro.core.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 搜索页（二级页，权威稿 `mockup_app.html` 的 `#pg-search`）。
 *
 * 结构自上而下（不可调换）：
 * `.dbar`（返回键 + 搜索框同一栏）→ 双模式 `.modes` → 类型 `#spTypes` →
 * 搜索平台 `#spPlatBlock`（本地模式隐藏）→ 结果区 `#spBody`。
 *
 * ⚠️ 两条硬性规格：
 * - 15 §4.2 #2：**模式入口位于搜索框正下方**（这是产品定位的 UI 体现）
 * - 15 §5：每行都要标来源（在线音源名 / 本地）
 *
 * ⚠️ 输入框文本用**本地状态**（`rememberSaveable`），不绑到 ViewModel 的搜索结果状态上——
 * 否则防抖搜索完成后回写会让光标跳回最前（2026-10-02 真机踩坑）。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 歌手 / 专辑卡片 → 详情页（M3 任务 10）；默认退回「用该名字再搜一次」 */
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    // 省流「全无图」：强制等同右上角按钮切到无图模式（#128，不改用户自己的选择记忆）
    val dataSaverNoImage by com.lxpro.core.model.DataSaver.imagesBlockedFlow.collectAsState()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    var query by rememberSaveable { mutableStateOf(viewModel.initialQuery) }
    val listState = rememberLazyListState()

    // 播放队列 = 当前搜索结果。⚠️ 必须在这里 remember 一次：若写进每行的 onClick 里，
    // 每次重组都会为每一行重新构造这个列表（N 行 × 每帧），是滚动掉帧的常见来源
    val queue = remember(state.results) { state.results.map { it.song } }

    // 触底加载下一页（本地模式没有分页，hasMore 恒为 false）
    // ⚠️ 判据必须用**整表**条数（列表前面还有模式/类型/平台等头部项），
    // 否则带头部项时永远触发不到触底（结果条数 ≠ 列表项数）
    //
    // 单曲列表是**最后一个区块**（归纳预览都在它前面），所以触底续页不会把任何东西推走
    LaunchedEffect(listState, state.hasMore, state.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                val atEnd = total > 0 && lastVisible >= total - 3
                if (state.hasMore && !state.loadingMore && atEnd) {
                    viewModel.loadMore()
                }
            }
    }

    Column(modifier = modifier.fillMaxSize().lxSafeDrawingPadding()) {
        // ── .dbar：返回键与搜索框同一栏 ──
        Dbar(
            query = query,
            mode = mode,
            layout = layout,
            focusOnStart = viewModel.initialQuery.isBlank(),
            onQueryChange = {
                query = it
                viewModel.onKeywordChange(it)
            },
            onSubmit = viewModel::onSubmit,
            onClear = {
                query = ""
                viewModel.onClearInput()
            },
            onToggleLayout = viewModel::onLayoutToggle,
            onBack = onBack,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = scalars.sidePadding,
                end = scalars.sidePadding,
                bottom = scalars.sectionGap,
            ),
        ) {
            // ── 双模式（紧贴搜索框下方，15 §4.2 #2）──
            item(key = "modes") {
                LXModeSwitch(
                    mode = mode,
                    onModeChange = viewModel::onModeChange,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ── 结果呈现类型 ──
            item(key = "types") {
                TypeChipRow(
                    selected = type,
                    onSelect = viewModel::onTypeChange,
                    // 原型 `.chiprow` padding-bottom:6px + `.sblock` 块距 13px
                    modifier = Modifier.padding(top = 13.dp, bottom = 6.dp),
                )
            }

            // ── 搜索平台（本地档隐藏：本地不经过任何音源）──
            // ⚠️ 这里列的是**音乐平台**（酷我 / QQ / 网易云…），不是音源脚本：
            // 同一平台可能有内置源 + 多个脚本，都归到一个 chip 下（2026-10-27 用户反馈）
            if (mode != SearchMode.LOCAL && prefs.platforms.isNotEmpty()) {
                item(key = "platforms") {
                    Spacer(Modifier.height(13.dp))
                    BlockHeader(
                        title = "搜索平台",
                        note = platformSummary(prefs),
                    )
                    ChipFlow {
                        prefs.platforms.forEach { platform ->
                            PlatformChip(
                                label = platform.label,
                                selected = platform.selected,
                                onClick = { viewModel.onPlatformToggle(platform.id) },
                            )
                        }
                    }
                }
            }

            when {
                // ── 初始态：搜索历史 / 常搜（权威稿 `#spBody` 的空关键词分支）──
                !state.searched -> {
                    if (prefs.history.isNotEmpty()) {
                        item(key = "history") {
                            Spacer(Modifier.height(18.dp))
                            BlockHeader(
                                title = "搜索历史",
                                note = "共 ${prefs.history.size} 条",
                                trailing = {
                                    Text(
                                        text = "清空",
                                        style = LXType.labelSmall,
                                        color = colors.ink3,
                                        modifier = Modifier.clickable(onClick = viewModel::clearHistory),
                                    )
                                },
                            )
                            ChipFlow {
                                prefs.history.forEach { word ->
                                    WordChip(
                                        label = word,
                                        onClick = { query = word; viewModel.onSubmit(word) },
                                        onRemove = { viewModel.removeHistory(word) },
                                    )
                                }
                            }
                        }
                    }
                    if (prefs.hotQueries.isNotEmpty()) {
                        item(key = "hot") {
                            Spacer(Modifier.height(13.dp))
                            BlockHeader(title = "常搜", note = "本地统计 · 不是云端热榜")
                            ChipFlow {
                                prefs.hotQueries.forEach { word ->
                                    WordChip(
                                        label = word,
                                        onClick = { query = word; viewModel.onSubmit(word) },
                                    )
                                }
                            }
                        }
                    }
                    if (prefs.history.isEmpty() && prefs.hotQueries.isEmpty()) {
                        item(key = "initial-empty") {
                            EmptyBlock(
                                title = "输入关键词开始搜索",
                                hint = if (mode == SearchMode.LOCAL) {
                                    "本地模式只搜已扫描 / 已导入的曲目"
                                } else {
                                    "标点不影响：luvsicpt3 也能搜到 Luv(sic.)pt3"
                                },
                            )
                        }
                    }
                }

                // 只在「还没有任何结果」时占满一屏转圈；已经有结果就继续往下渲染，
                // 尾巴上挂一条「还在搜其他平台」（多音源到货顺序不一致，不能干等）
                state.loading && state.results.isEmpty() -> item(key = "loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }

                // ── 结果分组 ──
                // ⚠️ 归纳预览（歌手 / 专辑 / 歌单）必须排在**可翻页的单曲列表之前**（2026-10-02 用户裁决）：
                // 单曲列表会一直往下续页，任何排在他后面的区块都会被新一页歌曲推向更深处、永远看不到。
                // 原型把单曲放最前是静态数据的排法，真机无限下拉时不成立。
                else -> {
                    // 歌手 / 专辑归纳与歌单分组只在有在线结果时出现（归纳数据来自在线部分，
                    // 本地档没有在线结果，各分组内部对空列表直接不渲染）
                    if (mode != SearchMode.LOCAL) {
                        artistSection(
                            visible = type == SearchType.ALL || type == SearchType.ARTIST,
                            artists = state.artists,
                            onPick = { singer -> onOpenArtist(singer) },
                        )
                        albumSection(
                            visible = type == SearchType.ALL || type == SearchType.ALBUM,
                            albums = state.albums,
                            onPick = { album -> onOpenAlbum(album) },
                        )
                        playlistSection(
                            visible = type == SearchType.ALL || type == SearchType.LIST,
                            playlists = state.playlists,
                            onOpen = onOpenPlaylist,
                        )
                    }

                    songSection(
                        visible = type == SearchType.ALL || type == SearchType.SONG,
                        results = state.results,
                        queue = queue,
                        compact = layout == ResultLayout.COMPACT || dataSaverNoImage,
                        onSongClick = onSongClick,
                        onMoreSong = onMoreSong,
                    )

                    if (mode == SearchMode.LOCAL &&
                        (type == SearchType.ARTIST || type == SearchType.ALBUM || type == SearchType.LIST)
                    ) {
                        // 音源接口只有关键字检索；本地又不做分类归纳 —— 如实说明，不假装有结果
                        item(key = "local-type-note") {
                            EmptyBlock(
                                title = "本地搜索只做单曲检索",
                                hint = "歌手 / 专辑 / 歌单分组由在线音源的结果归纳，请切到在线音源",
                            )
                        }
                    }

                    if (nothingToShow(state, mode, type)) {
                        item(key = "empty") {
                            EmptyBlock(
                                title = state.error ?: "没有匹配的内容",
                                hint = if (mode == SearchMode.LOCAL) {
                                    "试试切到在线音源"
                                } else {
                                    "换个关键词、放开平台筛选，或切到本地音乐看看"
                                },
                            )
                        }
                    }

                    // 已经有结果、但还有音源没回来：尾巴上说明「还没搜完」，别让用户以为就这些
                    if (state.loading) {
                        item(key = "searching-rest") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = colors.ink3,
                                    strokeWidth = 1.5.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "正在搜其他平台…",
                                    style = LXType.labelSmall,
                                    color = colors.ink3,
                                )
                            }
                        }
                    }

                    if (state.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── .dbar ───────────────────────────

@Composable
private fun Dbar(
    query: String,
    mode: SearchMode,
    layout: ResultLayout,
    focusOnStart: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit,
    onToggleLayout: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    val focusRequester = remember { FocusRequester() }
    // 空词进页 = 用户是来打字的，直接聚焦（少点一次；2026-10-02 用户反馈）
    LaunchedEffect(Unit) {
        if (focusOnStart) {
            delay(FOCUS_DELAY_MS)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = scalars.sidePadding)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // .backbtn：34 圆 + 发丝线
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LxIcons.Back,
                contentDescription = "返回",
                tint = colors.ink1,
                modifier = Modifier.size(17.dp),
            )
        }

        // .searchwrap + .searchbox：无边框填充输入，聚焦时底色变深
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            singleLine = true,
            textStyle = LXType.labelLarge,
            shape = RoundedCornerShape(13.dp),
            placeholder = {
                Text(
                    text = when (mode) {
                        SearchMode.LOCAL -> "搜索本地曲库"
                        SearchMode.ALL -> "搜索本地与在线音源"
                        SearchMode.ONLINE -> "搜索歌曲、歌手、专辑"
                    },
                    style = LXType.labelLarge,
                    color = colors.ink3,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = LxIcons.Search,
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier.size(15.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(23.dp)
                            .clip(CircleShape)
                            .background(LXTheme.pageInset)
                            .clickable(onClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = LxIcons.Close,
                            contentDescription = "清空",
                            tint = colors.ink2,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            },
            // 输入法上的「搜索」= 记一次历史并立即重搜
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
            // ⚠️ 必须显式取 LX token，不能吃 M3 默认色（否则在部分配色下字看不清）
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.ink1,
                unfocusedTextColor = colors.ink1,
                focusedContainerColor = colors.hairline,
                unfocusedContainerColor = colors.inset,
                cursorColor = colors.accent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedPlaceholderColor = colors.ink3,
                unfocusedPlaceholderColor = colors.ink3,
                focusedLeadingIconColor = colors.ink3,
                unfocusedLeadingIconColor = colors.ink3,
            ),
        )

        // `.sbtn`：有图 ⇄ 无图（与歌单详情页同一枚按钮语义 `#plDcover`）。
        // 选中有图 = 高亮（原型 `.sbtn.on`：accent 描边 + accent 淡底 + accent 图标）
        val on = layout == ResultLayout.COVER
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (on) colors.accent.copy(alpha = 0.13f) else LXTheme.pageSurface)
                .clickable(onClick = onToggleLayout),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LxIcons.CoverBox,
                contentDescription = if (on) "切换为无封面" else "切换为有封面",
                tint = if (on) colors.accent else colors.ink2,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ─────────────────────────── 类型 chips ───────────────────────────

/** `.chiprow` + `.tchip`：横向滚动一排，选中 = accent 实底 */
@Composable
private fun TypeChipRow(
    selected: SearchType,
    onSelect: (SearchType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SearchType.entries.forEach { item ->
            val on = item == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (on) colors.accent else LXTheme.pageSurface)
                    .border(
                        width = 1.dp,
                        color = if (on) colors.accent else colors.hairline,
                        shape = RoundedCornerShape(percent = 50),
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = item.label,
                    style = LXType.labelSmall.copy(
                        fontWeight = if (on) FontWeight.SemiBold else LXType.labelSmall.fontWeight,
                    ),
                    color = if (on) colors.bg else colors.ink2,
                )
            }
        }
    }
}

// ─────────────────────────── chips 容器 / 原子 ───────────────────────────

/** `.slab`：小标题居左 + 摘要居右（原型 space-between，批次三 #1 收敛） */
@Composable
private fun BlockHeader(
    title: String,
    note: String?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(text = title, style = LXType.labelSmall, color = colors.ink3)
        Spacer(Modifier.weight(1f))
        note?.let {
            Text(
                text = it,
                style = LXType.labelSmall,
                color = colors.ink3.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}

/** `.slist`：先换行，超过三行在这块里上下滚（不是横向滚动条 —— 那是废弃方案） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = CHIP_LIST_MAX)
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

/** `.pchip`：音源多选（选中 = accent 淡底 + accent 描边；未选 = surface + 发丝线） */
@Composable
private fun PlatformChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) colors.accent.copy(alpha = 0.13f) else LXTheme.pageSurface)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.hairline,
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = LXType.labelSmall,
            color = if (selected) colors.accent else colors.ink2,
        )
    }
}

/** `.hchip`：搜索历史 / 常搜的词 chip；历史可带一枚 × 单独删 */
@Composable
private fun WordChip(label: String, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(LXTheme.pageInset)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (onRemove == null) 12.dp else 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = LXType.labelSmall, color = colors.ink2)
        if (onRemove != null) {
            Spacer(Modifier.width(2.dp))
            // 触摸区 22dp（≥ 最小可点尺寸），图标本身 9dp
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LxIcons.Close,
                    contentDescription = "删除「$label」",
                    tint = colors.ink3,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

// ─────────────────────────── 结果分组 ───────────────────────────

/**
 * 单曲：`#spBody` 的**最后一个**分组（原型排版里它在最前，但这里必须在归纳预览之后 ——
 * 它是唯一会无限续页的区块，见上方触底加载处的说明）。
 */
private fun LazyListScope.songSection(
    visible: Boolean,
    results: List<SearchResultItem>,
    queue: List<Song>,
    compact: Boolean,
    onSongClick: (Song, List<Song>) -> Unit,
    onMoreSong: (Song) -> Unit,
) {
    if (!visible || results.isEmpty()) return
    item(key = "song-head") {
        Spacer(Modifier.height(13.dp))
        BlockHeader(title = "单曲", note = "${results.size} 条")
    }
    itemsIndexed(
        items = results,
        key = { _, item -> "${item.song.source.value}:${item.song.id}" },
        contentType = { _, _ -> "song" },
    ) { index, item ->
        // 点哪首就从哪首开始（队列是上面 remember 好的那一份）
        SongRow(
            index = index,
            item = item,
            compact = compact,
            onClick = { onSongClick(item.song, queue) },
            onMore = { onMoreSong(item.song) },
        )
    }
}

/** 歌手：由结果归纳；点击 = 用该歌手名再搜一次（详情页是 M3 任务 10） */
private fun LazyListScope.artistSection(
    visible: Boolean,
    artists: List<ArtistGroup>,
    onPick: (String) -> Unit,
) {
    if (!visible || artists.isEmpty()) return
    item(key = "artist") {
        Spacer(Modifier.height(13.dp))
        BlockHeader(title = "歌手", note = "${artists.size} 位 · 由当前结果归纳")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(artists, key = { it.singer }) { artist ->
                ArtistCard(artist = artist, onClick = { onPick(artist.singer) })
            }
        }
    }
}

/** 专辑：同样由结果归纳 */
private fun LazyListScope.albumSection(
    visible: Boolean,
    albums: List<AlbumGroup>,
    onPick: (String) -> Unit,
) {
    if (!visible || albums.isEmpty()) return
    item(key = "album") {
        Spacer(Modifier.height(13.dp))
        BlockHeader(title = "专辑", note = "${albums.size} 张 · 由当前结果归纳")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(albums, key = { it.album }) { album ->
                AlbumCard(album = album, onClick = { onPick(album.album) })
            }
        }
    }
}

/** 歌单：本地歌单按名字命中（本产品没有云端歌单） */
private fun LazyListScope.playlistSection(
    visible: Boolean,
    playlists: List<PlaylistGroup>,
    onOpen: (String) -> Unit,
) {
    if (!visible || playlists.isEmpty()) return
    item(key = "playlist") {
        Spacer(Modifier.height(13.dp))
        BlockHeader(title = "歌单", note = "${playlists.size} 个 · 本地歌单")
        Column {
            playlists.forEach { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(playlist.id) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = LXType.titleSmall,
                            color = LXTheme.colors.ink1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "本地歌单",
                            style = LXType.labelSmall,
                            color = LXTheme.colors.ink3,
                        )
                    }
                    Text(
                        text = "${playlist.itemCount} 首",
                        style = LXType.labelSmall,
                        color = LXTheme.colors.ink3,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistCard(artist: ArtistGroup, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Column(
        modifier = Modifier.width(74.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(LXTheme.pageInset),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = artist.singer.take(1), style = LXType.titleLarge, color = colors.ink2)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.singer,
            style = LXType.labelSmall,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${artist.trackCount} 首",
            style = LXType.labelSmall,
            color = colors.ink3,
            maxLines = 1,
        )
    }
}

@Composable
private fun AlbumCard(album: AlbumGroup, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.width(128.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(LXTheme.pageInset),
            contentAlignment = Alignment.Center,
        ) {
            if (album.coverUrl != null) {
                AsyncArtwork(
                    model = album.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = LxIcons.CoverBox,
                    contentDescription = null,
                    tint = colors.ink3,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = album.album,
            style = LXType.labelLarge,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${album.singer} · ${album.trackCount} 首",
            style = LXType.labelSmall,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SongRow(
    index: Int,
    item: SearchResultItem,
    compact: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = LXTheme.colors
    val song = item.song
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 有图 = 44dp 封面（与歌单 / 歌手专辑列表同一档 `LXDimens.coverList`，
        // 2026-10-27 用户反馈搜索结果封面明显小于其它页面）；无图 = 26dp 序号
        Box(
            modifier = Modifier
                .size(if (compact) 26.dp else LXDimens.coverList)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (compact) {
                Text(text = "${index + 1}", style = LXType.labelSmall, color = colors.ink3)
            } else {
                // 封面：在线走 Coil，本地走缩略图通道（AsyncArtwork 内部区分）
                AsyncArtwork(
                    model = song.picUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = LXType.titleSmall,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(song.singer)
                    song.interval?.let { append("  ·  ").append(formatDuration(it)) }
                },
                style = LXType.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 来源标记（15 §5 跨页面强制）
        Text(
            text = item.originLabel,
            style = LXType.labelSmall,
            color = colors.ink3,
            maxLines = 1,
        )
        // 歌曲操作入口（原型行尾 `data-more`：与歌单页同一套动作）
        IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = LxIcons.More,
                contentDescription = "更多",
                tint = colors.ink3,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** `.empty`：说清「没结果」和下一步怎么做，不给假成功 */
@Composable
private fun EmptyBlock(title: String, hint: String) {
    val colors = LXTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = LXType.titleSmall, color = colors.ink2)
        Spacer(Modifier.height(6.dp))
        Text(text = hint, style = LXType.labelSmall, color = colors.ink3)
    }
}

/**
 * 当前这组结果是不是「一个分组都没出来」。
 * ⚠️ 不能只看 `results.isEmpty()` —— 用户可能只切到「歌单」而结果里没有本地歌单，
 * 那时也必须给空态，不能留白。
 */
private fun nothingToShow(state: SearchUiState, mode: SearchMode, type: SearchType): Boolean {
    val songs = state.results.isNotEmpty() && (type == SearchType.ALL || type == SearchType.SONG)
    val derived = mode != SearchMode.LOCAL && when (type) {
        SearchType.ARTIST -> state.artists.isNotEmpty()
        SearchType.ALBUM -> state.albums.isNotEmpty()
        SearchType.LIST -> state.playlists.isNotEmpty()
        else -> state.artists.isNotEmpty() || state.albums.isNotEmpty() || state.playlists.isNotEmpty()
    }
    val localTypeNote = mode == SearchMode.LOCAL &&
        (type == SearchType.ARTIST || type == SearchType.ALBUM || type == SearchType.LIST)
    return !songs && !derived && !localTypeNote
}

/** 平台摘要（权威稿 `platSum`）：全选 / 未选 / 列举 */
private fun platformSummary(prefs: SearchPrefs): String {
    if (prefs.sourceCount == 0) return "还没有平台"
    if (prefs.enabledCount == 0) return "未勾选平台 · 不会有结果"
    if (prefs.enabledCount == prefs.sourceCount) return "全部平台"
    return prefs.platforms.filter { it.id != "all" && it.selected }.joinToString(" · ") { it.label }
}

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

/** chips 限宽纵向滚动的高度（原型 `.slist` 的 max-height:76px） */
private val CHIP_LIST_MAX = 76.dp

/** 进页面后请求焦点的延时（等页面转场起来再聚焦） */
private const val FOCUS_DELAY_MS = 140L