package com.lxpro.core.designsystem.component

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lxpro.core.model.LOCAL_ART_PREFIX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地曲目封面（专辑图）加载器。
 *
 * ⚠️ 为什么不用 `content://media/external/audio/albumart/<id>`：
 * 该 content URI 自 **Android 10 起已被系统对三方应用关闭**，会抛 SecurityException/FileNotFound。
 * 现行做法是 `ContentResolver.loadThumbnail(fileUri, size, null)`（API 29+）。
 *
 * API 26–28 上取不到专辑图，回落为占位色块（这几版设备占比极低，封面属观感而非功能）。
 */
object LocalArtworkCache {

    private const val THUMBNAIL_SIZE = 256
    private const val MAX_ENTRIES = 64

    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    fun peek(model: String?): ImageBitmap? = model?.let { cache.get(it) }

    suspend fun load(context: Context, model: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(model)?.let { return@withContext it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null

        val fileUri = runCatching {
            Uri.parse(Uri.decode(model.removePrefix(LOCAL_ART_PREFIX)))
        }.getOrNull() ?: return@withContext null

        // 取不到封面不是错误，静默回落占位色即可
        runCatching {
            context.contentResolver
                .loadThumbnail(fileUri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
                .asImageBitmap()
        }.getOrNull()?.also { cache.put(model, it) }
    }
}