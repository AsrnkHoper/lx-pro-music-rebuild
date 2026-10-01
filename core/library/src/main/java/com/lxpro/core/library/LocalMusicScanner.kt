package com.lxpro.core.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.model.Song
import com.lxpro.core.model.SongFingerprint
import com.lxpro.core.model.LOCAL_SOURCE_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 本地曲库扫描（MediaStore）。 */
@Singleton
class LocalMusicScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun scan(): List<LocalTrackEntity> = withContext(Dispatchers.IO) { queryMediaStore() }

    private fun queryMediaStore(): List<LocalTrackEntity> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        // IS_MUSIC 已排除铃声/通知音；再按最短时长滤掉音效碎片
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
            "AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_DURATION_MS.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        // key = uri，顺手在扫描阶段去重（多卷/重复挂载时 MediaStore 会给出重复行）
        val deduped = LinkedHashMap<String, LocalTrackEntity>()

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dataIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val path = if (dataIndex >= 0) cursor.getString(dataIndex) else null
                    val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                        ?: path?.let { File(it).nameWithoutExtension }
                        ?: continue
                    val artist = cursor.getString(artistIndex)
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                        ?: UNKNOWN_ARTIST
                    val durationMs = cursor.getLong(durationIndex)
                    val folder = path?.let { File(it).parentFile?.name }
                        ?: run {
                            val index = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                            if (index >= 0) cursor.getString(index)?.trimEnd('/') else null
                        }

                    deduped[uri] = LocalTrackEntity(
                        uri = uri,
                        title = title,
                        artist = artist,
                        album = cursor.getString(albumIndex)?.takeIf { it.isNotBlank() },
                        durationMs = durationMs,
                        sizeBytes = cursor.getLong(sizeIndex),
                        mimeType = cursor.getString(mimeIndex),
                        folder = folder,
                        dateAddedSec = cursor.getLong(dateIndex),
                        fingerprint = SongFingerprint.of(title, artist),
                        indexedAt = System.currentTimeMillis(),
                        lastScanBatch = 0L,
                    )
                }
            }

        return deduped.values.toList()
    }

    private companion object {
        /** 短于 10 秒的不当作"音乐"（音效/提示音碎片） */
        const val MIN_DURATION_MS = 10_000L
        const val UNKNOWN_ARTIST = "未知艺术家"
    }
}