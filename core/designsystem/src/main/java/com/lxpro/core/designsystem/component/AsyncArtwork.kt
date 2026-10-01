package com.lxpro.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.model.LOCAL_ART_PREFIX

/**
 * 统一封面组件：在线封面走 Coil，**本地封面走缩略图通道**。
 *
 * 两者对调用方是同一个 API —— 否则每个列表/迷你条/播放页都要写一遍「这是本地还是在线」的分支。
 */
@Composable
fun AsyncArtwork(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val colors = LXTheme.colors
    val context = LocalContext.current

    val isLocalArtwork = model != null && model.startsWith(LOCAL_ART_PREFIX)
    if (!isLocalArtwork) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier.background(colors.inset),
        )
        return
    }

    var bitmap: ImageBitmap? by remember(model) { mutableStateOf(LocalArtworkCache.peek(model)) }
    LaunchedEffect(model) {
        if (bitmap == null) bitmap = LocalArtworkCache.load(context, model!!)
    }

    Box(modifier = modifier.background(colors.inset), contentAlignment = Alignment.Center) {
        bitmap?.let { loaded ->
            Image(
                bitmap = loaded,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}