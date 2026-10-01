package com.lxpro.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song

/**
 * 搜索页（M1 版本：在线音源单模式；双模式与类型筛选留到 M2/M3）。
 */
@Composable
fun SearchScreen(
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .padding(horizontal = scalars.sidePadding),
    ) {
        Spacer(Modifier.height(scalars.sectionGap))

        OutlinedTextField(
            value = state.keyword,
            onValueChange = viewModel::onKeywordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索歌曲 / 视频（如 luvsicpt3）", style = LXType.bodyMedium) },
        )

        Spacer(Modifier.height(scalars.sectionGap))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error.orEmpty(), style = LXType.bodyMedium, color = colors.ink2)
            }

            state.isInitial -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词开始搜索", style = LXType.bodyMedium, color = colors.ink3)
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = scalars.sectionGap),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = state.results, key = { "${it.source.value}:${it.id}" }, contentType = { "song" }) { song ->
                    SongRow(song = song, onClick = { onSongClick(song) })
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.inset, RoundedCornerShape(8.dp)),
        )
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
                    append("  ·  小哔音乐")
                },
                style = LXType.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)