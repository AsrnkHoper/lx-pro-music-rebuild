package com.lxpro.core.designsystem.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 页面级安全区内边距。
 *
 * 应用已 `enableEdgeToEdge()`，因此**所有页面级容器必须自己处理系统栏内边距**，
 * 否则页面标题会钻到状态栏下面被遮挡（2026-10-01 真机实测：`luming`）。
 *
 * ⚠️ 用法约定：
 * - 应用在**可滚动容器之外**（先内边距再滚动），否则顶部内边距会随内容滚走；
 * - M3 的 5 Tab 页面请由外层统一处理底部（迷你条 56dp + Tab 64dp），
 *   内容区只需本 Modifier 或 `WindowInsets.safeDrawing` 的上/左右。
 */
@Composable
fun Modifier.lxSafeDrawingPadding(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing)