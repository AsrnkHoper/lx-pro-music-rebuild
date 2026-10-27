package com.lxpro.core.designsystem.ambient

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.theme.LXTheme

/**
 * 动态背景层：全局背景 = 当前播放封面（对齐 LX-Music「设置 → 主题设置 → 使用动态背景」）。
 *
 * 结构（与 LX 的 PageContent 一致）：
 * 1. 封面图 cover 铺满全屏（[ContentScale.Crop]）
 * 2. 高斯模糊 —— Android 12+（API 31）用 [Modifier.blur]（RenderEffect）；
 *    低版本无硬件模糊，直接铺原图、靠遮罩压住（降级链的一环，不额外做软件模糊耗电）
 * 3. 遮罩 = 当前配色的 `bg` 色 × [maskOpacity]，文字/卡片仍走所选配色的 token
 *
 * 封面加载走 [AsyncArtwork] 的双通道（在线 Coil / 本地 localart），失败时整层不渲染，
 * 露出底下的氛围层 —— 与 13 §6.3 的降级精神一致。
 */
@Composable
fun BoxScope.LXDynamicBackground(
    coverModel: String?,
    blur: Int,
    maskOpacity: Int,
) {
    if (coverModel.isNullOrBlank()) return
    val colors = LXTheme.colors
    val supportRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier = Modifier.matchParentSize().clipToBounds()) {
        AsyncArtwork(
            model = coverModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (supportRenderEffect) Modifier.blur(blur.coerceAtLeast(0).dp) else Modifier),
        )
        // 遮罩：当前配色的底色 × 不透明度（LX 用 c-content-background + picOpacity/100）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = maskOpacity.coerceIn(0, 100) / 100f)),
        )
    }
}
