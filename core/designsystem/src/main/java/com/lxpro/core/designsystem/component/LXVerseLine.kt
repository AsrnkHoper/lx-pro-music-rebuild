package com.lxpro.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxpro.core.common.FlowRandom
import com.lxpro.core.common.Verse
import com.lxpro.core.common.VerseGroup
import com.lxpro.core.common.VersePicker
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * 文案轮换配置：全部文案组 + 组级启停 + 时段分组启停 + 轮换间隔。
 * 空集语义遵循「设置 → 文案自定义」：空 = 全开；全关回落全池。
 */
data class VerseRotationConfig(
    val groups: List<VerseGroup> = emptyList(),
    val enabledIds: Set<String> = emptySet(),
    val slots: Set<String> = emptySet(),
    /** 轮换间隔（设置 → 文案自定义 → 展示，默认 9s）；页面可用参数覆盖 */
    val intervalMs: Long = 9_000L,
)

/**
 * 全局文案轮换配置。App 层按「设置 → 文案自定义」提供，页面调用 [LXVerseLine] 时无需各自注入。
 * 非 static：设置变更要让所有在展示的文案行实时换池。
 */
val LocalVerseRotation = compositionLocalOf { VerseRotationConfig() }

/**
 * inmyfeelings 文案行（原型 `.plverse` / 首页 `#verse`）。
 *
 * 左侧 2dp accent 竖条 + 一句文案，按轮换间隔换句（「设置 → 文案自定义 → 展示」可调，
 * 页面显式传 `intervalMs` 则优先），换句时淡出 800ms 再淡入（原型 `transition:opacity .8s`）。
 *
 * 池子由 [VersePicker] 按时段与设置决定，`rotation` 留空 = 跟随全局设置。
 */
@Composable
fun LXVerseLine(
    intervalMs: Long? = null,
    modifier: Modifier = Modifier,
    rotation: VerseRotationConfig? = null,
    style: TextStyle = LXType.labelLarge,
    color: Color? = null,
    /** 竖条宽度：主页 2.5dp（`.verse`）、歌单/统计页 2dp（`.plverse`） */
    barWidth: Dp = 2.dp,
) {
    val colors = LXTheme.colors
    val inkColor = color ?: colors.ink2
    // 显式传参优先；留空时跟随设置
    val config = rotation ?: LocalVerseRotation.current
    val effectiveIntervalMs = intervalMs ?: config.intervalMs

    // ⚠️ 必须**真随机**：早期用 pool[0] 起步 + 顺序递增，同一时段每次进页面都是同一句
    //（2026-10-02 用户反馈）。种子取时间这类流动因素 —— 不依赖固定序列。
    val initialPool = remember(config) {
        VersePicker.pool(LocalTime.now().hour, config.groups, config.enabledIds, config.slots)
    }
    var pool by remember(config) { mutableStateOf(initialPool) }
    var index by remember(config) {
        mutableIntStateOf(FlowRandom.nextIndex(initialPool.size, previous = -1))
    }
    val verse: Verse = pool[index % pool.size]

    LaunchedEffect(effectiveIntervalMs, config) {
        while (true) {
            delay(effectiveIntervalMs)
            // 顺带重算池子：跨过时段边界（如 11:00）时偏好分类会变
            val nextPool = VersePicker.pool(
                LocalTime.now().hour,
                config.groups,
                config.enabledIds,
                config.slots,
            )
            pool = nextPool
            index = FlowRandom.nextIndex(
                size = nextPool.size,
                previous = index.coerceIn(0, nextPool.size - 1),
            )
        }
    }

    Row(
        modifier = modifier.height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent.copy(alpha = 0.72f)),
        )
        Spacer(Modifier.width(13.dp))
        AnimatedContent(
            targetState = verse,
            transitionSpec = {
                fadeIn(tween(VERSE_FADE_MS)) togetherWith fadeOut(tween(VERSE_FADE_MS))
            },
            label = "verse",
        ) { current ->
            Text(text = current.text, style = style, color = inkColor)
        }
    }
}

private const val VERSE_FADE_MS = 800
