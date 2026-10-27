@file:OptIn(ExperimentalLayoutApi::class)

package com.lxpro.feature.stats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXToast
import com.lxpro.core.designsystem.component.LXVerseLine
import com.lxpro.core.designsystem.component.LXViewToggle
import com.lxpro.core.designsystem.component.LXViewToggleGroup
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXColorScheme
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song
import com.lxpro.core.model.DataSaver
import com.lxpro.core.stats.ArtistRank
import com.lxpro.core.stats.DayPoint
import com.lxpro.core.stats.HourBucket
import com.lxpro.core.stats.Metric
import com.lxpro.core.stats.NoiseOverview
import com.lxpro.core.stats.NoiseStatsSnapshot
import com.lxpro.core.stats.PlatformSlice
import com.lxpro.core.stats.RadarAxis
import com.lxpro.core.stats.SongRank
import com.lxpro.core.stats.StackRow
import com.lxpro.core.stats.StatsCalculator
import com.lxpro.core.stats.StatsListScope
import com.lxpro.core.stats.StatsOverview
import com.lxpro.core.stats.StatsRange
import com.lxpro.core.stats.StatsSnapshot
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 统计页（`mockup_app.html` 的 `data-view="stats"`）。
 *
 * 结构参照旧 LX-Pro：**统计 / 日历 / 配置** 三个子页（2026-10-10 用户要求；配置页不含
 * LX-Pro 的「AI听歌报告」）。子页内容：
 * - **统计**：Hero → 六档时间 → 听歌报告 → 最常听 → 活跃时间分布 → 活跃时长趋势 →
 *   听歌画像 → 听歌习惯 → 长期偏好画像 → 音乐平台占比 → 歌曲排行 → 歌手排行
 *   （版面顺序**不可调换**，用户 2026-09-29 裁决；热力图已挪去「日历」子页）
 * - **日历**：月度热力（点格子看当天，批次三 #7）+ 年度总览（12 个月时长条 + 年度合计）
 * - **配置**：统计口径（写入时判定）+ 统计数据管理（导出 / 导入 / 清空）
 *
 * ⚠️ 与原型的**唯一实质偏离**（2026-10-03 用户裁决）：原型的数据是写死的 mock，
 * 本页全部由 `stats_events` 真实账本算出；「听歌画像 / 长期偏好画像」因 Song 模型
 * 没有曲风/语言元数据，改用**可实测的行为维度**（不照搬原型那六个曲风）。
 */
@Composable
fun StatsScreen(
    /** 榜单行点一下即起播（队列 = 整个榜单） */
    onPlaySong: (Song, List<Song>) -> Unit,
    /** 歌手 / 专辑 → 榜单列表页 */
    onOpenScope: (StatsListScope, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val noiseState by viewModel.noiseUiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val scalars = LXTheme.scalars
    val snapshot = state.snapshot
    val side = Modifier.padding(horizontal = scalars.sidePadding)

    var tab by rememberSaveable { mutableStateOf(StatsTab.OVERVIEW) }

    // 配置子页的操作回执：轻提示自动消失
    LaunchedEffect(message) {
        if (message != null) {
            delay(TOAST_DURATION_MS)
            viewModel.onMessageShown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // B3：改用 LazyColumn —— 只有进入视口的区块才参与组合，避免首次进入时
        // 10 个区块 + 图表在同一帧全部组合；B1：不再整页替换空态，空账本也照常渲染。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .lxSafeDrawingPadding(),
            contentPadding = PaddingValues(top = 14.dp, bottom = scalars.sectionGap),
        ) {
            item(key = "tabs") {
                StatsTabBar(tab = tab, onSelect = { tab = it }, modifier = side)
            }
            when (tab) {
                StatsTab.OVERVIEW -> {
                    item(key = "hero") {
                        // 顶部卡片固定按「全部」算，不随档位变（用户 2026-10-03 要求）
                        HeroCard(
                            overview = state.overview,
                            modifier = Modifier
                                .padding(horizontal = scalars.sidePadding)
                                .padding(top = 16.dp),
                        )
                    }
                    item(key = "range") {
                        RangeSegments(
                            selected = state.range,
                            onSelect = viewModel::onRangeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = scalars.sidePadding)
                                .padding(top = 16.dp),
                        )
                    }
                    item(key = "note") {
                        // 口径说明：让「本季 == 本月」这类**正确但反直觉**的区间自我解释
                        Text(
                            text = "${snapshot.range.label}口径：${snapshot.rangeNote}",
                            style = LXType.labelSmall,
                            color = LXTheme.colors.ink3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = scalars.sidePadding)
                                .padding(top = 7.dp),
                        )
                    }
                    item(key = "report") { ReportSection(snapshot, side) }
                    item(key = "fav") { FavouritesSection(snapshot, side, onPlaySong, onOpenScope) }
                    item(key = "hourly") { HourlySection(snapshot, side) }
                    item(key = "trend") { TrendSection(snapshot, side) }
                    item(key = "radar") { RadarSection(snapshot, side) }
                    item(key = "habits") { HabitsSection(snapshot, side) }
                    item(key = "longterm") { LongTermSection(snapshot, side) }
                    item(key = "platform") { PlatformSection(snapshot, side) }
                    item(key = "songs") { SongRankSection(snapshot, side, onPlaySong) }
                    item(key = "artists") { ArtistRankSection(snapshot, side, onOpenScope) }
                }

                StatsTab.NOISE -> {
                    // 白噪音子页：口径与听歌统计同权，但来自独立账本 noise_events
                    val noise = noiseState.snapshot
                    item(key = "noise-hero") {
                        NoiseHeroCard(
                            overview = noiseState.overview,
                            modifier = Modifier
                                .padding(horizontal = scalars.sidePadding)
                                .padding(top = 16.dp),
                        )
                    }
                    item(key = "noise-range") {
                        RangeSegments(
                            selected = noiseState.range,
                            onSelect = viewModel::onRangeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = scalars.sidePadding)
                                .padding(top = 16.dp),
                        )
                    }
                    item(key = "noise-note") {
                        Text(
                            text = "白噪音口径：${noise.rangeNote}",
                            style = LXType.labelSmall,
                            color = LXTheme.colors.ink3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = scalars.sidePadding)
                                .padding(top = 7.dp),
                        )
                    }
                    item(key = "noise-report") { NoiseReportSection(noise, side) }
                    item(key = "noise-split") { NoiseSplitSection(noise, side) }
                    item(key = "noise-hourly") { NoiseHourlySection(noise, side) }
                    item(key = "noise-trend") { NoiseTrendSection(noise, side) }
                    item(key = "noise-sounds") { NoiseSoundRankSection(noise, side) }
                    item(key = "noise-scenes") { NoiseSceneRankSection(noise, side) }
                    item(key = "noise-habits") { NoiseHabitsSection(noise, side) }
                }

                StatsTab.CALENDAR -> {
                    // 日历子页：年导航 + 月历/柱状双模式 + 点格看当天（批四重构）
                    item(key = "calendar") { YearCalendarSection(snapshot, side) }
                }

                StatsTab.CONFIG -> {
                    item(key = "cfg") {
                        StatsConfigSection(
                            config = config,
                            onMinSeconds = viewModel::setMinPlaySeconds,
                            onMinRatio = viewModel::setMinPlayRatio,
                            onPriority = viewModel::setPriority,
                            modifier = side,
                        )
                    }
                    item(key = "data") {
                        StatsDataSection(
                            onExport = viewModel::exportTo,
                            onImport = viewModel::importFrom,
                            onClear = viewModel::clearAll,
                            modifier = side,
                        )
                    }
                }
            }
        }
        LXToast(
            message = message,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp),
        )
    }
}

// ── 三个子页签（统计 / 日历 / 配置；参照旧 LX-Pro 的 tabBar）──

private enum class StatsTab(val label: String) {
    OVERVIEW("统计"),
    CALENDAR("日历"),
    NOISE("白噪音"),
    CONFIG("配置"),
}

/** 子页签条：与六档时间同一套视觉语言（inset 容器 + surface 选中胶囊），三档等宽 */
@Composable
private fun StatsTabBar(
    tab: StatsTab,
    onSelect: (StatsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(LXTheme.pageInset)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatsTab.entries.forEach { entry ->
            val on = entry == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) LXTheme.pageSurface else Color.Transparent)
                    .clickable { onSelect(entry) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    style = LXType.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.ink1 else colors.ink3,
                )
            }
        }
    }
}

// ── Hero ──

@Composable
private fun HeroCard(overview: StatsOverview, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    LXCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(19.dp)) {
            Text(
                text = "YOUR LISTENING LIFE",
                style = LXType.labelSmall,
                color = colors.ink3,
                letterSpacing = 1.8.sp,
            )
            Text(
                text = "你的听歌人生",
                style = LXType.headlineLarge,
                color = colors.ink1,
                modifier = Modifier.padding(top = 7.dp),
            )
            // 文案：间隔跟随「设置 → 文案自定义 → 展示」
            LXVerseLine(
                style = LXType.bodySmall,
                barWidth = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp),
            )
            // 三个合计指标与文案之间用发丝线分组（批五排版优化）
            Box(Modifier.fillMaxWidth().padding(top = 15.dp).height(1.dp).background(colors.hairline))
            Row(
                modifier = Modifier.padding(top = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HeroStat(StatsCalculator.formatHours(overview.totalDurationMs), "累计时长")
                HeroStat("${overview.effectivePlays} 次", "有效收听")
                HeroStat("${overview.activeDays} 天", "活跃天数")
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    val colors = LXTheme.colors
    Column {
        Text(text = value, style = LXType.headlineSmall, color = colors.ink1)
        Text(
            text = label,
            style = LXType.labelSmall,
            color = colors.ink3,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

// ── 六档时间维度 ──

@Composable
private fun RangeSegments(
    selected: StatsRange,
    onSelect: (StatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(LXTheme.pageInset)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatsRange.entries.forEach { range ->
            val on = range == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) LXTheme.pageSurface else Color.Transparent)
                    .clickable { onSelect(range) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = range.label,
                    style = LXType.labelSmall,
                    color = if (on) colors.ink1 else colors.ink3,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ── 区块骨架 ──

/**
 * 区块骨架（批五排版优化）：标题行下加一条发丝线，让 12 个区块在长页面里有
 * 清晰的分节锚点；标题行与内容之间的节奏统一为 9dp + 线 + 12dp。
 */
@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    Column(modifier = modifier.fillMaxWidth().padding(top = scalars.sectionGap)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = LXType.titleSmall, color = colors.ink1)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** 图表容器（批五排版优化）：裸图表统一包进内衬底圆角容器，与卡片家族同一视觉语言 */
@Composable
private fun ChartPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LXTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LXTheme.pageInset)
            .padding(13.dp),
        content = content,
    )
}

/** 「大数值 + 说明」的指标格（听歌报告 / 听歌习惯共用） */
@Composable
private fun MetricGrid(items: List<Metric>, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                pair.forEach { metric ->
                    LXCard(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = metric.value, style = LXType.headlineSmall, color = colors.ink1)
                            Text(
                                text = metric.label,
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── ① 听歌报告 ──

@Composable
private fun ReportSection(snapshot: StatsSnapshot, modifier: Modifier) {
    Section(
        title = "听歌报告",
        modifier = modifier,
        subtitle = snapshot.rangeSpan,
    ) {
        MetricGrid(
            listOf(
                Metric(StatsCalculator.formatHours(snapshot.totalDurationMs), "累计时长"),
                Metric("${snapshot.effectivePlays} 次", "有效收听"),
                Metric("${snapshot.activeDays} 天", "活跃天数"),
                Metric(StatsCalculator.formatHours(snapshot.dailyAvgMs), "日均"),
            ),
        )
    }
}

// ── ② 最常听 ──

@Composable
private fun FavouritesSection(
    snapshot: StatsSnapshot,
    modifier: Modifier,
    onPlaySong: (Song, List<Song>) -> Unit,
    onOpenScope: (StatsListScope, String) -> Unit,
) {
    val colors = LXTheme.colors
    var withCover by rememberSaveable { mutableStateOf(true) }
    // B1：空态也照常给出「歌手 / 歌曲 / 专辑」三张卡，次数如实显示 0
    val slots = listOf(
        "歌手" to snapshot.favSinger,
        "歌曲" to snapshot.favSong,
        "专辑" to snapshot.favAlbum,
    )
    // 点「歌曲」起播；点「歌手 / 专辑」进对应的榜单列表页
    val topSongs = snapshot.songs.map { it.song }
    Section(
        title = "最常听",
        modifier = modifier,
        // 图标式切换（与「我的歌单」同一套样式）—— 用户明确要求不要文字按钮
        trailing = {
            LXViewToggleGroup {
                LXViewToggle(LxIcons.CoverBox, "有图模式", withCover) { withCover = true }
                LXViewToggle(LxIcons.ViewList, "无图模式", !withCover) { withCover = false }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            slots.forEach { (kind, card) ->
                LXCard(
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (card != null) {
                                    Modifier.clickable {
                                        when (kind) {
                                            "歌曲" -> snapshot.songs.firstOrNull { it.name == card.name }
                                                ?.let { onPlaySong(it.song, topSongs) }

                                            "歌手" -> onOpenScope(StatsListScope.ARTIST, card.name)
                                            else -> onOpenScope(StatsListScope.ALBUM, card.name)
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(13.dp),
                    ) {
                        if (withCover && !rememberDataSaverNoImage()) {
                            // 有图模式：封面缩略图取代小色块，维度名降为一行小字
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(LXTheme.pageInset),
                            ) {
                                AsyncArtwork(
                                    model = card?.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Text(
                                text = kind,
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                modifier = Modifier.padding(top = 9.dp),
                            )
                            // 空态不写歌名（避免空字符串假值），只如实显示 0 次
                            if (card != null) {
                                Text(
                                    text = card.name,
                                    style = LXType.bodySmall,
                                    color = colors.ink1,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.accent.copy(alpha = 0.13f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = kind,
                                    style = LXType.labelSmall,
                                    color = colors.accent,
                                )
                            }
                            if (card != null) {
                                Text(
                                    text = card.name,
                                    style = LXType.bodySmall,
                                    color = colors.ink1,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            }
                        }
                        Text(
                            text = "${card?.plays ?: 0} 次",
                            style = LXType.labelSmall,
                            color = colors.ink3,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── ③ 活跃时间分布 ──

@Composable
private fun HourlySection(snapshot: StatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    Section(title = "活跃时间分布", modifier = modifier, subtitle = "24 小时") {
        ChartPanel {
            HourlyBars(
                buckets = snapshot.hourly,
                peakHour = snapshot.peakHour,
                barColor = colors.accent.copy(alpha = 0.18f),
                peakColor = colors.accent,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("0", "6", "12", "18", "23").forEach { AxisLabel(it) }
            }
        }
        Text(
            text = "最活跃 ${snapshot.peakHour} 时 · 单小时 ${snapshot.peakHourPlays} 次",
            style = LXType.labelSmall,
            color = colors.ink3,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun HourlyBars(
    buckets: List<HourBucket>,
    peakHour: Int,
    barColor: Color,
    peakColor: Color,
) {
    val maxPlays = max(1, buckets.maxOfOrNull { it.plays } ?: 1)
    Row(
        modifier = Modifier.fillMaxWidth().height(86.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        buckets.forEach { bucket ->
            val fraction = bucket.plays.toFloat() / maxPlays
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().height((86f * fraction).dp),
            ) {
                if (bucket.plays > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((86f * fraction).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (bucket.hour == peakHour) peakColor else barColor),
                    )
                }
            }
        }
    }
}

// ── ④ 活跃时长趋势 ──

@Composable
private fun TrendSection(snapshot: StatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    val days = snapshot.trend
    Section(title = "活跃时长趋势", modifier = modifier, subtitle = "近 ${snapshot.trendDays} 天") {
        ChartPanel {
            DayTrendChart(days, colors.accent, colors.surface)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(0, days.size / 2, days.lastIndex).distinct()
                    .filter { it in days.indices }
                    .forEach { AxisLabel(days[it].label) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            TrendCard(snapshot.trendCards.peakValue, "单日峰值", snapshot.trendCards.peakLabel, Modifier.weight(1f))
            TrendCard(snapshot.trendCards.last7Value, "近 7 天", snapshot.trendCards.last7Label, Modifier.weight(1f))
            TrendCard(
                snapshot.trendCards.workdayValue,
                "工作日均值",
                snapshot.trendCards.workdayLabel,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrendCard(value: String, label: String, sub: String, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    LXCard(modifier = modifier) {
        Column(modifier = Modifier.padding(11.dp)) {
            Text(text = label, style = LXType.labelSmall, color = colors.ink3)
            Text(
                text = value,
                style = LXType.titleSmall,
                color = colors.ink1,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = sub,
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun DayTrendChart(days: List<DayPoint>, line: Color, dotFill: Color) {
    if (days.size < 2) return
    Canvas(modifier = Modifier.fillMaxWidth().height(84.dp)) {
        val padX = 8.dp.toPx()
        val padTop = 8.dp.toPx()
        val padBottom = 10.dp.toPx()
        val maxValue = max(1L, days.maxOf { it.dayMs })
        val step = (size.width - padX * 2) / (days.size - 1)
        fun x(i: Int) = padX + step * i
        fun y(v: Long) = size.height - padBottom -
            (v.toFloat() / maxValue) * (size.height - padTop - padBottom)

        val path = Path()
        days.forEachIndexed { index, day ->
            val px = x(index)
            val py = y(day.dayMs)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color = line, style = Stroke(width = 2.dp.toPx()))

        val peakIndex = days.indices.maxByOrNull { days[it].dayMs } ?: return@Canvas
        days.forEachIndexed { index, day ->
            val isPeak = index == peakIndex
            drawCircle(
                color = if (isPeak) line else dotFill,
                radius = (if (isPeak) 3.dp else 2.dp).toPx(),
                center = Offset(x(index), y(day.dayMs)),
                style = if (isPeak) androidx.compose.ui.graphics.drawscope.Fill
                else Stroke(width = 1.4.dp.toPx()),
            )
        }
    }
}

// ── ④b 听歌热力图（照搬旧项目 MonthHeatMap：月历 · 按天有效时长） ──

/**
 * 热力色阶：0 → 主题 inset（几乎透明）；正数 → 从 inset 到 accent 的插值。
 * 口径同旧项目 `getHeatColor` 的 `sqrt(duration / max)` 归一化，但不引入游离颜色。
 */
private fun heatColor(value: Long, maxMs: Long, colors: LXColorScheme): Color {
    if (value <= 0L) return colors.inset
    val ratio = sqrt(value.toDouble() / maxMs.coerceAtLeast(1L).toDouble())
        .toFloat().coerceIn(0f, 1f)
    // 最低也给 0.25 权重，保证「有数据」的格子肉眼可辨
    return lerp(colors.inset, colors.accent, 0.25f + 0.75f * ratio)
}

// ── ④b 听歌日历（「日历」子页：年导航 + 单月月历 / GitHub 式全年热力双模式）──

/** 日历视图模式（2026-10-11 用户订正：月历 + GitHub 热力，不要柱状、不要年历） */
private enum class CalendarMode(val label: String) {
    MONTH("月历"),
    HEAT("热力"),
}

/**
 * 听歌日历（年 + 月导航，两种呈现）：
 * - **月历**：一次只看一个月（周一起始大格子热力，点格看当天明细）—— 批五用户订正，
 *   不要 12 个月挤一年的年历
 * - **热力**：GitHub 贡献图式全年热力（周为列、周一~周日为行，横向滚动；当年初始滚到最近几周）
 * - 年导航 ‹/› 对两种模式都生效（批四要求：按年翻，到今年为止禁「下一年」）
 */
@Composable
private fun YearCalendarSection(snapshot: StatsSnapshot, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year) }
    var month by rememberSaveable { mutableStateOf(LocalDate.now().monthValue) }
    var mode by rememberSaveable { mutableStateOf(CalendarMode.MONTH) }
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val today = LocalDate.now()

    // 切年/切月后的收尾：月不越界（当年没到的月份不可达）、选中日不跨期悬挂
    val monthReachable = year < today.year || month <= today.monthValue
    LaunchedEffect(year) {
        if (year == today.year && month > today.monthValue) month = today.monthValue
        val sel = selectedEpochDay ?: return@LaunchedEffect
        if (LocalDate.ofEpochDay(sel).year != year) selectedEpochDay = null
    }

    // 全年每日有效时长（热力口径：全量有效事件，不随六档时间变）
    val yearDaily = remember(snapshot.dailyMs, year) {
        buildMap {
            snapshot.dailyMs.forEach { (epochDay, ms) ->
                if (LocalDate.ofEpochDay(epochDay).year == year) put(epochDay, ms)
            }
        }
    }
    val maxMs = remember(yearDaily) { yearDaily.values.maxOrNull() ?: 0L }

    // 年度合计：时长 / 活跃天数 / 有效播放次数
    val yearStats = remember(yearDaily, snapshot.dailySongs) {
        var totalMs = 0L
        var activeDays = 0
        yearDaily.forEach { (_, ms) ->
            totalMs += ms
            if (ms > 0L) activeDays++
        }
        val plays = snapshot.dailySongs.entries
            .filter { LocalDate.ofEpochDay(it.key).year == year }
            .sumOf { (_, rows) -> rows.sumOf { it.plays } }
        Triple(totalMs, activeDays, plays)
    }

    val onDayClick: (Long) -> Unit = { epochDay ->
        selectedEpochDay = if (selectedEpochDay == epochDay) null else epochDay
    }

    Section(title = "听歌日历", modifier = modifier, subtitle = "点格子看那天听了什么") {
        // ── 年导航 + 模式切换 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalendarNavButton(text = "‹", enabled = true, onClick = { year -= 1 })
            Text(
                text = "$year 年",
                style = LXType.bodySmall,
                color = colors.ink1,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            CalendarNavButton(
                text = "›",
                enabled = year < today.year,
                onClick = { year += 1 },
            )
            Spacer(Modifier.weight(1f))
            CalendarMode.entries.forEach { entry ->
                val on = entry == mode
                Text(
                    text = entry.label,
                    style = LXType.labelSmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) colors.accent else colors.ink3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (on) colors.accent.copy(alpha = 0.13f) else Color.Transparent)
                        .clickable { mode = entry }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        when (mode) {
            CalendarMode.MONTH -> {
                // ── 月导航（跨年会带着年一起翻：12月› = 次年1月）──
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarNavButton(
                        text = "‹",
                        enabled = true,
                        onClick = {
                            if (month == 1) {
                                month = 12
                                year -= 1
                            } else {
                                month -= 1
                            }
                        },
                    )
                    Text(
                        text = "$month 月",
                        style = LXType.bodySmall,
                        color = colors.ink2,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    CalendarNavButton(
                        text = "›",
                        enabled = monthReachable,
                        onClick = {
                            if (month == 12) {
                                month = 1
                                year += 1
                            } else {
                                month += 1
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${firstDayOfMonth(year, month).lengthOfMonth()} 天",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                    )
                }
                ChartPanel {
                    MonthHeatmap(
                        year = year,
                        month = month,
                        dailyMs = yearDaily,
                        maxMs = maxMs,
                        today = today,
                        selectedEpochDay = selectedEpochDay,
                        onDayClick = onDayClick,
                    )
                }
            }

            CalendarMode.HEAT -> ChartPanel {
                GitHubHeatmap(
                    year = year,
                    dailyMs = yearDaily,
                    maxMs = maxMs,
                    today = today,
                    selectedEpochDay = selectedEpochDay,
                    onDayClick = onDayClick,
                )
            }
        }

        // 图例：少 → 多
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("少", style = LXType.labelSmall, color = colors.ink3)
            Spacer(Modifier.width(6.dp))
            (0..5).forEach { i ->
                val v = if (maxMs > 0L) maxMs * (i + 1) / 6 else 0L
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(heatColor(v, maxMs, colors)),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text("多", style = LXType.labelSmall, color = colors.ink3)
        }
        if (maxMs > 0L) {
            Text(
                text = "本年峰值 ${StatsCalculator.formatHours(maxMs)}",
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.padding(top = 7.dp),
            )
        }

        // 年度合计
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HeroStat(StatsCalculator.formatHours(yearStats.first), "$year 合计")
            HeroStat("${yearStats.third} 次", "有效收听")
            HeroStat("${yearStats.second} 天", "活跃天数")
        }

        // 选中日明细（点格子后出现，再点一次收起）
        selectedEpochDay?.let { epochDay -> SelectedDayPanel(snapshot, epochDay) }
    }
}

private fun firstDayOfMonth(year: Int, month: Int): LocalDate = LocalDate.of(year, month, 1)

@Composable
private fun CalendarNavButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = LXType.titleSmall, color = if (enabled) colors.ink2 else colors.ink3)
    }
}

/** 月历热力：一次一个月（周一起始），大格子 = 热力色块 + 日数字，点选看当天 */
@Composable
private fun MonthHeatmap(
    year: Int,
    month: Int,
    dailyMs: Map<Long, Long>,
    maxMs: Long,
    today: LocalDate,
    selectedEpochDay: Long?,
    onDayClick: (Long) -> Unit,
) {
    val colors = LXTheme.colors
    val first = remember(year, month) { firstDayOfMonth(year, month) }
    // 周一起始：第一格里补足上月末尾的占位
    val gridStart = remember(first) { first.minusDays((first.dayOfWeek.value - 1).toLong()) }
    val weeks = remember(first) { ((first.dayOfWeek.value - 1) + first.lengthOfMonth() + 6) / 7 }

    Column {
        // 星期表头
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            DAY_HEADERS.forEach { head ->
                Text(
                    text = head,
                    style = LXType.labelSmall,
                    fontSize = 10.sp,
                    color = colors.ink3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            (0 until weeks).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    (0 until 7).forEach { dow ->
                        val day = gridStart.plusDays((week * 7 + dow).toLong())
                        val inMonth = day.year == year && day.monthValue == month
                        CalendarDayCell(
                            day = day,
                            inMonth = inMonth,
                            value = if (inMonth) dailyMs[day.toEpochDay()] ?: 0L else 0L,
                            maxMs = maxMs,
                            today = today,
                            selected = selectedEpochDay == day.toEpochDay(),
                            onClick = { onDayClick(day.toEpochDay()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 月历里的一天：大格子（宽 1/7 × 正方形），色块深浅 = 当天时长 */
@Composable
private fun CalendarDayCell(
    day: LocalDate,
    inMonth: Boolean,
    value: Long,
    maxMs: Long,
    today: LocalDate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val fill = if (inMonth) heatColor(value, maxMs, colors) else Color.Transparent
    val textColor = when {
        !inMonth -> Color.Transparent
        value <= 0L -> colors.ink3
        fill.luminance() > 0.5f -> colors.ink1
        else -> colors.surface
    }
    // 描边优先级：选中 > 今天（同一天叠加时只画一个框，避免双框打架）
    val borderColor = when {
        selected && inMonth -> colors.accent
        day == today && inMonth -> colors.accent
        else -> null
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(9.dp))
            .background(fill)
            .then(
                if (borderColor != null) {
                    Modifier.border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(9.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (inMonth) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (inMonth) {
            Text(
                text = "${day.dayOfMonth}",
                style = LXType.labelSmall,
                fontSize = 11.sp,
                color = textColor,
            )
        }
    }
}

/**
 * GitHub 贡献图式全年热力：周为列（横向滚动）、周一~周日为行。
 * 顶部按月打标、左侧只标 一/三/五（GitHub 同款），当年初始定位把「本周」**居中**。
 */
@Composable
private fun GitHubHeatmap(
    year: Int,
    dailyMs: Map<Long, Long>,
    maxMs: Long,
    today: LocalDate,
    selectedEpochDay: Long?,
    onDayClick: (Long) -> Unit,
) {
    val colors = LXTheme.colors
    val scrollState = rememberScrollState()
    val first = remember(year) { LocalDate.of(year, 1, 1) }
    // 周一起始：第一列补足上年末尾的占位行
    val gridStart = remember(first) { first.minusDays((first.dayOfWeek.value - 1).toLong()) }
    val last = remember(year) { LocalDate.of(year, 12, 31) }
    val weeks = remember(gridStart, last) {
        (((last.toEpochDay() - gridStart.toEpochDay() + 1) + 6) / 7).toInt()
    }
    // 每个月的首日落在第几列（月份标签的位置）
    val monthStartWeek = remember(gridStart, year) {
        (1..12).associate { m ->
            val d = LocalDate.of(year, m, 1)
            m to (((d.toEpochDay() - gridStart.toEpochDay()) / 7).toInt())
        }
    }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 当年初始把本周列滚到视口正中；翻到别的年回到开头
        //（2026-10-11 用户要求：初始化自动居中定位到当前时间点）
        LaunchedEffect(year) {
            if (year == today.year) {
                val weekOfToday = ((today.toEpochDay() - gridStart.toEpochDay()) / 7).toInt()
                val pitchPx = with(density) { HEAT_CELL_PITCH.toPx() }
                // 本周列中心 - 视口一半 = 让本周落在正中的滚动量（上限由 scrollTo 内部 clamp）
                val target = (weekOfToday + 0.5f) * pitchPx - constraints.maxWidth / 2f
                scrollState.scrollTo(target.roundToInt().coerceAtLeast(0))
            } else {
                scrollState.scrollTo(0)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState)) {
        // 月份标签行（与周列一一对齐）
        Row(verticalAlignment = Alignment.Bottom) {
            Spacer(Modifier.width(HEAT_LABEL_W + 3.dp))
            (0 until weeks).forEach { week ->
                val label = monthStartWeek.entries.firstOrNull { it.value == week }?.key
                Box(modifier = Modifier.width(HEAT_CELL_PITCH)) {
                    if (label != null) {
                        Text(
                            text = "$label 月",
                            style = LXType.labelSmall,
                            fontSize = 9.sp,
                            color = colors.ink3,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            // 星期标签列（只标 一/三/五，同 GitHub）
            Column(
                modifier = Modifier.width(HEAT_LABEL_W),
                verticalArrangement = Arrangement.spacedBy(HEAT_CELL_GAP),
            ) {
                WEEK_LABELS.forEach { label ->
                    Box(modifier = Modifier.size(HEAT_CELL_SIZE), contentAlignment = Alignment.Center) {
                        if (label.isNotEmpty()) {
                            Text(
                                text = label,
                                style = LXType.labelSmall,
                                fontSize = 9.sp,
                                color = colors.ink3,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(HEAT_CELL_GAP)) {
                (0 until weeks).forEach { week ->
                    Column(verticalArrangement = Arrangement.spacedBy(HEAT_CELL_GAP)) {
                        (0 until 7).forEach { dow ->
                            val day = gridStart.plusDays((week * 7 + dow).toLong())
                            val inYear = day.year == year
                            HeatDayCell(
                                day = day,
                                inYear = inYear,
                                value = if (inYear) dailyMs[day.toEpochDay()] ?: 0L else 0L,
                                maxMs = maxMs,
                                today = today,
                                selected = selectedEpochDay == day.toEpochDay(),
                                onClick = { onDayClick(day.toEpochDay()) },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/** 热力图里的一天：固定小方格（12dp），颜色深浅 = 当天时长 */
@Composable
private fun HeatDayCell(
    day: LocalDate,
    inYear: Boolean,
    value: Long,
    maxMs: Long,
    today: LocalDate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    val fill = if (inYear) heatColor(value, maxMs, colors) else Color.Transparent
    val borderColor = when {
        selected && inYear -> colors.accent
        day == today && inYear -> colors.accent
        else -> null
    }
    Box(
        modifier = Modifier
            .size(HEAT_CELL_SIZE)
            .clip(RoundedCornerShape(3.dp))
            .background(fill)
            .then(
                if (borderColor != null) {
                    Modifier.border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(3.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (inYear) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

private val DAY_HEADERS = listOf("一", "二", "三", "四", "五", "六", "日")
private val WEEK_LABELS = listOf("一", "", "三", "", "五", "", "日")

/** GitHub 热力格边长 */
private val HEAT_CELL_SIZE = 12.dp

/** GitHub 热力格间距 */
private val HEAT_CELL_GAP = 3.dp

/** 一列的步进（格 + 距），月份标签行与周列靠它对齐 */
private val HEAT_CELL_PITCH = HEAT_CELL_SIZE + HEAT_CELL_GAP

/** 星期标签列宽 */
private val HEAT_LABEL_W = 14.dp

/** 选中日的当日明细（批三 #7 的能力保留；从旧热力图区块抽出复用） */
@Composable
private fun SelectedDayPanel(snapshot: StatsSnapshot, epochDay: Long) {
    val colors = LXTheme.colors
    val day = LocalDate.ofEpochDay(epochDay)
    val rows = snapshot.dailySongs[epochDay].orEmpty()
    val dayMs = snapshot.dailyMs[epochDay] ?: 0L
    val weekLabel = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.dayOfWeek.value - 1]
    // 有图 / 无图切换（2026-10-11 用户要求：与全项目列表统一图标式切换）
    var withCover by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LXTheme.pageInset)
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "${day.monthValue}月${day.dayOfMonth}日 · $weekLabel",
                style = LXType.titleSmall,
                color = colors.ink1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (dayMs > 0L) "共 ${StatsCalculator.formatHours(dayMs)}" else "没有收听",
                style = LXType.labelSmall,
                color = colors.ink3,
            )
            if (rows.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                LXViewToggleGroup {
                    LXViewToggle(LxIcons.CoverBox, "有图模式", withCover) { withCover = true }
                    LXViewToggle(LxIcons.ViewList, "无图模式", !withCover) { withCover = false }
                }
            }
        }
        if (rows.isEmpty()) {
            Text(
                text = "这天没有入账的有效播放（低于口径阈值的不计）",
                style = LXType.labelSmall,
                color = colors.ink3,
            )
        } else {
            // 一天听几十首也放得下：面板内限高滚动，不把统计页顶穿
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (withCover && !rememberDataSaverNoImage()) {
                            // 有图模式：封面缩略图（与榜单行同一套 36dp 圆角方块）
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surface),
                            ) {
                                AsyncArtwork(
                                    model = row.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.width(9.dp))
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                modifier = Modifier.width(17.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = row.name,
                                style = LXType.bodyMedium,
                                color = colors.ink1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append(row.singer)
                                    append(" · ").append(row.sourceLabel)
                                    if (row.plays > 1) append(" · ${row.plays} 次")
                                },
                                style = LXType.labelSmall,
                                color = colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = StatsCalculator.formatHours(row.playedMs),
                            style = LXType.labelSmall,
                            color = colors.ink2,
                        )
                    }
                }
            }
        }
    }
}


// ── ④d 统计口径（「配置」子页；参照旧 LX-Pro StatsConfig）──

@Composable
private fun StatsConfigSection(
    config: StatsConfigState,
    onMinSeconds: (Int) -> Unit,
    onMinRatio: (Int) -> Unit,
    onPriority: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    var secondsText by remember { mutableStateOf(config.minPlaySeconds.toString()) }
    var ratioText by remember { mutableStateOf(config.minPlayRatio.toString()) }

    Section(title = "统计口径", modifier = modifier, subtitle = "写入时判定 · 只影响之后的新播放") {
        Text(
            text = "低于阈值的播放不计入统计，也不会进播放历史",
            style = LXType.labelSmall,
            color = colors.ink3,
        )
        ConfigNumberField(
            label = "最低计入秒数",
            value = secondsText,
            fallback = config.minPlaySeconds,
            suffix = "秒",
            range = 1..600,
            onValueChange = { secondsText = it },
            onCommit = onMinSeconds,
        )
        ConfigNumberField(
            label = "最低播放比例",
            value = ratioText,
            fallback = config.minPlayRatio,
            suffix = "%",
            range = 1..100,
            onValueChange = { ratioText = it },
            onCommit = onMinRatio,
        )

        // ── 统计方式（对齐旧 LX-Pro StatsConfig）：仅秒数 / 仅比例 / 都满足 ──
        Text(
            text = "统计方式",
            style = LXType.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.ink1,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "仅秒数：只看最低计入秒数；仅比例：只看最低播放比例；都满足：两个条件都达标才计入",
            style = LXType.labelSmall,
            color = colors.ink3,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val bothSelected = config.priority == "timeFirst" || config.priority == "ratioFirst"
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConfigChip("仅秒数", selected = config.priority == "time") { onPriority("time") }
            ConfigChip("仅比例", selected = config.priority == "ratio") { onPriority("ratio") }
            // 点「都满足」时：若已在都满足组则保持当前优先序，否则默认秒数优先
            ConfigChip("都满足", selected = bothSelected) {
                onPriority(if (bothSelected) config.priority else "timeFirst")
            }
        }

        // ── 优先检查（仅「都满足」时出现）──
        if (bothSelected) {
            Text(
                text = "优先检查",
                style = LXType.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink1,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigChip("秒数", selected = config.priority == "timeFirst") { onPriority("timeFirst") }
                ConfigChip("比例", selected = config.priority == "ratioFirst") { onPriority("ratioFirst") }
            }
        }
    }
}

/** 配置项选择 chip（描边胶囊：选中 accent，未选中 hairline） */
@Composable
private fun ConfigChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.hairline,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = LXType.labelLarge,
            color = if (selected) colors.accent else colors.ink2,
        )
    }
}

/** 数字输入行：只放数字；**失焦才提交**（避免每敲一位数字就写一次 DataStore 弹一次提示） */
@Composable
private fun ConfigNumberField(
    label: String,
    value: String,
    fallback: Int,
    suffix: String,
    range: IntRange,
    onValueChange: (String) -> Unit,
    onCommit: (Int) -> Unit,
) {
    val colors = LXTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // 失焦提交：合法 → 落库；不合法（越界/空）→ 回滚成已存的值
    LaunchedEffect(focused) {
        if (!focused) {
            val parsed = value.toIntOrNull()
            if (parsed != null && parsed in range) {
                if (parsed != fallback) onCommit(parsed)
            } else {
                onValueChange(fallback.toString())
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = label,
            style = LXType.bodyMedium,
            color = colors.ink1,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                onValueChange(text.filter { it.isDigit() }.take(3))
            },
            modifier = Modifier
                .width(116.dp)
                .heightIn(min = 46.dp),
            singleLine = true,
            textStyle = LXType.labelLarge.copy(textAlign = TextAlign.Center),
            suffix = {
                Text(text = suffix, style = LXType.labelSmall, color = colors.ink3)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(11.dp),
            interactionSource = interaction,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.ink1,
                unfocusedTextColor = colors.ink1,
                focusedContainerColor = colors.hairline,
                unfocusedContainerColor = colors.inset,
                cursorColor = colors.accent,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
    }
}

// ── ④e 统计数据管理（「配置」子页；参照旧 LX-Pro DataManager，不含 AI 听歌报告）──

@Composable
private fun StatsDataSection(
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(onExport) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    Section(title = "统计数据管理", modifier = modifier, subtitle = "只导出统计；全量备份（含标签、白噪音、歌单）在「设置 → 数据与备份」") {
        Text(
            text = "备份包含全部播放事件（歌曲、来源、时间、时长、是否入账）；导入会覆盖整本统计",
            style = LXType.labelSmall,
            color = colors.ink3,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ActionPill(
                label = "导出数据",
                textColor = colors.ink1,
                background = colors.surface,
                border = colors.hairline,
                onClick = { exportLauncher.launch("lxpro_stats.json") },
            )
            ActionPill(
                label = "导入数据",
                textColor = colors.accent,
                background = colors.accent.copy(alpha = 0.13f),
                border = Color.Transparent,
                onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            )
            ActionPill(
                label = "清空数据",
                textColor = colors.danger,
                background = colors.danger.copy(alpha = 0.10f),
                border = Color.Transparent,
                onClick = { confirmClear = true },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.surface,
            title = {
                Text(text = "清空统计数据", style = LXType.titleSmall, color = colors.ink1)
            },
            text = {
                Text(
                    text = "全部听歌统计将被删除且不可恢复。建议先导出备份。",
                    style = LXType.bodyMedium,
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空", style = LXType.labelLarge, color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消", style = LXType.labelLarge, color = colors.ink2)
                }
            },
        )
    }
}

@Composable
private fun ActionPill(
    label: String,
    textColor: Color,
    background: Color,
    border: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(
                if (border != Color.Transparent) {
                    Modifier.border(1.dp, border, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = LXType.labelLarge, color = textColor)
    }
}

// ── ⑤ 听歌画像（六维雷达） ──

@Composable
private fun RadarSection(snapshot: StatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    val axes = snapshot.radar
    Section(title = "听歌画像", modifier = modifier, subtitle = "六维 · 可实测") {
        ChartPanel {
            RadarChart(axes, colors.accent, colors.hairline, colors.ink3)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            axes.forEach { axis ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(colors.accent),
                    )
                    Text(
                        text = "${axis.name} ${axis.score}",
                        style = LXType.labelSmall,
                        color = colors.ink2,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarChart(axes: List<RadarAxis>, accent: Color, grid: Color, labelColor: Color) {
    if (axes.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)
    val n = axes.size
    Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(220f / 148f)) {
        val scale = size.width / 220f
        val cx = 110f * scale
        val cy = 74f * scale
        val radius = 62f * scale

        fun point(index: Int, value: Float): Offset {
            val angle = -Math.PI / 2 + index * (2 * Math.PI / n)
            return Offset(
                x = cx + (radius * value) * cos(angle).toFloat(),
                y = cy + (radius * value) * sin(angle).toFloat(),
            )
        }

        // 三层同心多边形 + 辐条
        listOf(1f, 0.66f, 0.33f).forEach { level ->
            val ring = Path()
            for (i in 0 until n) {
                val p = point(i, level)
                if (i == 0) ring.moveTo(p.x, p.y) else ring.lineTo(p.x, p.y)
            }
            ring.close()
            drawPath(ring, color = grid, style = Stroke(width = 1f))
        }
        for (i in 0 until n) {
            drawLine(grid, Offset(cx, cy), point(i, 1f), strokeWidth = 1f)
        }

        // 数据多边形
        val data = Path()
        for (i in 0 until n) {
            val p = point(i, (axes[i].score / 100f).coerceIn(0f, 1f))
            if (i == 0) data.moveTo(p.x, p.y) else data.lineTo(p.x, p.y)
        }
        data.close()
        drawPath(data, color = accent.copy(alpha = 0.18f))
        drawPath(data, color = accent, style = Stroke(width = 1.6.dp.toPx()))

        // 轴标签
        for (i in 0 until n) {
            val p = point(i, 1.28f)
            drawLabel(measurer, axes[i].name, labelStyle, Offset(p.x, p.y))
        }
    }
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    center: Offset,
) {
    val layout = measurer.measure(text, style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = center.x - layout.size.width / 2f,
            y = center.y - layout.size.height / 2f,
        ),
    )
}

// ── ⑥ 听歌习惯 ──

@Composable
private fun HabitsSection(snapshot: StatsSnapshot, modifier: Modifier) {
    Section(title = "听歌习惯", modifier = modifier) {
        MetricGrid(snapshot.habits)
    }
}

// ── ⑦ 长期偏好画像 ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LongTermSection(snapshot: StatsSnapshot, modifier: Modifier) {
    val tones = toneColors()
    Section(title = "长期偏好画像", modifier = modifier, subtitle = "时段 · 时长 · 重听") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            snapshot.longTerm.forEach { row ->
                StackBar(row, tones)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackBar(row: StackRow, tones: List<Color>) {
    val colors = LXTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = row.title, style = LXType.labelLarge, color = colors.ink1)
            Spacer(Modifier.weight(1f))
            Text(text = "${row.categoryCount} 类", style = LXType.labelSmall, color = colors.ink3)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LXTheme.pageInset),
        ) {
            row.segments.forEach { segment ->
                Box(
                    modifier = Modifier
                        .weight(segment.share.coerceAtLeast(0.001f))
                        .fillMaxSize()
                        .background(tones[segment.tone % tones.size]),
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.segments.forEach { segment ->
                Text(
                    text = "${segment.name} ${segment.detail}",
                    style = LXType.labelSmall,
                    color = colors.ink3,
                )
            }
        }
    }
}

/** 单色阶：从主题 accent 往 surface 上靠，保证六套配色（含灰系）都成立，无游离颜色 */
@Composable
private fun toneColors(): List<Color> {
    val colors = LXTheme.colors
    return listOf(
        colors.accent,
        lerp(colors.accent, colors.surface, 0.28f),
        lerp(colors.accent, colors.surface, 0.48f),
        lerp(colors.ink2, colors.surface, 0.15f),
        lerp(colors.ink2, colors.surface, 0.45f),
        lerp(colors.ink3, colors.surface, 0.25f),
    )
}

// ── ⑧ 音乐平台占比 ──

@Composable
private fun PlatformSection(snapshot: StatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    val slices = snapshot.platforms
    val tones = toneColors()
    val total = slices.sumOf { it.value }
    Section(title = "音乐平台占比", modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(slices, tones, colors.inset, colors.ink1, colors.ink3, total)
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                slices.take(4).forEach { slice ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = slice.name, style = LXType.labelSmall, color = colors.ink1)
                        Spacer(Modifier.weight(1f))
                        Text(text = "${slice.value} 次", style = LXType.labelSmall, color = colors.ink3)
                    }
                }
                // B1：空态如实显示 0，不给空字符串 / 假值
                if (slices.isEmpty()) {
                    Text(text = "0 次", style = LXType.labelSmall, color = colors.ink3)
                }
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            slices.forEachIndexed { index, slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(tones[index % tones.size]),
                    )
                    Text(
                        text = "${slice.name} ${(slice.share * 100).toInt()}%",
                        style = LXType.labelSmall,
                        color = colors.ink2,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DonutChart(
    slices: List<PlatformSlice>,
    tones: List<Color>,
    track: Color,
    totalColor: Color,
    captionColor: Color,
    total: Int,
) {
    val measurer = rememberTextMeasurer()
    val totalStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = totalColor)
    val captionStyle = TextStyle(fontSize = 9.sp, color = captionColor)
    Canvas(modifier = Modifier.size(120.dp)) {
        val stroke = 15.dp.toPx()
        val radius = (size.minDimension - stroke) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(track, radius = radius, center = center, style = Stroke(width = stroke))

        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = slice.share * 360f
            val gap = if (slices.size > 1) 2f else 0f
            drawArc(
                color = tones[index % tones.size],
                startAngle = startAngle + gap / 2f,
                sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = stroke),
            )
            startAngle += sweep
        }

        val totalLayout = measurer.measure("$total", totalStyle)
        val captionLayout = measurer.measure("总播放（次）", captionStyle)
        drawText(
            textLayoutResult = totalLayout,
            topLeft = Offset(
                center.x - totalLayout.size.width / 2f,
                center.y - totalLayout.size.height - 1.dp.toPx(),
            ),
        )
        drawText(
            textLayoutResult = captionLayout,
            topLeft = Offset(
                center.x - captionLayout.size.width / 2f,
                center.y + 1.dp.toPx(),
            ),
        )
    }
}

// ── ⑨ 歌曲排行 ──

@Composable
private fun SongRankSection(
    snapshot: StatsSnapshot,
    modifier: Modifier,
    onPlaySong: (Song, List<Song>) -> Unit,
) {
    var byDuration by rememberSaveable { mutableStateOf(false) }
    var withCover by rememberSaveable { mutableStateOf(true) }
    val colors = LXTheme.colors
    val songs = snapshot.songs
    // B1：空态也照常渲染区块（maxOfOrNull 防空列表崩溃），列表为空时如实显示 0 首
    val maxValue = max(
        1L,
        if (byDuration) songs.maxOfOrNull { it.durationMs } ?: 0L
        else (songs.maxOfOrNull { it.plays } ?: 0).toLong(),
    )
    Section(
        title = "歌曲排行",
        modifier = modifier,
        // 当前排序口径写在副标题上，按钮只留图标（用户要求不用文字做按钮）
        subtitle = if (byDuration) "按时长" else "按次数",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LXViewToggleGroup {
                    LXViewToggle(LxIcons.CoverBox, "有图模式", withCover) { withCover = true }
                    LXViewToggle(LxIcons.ViewList, "无图模式", !withCover) { withCover = false }
                }
                LXViewToggleGroup {
                    LXViewToggle(LxIcons.SortAsc, "切换为${if (byDuration) "按次数" else "按时长"}", byDuration) {
                        byDuration = !byDuration
                    }
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (songs.isEmpty()) {
                Text(text = "本区间内 0 首", style = LXType.labelSmall, color = colors.ink3)
            }
            songs.forEachIndexed { index, song ->
                val value = if (byDuration) song.durationMs else song.plays.toLong()
                RankRow(
                    index = index + 1,
                    name = song.name,
                    sub = "${song.plays} 次 / ${formatDuration(song.durationMs)}",
                    fraction = value.toFloat() / maxValue,
                    coverUrl = song.coverUrl,
                    withCover = withCover && !rememberDataSaverNoImage(),
                    // 点一行即从这首起播，队列就是整个榜单
                    onClick = { onPlaySong(song.song, songs.map { it.song }) },
                )
            }
        }
    }
}

// ── ⑩ 歌手排行 ──

@Composable
private fun ArtistRankSection(
    snapshot: StatsSnapshot,
    modifier: Modifier,
    onOpenScope: (StatsListScope, String) -> Unit,
) {
    val colors = LXTheme.colors
    var merged by rememberSaveable { mutableStateOf(true) }
    var withCover by rememberSaveable { mutableStateOf(true) }
    val info = snapshot.artistMerge
    val rows = if (merged) snapshot.artistsMerged else snapshot.artistsRaw
    // B1：空态照常渲染，用 maxOfOrNull 防崩溃、如实显示 0 位
    val maxPlays = max(1, rows.maxOfOrNull { it.plays } ?: 0)

    Section(
        title = "歌手排行",
        modifier = modifier,
        subtitle = if (merged) "归并后" else "原始",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LXViewToggleGroup {
                    LXViewToggle(LxIcons.CoverBox, "有图模式", withCover) { withCover = true }
                    LXViewToggle(LxIcons.ViewList, "无图模式", !withCover) { withCover = false }
                }
                LXViewToggleGroup {
                    LXViewToggle(LxIcons.Merge, "切换为${if (merged) "原始榜" else "归并后"}", merged) {
                        merged = !merged
                    }
                }
            }
        },
    ) {
        if (info.groupCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 13.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accent.copy(alpha = 0.13f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "发现 ${info.groupCount} 组同名歌手被拆成 ${info.splitEntries} 条，" +
                        "归并后榜首由 ${info.rawTopPlays} 次升到 ${info.mergedTopPlays} 次",
                    style = LXType.labelSmall,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (rows.isEmpty()) {
                Text(text = "本区间内 0 位歌手", style = LXType.labelSmall, color = colors.ink3)
            }
            rows.forEachIndexed { index, artist ->
                ArtistRow(
                    index = index + 1,
                    artist = artist,
                    fraction = artist.plays.toFloat() / maxPlays,
                    withCover = withCover && !rememberDataSaverNoImage(),
                    // 点歌手 → 该歌手的榜单列表页
                    onClick = { onOpenScope(StatsListScope.ARTIST, artist.name) },
                )
            }
        }
    }
}

/** 省流「全无图」时统计页一切封面强制隐藏（等同用户切了无图模式，但不改其选择记忆）（#128） */
@Composable
private fun rememberDataSaverNoImage(): Boolean =
    DataSaver.imagesBlockedFlow.collectAsState().value

@Composable
private fun ArtistRow(
    index: Int,
    artist: ArtistRank,
    fraction: Float,
    withCover: Boolean,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    RankRow(
        index = index,
        name = if (artist.mergedCount > 0) "${artist.name}（${artist.mergedCount} 条归并）" else artist.name,
        sub = "${artist.plays} 次 · ${artist.trackCount} 首",
        fraction = fraction,
        highlight = artist.mergedCount > 0,
        highlightColor = colors.accent,
        coverUrl = artist.coverUrl,
        withCover = withCover,
        onClick = onClick,
    )
}

/**
 * 排行一行。
 *
 * `withCover` = 有图模式：行首是封面缩略图；关掉后回到序号 ——
 * 与歌单页的双展示模式同一套语义（用户 2026-10-03 要求排行榜也要有）。
 */
@Composable
private fun RankRow(
    index: Int,
    name: String,
    sub: String,
    fraction: Float,
    coverUrl: String? = null,
    withCover: Boolean = false,
    highlight: Boolean = false,
    highlightColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val colors = LXTheme.colors
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "rankBar",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withCover) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LXTheme.pageInset),
            ) {
                AsyncArtwork(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(9.dp))
        } else {
            Text(
                text = "$index",
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.width(17.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = LXType.bodySmall,
                    color = if (highlight) highlightColor else colors.ink1,
                    fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = sub,
                    style = LXType.labelSmall,
                    color = colors.ink3,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LXTheme.pageInset),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.accent),
                )
            }
        }
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        style = LXType.labelSmall,
        color = LXTheme.colors.ink3,
        fontSize = 8.5.sp,
    )
}

/** 排行榜右侧的时长（mm:ss） */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
/** 配置子页操作回执（轻提示）的停留时长 */
private const val TOAST_DURATION_MS = 2_600L

// ═══════════════════════════════════════════════════════════════════════
// 白噪音子页（2026-10-26 用户要求：白噪音也要被统计，且独立成 Tab）
//
// 账本是 `noise_events`，一段连续收听 = 一行；「单独 / 伴随歌曲」按
// 当时是否真有歌在播归类（不看「与歌曲同时播放」开关）。
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun NoiseHeroCard(overview: NoiseOverview, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    LXCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(19.dp)) {
            Text(
                text = "YOUR WHITE NOISE LIFE",
                style = LXType.labelSmall,
                color = colors.ink3,
                letterSpacing = 1.8.sp,
            )
            Text(
                text = "你的白噪音时光",
                style = LXType.headlineLarge,
                color = colors.ink1,
                modifier = Modifier.padding(top = 7.dp),
            )
            Text(
                text = if (overview.sessionCount == 0) {
                    "还没有白噪音记录 —— 播放任意白噪音后，这里会自动开始统计；单独听与和歌曲一起听分开记账。"
                } else {
                    "单独聆听与伴随歌曲分开记账：只有歌曲真的在播，才算「与歌曲共同播放」。"
                },
                style = LXType.bodySmall,
                color = colors.ink3,
                modifier = Modifier.padding(top = 11.dp),
            )
            Box(Modifier.fillMaxWidth().padding(top = 15.dp).height(1.dp).background(colors.hairline))
            Row(
                modifier = Modifier.padding(top = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HeroStat(StatsCalculator.formatHours(overview.totalDurationMs), "累计时长")
                HeroStat("${overview.sessionCount} 段", "收听次数")
                HeroStat("${overview.activeDays} 天", "活跃天数")
            }
        }
    }
}

@Composable
private fun NoiseReportSection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    Section(title = "白噪音报告", modifier = modifier, subtitle = snapshot.range.label) {
        MetricGrid(
            items = listOf(
                Metric(StatsCalculator.formatHours(snapshot.totalMs), "收听时长"),
                Metric("${snapshot.sessionCount} 段", "收听次数"),
                Metric("${snapshot.activeDays} 天", "活跃天数"),
                Metric(StatsCalculator.formatHours(snapshot.dailyAvgMs), "日均（活跃天）"),
            ),
        )
    }
}

/** 聆听方式：单独忍耐 vs 有歌相伴（按真实是否同时有歌在播归类） */
@Composable
private fun NoiseSplitSection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    val total = snapshot.standaloneMs + snapshot.withMusicMs
    val standaloneShare = if (total > 0L) snapshot.standaloneMs.toFloat() / total else 0f
    val musicShare = if (total > 0L) snapshot.withMusicMs.toFloat() / total else 0f
    Section(title = "聆听方式", modifier = modifier, subtitle = "单独 / 伴随歌曲") {
        ChartPanel {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp)),
            ) {
                if (standaloneShare > 0f) {
                    Box(
                        Modifier
                            .weight(standaloneShare.coerceAtLeast(0.0001f))
                            .fillMaxHeight()
                            .background(colors.accent.copy(alpha = 0.35f)),
                    )
                }
                if (musicShare > 0f) {
                    Box(
                        Modifier
                            .weight(musicShare.coerceAtLeast(0.0001f))
                            .fillMaxHeight()
                            .background(colors.accent),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                NoiseSplitStat(
                    label = "单独聆听",
                    ms = snapshot.standaloneMs,
                    share = standaloneShare,
                    dot = colors.accent.copy(alpha = 0.35f),
                    modifier = Modifier.weight(1f),
                )
                NoiseSplitStat(
                    label = "伴随歌曲",
                    ms = snapshot.withMusicMs,
                    share = musicShare,
                    dot = colors.accent,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "开着「与歌曲同时播放」但那一刻没有歌在响，仍计为单独聆听。",
                style = LXType.labelSmall,
                color = colors.ink3,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

@Composable
private fun NoiseSplitStat(label: String, ms: Long, share: Float, dot: Color, modifier: Modifier = Modifier) {
    val colors = LXTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Column(modifier = Modifier.padding(start = 7.dp)) {
            Text(text = label, style = LXType.labelSmall, color = colors.ink3)
            Text(
                text = StatsCalculator.formatHours(ms),
                style = LXType.titleSmall,
                color = colors.ink1,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "${(share * 100).roundToInt()}%",
                style = LXType.labelSmall,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun NoiseHourlySection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    Section(title = "收听时段分布", modifier = modifier, subtitle = "24 小时") {
        ChartPanel {
            // 复用时段的条形图，秒数只用于相对高度（这张图看的是形状不是绝对值）
            HourlyBars(
                buckets = snapshot.hourly.map { HourBucket(it.hour, (it.durationMs / 1000L).toInt()) },
                peakHour = snapshot.peakHour,
                barColor = colors.accent.copy(alpha = 0.18f),
                peakColor = colors.accent,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("0", "6", "12", "18", "23").forEach { AxisLabel(it) }
            }
        }
        Text(
            text = "最常听 ${snapshot.peakHour} 时 · 累计 ${StatsCalculator.formatHours(snapshot.peakHourMs)}",
            style = LXType.labelSmall,
            color = colors.ink3,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun NoiseTrendSection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    val days = snapshot.trend
    Section(title = "白噪音时长趋势", modifier = modifier, subtitle = "近 ${days.size} 天") {
        ChartPanel {
            DayTrendChart(days, colors.accent, colors.surface)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(0, days.size / 2, days.lastIndex).distinct()
                    .filter { it in days.indices }
                    .forEach { AxisLabel(days[it].label) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            TrendCard(snapshot.trendCards.peakValue, "单日峰值", snapshot.trendCards.peakLabel, Modifier.weight(1f))
            TrendCard(snapshot.trendCards.last7Value, "近 7 天", snapshot.trendCards.last7Label, Modifier.weight(1f))
            TrendCard(
                snapshot.trendCards.workdayValue,
                "工作日均值",
                snapshot.trendCards.workdayLabel,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NoiseSoundRankSection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    val colors = LXTheme.colors
    val rows = snapshot.sounds
    // 多图层会话会给每个声音各计一遍，故百分比用「相对榜首」，不是占总量
    val maxValue = max(1L, rows.maxOfOrNull { it.durationMs } ?: 0L)
    Section(title = "白噪音排行", modifier = modifier, subtitle = "按时长") {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (rows.isEmpty()) {
                Text(text = "本区间内 0 条", style = LXType.labelSmall, color = colors.ink3)
            }
            rows.forEachIndexed { index, rank ->
                RankRow(
                    index = index + 1,
                    name = rank.name,
                    sub = "${StatsCalculator.formatHours(rank.durationMs)} / ${rank.sessions} 段",
                    fraction = rank.durationMs.toFloat() / maxValue,
                )
            }
        }
    }
}

@Composable
private fun NoiseSceneRankSection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    val scenes = snapshot.scenes
    if (scenes.isEmpty()) return
    val maxValue = max(1L, scenes.maxOfOrNull { it.durationMs } ?: 0L)
    Section(title = "场景排行", modifier = modifier, subtitle = "按场景") {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            scenes.forEachIndexed { index, scene ->
                RankRow(
                    index = index + 1,
                    name = scene.name,
                    sub = "${StatsCalculator.formatHours(scene.durationMs)} / ${scene.sessions} 段",
                    fraction = scene.durationMs.toFloat() / maxValue,
                )
            }
        }
    }
}

@Composable
private fun NoiseHabitsSection(snapshot: NoiseStatsSnapshot, modifier: Modifier) {
    if (snapshot.totalMs <= 0L) return
    Section(title = "收听习惯", modifier = modifier, subtitle = "行为维度") {
        MetricGrid(items = snapshot.habits)
    }
}
