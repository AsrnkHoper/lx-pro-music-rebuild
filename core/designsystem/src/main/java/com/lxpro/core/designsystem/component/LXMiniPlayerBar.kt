package com.lxpro.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType

/**
 * 底部迷你播放条（14 §3.2：56dp）。
 *
 * 三键布局固定为「上一首 · 播放 · 下一首」——**白噪音不占按钮位**，
 * 它以封面右上角 19px 角标呈现（M4 实现；此处已预留 [noiseActive] 槽位）。
 */
@Composable
fun LXMiniPlayerBar(
    title: String,
    subtitle: String,
    coverUrl: String?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    noiseActive: Boolean = false,
) {
    val colors = LXTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LXDimens.miniBarHeight)
            .background(colors.bg2)
            .clickable(onClick = onOpenPlayer)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncArtwork(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(LXDimens.coverList)
                    .clip(RoundedCornerShape(8.dp)),
            )
            // 白噪音角标位（19px，M4 落地；现在只在开启时占位显示）
            if (noiseActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(19.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(colors.accent),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = LXType.titleSmall,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = LXType.labelLarge,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onPrevious) {
            Icon(LxIcons.Previous, contentDescription = "上一首", tint = colors.ink1)
        }
        if (isBuffering) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colors.accent,
                    strokeWidth = 2.dp,
                )
            }
        } else {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) LxIcons.Pause else LxIcons.Play,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = colors.accent,
                )
            }
        }
        IconButton(onClick = onNext) {
            Icon(LxIcons.Next, contentDescription = "下一首", tint = colors.ink1)
        }
    }
}