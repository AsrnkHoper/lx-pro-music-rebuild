package com.lxpro.core.designsystem.component

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.Song
import com.lxpro.core.model.SongLink
import java.io.File

/** 默认分享邀请语（与 SettingsRepository.DEFAULT_SHARE_TEMPLATE 保持一致） */
const val DEFAULT_SHARE_TEMPLATE =
    "我在阿玉音乐听《{title}-{artist}》，复制前往阿玉音乐聆听！{link}"

/**
 * 分享邀请语的运行时缓存。
 *
 * 为什么用内存缓存而不是各模块各自注入 DataStore：
 * `shareSong` 是普通（非 Composable）工具函数，被 app 与 feature 多个模块直接调用，
 * 让每个模块都依赖 core:datastore 会造成不必要的耦合。由 MainActivity 在收集
 * `settings.shareTemplate` 时写入这里，所有调用点自动拿到用户最新的自定义模板。
 */
object ShareTemplateCache {
    @Volatile
    var template: String = DEFAULT_SHARE_TEMPLATE
}

/** 模板填充：{title} 歌名、{artist} 艺术家、{link} 软链接 */
fun fillShareTemplate(template: String, title: String, artist: String, link: String): String =
    template
        .replace("{title}", title.ifBlank { "未命名歌曲" })
        .replace("{artist}", artist.ifBlank { "未知艺术家" })
        .replace("{link}", link)

/**
 * 复制文本到剪贴板，并**要求系统不要展开内容预览**（2026-10-27 用户反馈）。
 *
 * Android 13（API 33）起，应用在台前写剪贴板后系统会弹一块内容预览气泡。分享邀请语
 * 有一两百字，预览直接糊在页面上、连续复制还会叠两层，看起来像界面出了 bug。
 * 给剪贴数据打上 `EXTRA_IS_SENSITIVE` 后系统预览不再展示内容；**粘贴到的文本完全不变**。
 * 低版本系统没有这个开关，只能照常复制（由应用自己的轻提示回报「已复制」）。
 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}

/**
 * 分享歌曲：**自动区分本地 / 在线**（用户 2026-10-25 需求）。
 *
 * - 本地曲目：直接把音频源文件交给系统分享面板（`EXTRA_STREAM` + 读权限授予），
 *   对方拿到的是文件本身；下载目录在应用私有空间，故 file:// 必须过 FileProvider。
 * - 在线曲目：用分享邀请语模板生成有人情味的文案（含歌名/艺术家/软链接），
 *   复制到剪贴板 **并** 弹出系统分享面板，而不是只丢一个裸链接。
 *
 * @param template 邀请语模板，默认取 [ShareTemplateCache]，调用方也可显式传入
 */
fun shareSong(
    context: Context,
    song: Song,
    template: String = ShareTemplateCache.template,
) {
    if (song.source.value == LOCAL_SOURCE_ID.value) {
        val uri = localShareUri(context, song)
        if (uri == null) {
            Toast.makeText(context, "找不到这首歌的文件，无法分享", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }
        context.startActivity(Intent.createChooser(intent, "分享歌曲文件"))
        return
    }

    // 在线曲目：模板化邀请语 + 软链接
    val link = SongLink.encode(song)
    val text = fillShareTemplate(template, song.name, song.singer, link)
    copyToClipboard(context, "阿玉音乐分享邀请语", text)
    Toast.makeText(context, "邀请语已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, "分享歌曲")
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "分享到")) }
}

/** 本地曲目的可分享 uri：媒体库/SAF 的 content:// 直接用；下载来的 file:// 走 FileProvider */
private fun localShareUri(context: Context, song: Song): Uri? {
    val uri = runCatching { Uri.parse(song.raw["uri"] ?: song.id) }.getOrNull() ?: return null
    return when (uri.scheme) {
        "content" -> uri
        "file" -> {
            val path = uri.path ?: return null
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            }.getOrNull()
        }
        else -> null
    }
}
