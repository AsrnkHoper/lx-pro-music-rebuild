package com.lxpro.feature.library

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
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
 * 两个入口：
 * - **扫描本机音乐**：走 MediaStore（系统媒体库），覆盖主流场景
 * - **添加目录**：走 SAF（`ACTION_OPEN_DOCUMENT_TREE`），补媒体库扫不到的目录
 */
@Composable
fun LocalLibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val roots by viewModel.safRoots.collectAsStateWithLifecycle()
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

    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        // ⚠️ 不 takePersistableUriPermission 的话，重启后权限失效、下次扫描直接抛 SecurityException
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment
            ?: "已授权目录"
        viewModel.addSafRoot(treeUri.toString(), displayName)
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
                    text = state.lastOutcome?.let { outcome ->
                        val base = "上次扫描：媒体库 ${outcome.mediaStoreFound} 首" +
                            " · 授权目录 ${outcome.safFound} 首"
                        if (outcome.failedRoots > 0) {
                            "$base（${outcome.failedRoots} 个目录读取失败，其索引已保留）"
                        } else {
                            base
                        }
                    } ?: "还没有扫描过本机音乐",
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

        LXSectionHeader(title = "授权目录")
        LXCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(LXDimens.space16)) {
                if (roots.isEmpty()) {
                    Text(
                        text = "还没有授权任何目录",
                        style = LXType.labelLarge,
                        color = colors.ink2,
                    )
                } else {
                    roots.forEach { root ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = root.displayName,
                                style = LXType.labelLarge,
                                color = colors.ink1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.removeSafRoot(root.treeUri) }) {
                                Text(text = "移除", style = LXType.labelLarge, color = colors.accent)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(LXDimens.space8))
                TextButton(
                    onClick = { treeLauncher.launch(null) },
                    enabled = !state.scanning,
                ) {
                    Text(text = "添加目录", style = LXType.labelLarge, color = colors.accent)
                }
                Text(
                    text = "系统媒体库扫不到的音乐（例如某些 App 私有目录、刚拷进去还没被系统索引的文件）可以用这里补。",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
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
        Text(text = "本地", style = LXType.labelSmall, color = colors.ink3)
    }
}

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)