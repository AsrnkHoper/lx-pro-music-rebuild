package com.lxpro.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import coil3.compose.SubcomposeAsyncImage
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.model.LOCAL_ART_PREFIX
import com.lxpro.core.model.DataSaver

/**
 * 统一封面组件：在线封面走 Coil，**本地封面走缩略图通道**。
 *
 * 两者对调用方是同一个 API —— 否则每个列表/迷你条/播放页都要写一遍「这是本地还是在线」的分支。
 *
 * 省流量模式（DataSaver，2026-10-26 用户验收修订 #127/#128）：
 * - 「全无图」开启时：**根本不渲染**（连占位框都不留），等同各页面既有的无图模式按钮——
 *   列表类页面（搜索/歌单/统计）在行级另有 compact 布局覆盖，本组件负责其余所有场景；
 * - 「全无图」未开但省流生效中：**只显示 Coil 已缓存（内存/磁盘）的图片**——网络层装了
 *   硬拦截（见 LXProApp 的 ImageLoader 拦截器），未缓存的图片请求直接失败，
 *   失败/加载中一律不渲染内容、不加灰底，因此未缓存的图就是「没有」，绝不全灰。
 * 本地 `localart://` 缩略图不走网络，任何模式下都照常显示。
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
    // 响应式读取：开关一翻立即重组（#128 之前读 volatile 不触发重组，导致行为看起来「没变」）
    val saverActive by DataSaver.activeFlow.collectAsState()
    val imagesBlocked by DataSaver.imagesBlockedFlow.collectAsState()

    val isLocalArtwork = model != null && model.startsWith(LOCAL_ART_PREFIX)
    if (!isLocalArtwork) {
        // 无图模式：不发射任何 composable → 调用方的尺寸 modifier 随之失效，图片槽位真正消失
        if (imagesBlocked) return
        if (saverActive) {
            // 有图 + 省流：网络已被硬拦截，只有缓存命中能成功；加载中/失败不渲染、不加底色
            SubcomposeAsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = modifier,
                loading = {},
                error = {},
            )
            return
        }
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier
                .background(colors.inset),
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