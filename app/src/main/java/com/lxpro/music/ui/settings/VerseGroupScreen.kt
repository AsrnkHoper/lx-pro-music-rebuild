package com.lxpro.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxpro.core.datastore.VERSES_PER_GROUP_LIMIT
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding

/**
 * 文案组详情（设置 → 文案自定义 → 点某个组）。
 *
 * 组内文案的查看 / 批量添加（每行一条，空行自动忽略）/ 点击改 / 行尾删；
 * 组本身可重命名、可整组删除（确认弹窗）。内置组同样可改 —— 高度自定义（用户 2026-10-25）。
 */
@Composable
fun VerseGroupScreen(
    groupId: String,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val state by viewModel.state.collectAsStateWithLifecycle()
    // DataStore 首读前几帧组列表为空，这时不能下「已删除」的结论
    val groupsLoaded = state.verseGroups.isNotEmpty()
    val group = state.verseGroups.firstOrNull { it.id == groupId }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editingVerse by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding(),
    ) {
        // ── 顶栏：返回 + crumb「文案 / 组名」 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = scalars.sidePadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(LxIcons.Back, contentDescription = "返回", tint = colors.ink1)
            }
            Text(
                text = "文案 / ${group?.name.orEmpty()}",
                style = LXType.titleSmall,
                color = colors.ink1,
            )
        }

        if (group == null) {
            Text(
                text = if (groupsLoaded) "该文案组不存在或已删除" else "加载中…",
                style = LXType.bodyMedium,
                color = colors.ink3,
                modifier = Modifier.padding(scalars.sidePadding),
            )
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = scalars.sidePadding)
                .padding(bottom = scalars.sectionGap),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(text = group.name, style = LXType.headlineLarge, color = colors.ink1)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${group.verses.size} 条 · " + if (group.builtin) "内置组" else "自建组",
                style = LXType.labelSmall,
                color = colors.ink3,
            )

            // ── 组管理 ──
            Text(
                text = "组管理",
                style = LXType.titleSmall,
                color = colors.ink1,
                modifier = Modifier.padding(top = 20.dp, bottom = 11.dp),
            )
            LXCard(modifier = Modifier.fillMaxWidth()) {
                VerseGroupActionRow(title = "重命名组", subtitle = "当前：${group.name}") {
                    showRenameDialog = true
                }
                VerseGroupDivider()
                VerseGroupActionRow(
                    title = "添加文案",
                    subtitle = "支持批量粘贴：每行一条，空行自动忽略（组内上限 $VERSES_PER_GROUP_LIMIT 条）",
                ) {
                    showAddDialog = true
                }
                VerseGroupDivider()
                VerseGroupActionRow(
                    title = "删除此组",
                    subtitle = "组内 ${group.verses.size} 条文案将一并删除",
                    danger = true,
                ) {
                    showDeleteDialog = true
                }
            }

            // ── 文案列表：点文案改，行尾删 ──
            Text(
                text = "文案（${group.verses.size}）",
                style = LXType.titleSmall,
                color = colors.ink1,
                modifier = Modifier.padding(top = 20.dp, bottom = 11.dp),
            )
            LXCard(modifier = Modifier.fillMaxWidth()) {
                if (group.verses.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                    ) {
                        Text(
                            text = "还没有文案，点上面「添加文案」批量粘贴",
                            style = LXType.labelLarge,
                            color = colors.ink3,
                        )
                    }
                } else {
                    group.verses.forEachIndexed { index, text ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingVerse = text }
                                .padding(start = 15.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = text,
                                style = LXType.labelLarge,
                                color = colors.ink1,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                viewModel.removeVerseFromGroup(groupId, text)
                            }) {
                                Icon(
                                    LxIcons.Trash,
                                    contentDescription = "删除这条文案",
                                    tint = colors.ink3,
                                )
                            }
                        }
                        if (index != group.verses.lastIndex) VerseGroupDivider()
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 弹窗层（放在 Column 外，组被删后仍能把弹窗关掉）──

    // 重命名组
    if (showRenameDialog && group != null) {
        var nameInput by remember(group.id) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名组", style = LXType.titleMedium) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameVerseGroup(groupId, nameInput) { renamed ->
                            onNotice(if (renamed) "已重命名" else "未改名：组名为空或与其他组重名")
                        }
                        showRenameDialog = false
                    },
                    enabled = nameInput.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
        )
    }

    // 批量添加文案（多行粘贴；trim / 去空行 / 去重 / 上限由仓库层保证）
    if (showAddDialog && group != null) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加文案", style = LXType.titleMedium) },
            text = {
                Column {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("每行一条，空行自动忽略…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 6,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已粘贴 ${input.lines().count { it.isNotBlank() }} 行 · " +
                            "组内现有 ${group.verses.size}/$VERSES_PER_GROUP_LIMIT 条",
                        style = LXType.labelSmall,
                        color = colors.ink3,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addVersesToGroup(groupId, input.lines()) { added ->
                            onNotice(
                                if (added > 0) "添加了 $added 条"
                                else "未添加：全是空行、与组内重复或组已满",
                            )
                        }
                        showAddDialog = false
                    },
                    enabled = input.isNotBlank(),
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }

    // 编辑单条文案
    editingVerse?.let { old ->
        var input by remember(old) { mutableStateOf(old) }
        AlertDialog(
            onDismissRequest = { editingVerse = null },
            title = { Text("编辑文案", style = LXType.titleMedium) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateVerseInGroup(groupId, old, input) { updated ->
                            onNotice(if (updated) "已保存" else "未修改：内容为空、未变化或与组内重复")
                        }
                        editingVerse = null
                    },
                    enabled = input.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingVerse = null }) { Text("取消") }
            },
        )
    }

    // 删除整组确认（危险操作才加确认层）
    if (showDeleteDialog && group != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除文案组", style = LXType.titleMedium) },
            text = {
                Text(
                    "确认删除「${group.name}」？组内 ${group.verses.size} 条文案将一并删除。",
                    style = LXType.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeVerseGroup(groupId)
                    showDeleteDialog = false
                    onBack()
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun VerseGroupActionRow(
    title: String,
    subtitle: String?,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LXType.labelLarge,
                color = if (danger) colors.danger else colors.ink1,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(text = subtitle, style = LXType.labelSmall, color = colors.ink3)
            }
        }
        Text(text = "›", style = LXType.titleSmall, color = colors.ink3)
    }
}

@Composable
private fun VerseGroupDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LXTheme.colors.hairline),
    )
}
