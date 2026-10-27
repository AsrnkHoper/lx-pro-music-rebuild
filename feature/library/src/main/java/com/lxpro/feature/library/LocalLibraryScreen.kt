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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.lxpro.core.designsystem.component.LXViewToggle
import com.lxpro.core.designsystem.component.LXViewToggleGroup
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song

/** 首页两张卡带过来的动作（权威稿 `data-act` 是两个不同动作） */
object LocalLibraryAction {
    const val SCAN = "scan"
    const val IMPORT = "import"
}

/** 路由参数名，与 `Routes.LIBRARY` 的占位符一致 */
const val ARG_LIBRARY_ACTION = "action"

private fun hasAudioPermission(context: android.content.Context, permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * 本地音乐。
 *
 * 两个入口：
 * - **扫描本机音乐**：走 MediaStore（系统媒体库），覆盖主流场景
 * - **添加目录**：走 SAF（`ACTION_OPEN_DOCUMENT_TREE`），补媒体库扫不到的目录
 */
@Composable
fun LocalLibraryScreen(
    onSongClick: (Song, List<Song>) -> Unit,
    modifier: Modifier = Modifier,
    initialAction: String = "",
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val roots by viewModel.safRoots.collectAsStateWithLifecycle()
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val context = LocalContext.current
    // 曲目列表的「有图 / 无图」——与「我的歌单」同一套图标式切换（用户 2026-10-03 要求全站统一）。
    // 默认值跟随设置；本页切换只覆盖本次会话（不反向改设置）
    val defaultShowCover by viewModel.showCover.collectAsStateWithLifecycle()
    var coverOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val withCover = coverOverride ?: defaultShowCover

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

    // 从首页两张卡进来时各执行各的动作（权威稿 `data-act="scan"` / `"import"` 是两个动作）
    LaunchedEffect(initialAction) {
        when (initialAction) {
            LocalLibraryAction.SCAN -> if (hasAudioPermission(context, audioPermission)) {
                viewModel.scan()
            } else {
                permissionLauncher.launch(audioPermission)
            }

            LocalLibraryAction.IMPORT -> treeLauncher.launch(null)
            else -> Unit
        }
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
                    // 用列表长度而不是「数据库行数」：行数含同一首歌的两条通道记录，会虚高
                    text = "已索引 ${tracks.size} 首",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.lastOutcome?.let { outcome ->
                        val base = "上次扫描：媒体库 ${outcome.mediaStoreFound} 首" +
                            " · 授权目录 ${outcome.safFound} 首"
                        // 「为什么不全」要给得出解释，而不是让用户自己猜
                        val notes = buildList {
                            if (outcome.skippedAsDuplicate > 0) {
                                add("跳过 ${outcome.skippedAsDuplicate} 首与媒体库重复的")
                            }
                            if (outcome.droppedAsTooShort > 0) {
                                add("跳过 ${outcome.droppedAsTooShort} 首短于 10 秒的")
                            }
                            if (outcome.droppedAsNonMusic > 0) {
                                add("跳过 ${outcome.droppedAsNonMusic} 首系统提示音/铃声")
                            }
                            if (outcome.failedRoots > 0) {
                                add("${outcome.failedRoots} 个目录读取失败（索引已保留）")
                            }
                        }
                        if (notes.isEmpty()) base else "$base（${notes.joinToString("，")}）"
                    } ?: "还没有扫描过本机音乐",
                    style = LXType.labelLarge,
                    color = colors.ink2,
                )
                Text(
                    text = "系统媒体库只收录共享存储（/sdcard 下 Music、Download 等）里的音频；" +
                        "App 私有目录 Android/data 里的文件任何播放器都读不到——那是系统的隔离，不是本 App 的权限问题。",
                    style = LXType.labelSmall,
                    color = colors.ink3,
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
                    text = "系统不允许授权「Download 根目录」「Android/data」这类位置（选择器里会置灰），" +
                        "这是 Android 11+ 的平台限制。要收录它们里面的音乐，请授权其子目录，" +
                        "或先把歌曲移到 Music/ 之类可授权的位置。",
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
            // 列表头：曲目数 + 有图/无图切换（图标式，与「我的歌单」一致）
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "本机曲目 ${tracks.size}",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f),
                )
                LXViewToggleGroup {
                    LXViewToggle(LxIcons.CoverBox, "有图模式", withCover) { coverOverride = true }
                    LXViewToggle(LxIcons.ViewList, "无图模式", !withCover) { coverOverride = false }
                }
            }
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
                        withCover = withCover,
                        onClick = { onSongClick(song, tracks) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRow(song: Song, withCover: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (withCover) {
            AsyncArtwork(
                model = song.picUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
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