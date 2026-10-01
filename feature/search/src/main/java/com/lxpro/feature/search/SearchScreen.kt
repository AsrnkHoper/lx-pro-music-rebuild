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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXPillChip
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.SearchMode
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 搜索页（双模式）。
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
    onSongClick: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 触底加载下一页（本地模式没有分页，hasMore 恒为 false）
    LaunchedEffect(listState, state.results.size, state.hasMore, state.loadingMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (state.hasMore && !state.loadingMore && lastVisible >= state.results.size - 3) {
                    viewModel.loadMore()
                }
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .padding(horizontal = scalars.sidePadding),
    ) {
        Spacer(Modifier.height(scalars.sectionGap))

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.onKeywordChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = LXType.bodyLarge,
            shape = RoundedCornerShape(16.dp),
            placeholder = {
                Text(
                    text = if (mode == SearchMode.LOCAL) {
                        "搜索本地曲库"
                    } else {
                        "搜索歌曲 / 视频（如 luvsicpt3）"
                    },
                    style = LXType.bodyMedium,
                    color = colors.ink3,
                )
            },
            // ⚠️ 必须显式取 LX token，不能吃 M3 默认色（否则在部分配色下字看不清）
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.ink1,
                unfocusedTextColor = colors.ink1,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                cursorColor = colors.accent,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.hairline,
                focusedPlaceholderColor = colors.ink3,
                unfocusedPlaceholderColor = colors.ink3,
            ),
        )

        // 模式入口：紧贴搜索框下方（15 §4.2 #2）
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LXPillChip(
                label = "在线音源",
                selected = mode == SearchMode.ONLINE,
                onClick = { viewModel.onModeChange(SearchMode.ONLINE) },
            )
            LXPillChip(
                label = "本地音乐",
                selected = mode == SearchMode.LOCAL,
                onClick = { viewModel.onModeChange(SearchMode.LOCAL) },
            )
        }

        Spacer(Modifier.height(scalars.sectionGap))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }

            state.error != null && state.results.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error.orEmpty(),
                    style = LXType.bodyMedium,
                    color = colors.ink2,
                )
            }

            !state.searched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词开始搜索", style = LXType.bodyMedium, color = colors.ink3)
            }

            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = scalars.sectionGap),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    items = state.results,
                    key = { "${it.song.source.value}:${it.song.id}" },
                    contentType = { "song" },
                ) { item ->
                    // 播放队列 = 当前搜索结果，点哪首就从哪首开始
                    SongRow(
                        item = item,
                        onClick = { onSongClick(item.song, state.results.map { it.song }) },
                    )
                }
                if (state.loadingMore) {
                    item(key = "loading-more", contentType = "footer") {
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

@Composable
private fun SongRow(item: SearchResultItem, onClick: () -> Unit) {
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
        // 封面：在线走 Coil，本地走缩略图通道（AsyncArtwork 内部区分）
        AsyncArtwork(
            model = song.picUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
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
    }
}

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)