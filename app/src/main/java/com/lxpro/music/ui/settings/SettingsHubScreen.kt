package com.lxpro.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding

/**
 * 设置 hub（M3 任务 9 的第一级）。
 *
 * 依据 `mockup_app.html` 的 `data-view="settings"`（`SET_CATS` / `renderHub`）：
 * 标题 + 说明 → 跨分类搜索框 → 9 个分类入口（每行 = 图标 + 分类名 + 状态摘要 + `›`）。
 * 搜索时把命中行平铺出来，副标题带上所属分类，点结果直达该分类详情。
 */
@Composable
fun SettingsHubScreen(
    onOpenCategory: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val state by viewModel.state.collectAsStateWithLifecycle()

    // hub 只读目录（摘要 + 搜索索引），动作一律留给详情页
    val catalog = remember(state) { buildSettingsCatalog(state, readOnlyHandlers()) }

    var query by remember { mutableStateOf("") }
    val keyword = normalize(query)
    val hits = remember(keyword, catalog) {
        if (keyword.isEmpty()) {
            emptyList()
        } else {
            catalog.flatMap { category ->
                category.groups.flatMap { group ->
                    group.rows.filter { row ->
                        normalize(row.title).contains(keyword) ||
                            normalize(row.subtitle.orEmpty()).contains(keyword)
                    }.map { category to it }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = scalars.sidePadding)
            // 底部 chrome（迷你条 + Tab 栏）占布局空间不浮层，内容区已被推到其上方，
            // 只需留 sectionGap 作呼吸间距即可
            .padding(bottom = scalars.sectionGap),
    ) {
        Spacer(Modifier.height(scalars.sectionGap))

        Text(text = "设置", style = LXType.headlineLarge, color = colors.ink1)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "分类导航 + 跨分类搜索；每个分类都有状态摘要，不进详情也知道现在是什么状态。",
            style = LXType.labelSmall,
            color = colors.ink3,
        )

        Spacer(Modifier.height(15.dp))

        SettingsSearchBox(
            value = query,
            onValueChange = { raw ->
                // 纯符号输入不参与检索（原型 renderHub 的行为）
                if (raw.isNotBlank() && raw.none { it.isLetterOrDigit() }) {
                    onNotice("先输入文字，纯符号不参与检索")
                } else {
                    query = raw
                }
            },
            onClear = { query = "" },
        )

        Spacer(Modifier.height(15.dp))

        if (keyword.isEmpty()) {
            LXCard(modifier = Modifier.fillMaxWidth()) {
                catalog.forEachIndexed { index, category ->
                    CategoryRow(
                        name = category.name,
                        summary = category.summary,
                        iconName = category.icon,
                        onClick = { onOpenCategory(category.key) },
                    )
                    if (index != catalog.lastIndex) RowDivider()
                }
            }
        } else if (hits.isEmpty()) {
            EmptyHint(title = "没有匹配的设置项", body = "换个关键词试试")
        } else {
            LXCard(modifier = Modifier.fillMaxWidth()) {
                hits.forEachIndexed { index, (category, row) ->
                    CategoryRow(
                        name = row.title,
                        summary = listOfNotNull(category.name, row.subtitle).joinToString(" · "),
                        iconName = category.icon,
                        onClick = { onOpenCategory(category.key) },
                    )
                    if (index != hits.lastIndex) RowDivider()
                }
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))
    }
}

/** 无动作的目录（hub 只读摘要与索引） */
private fun readOnlyHandlers(): SettingsHandlers = SettingsHandlers(
    onPalette = {},
    onAmbient = {},
    onDynamicBg = {},
    onDynamicBgBlur = {},
    onDynamicBgMask = {},
    onQuality = {},
    onImportScript = {},
    onScriptActive = {},
    onScriptRemove = {},
    onScriptTest = {},
    onConcurrency = {},
    onAutoImport = {},
    onRetryLimit = {},
    onFollowSystemDark = {},
    onRememberPosition = {},
    onPauseOnHeadphone = {},
    onPlayWithOtherApps = {},
    onDesktopLyric = {},
    onStatusBarLyric = {},
    onTraditionalLyric = {},
    onDesktopLyricLock = {},
    onDesktopLyricFontSize = {},
    onDesktopLyricAlignment = {},
    onDesktopLyricOpacity = {},
    onDesktopLyricPlayedColor = {},
    onDesktopLyricPendingColor = {},
    onIgnoreShortAudio = {},
    onAutoScanOnBoot = {},
    onWifiOnlyDownload = {},
    onDataSaverEnabled = {},
    onDataSaverAutoQuality = {},
    onDataSaverNoImage = {},
    onDataSaverApplyOnWifi = {},
    onShowCover = {},
    onNoiseWithMusic = {},
    onNoiseDefaultScene = {},
    onNoiseSleepTimer = {},
    onVerseGroupToggle = { _, _ -> },
    onOpenVerseGroup = {},
    onAddVerseGroupClick = {},
    onVerseSlot = { _, _ -> },
    onVerseInterval = {},
    onScanFolder = {},
    onImportMusic = {},
    onOpenAbout = {},
    onOpenEq = {},
    onNotice = {},
    onRequestBatteryOptimization = {},
    onOpenAutoStartSettings = {},
    onRefreshBatteryStatus = {},
    onEditShareTemplate = {},
    onSetShareTemplate = {},
)

/** 分类入口行：`[图标 34dp 圆角块][分类名 / 状态摘要][›]`（14 §4.5） */
@Composable
private fun CategoryRow(
    name: String,
    summary: String,
    iconName: ImageVector,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconName,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = LXType.labelLarge, color = colors.ink1)
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(text = "›", style = LXType.titleSmall, color = colors.ink3)
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LXTheme.colors.hairline),
    )
}

@Composable
private fun EmptyHint(title: String, body: String) {
    val colors = LXTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 44.dp, bottom = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = LXType.labelLarge, color = colors.ink2)
        Spacer(Modifier.height(6.dp))
        Text(text = body, style = LXType.labelSmall, color = colors.ink3)
    }
}

/** 跨分类搜索框（原型 `.searchwrap` / `.searchbox`：inset 底 + 13dp 圆角 + 右侧清除） */
@Composable
private fun SettingsSearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LXTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = LXType.bodyMedium,
        shape = RoundedCornerShape(13.dp),
        placeholder = {
            Text(
                text = "搜索设置项（如：淡入、音质、文案）",
                style = LXType.bodyMedium,
                color = colors.ink3,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = LxIcons.Search,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(17.dp),
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = LxIcons.Close,
                        contentDescription = "清除",
                        tint = colors.ink3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.ink1,
            unfocusedTextColor = colors.ink1,
            focusedContainerColor = colors.inset,
            unfocusedContainerColor = colors.inset,
            cursorColor = colors.accent,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
    )
}

/** 归一化：只留字母/数字并小写，容忍标点（打「淡入」或「danru」都能命中） */
private fun normalize(raw: String): String =
    raw.lowercase().filter { it.isLetterOrDigit() }