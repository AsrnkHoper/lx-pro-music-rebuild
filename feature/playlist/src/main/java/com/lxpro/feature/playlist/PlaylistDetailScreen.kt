package com.lxpro.feature.playlist

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXStaggerItem
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song

/**
 * 歌单详情页 —— 两级跳转的第二级。
 * 本页**不显示底部 Tab**（14 §4.1：详情页全屏沉浸），返回靠左上的返回键。
 */
@Composable
fun PlaylistDetailScreen(
    state: PlaylistDetailUiState,
    onBack: () -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRemoveSong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .padding(horizontal = scalars.sidePadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(LxIcons.Back, contentDescription = "返回", tint = colors.ink1)
            }
        }

        Text(
            text = state.name,
            style = LXType.headlineSmall,
            color = colors.ink1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${state.songs.size} 首",
            style = LXType.labelLarge,
            color = colors.ink3,
        )

        Spacer(Modifier.height(LXDimens.space12))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onPlayAll(state.songs) },
                enabled = state.songs.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.bg,
                ),
            ) {
                Text(text = "播放全部", style = LXType.labelLarge)
            }

            Spacer(Modifier.weight(1f))

            // 系统歌单（收藏）不可改名/删除
            if (!state.isSystem) {
                TextButton(onClick = { showRenameDialog = true }) {
                    Text(text = "重命名", style = LXType.labelLarge, color = colors.accent)
                }
                TextButton(onClick = { showDeleteDialog = true }) {
                    Text(text = "删除", style = LXType.labelLarge, color = colors.accent)
                }
            }
        }

        Spacer(Modifier.height(LXDimens.space12))

        if (state.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "这个歌单还是空的",
                    style = LXType.bodyMedium,
                    color = colors.ink3,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = scalars.sectionGap),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = state.songs,
                    key = { _, song -> "${song.source.value}:${song.id}" },
                    contentType = { _, _ -> "playlist-song" },
                ) { index, song ->
                    LXStaggerItem(index = index) {
                        PlaylistSongRow(
                            song = song,
                            sourceLabel = state.sourceNames[song.source.value] ?: song.source.value,
                            onClick = { onSongClick(song, state.songs) },
                            onRemove = { onRemoveSong(song) },
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        PlaylistNameDialog(
            title = "重命名歌单",
            initialValue = state.name,
            confirmLabel = "保存",
            onDismiss = { showRenameDialog = false },
            onConfirm = { name ->
                onRename(name)
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
                    text = "「${state.name}」及其中的 ${state.songs.size} 首歌都会从这个歌单里移除。",
                    style = LXType.bodyMedium,
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text(text = "删除", style = LXType.labelLarge, color = colors.accent)
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

@Composable
private fun PlaylistSongRow(
    song: Song,
    sourceLabel: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LXTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncArtwork(
            model = song.picUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(LXDimens.coverList)
                .clip(RoundedCornerShape(LXDimens.radiusSmall)),
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
                text = song.singer,
                style = LXType.bodyMedium,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 来源标记（15 §5 强制）
        Text(text = sourceLabel, style = LXType.labelSmall, color = colors.ink3)
        TextButton(onClick = onRemove) {
            Text(text = "移除", style = LXType.labelSmall, color = colors.accent)
        }
    }
}