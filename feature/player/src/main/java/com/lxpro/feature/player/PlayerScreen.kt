package com.lxpro.feature.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.common.LyricLine
import com.lxpro.core.common.LyricParser
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.media.PlayerState

/**
 * 播放页。
 * M2 版本：封面 + 信息 + **逐行歌词（与进度同步、自动滚动并高亮当前行）** + 进度 + 三键。
 * ⚠️ 正式的「封面 ⇄ 歌词双模式、四态歌词、点句跳转、立体歌词档」按 `mockup_player.html`
 * 与 14 §4.4 在 M3 落地。
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
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    // 拖动中的进度用本地状态，避免拖动时被 500ms 轮询覆盖
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val displayPosition = if (dragging) (dragValue * duration).toLong() else state.positionMs
    val sliderValue = (displayPosition.toFloat() / duration).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = scalars.sidePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(LxIcons.Back, contentDescription = "返回", tint = colors.ink1)
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.70f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.inset),
            contentAlignment = Alignment.Center,
        ) {
            AsyncArtwork(
                model = state.current?.picUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.isBuffering) {
                CircularProgressIndicator(color = colors.accent)
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))

        Text(
            text = state.current?.name ?: "未在播放",
            style = LXType.titleLarge,
            color = colors.ink1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.current?.singer.orEmpty(),
            style = LXType.bodyMedium,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        state.error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = LXType.labelLarge,
                color = colors.accent,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(scalars.sectionGap))

        LyricPanel(
            lyrics = lyrics,
            positionMs = displayPosition,
            modifier = Modifier
                .fillMaxWidth()
                .height(LYRIC_PANEL_HEIGHT),
        )

        Spacer(Modifier.height(scalars.sectionGap))

        Slider(
            value = sliderValue,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.inset,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(displayPosition), style = LXType.labelSmall, color = colors.ink3)
            Text(formatMs(state.durationMs), style = LXType.labelSmall, color = colors.ink3)
        }

        Spacer(Modifier.height(scalars.sectionGap))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
                Icon(LxIcons.Previous, contentDescription = "上一首", tint = colors.ink1, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.size(20.dp))
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp)) {
                Icon(
                    imageVector = if (state.isPlaying) LxIcons.Pause else LxIcons.Play,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = colors.accent,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.size(20.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
                Icon(LxIcons.Next, contentDescription = "下一首", tint = colors.ink1, modifier = Modifier.size(32.dp))
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))
    }
}

/**
 * 逐行歌词：当前行高亮并自动滚动，上下行降为辅助色。
 *
 * ⚠️ 固定高度 + 自带滚动，嵌在外层 `verticalScroll` 里是必要的——
 * 否则 LazyColumn 会拿到无限高度约束而崩溃。
 */
@Composable
private fun LyricPanel(
    lyrics: LyricsUiState,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val listState = rememberLazyListState()

    val currentIndex = remember(lyrics.lines, positionMs) {
        LyricParser.indexAt(lyrics.lines, positionMs)
    }

    // 只在高亮行变化时滚动，避免每 500ms 的进度回调都触发一次动画
    LaunchedEffect(currentIndex, lyrics.lines) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            lyrics.loading -> CircularProgressIndicator(color = colors.accent)

            lyrics.lines.isEmpty() -> Text(
                text = lyrics.message ?: "暂无歌词",
                style = LXType.bodyMedium,
                color = colors.ink3,
                textAlign = TextAlign.Center,
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(
                    items = lyrics.lines,
                    key = { index, line -> "${line.timeMs}:$index" },
                ) { index, line ->
                    LyricLineItem(
                        line = line,
                        isCurrent = index == currentIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricLineItem(line: LyricLine, isCurrent: Boolean) {
    val colors = LXTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line.text,
            style = if (isCurrent) LXType.titleSmall else LXType.bodyMedium,
            color = if (isCurrent) colors.accent else colors.ink3,
            textAlign = TextAlign.Center,
        )
        line.translation?.let { translation ->
            Text(
                text = translation,
                style = LXType.labelSmall,
                color = if (isCurrent) colors.ink2 else colors.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 歌词面板固定高度：够显示 4–5 行，又不至于把控制区挤出屏幕 */
private val LYRIC_PANEL_HEIGHT = 150.dp

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}