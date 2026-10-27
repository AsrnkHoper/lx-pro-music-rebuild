package com.lxpro.feature.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.common.ShareLinkBus
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXModeSwitch
import com.lxpro.core.designsystem.component.LXVerseLine
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.designsystem.tutorial.tutorialTarget
import com.lxpro.core.model.SearchMode
import com.lxpro.core.model.Song
import com.lxpro.core.model.SongLink

/**
 * 首页（`mockup_home.html`）。
 *
 * 版面顺序**不可调换**（原型自上而下即优先级）：
 * 文案（首屏视觉主体）→ 问候/日期 → 搜索栏（输入 + 搜索按钮 + 下载管理）→
 * 双模式切换 → 本地动作行（仅本地模式）→ 三货架（最近播放 / 我的歌单 / 常听歌手）。
 *
 * 批四（2026-10-10）：搜索栏是**真输入框**，点它只起键盘、不展开任何东西；
 * 「音乐平台」多选挂在框内左侧放大镜的下拉里，历史/常搜只在搜索页出现。
 *
 * ⚠️ 配色与氛围开关**不在本页** —— 它们在「设置 → 外观」，
 * 首页回归原型定义的「文案 + 搜索 + 三货架」。
 */
@Composable
fun HomeScreen(
    onOpenSearch: (String) -> Unit,
    onScanFolder: () -> Unit,
    onImportMusic: () -> Unit,
    onOpenPlaylists: () -> Unit,
    /** 点「我的歌单」里的具体封面：直接进该歌单详情（不再绕到歌单总览） */
    onOpenPlaylist: (String) -> Unit,
    /** 点「常听歌手」：进该歌手的歌曲列表 */
    onOpenArtist: (String) -> Unit,
    onOpenDownloadManager: () -> Unit,
    /** 起播后进播放页（15 §2：播放页独立全屏，**从任意歌曲行进入**） */
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    // 输入框文本归输入框自己管，避免防抖搜索回写把光标顶到最前（2026-10-02 真机踩坑）
    var query by rememberSaveable { mutableStateOf("") }

    /** 提交搜索：有词记一次历史再跳搜索页；空词也进搜索页（那里有历史/常搜可挑） */
    fun submitSearch() {
        val word = query.trim()
        // 分享来的软链接 / 整句邀请语：点击搜索后**不做任何关键词搜索、也不进搜索页**，
        // 直接把链接交给播放器（起播 + 自动进播放页）。此前只认 `lxpro://song/`，
        // 整句邀请语走了搜索页 → 先搜一遍再自动跳播放页，正是用户反馈的错误行为。
        val link = SongLink.extractLinkFromText(word)
        if (link != null) {
            ShareLinkBus.emit(link)
            query = ""
            return
        }
        if (word.isNotEmpty()) {
            viewModel.recordSearch(word)
            query = ""
        }
        onOpenSearch(word)
    }

    // 长按「常听歌手」弹出的自定义图标目标（非空 = 弹层打开）
    var iconTarget by remember { mutableStateOf<HomeArtistCard?>(null) }
    // 「选本地图片」要先收起弹层再拉系统选择器，歌手键暂存在这里
    var pendingImageArtistKey by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pickArtistImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pendingImageArtistKey
        pendingImageArtistKey = null
        if (uri != null && key != null) {
            // ⚠️ 不 take 的话重启后权限失效、图标会裂（与 SAF 扫描目录同一坑）
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setArtistIconFromImage(key, uri.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(top = 14.dp, bottom = scalars.sectionGap),
    ) {
        // ── 文案（置顶，首屏视觉主体；间隔跟随「设置 → 文案自定义 → 展示」）──
        LXVerseLine(
            style = LXType.titleLarge,
            barWidth = 2.5.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = scalars.sidePadding),
        )

        // ── 问候语降为副标题 + 日期行 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = scalars.sidePadding)
                .padding(top = 15.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = state.greeting, style = LXType.labelLarge, color = colors.ink2)
            Spacer(Modifier.width(10.dp))
            Text(
                text = state.dateline,
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── 搜索栏 + 下载管理 ──
        // 批四（2026-10-10）重构：真输入框 + 搜索按钮，点框只起键盘**不再展开任何面板**
        //（旧内联面板把「平台/历史/常搜」全铺开，用户嫌「主页乱糟糟」—— 那些都在搜索页）。
        // 左侧放大镜 = 音乐平台多选下拉（替代原「搜索平台（音源）」chips 块）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 教程高亮目标：新手引导会在这里挖洞，并等用户点一下才继续
                .tutorialTarget("home_search")
                .padding(horizontal = scalars.sidePadding)
                .padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(13.dp),
                placeholder = {
                    Text(
                        text = when (state.mode) {
                            SearchMode.LOCAL -> "搜索本地曲库（${state.localTrackCount} 首）"
                            SearchMode.ALL -> "搜索本地与在线音源"
                            SearchMode.ONLINE -> "搜索歌曲、歌手、专辑"
                        },
                        style = LXType.labelLarge,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    PlatformPickerAnchor(
                        platforms = state.platforms,
                        onToggle = viewModel::onPlatformToggle,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(23.dp)
                                .clip(CircleShape)
                                .background(LXTheme.pageInset)
                                .clickable { query = "" },
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
                // 输入法「搜索」= 记历史并跳搜索页
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.ink1,
                    unfocusedTextColor = colors.ink1,
                    focusedContainerColor = colors.hairline,
                    unfocusedContainerColor = colors.inset,
                    cursorColor = colors.accent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedLeadingIconColor = colors.ink3,
                    unfocusedLeadingIconColor = colors.ink3,
                ),
            )

            // 搜索按钮：accent 实底（有词 = 带词进搜索页；空词 = 也进搜索页挑历史/常搜）
            Box(
                modifier = Modifier
                    .height(41.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.accent)
                    .clickable(onClick = { submitSearch() })
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "搜索",
                    style = LXType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.bg,
                )
            }

            Box(
                modifier = Modifier
                    .size(41.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(LXTheme.pageInset)
                    .clickable(onClick = onOpenDownloadManager),
                    contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LxIcons.Download,
                    contentDescription = "下载管理",
                    tint = colors.ink2,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        // ── 双模式切换（搜索框正下方：产品定位的 UI 体现，15 §4.2）──
        LXModeSwitch(
            mode = state.mode,
            onModeChange = viewModel::onModeChange,
            modifier = Modifier
                .padding(horizontal = scalars.sidePadding)
                .padding(top = 12.dp),
        )

        // ── 本地模式动作行（仅本地模式出现）──
        AnimatedVisibility(
            visible = state.mode == SearchMode.LOCAL,
            enter = fadeIn(tween(PANEL_MS)),
            exit = fadeOut(tween(LXMotion.quick)),
        ) {
            LocalActionRow(onScan = onScanFolder, onImport = onImportMusic)
        }

        // ── 三货架 ──
        ShelfSection(
            title = "最近播放",
            badge = "继续听",
            // 权威稿 `.sec-head` 就这么给的目标：查看全部 → 歌单 Tab
            actionLabel = "查看全部",
            onAction = onOpenPlaylists,
            isEmpty = state.recent.isEmpty(),
            emptyHint = "还没有播放记录 —— 播一首就会出现在这里",
        ) {
            items(state.recent, key = { "${it.song.source.value}:${it.song.id}" }) { card ->
                RecentShelfCard(
                    card = card,
                    playing = "${card.song.source.value}:${card.song.id}" == state.playingKey,
                    isPlaying = state.isPlaying,
                    onClick = {
                        viewModel.play(card.song, state.recent.map { it.song })
                        onOpenPlayer()
                    },
                )
            }
        }

        ShelfSection(
            title = "我的歌单",
            badge = if (state.playlists.isEmpty()) null else "${state.playlists.size} 个",
            actionLabel = "全部",
            onAction = onOpenPlaylists,
            isEmpty = state.playlists.isEmpty(),
            emptyHint = "还没有歌单",
        ) {
            items(state.playlists, key = { it.id }) { card ->
                PlaylistShelfCard(card = card, onClick = { onOpenPlaylist(card.id) })
            }
        }

        ShelfSection(
            title = "常听歌手",
            badge = "按曲目数",
            isEmpty = state.topArtists.isEmpty(),
            emptyHint = "还没有统计 —— 听几首之后按曲目数排给你",
        ) {
            items(state.topArtists, key = { it.artist.singer }) { card ->
                ArtistShelfCard(
                    card = card,
                    onClick = { onOpenArtist(card.artist.singer) },
                    onLongClick = { iconTarget = card },
                )
            }
        }
    }

    // ── 长按歌手 → 自定义图标弹层 ──
    iconTarget?.let { target ->
        ArtistIconSheet(
            card = target,
            onLoadCovers = { onLoaded -> viewModel.loadArtistCoverCandidates(target.artistKey, onLoaded) },
            onPickCover = { url -> viewModel.setArtistIconFromCover(target.artistKey, url) },
            onPickLocalImage = {
                pendingImageArtistKey = target.artistKey
                pickArtistImage.launch(arrayOf("image/*"))
            },
            onClear = { viewModel.clearArtistIcon(target.artistKey) },
            onDismiss = { iconTarget = null },
        )
    }
}

// ─────────────────────────── 音乐平台下拉 ───────────────────────────

/**
 * 搜索框左侧的放大镜 = **音乐平台**多选下拉（批四重构，替代旧内联面板的
 * 「搜索平台（音源）」chips 块）。
 *
 * ⚠️ 措辞用「音乐平台」而非「音源」—— 用户 2026-10-10 明确：两者不是同一个东西；
 * 列表项是已装音源对外的平台名（与统计页「音乐平台占比」同一口径）。
 * 点选不收菜单（多选），点菜单外/返回键才收。
 */
@Composable
private fun PlatformPickerAnchor(
    platforms: List<PlatformChip>,
    onToggle: (String) -> Unit,
) {
    val colors = LXTheme.colors
    var open by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .height(41.dp)
            .clickable { open = true }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LxIcons.Search,
            contentDescription = "选择音乐平台",
            tint = colors.ink3,
            modifier = Modifier.size(15.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // 必须显式给 LX token：M3 默认色会游离出配色体系
            containerColor = colors.surface,
            shape = RoundedCornerShape(13.dp),
        ) {
            if (platforms.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "还没有可用的音乐平台",
                            style = LXType.labelSmall,
                            color = colors.ink3,
                        )
                    },
                    enabled = false,
                    onClick = {},
                )
            } else {
                Text(
                    text = "音乐平台 · 多选",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 2.dp),
                )
                HorizontalDivider(
                    color = colors.hairline,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                platforms.forEach { chip ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = chip.label,
                                style = LXType.labelLarge,
                                fontWeight = if (chip.selected) FontWeight.SemiBold else null,
                                color = if (chip.selected) colors.accent else colors.ink2,
                            )
                        },
                        trailingIcon = {
                            if (chip.selected) {
                                Icon(
                                    imageVector = LxIcons.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        },
                        onClick = { onToggle(chip.id) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────── 本地动作行 ───────────────────────────

/**
 * 本地动作行（仅本地模式）。
 *
 * ⚠️ 两张卡是**两个动作**，不是同一个入口的两个说法（权威稿 `#localActs` 的
 * `data-act="scan"` / `data-act="import"`）：
 * 扫描 = 直接开始全盘扫描；导入 = 拉起系统文件/目录选择器。
 */
@Composable
private fun LocalActionRow(onScan: () -> Unit, onImport: () -> Unit) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = scalars.sidePadding)
            .padding(top = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        LocalActionCard(
            icon = LxIcons.Playlist,
            title = "扫描文件夹",
            subtitle = "全盘搜索音频文件",
            onClick = onScan,
            modifier = Modifier.weight(1f),
        )
        LocalActionCard(
            icon = LxIcons.Download,
            title = "导入音乐",
            subtitle = "单曲 / 整张专辑",
            onClick = onImport,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LocalActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(LXTheme.pageSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(5.dp))
        Text(text = title, style = LXType.labelLarge, color = colors.ink1)
        Text(text = subtitle, style = LXType.labelSmall, color = colors.ink3)
    }
}

// ─────────────────────────── 货架 ───────────────────────────

@Composable
private fun ShelfSection(
    title: String,
    badge: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isEmpty: Boolean,
    emptyHint: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(modifier = Modifier.padding(top = scalars.sectionGap)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = scalars.sidePadding)
                .padding(bottom = 13.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = title, style = LXType.labelLarge, color = colors.ink1)
            badge?.let {
                Spacer(Modifier.width(7.dp))
                Text(text = it, style = LXType.labelSmall, color = colors.ink3)
            }
            Spacer(Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel,
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.clickable(onClick = onAction),
                )
            }
        }
        if (isEmpty && emptyHint != null) {
            Text(
                text = emptyHint,
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = scalars.sidePadding),
            )
        } else {
            // ⚠️ 横向留白交给 contentPadding：卡片要能滚到屏幕边缘（原型 `.shelf` 负 margin 同理）
            LazyRow(
                contentPadding = PaddingValues(horizontal = scalars.sidePadding),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RecentShelfCard(card: RecentCard, playing: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.width(134.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(134.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(LXTheme.pageInset),
        ) {
            AsyncArtwork(
                model = card.song.picUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 音源徽章（原型 `.badge`：最近播放卡左上角必须能看出在线/本地）
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface.copy(alpha = 0.78f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(text = card.sourceLabel, style = LXType.labelSmall, color = colors.ink1)
            }
            if (playing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(colors.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    EqBars(animate = isPlaying)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = card.song.name,
            style = LXType.labelLarge,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = card.song.singer,
            style = LXType.labelSmall,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaylistShelfCard(card: HomePlaylistCard, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.width(128.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(LXTheme.pageInset),
            contentAlignment = Alignment.Center,
        ) {
            if (card.coverUrl != null) {
                AsyncArtwork(
                    model = card.coverUrl,
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
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = card.name,
            style = LXType.labelLarge,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = "${card.itemCount} 首", style = LXType.labelSmall, color = colors.ink3)
    }
}

/**
 * 常听歌手。
 * ⚠️ 与原型的偏离：原型用「按名字生成的占位渐变」当头像，那会引入游离颜色（14 §5 禁止）；
 * 默认是 token 底色 + 首字，颜色全部来自配色 token；用户可长按换成自定义图标。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistShelfCard(card: HomeArtistCard, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = LXTheme.colors
    Column(
        modifier = Modifier
            .width(74.dp)
            // 点按进该歌手歌曲列表；长按进「自定义图标」
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(LXTheme.pageInset),
            contentAlignment = Alignment.Center,
        ) {
            if (card.iconValue != null) {
                // 自定义图标：网络封面 url / 本地 content uri，Coil 都能渲染
                AsyncArtwork(
                    model = card.iconValue,
                    contentDescription = card.artist.singer,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = card.artist.singer.take(1), style = LXType.titleLarge, color = colors.ink2)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = card.artist.singer,
            style = LXType.labelSmall,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${card.artist.trackCount} 首",
            style = LXType.labelSmall,
            color = colors.ink3,
            maxLines = 1,
        )
    }
}

// ─────────────────────────── 歌手图标自定义弹层 ───────────────────────────

private enum class ArtistIconPanel { MENU, COVERS }

/**
 * 常听歌手「自定义图标」弹层 —— 沿用歌曲操作弹层（`LXSongActionSheet`）的
 * ModalBottomSheet 语言：标题条 + 动作行 + 二级面板（封面挑选）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ArtistIconSheet(
    card: HomeArtistCard,
    onLoadCovers: (onLoaded: (List<String>) -> Unit) -> Unit,
    onPickCover: (String) -> Unit,
    onPickLocalImage: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LXTheme.colors
    var panel by remember { mutableStateOf(ArtistIconPanel.MENU) }
    // null = 还没拉取；空表 = 播放记录里没有它的封面
    var covers by remember { mutableStateOf<List<String>?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (panel == ArtistIconPanel.COVERS && covers == null) {
        LaunchedEffect(Unit) { onLoadCovers { covers = it } }
    }

    /** 立即收起并执行（与歌曲操作弹层同一约定） */
    fun finish(action: () -> Unit) {
        action()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        scrimColor = colors.ink1.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 9.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.hairline),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (panel) {
                ArtistIconPanel.MENU -> {
                    ArtistSheetTitle(
                        title = "自定义图标 · ${card.artist.singer}",
                        subtitle = if (card.iconValue == null) "当前是默认样式（底色 + 首字）" else "当前是自定义图标",
                    )
                    ArtistSheetAction(
                        icon = LxIcons.CoverBox,
                        label = "用播放记录里的封面",
                        hint = "从听过的歌里挑一张",
                        onClick = { panel = ArtistIconPanel.COVERS },
                    )
                    ArtistSheetAction(
                        icon = LxIcons.Folder,
                        label = "选本地图片",
                        hint = "相册或文件里的图",
                        onClick = { finish(onPickLocalImage) },
                    )
                    if (card.iconValue != null) {
                        ArtistSheetAction(
                            icon = LxIcons.Trash,
                            label = "恢复默认",
                            hint = "回到底色 + 首字",
                            tint = colors.danger,
                            onClick = { finish(onClear) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                ArtistIconPanel.COVERS -> {
                    ArtistSheetTitle(
                        title = "播放记录里的封面 · ${card.artist.singer}",
                        subtitle = "最近听过的排在前面",
                    )
                    val picked = covers
                    when {
                        picked == null -> ArtistSheetHint("读取中…")
                        picked.isEmpty() -> ArtistSheetHint("播放记录里还没有它的封面")
                        else -> FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = ARTIST_COVERS_MAX)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            picked.forEach { url ->
                                AsyncArtwork(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(LXTheme.pageInset)
                                        .clickable { finish { onPickCover(url) } },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ArtistSheetTitle(title: String, subtitle: String) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp)) {
        Text(
            text = title,
            style = LXType.labelLarge,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = LXType.labelSmall,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
}

@Composable
private fun ArtistSheetAction(
    icon: ImageVector,
    label: String,
    hint: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: colors.ink2,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = LXType.labelLarge, color = tint ?: colors.ink1)
            Spacer(Modifier.height(3.dp))
            Text(text = hint, style = LXType.labelSmall, color = colors.ink3)
        }
    }
}

@Composable
private fun ArtistSheetHint(text: String) {
    Text(
        text = text,
        style = LXType.bodyMedium,
        color = LXTheme.colors.ink3,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** 播放中的三根均衡条（16 §5：820ms 循环；暂停时保持形态，不归零） */
@Composable
private fun EqBars(animate: Boolean) {
    val colors = LXTheme.colors
    val transition = rememberInfiniteTransition(label = "shelfEq")
    val heights = listOf(5.dp, 10.dp, 7.dp)
    val scales = heights.indices.map { index ->
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 820,
                    delayMillis = index * 170,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "shelfEq$index",
        ).value
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(13.dp),
    ) {
        heights.forEachIndexed { index, h ->
            Box(
                Modifier
                    .width(2.dp)
                    .height(h)
                    .graphicsLayer {
                        // 暂停时保持形态（原型 `.eq.paused`）
                        scaleY = if (animate) scales[index] else 1f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(colors.ink1, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** 本地动作行淡入/淡出时长（沿用原型 `.spanel` 的 340ms 档） */
private const val PANEL_MS = 340

/** 封面挑选面板的限高（与歌曲操作弹层 `SHEET_BODY_MAX` 同思路，网格稍矮一点） */
private val ARTIST_COVERS_MAX = 320.dp