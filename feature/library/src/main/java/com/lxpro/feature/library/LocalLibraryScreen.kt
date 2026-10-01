package com.lxpro.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXSectionHeader
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song

/**
 * 本地音乐页（M2 双模式支柱之一：扫描 / 导入 / 索引）。
 *
 * ⚠️ 本地音乐是 **LX-Music 与 LX-Pro 都没有的能力**（12 §3），不是可选项。
 */
@Composable
fun LocalLibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val context = LocalContext.current

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        @Suppress("DEPRECATION")
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.scan() else viewModel.reportPermissionDenied()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .padding(horizontal = scalars.sidePadding),
    ) {
        Spacer(Modifier.height(scalars.sectionGap))

        LXSectionHeader(title = "本地音乐")

        LXCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(LXDimens.space16)) {
                Text(
                    text = "已索引 ${state.indexedCount} 首",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.lastOutcome?.let { "上次扫描到 ${it.found} 首" }
                        ?: "还没有扫描过本机音乐",
                    style = LXType.labelLarge,
                    color = colors.ink2,
                )
                state.error?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(text = message, style = LXType.labelLarge, color = colors.accent)
                }
                Spacer(Modifier.height(LXDimens.space12))
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, audioPermission) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) viewModel.scan() else permissionLauncher.launch(audioPermission)
                    },
                    enabled = !state.scanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.bg,
                    ),
                ) {
                    if (state.scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = colors.bg,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = if (state.scanning) "扫描中…" else "扫描本机音乐",
                        style = LXType.labelLarge,
                    )
                }
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "扫描后这里会列出本机曲目",
                    style = LXType.bodyMedium,
                    color = colors.ink3,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = scalars.sectionGap),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    items = tracks,
                    key = { "${it.source.value}:${it.id}" },
                    contentType = { "local-song" },
                ) { song ->
                    LocalTrackRow(
                        song = song,
                        onClick = { onSongClick(song, tracks) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRow(song: Song, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
        Text(text = "本地", style = LXType.labelSmall, color = colors.ink3)
    }
}

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)