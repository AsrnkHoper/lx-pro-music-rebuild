package com.lxpro.music.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxpro.core.database.entity.SongTagEntity
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.library.SongTagFacts
import com.lxpro.core.library.SongTagRepository
import com.lxpro.core.model.Song
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 「试试这些」的预置建议（用户自己写永远优先；这里只是给个起点） */
private val SUGGESTED_TAGS = listOf("睡前", "通勤", "循环", "治愈", "专注", "雨天", "跑步", "回忆")

/**
 * 歌曲标签页（2026-10-26 用户要求第 8 项）。
 *
 * 两层标签：
 * - **软件自贴**（硬性）：初次遇见 / 播放次数 / 累计收听 / 常听时段 / 最近播放 / 平均完整度 / 听过的天数
 *   —— 全部由 `stats_events` 实时聚合，不落库、不可编辑、不会与统计页出现两套数字；
 * - **用户自贴**（可选）：自由文字，最多 12 个 × 12 字，可随时删改。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SongTagsSheet(
    state: SongTagsUiState,
    message: String?,
    onAddTag: (String) -> Unit,
    onRemoveTag: (SongTagEntity) -> Unit,
    onMessageShown: () -> Unit,
    onDismiss: () -> Unit,
) {
    val song = state.song ?: return
    val colors = LXTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember(song.id, song.source.value) { mutableStateOf("") }

    // 提示（重复 / 超上限）短暂停留后自动消失
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_600L)
            onMessageShown()
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        onAddTag(text)
        input = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        scrimColor = colors.ink1.copy(alpha = 0.42f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 9.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.hairline),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TagsHeader(song)

            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionLabel("软件标签")
                if (state.facts.firstPlayedAt == null) {
                    Hint("还没有播放记录 —— 听过一次，这些标签就会自己长出来。")
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        autoFacts(state.facts).forEach { (label, value) ->
                            AutoTagChip(label = label, value = value)
                        }
                    }
                }

                SectionLabel(
                    "我的标签",
                    trailing = "${state.tags.size}/${SongTagRepository.MAX_TAGS_PER_SONG}",
                )
                if (state.tags.isEmpty()) {
                    Hint("还没有标签。写一句你的感受，或者点下面的建议。")
                } else {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.tags.forEach { tag ->
                            UserTagChip(tag.tag) { onRemoveTag(tag) }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(SongTagRepository.MAX_TAG_LENGTH) },
                        placeholder = {
                            Text(
                                text = "给这首歌贴个标签",
                                style = LXType.labelSmall,
                                color = colors.ink3,
                            )
                        },
                        singleLine = true,
                        textStyle = LXType.labelLarge,
                        shape = RoundedCornerShape(LXDimens.radiusSmall),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.ink1,
                            unfocusedTextColor = colors.ink1,
                            focusedContainerColor = colors.bg2,
                            unfocusedContainerColor = colors.bg2,
                            cursorColor = colors.accent,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.hairline,
                        ),
                    )
                    TextButton(
                        onClick = { submit() },
                        enabled = input.isNotBlank() &&
                            state.tags.size < SongTagRepository.MAX_TAGS_PER_SONG,
                    ) {
                        Text(text = "添加", style = LXType.labelLarge, color = colors.accent)
                    }
                }

                message?.let {
                    Text(
                        text = it,
                        style = LXType.labelSmall,
                        color = colors.danger,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                val remaining = SUGGESTED_TAGS.filterNot { suggestion ->
                    state.tags.any { it.tag.equals(suggestion, ignoreCase = true) }
                }
                if (remaining.isNotEmpty() && state.tags.size < SongTagRepository.MAX_TAGS_PER_SONG) {
                    SectionLabel("试试这些")
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        remaining.forEach { suggestion ->
                            SuggestChip(suggestion) {
                                onAddTag(suggestion)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** 软件自贴标签的措辞（纯展示；数据全部来自 [SongTagFacts]） */
private fun autoFacts(facts: SongTagFacts): List<Pair<String, String>> {
    val zone = ZoneId.systemDefault()
    val list = mutableListOf<Pair<String, String>>()
    facts.firstPlayedAt?.let { at ->
        list += "初见" to formatDate(Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
    }
    list += "播放" to when {
        facts.plays > 0 -> "${facts.plays} 次"
        facts.playStarts > 0 -> "${facts.playStarts} 次起播"
        else -> "0 次"
    }
    if (facts.listenedMs > 0L) {
        list += "累计" to formatDuration(facts.listenedMs)
    }
    if (facts.activeDays > 0) {
        list += "听过" to "${facts.activeDays} 天"
    }
    facts.peakHour?.let { hour ->
        list += "常在" to peakHourLabel(hour)
    }
    facts.lastPlayedAt?.let { at ->
        list += "最近" to relativeDay(at, zone)
    }
    facts.completionPermille?.let { permille ->
        list += "平均听完" to "${(permille / 10)}%"
    }
    return list
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.CHINA))

private fun relativeDay(at: Long, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)) {
        0L -> "今天"
        1L -> "昨天"
        in 2L..6L -> "$days 天前"
        else -> formatDate(day)
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        minutes < 1L -> "不足 1 分钟"
        minutes < 60L -> "$minutes 分钟"
        else -> {
            val hours = minutes / 60L
            val rest = minutes % 60L
            if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分"
        }
    }
}

/** 最常听时段：以峰值整点为中心取 2 小时窗口 */
private fun peakHourLabel(hour: Int): String {
    val start = (hour - 1).coerceIn(0, 22)
    return when (start) {
        in 0..4 -> "凌晨 ${start}–${start + 2} 点"
        in 5..8 -> "早晨 ${start}–${start + 2} 点"
        in 9..11 -> "上午 ${start}–${start + 2} 点"
        in 12..13 -> "午间 ${start}–${start + 2} 点"
        in 14..17 -> "下午 ${start}–${start + 2} 点"
        else -> "晚上 ${start}–${start + 2} 点"
    }
}

// ─────────────────────────── 零件 ───────────────────────────

@Composable
private fun TagsHeader(song: Song) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(LXDimens.radiusSmall))
                .background(colors.inset),
        ) {
            AsyncArtwork(
                model = song.picUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "标签", style = LXType.titleSmall, color = colors.ink1)
            Spacer(Modifier.height(3.dp))
            Text(
                text = listOf(song.name, song.singer).filter { it.isNotBlank() }.joinToString(" · "),
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = LXType.labelSmall, color = colors.ink3, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(text = trailing, style = LXType.labelSmall, color = colors.ink3)
        }
    }
}

@Composable
private fun Hint(text: String) {
    val colors = LXTheme.colors
    Text(
        text = text,
        style = LXType.labelSmall,
        color = colors.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(LXDimens.radiusSmall))
            .background(colors.inset)
            .padding(horizontal = 13.dp, vertical = 11.dp),
    )
}

@Composable
private fun AutoTagChip(label: String, value: String) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.inset)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = LXType.labelSmall, color = colors.ink3)
        Spacer(Modifier.width(6.dp))
        Text(text = value, style = LXType.labelLarge, color = colors.ink1)
    }
}

@Composable
private fun UserTagChip(tag: String, onRemove: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent.copy(alpha = 0.14f))
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = tag, style = LXType.labelLarge, color = colors.accent)
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LxIcons.Close,
                contentDescription = "删除标签 $tag",
                tint = colors.accent,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun SuggestChip(tag: String, onClick: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "+", style = LXType.labelLarge, color = colors.ink3)
        Spacer(Modifier.width(5.dp))
        Text(text = tag, style = LXType.labelLarge, color = colors.ink2)
    }
}
