package com.lxpro.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.model.Song

/**
 * 弹层入场（16 §4）：面板 `sheetEnter`(300ms) 自底部滑入，遮罩 `scrimFade`(260ms) 淡入。
 * 返回 (遮罩透明度 0→1, 面板滑入进度 0→1)；面板按自身高度做 translationY，无需测量。
 */
@Composable
private fun rememberSheetEnter(): Pair<Float, Float> {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val mask by animateFloatAsState(if (entered) 1f else 0f, tween(LXMotion.scrimFade), label = "sheetMaskIn")
    val slide by animateFloatAsState(if (entered) 1f else 0f, tween(LXMotion.sheetEnter), label = "sheetSlideIn")
    return mask to slide
}

/**
 * 立体歌词参数面板（权威稿 `showLyric3dOpts`，3510-3537 行）。
 *
 * ⚠️ 两条硬性规格（14 §4.4:183-186）：
 * - 面板**半高 48%**，上面留出的歌词区就是预览 —— 所以遮罩必须是 `light`（不能把歌词压暗），
 *   而且当前句的居中基准会上移到没被挡住的那块区域（见 `LyricsView` 的 `ratio`）
 * - 参数**即时生效**，改任何一项都整轮重排
 */
@Composable
fun Lyric3dSheet(
    pageState: PlayerPageState,
    onDismiss: () -> Unit,
    onToggle3d: () -> Unit,
    onToggleTilt: () -> Unit,
    onAlignChange: (LyricAlign) -> Unit,
    onParamChange: (P3dParam, Float) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val (maskIn, slideIn) = rememberSheetEnter()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // `.pmask.light`：边调边看，遮罩不能压暗上面的歌词
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = maskIn }
                .background(Color.Black.copy(alpha = 0.08f))
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .graphicsLayer { translationY = (1f - slideIn) * size.height }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.surface)
                .padding(horizontal = scalars.sidePadding),
        ) {
            // 抓手下巴
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.hairline)
                    .align(Alignment.CenterHorizontally),
            )

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(text = "歌词参数", style = LXType.titleSmall, color = colors.ink1)
                Text(
                    text = "即时生效 · 全部可恢复默认 · 「通用」组平面与立体两档一起变",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )

                SwitchRow(
                    title = "立体歌词",
                    subtitle = "总开关；关掉＝回到平面四态淡化",
                    checked = pageState.lyric3d,
                    onToggle = onToggle3d,
                )

                // 歌词对齐（三档分段控件）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "歌词对齐", style = LXType.labelLarge, color = colors.ink1)
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.inset)
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LyricAlign.entries.forEach { item ->
                            val on = item == pageState.align
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (on) colors.surface else Color.Transparent)
                                    .clickable { onAlignChange(item) }
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = item.label,
                                    style = LXType.labelSmall,
                                    color = if (on) colors.ink1 else colors.ink3,
                                )
                            }
                        }
                    }
                }

                SwitchRow(
                    title = "启用 3D 倾斜",
                    subtitle = "关掉后不旋转，纵深的亮度分层与模糊保留",
                    checked = pageState.params.tiltOn,
                    onToggle = onToggleTilt,
                )

                // 两组滑块：通用（平面与立体一起变）/ 立体档专用（2026-10-24 用户要求的分组方式）
                listOf(true to "通用 · 平面与立体一起变", false to "立体歌词专用").forEach { (wantShared, title) ->
                    val paramsInGroup = P3dParam.entries.filter { it.shared == wantShared }
                    Text(
                        text = buildString {
                            append(title)
                            if (!wantShared && !pageState.lyric3d) append(" · 拖任意一项会自动开启立体")
                        },
                        style = LXType.labelSmall,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                    )
                    paramsInGroup.forEach { param ->
                        ParamRow(
                            param = param,
                            value = param.value(pageState.params),
                            onChange = { onParamChange(param, it) },
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 4.dp)
                        .height(1.dp)
                        .background(colors.hairline),
                )
                Text(
                    text = "恢复默认",
                    style = LXType.labelLarge,
                    color = colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onReset)
                        .padding(vertical = 12.dp),
                )
                Text(
                    text = "改任何一项都会立刻整轮重排：板宽 / 透视 / 消失点 / 上下缓冲 / 字号 / 景深 / 居中位置。",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ParamRow(param: P3dParam, value: Float, onChange: (Float) -> Unit) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (param.shared) "${param.label}（共用）" else param.label,
                style = LXType.labelSmall,
                color = colors.ink2,
            )
            Spacer(Modifier.weight(1f))
            Text(text = param.format(value), style = LXType.labelSmall, color = colors.ink3)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = param.min..param.max,
            // steps = 中间刻度数（起点与终点不算）
            steps = (((param.max - param.min) / param.step).toInt() - 1).coerceIn(0, 60),
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.inset,
            ),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = LXType.labelLarge, color = colors.ink1)
            Text(text = subtitle, style = LXType.labelSmall, color = colors.ink3)
        }
        // `.switch`：40×24 的胶囊开关
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 24.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (checked) colors.accent else colors.inset)
                .clickable(onClick = onToggle),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.surface),
            )
        }
    }
}

/**
 * 播放队列（原型 `#btnQueue` 打开的半高弹层）。
 * 点某一行 → 播那一首；当前播放的那首高亮。
 */
@Composable
fun QueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onPlayAt: (Int) -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val (maskIn, slideIn) = rememberSheetEnter()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = maskIn }
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .graphicsLayer { translationY = (1f - slideIn) * size.height }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scalars.sidePadding)
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "播放队列", style = LXType.titleSmall, color = colors.ink1)
                Spacer(Modifier.weight(1f))
                Text(text = "${queue.size} 首", style = LXType.labelSmall, color = colors.ink3)
            }
            if (queue.isEmpty()) {
                Text(
                    text = "队列还是空的",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = scalars.sidePadding, vertical = 20.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    // 打开时直接定位到正在播放的那一首（不用自己翻）
                    state = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0),
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = scalars.sidePadding,
                        end = scalars.sidePadding,
                        bottom = 20.dp,
                    ),
                ) {
                    itemsIndexed(items = queue, key = { index, song -> "$index:${song.source.value}:${song.id}" }) { index, song ->
                        val playing = index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlayAt(index) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                modifier = Modifier.width(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.name,
                                    style = LXType.titleSmall,
                                    color = if (playing) colors.accent else colors.ink1,
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
                            Text(
                                text = sourceLabelOf(song.source.value),
                                style = LXType.labelSmall,
                                color = colors.ink3,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── 评论 ───────────────────────────

/**
 * 评论弹层（批十九）：最新 / 热门双 tab + 分页加载。
 */
@Composable
fun CommentSheet(
    state: PlayerPageViewModel.CommentState,
    onDismiss: () -> Unit,
    onTabChange: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val (maskIn, slideIn) = rememberSheetEnter()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = maskIn }
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .graphicsLayer { translationY = (1f - slideIn) * size.height }
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = scalars.sidePadding)
                    .padding(top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "评论", style = LXType.titleSmall, color = colors.ink1)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "最新",
                        style = LXType.labelLarge,
                        color = if (!state.isHot) colors.accent else colors.ink3,
                        modifier = Modifier.clickable { onTabChange(false) },
                    )
                    Text(
                        text = "热门",
                        style = LXType.labelLarge,
                        color = if (state.isHot) colors.accent else colors.ink3,
                        modifier = Modifier.clickable { onTabChange(true) },
                    )
                }
            }

            if (state.error != null) {
                Text(
                    text = state.error,
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(20.dp),
                )
            } else if (state.loading && state.comments.isEmpty()) {
                Text(
                    text = "加载中…",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(20.dp),
                )
            } else if (state.comments.isEmpty()) {
                Text(
                    text = "暂无评论",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = scalars.sidePadding, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(state.comments) { _, comment ->
                        CommentItem(comment)
                    }
                    if (state.hasMore) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (state.loading) "加载中…" else "点击加载更多",
                                    style = LXType.labelLarge,
                                    color = colors.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(onClick = onLoadMore)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
private fun CommentItem(
    comment: com.lxpro.core.model.CommentInfo,
) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (comment.avatar != null) {
                com.lxpro.core.designsystem.component.AsyncArtwork(
                    model = comment.avatar,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(50)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = comment.userName,
                    style = LXType.labelSmall,
                    color = colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = comment.text,
                    style = LXType.bodySmall,
                    color = colors.ink1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    comment.timeStr?.let {
                        Text(it, style = LXType.labelSmall, color = colors.ink3)
                    }
                    comment.likedCount?.let {
                        Text("👍 $it", style = LXType.labelSmall, color = colors.ink3)
                    }
                    comment.replyNum?.let {
                        Text("💬 $it", style = LXType.labelSmall, color = colors.ink3)
                    }
                }
            }
        }
        if (comment.reply.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .padding(start = 42.dp, top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inset)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                comment.reply.forEach { reply ->
                    Text(
                        text = "@${reply.userName}：${reply.text}",
                        style = LXType.labelSmall,
                        color = colors.ink2,
                    )
                }
            }
        }
    }
}

