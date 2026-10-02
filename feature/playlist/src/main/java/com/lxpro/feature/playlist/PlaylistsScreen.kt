package com.lxpro.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

/**
 * 歌单网格页 —— **两级跳转的第一级**（15 §3：点具体歌单后进详情，不做同屏）。
 *
 * ⚠️ 同屏「列表 + 歌曲」在歌多时必然胀满（真实备份里最大一个歌单 213 首），所以拆成两级。
 */
@Composable
fun PlaylistsScreen(
    state: PlaylistsUiState,
    onQueryChange: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .padding(horizontal = scalars.sidePadding),
    ) {
        Spacer(Modifier.height(scalars.sectionGap))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "我的歌单",
                style = LXType.headlineLarge,
                color = colors.ink1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showCreateDialog = true }) {
                Text(text = "新建", style = LXType.labelLarge, color = colors.accent)
            }
        }

        Spacer(Modifier.height(LXDimens.space12))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(text = "在歌单里找…", style = LXType.bodyMedium, color = colors.ink3)
            },
            singleLine = true,
            shape = RoundedCornerShape(LXDimens.radiusMedium),
            modifier = Modifier.fillMaxWidth(),
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

        Spacer(Modifier.height(scalars.sectionGap))

        if (state.playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.query.isBlank()) "还没有歌单，点右上角「新建」" else "没有匹配的歌单",
                    style = LXType.bodyMedium,
                    color = colors.ink3,
                )
            }
        } else {
            // 双列网格，间距 14dp（14 §2）；封面 aspect 1:1
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_GAP),
                contentPadding = PaddingValues(bottom = scalars.sectionGap),
            ) {
                itemsIndexed(
                    items = state.playlists,
                    key = { _, card -> card.id },
                    contentType = { _, _ -> "playlist-card" },
                ) { index, card ->
                    LXStaggerItem(index = index) {
                        PlaylistGridItem(card = card, onClick = { onOpenPlaylist(card.id) })
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
}

@Composable
private fun PlaylistGridItem(card: PlaylistCard, onClick: () -> Unit) {
    val colors = LXTheme.colors

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(LXDimens.radiusMedium))
                .background(colors.inset),
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
        Spacer(Modifier.height(LXDimens.space8))
        Text(
            text = card.name,
            style = LXType.titleSmall,
            color = colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${card.itemCount} 首",
            style = LXType.labelSmall,
            color = colors.ink3,
        )
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

private val GRID_GAP = 14.dp