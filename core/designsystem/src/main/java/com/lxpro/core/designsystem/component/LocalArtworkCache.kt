package com.lxpro.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
 * 本地曲目封面（专辑图）加载器。**两级降级**，两条通道都要有封面：
 *
 * 1. `ContentResolver.loadThumbnail` —— 快，对 MediaStore 的 content uri 稳定有效；
 * 2. `MediaMetadataRetriever.embeddedPicture` —— 读音频文件**内嵌**的专辑图（ID3/FLAC tag）。
 *    SAF 的 document uri 不保证有缩略图 provider，第 1 级会拿不到，必须靠这一级兜。
 *
 * ⚠️ 不要用 `content://media/external/audio/albumart/<id>`：该 URI 自 Android 10 起
 * 已被系统对三方应用关闭，会抛 SecurityException/FileNotFound。
 */
object LocalArtworkCache {

    private const val THUMBNAIL_SIZE = 256
    private const val MAX_ENTRIES = 64

    private val cache = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    fun peek(model: String?): ImageBitmap? = model?.let { cache.get(it) }

    suspend fun load(context: Context, model: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(model)?.let { return@withContext it }

        val fileUri = runCatching {
            Uri.parse(Uri.decode(model.removePrefix(LOCAL_ART_PREFIX)))
        }.getOrNull() ?: return@withContext null

        // 取不到封面不是错误，静默回落占位色即可
        val bitmap = runCatching { loadViaThumbnail(context, fileUri) }.getOrNull()
            ?: runCatching { loadViaEmbeddedPicture(context, fileUri) }.getOrNull()

        bitmap?.asImageBitmap()?.also { cache.put(model, it) }
    }

    private fun loadViaThumbnail(context: Context, fileUri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return context.contentResolver.loadThumbnail(
            fileUri,
            Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
            null,
        )
    }

    private fun loadViaEmbeddedPicture(context: Context, fileUri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            val bytes = retriever.embeddedPicture ?: return null
            decodeSampled(bytes, THUMBNAIL_SIZE)
        } catch (throwable: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 封面原图往往上千像素，列表里只显示 44dp，必须降采样再进缓存 */
    private fun decodeSampled(bytes: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetSize &&
            bounds.outHeight / (sampleSize * 2) >= targetSize
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}