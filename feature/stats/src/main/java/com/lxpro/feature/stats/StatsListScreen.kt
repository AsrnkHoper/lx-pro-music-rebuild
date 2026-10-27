package com.lxpro.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXViewToggle
import com.lxpro.core.designsystem.component.LXViewToggleGroup
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song
import com.lxpro.core.model.DataSaver
import com.lxpro.core.stats.StatsCalculator
import com.lxpro.core.stats.StatsListEntry

/**
 * 统计榜单列表页（歌手 / 专辑 结构同源）。
 *
 * 从「最常听」卡片或「歌手排行」行点进来 —— 用户 2026-10-03 要求榜单要能点进列表页。
 * 每行点一下即起播（队列 = 整个列表）。
 */
@Composable
fun StatsListScreen(
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    var withCover by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding(),
    ) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.scope.title} · ${state.key}",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.entries.size} 首 · 按收听次数",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
            // 有图 / 无图：与「我的歌单」同一套图标式切换
            LXViewToggleGroup {
                LXViewToggle(LxIcons.CoverBox, "有图模式", withCover) { withCover = true }
                LXViewToggle(LxIcons.ViewList, "无图模式", !withCover) { withCover = false }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = scalars.sidePadding,
                end = scalars.sidePadding,
                top = 8.dp,
                bottom = scalars.sectionGap,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(state.entries, key = { _, entry -> "${entry.song.source.value}:${entry.song.id}" }) { _, entry ->
                StatsSongRow(
                    entry = entry,
                    // 省流「全无图」：强制无图（不改用户自己的选择记忆）（#128）
                    withCover = withCover && !DataSaver.imagesBlockedFlow.collectAsState().value,
                    onClick = {
                        onPlaySong(entry.song, state.entries.map { it.song })
                    },
                )
            }
        }
    }
}

@Composable
private fun StatsSongRow(
    entry: StatsListEntry,
    withCover: Boolean,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LXDimens.radiusSmall))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withCover) {
            Box(
                modifier = Modifier
                    .size(LXDimens.coverList)
                    .clip(RoundedCornerShape(LXDimens.radiusSmall))
                    .background(LXTheme.pageInset),
            ) {
                AsyncArtwork(
                    model = entry.song.picUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.song.name,
                style = LXType.titleSmall,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.song.singer,
                style = LXType.labelLarge,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${entry.plays} 次 · ${StatsCalculator.formatHours(entry.durationMs)}",
            style = LXType.labelSmall,
            color = colors.ink3,
            fontWeight = FontWeight.Normal,
        )
    }
}