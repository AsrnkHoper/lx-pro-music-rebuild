package com.lxpro.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lxpro.core.designsystem.component.LXCard
import com.lxpro.core.designsystem.component.LXSectionHeader
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.music.BuildConfig

/**
 * 关于页：应用名 + 版本 + 合规声明（02 §10.2）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = scalars.sidePadding, vertical = scalars.sectionGap),
    ) {
        TextButton(onClick = onBack) {
            Text(text = "← 返回", style = LXType.labelLarge, color = colors.accent)
        }

        Spacer(Modifier.height(LXDimens.space8))

        LXSectionHeader(title = "阿玉音乐")

        LXCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(LXDimens.space16)) {
                Text(
                    text = "版本  ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = LXType.bodyMedium,
                    color = colors.ink2,
                )
            }
        }

        Spacer(Modifier.height(scalars.sectionGap))

        LXSectionHeader(title = "合规声明")

        LXCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = COMPLIANCE_STATEMENT,
                style = LXType.bodyMedium,
                color = colors.ink2,
                modifier = Modifier.padding(LXDimens.space16),
            )
        }
    }
}

/** 02 §10.2 原文，应用名随正式定名更新 */
private const val COMPLIANCE_STATEMENT = """阿玉音乐是一款开源的本地音乐播放器。
本应用不提供、不分发、不推荐任何音乐内容或音源脚本。
用户自行导入的音源脚本由其自行承担相应责任。
本应用不对用户使用第三方音源产生的任何后果负责。
请支持正版音乐。"""