package com.lxpro.core.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.model.LocalSourceKind
import com.lxpro.core.model.SongFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 媒体库扫描结果：除曲目外还带出**文件路径集合**，供 SAF 扫描排除重复。 */
data class MediaStoreScan(
    val tracks: List<LocalTrackEntity>,
    val paths: Set<String>,
)

/** SAF 目录扫描结果。`skippedAsDuplicate` = 因媒体库已收录而跳过的文件数（给用户一个诚实交代）。 */
data class SafScan(
    val tracks: List<LocalTrackEntity>,
    val skippedAsDuplicate: Int,
)

/**
 * 本地曲库扫描。两个通道：
 * - **MediaStore**：系统媒体库，覆盖主流场景
 * - **SAF 授权目录**：MediaStore 未索引的目录（如某些 App 私有目录、新拷贝未触发扫描的文件）
 *
 * ⚠️ 两个通道产出的 uri 不同（`content://media/...` vs document uri），同一首歌可能两条，
 * 因此：扫描时按文件路径排除重复，清除策略按通道隔离
 * （见 [com.lxpro.core.database.dao.LocalTrackDao.deleteStaleByKind]）。
 */
@Singleton
class LocalMusicScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun scanMediaStore(): MediaStoreScan =
        withContext(Dispatchers.IO) { queryMediaStore() }

    /**
     * 递归扫描一个 SAF 授权目录。
     *
     * @param excludePaths 媒体库已索引的文件路径集合。**命中则跳过**——
     *   这些文件已经以 media_store 身份在库里，且元数据与封面更可靠；
     *   若这条目录之后被系统媒体库补建索引，下次扫描同样会跳过并触发标记-清除，自动收敛。
     *   ⚠️ SAF 不提供时长/标签，只能逐文件用 [MediaMetadataRetriever] 读 —— 因此比 MediaStore 慢。
     *   若抛出异常（权限失效等），调用方**不应**清空该目录的索引。
     */
    suspend fun scanSafRoot(
        treeUri: String,
        excludePaths: Set<String> = emptySet(),
    ): SafScan = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("授权目录不可读（可能权限已失效）：$treeUri")

        val result = LinkedHashMap<String, LocalTrackEntity>()
        var skipped = 0
        // 用显式队列而不是递归：深层目录树不至于爆栈
        val queue = ArrayDeque<Pair<DocumentFile, String?>>()
        tree.listFiles().forEach { queue.addLast(it to tree.name) }

        while (queue.isNotEmpty()) {
            val (doc, folderName) = queue.removeFirst()
            if (doc.isDirectory) {
                doc.listFiles().forEach { queue.addLast(it to (doc.name ?: folderName)) }
                continue
            }
            val name = doc.name ?: continue
            if (!isAudio(name, doc.type)) continue

            // 媒体库已经有了同一份文件 → 跳过，避免同一首歌在列表里出现两次
            val resolvedPath = documentUriToPath(doc.uri)
            if (resolvedPath != null && resolvedPath in excludePaths) {
                skipped++
                continue
            }

            val uri = doc.uri.toString()
            val meta = readMetadata(doc.uri)
            val durationMs = meta?.durationMs ?: 0L
            // 只在「读到了时长且明显过短」时跳过；读不到时长的不敢滤，宁可留着
            if (durationMs in 1 until MIN_DURATION_MS) continue

            val title = meta?.title?.takeIf { it.isNotBlank() }
                ?: name.substringBeforeLast('.', name)
            val artist = meta?.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST

            result[uri] = LocalTrackEntity(
                uri = uri,
                title = title,
                artist = artist,
                album = meta?.album?.takeIf { it.isNotBlank() },
                durationMs = durationMs,
                sizeBytes = doc.length(),
                mimeType = doc.type,
                folder = folderName,
                dateAddedSec = doc.lastModified() / 1000,
                fingerprint = SongFingerprint.of(title, artist),
                indexedAt = System.currentTimeMillis(),
                lastScanBatch = 0L,
                sourceKind = LocalSourceKind.SAF,
                safRootUri = treeUri,
            )
        }
        SafScan(tracks = result.values.toList(), skippedAsDuplicate = skipped)
    }

    private fun queryMediaStore(): MediaStoreScan {
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
        val paths = mutableSetOf<String>()

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
                    if (!path.isNullOrBlank()) paths += path
                    val title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                        ?: path?.let { File(it).nameWithoutExtension }
                        ?: continue
                    val artist = cursor.getString(artistIndex)
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                        ?: UNKNOWN_ARTIST
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
                        durationMs = cursor.getLong(durationIndex),
                        sizeBytes = cursor.getLong(sizeIndex),
                        mimeType = cursor.getString(mimeIndex),
                        folder = folder,
                        dateAddedSec = cursor.getLong(dateIndex),
                        fingerprint = SongFingerprint.of(title, artist),
                        indexedAt = System.currentTimeMillis(),
                        lastScanBatch = 0L,
                        sourceKind = LocalSourceKind.MEDIA_STORE,
                        safRootUri = null,
                    )
                }
            }

        return MediaStoreScan(tracks = deduped.values.toList(), paths = paths)
    }

    /**
     * SAF 的 document uri 反解成真实文件路径，用于和媒体库的 DATA 列比对。
     *
     * 只对 `com.android.externalstorage.documents` 这个 provider 有效（内部存储与 SD 卡都是它）；
     * 其它 provider（云盘等）解不出来，返回 null —— 此时**不排除**，因为那些文件本来就不在媒体库里。
     *
     * ⚠️ 已知局限：若某些机型/版本上媒体库的 `DATA` 列为空，路径比对会失效，
     * 届时由展示层按 fingerprint 去重兜底（见 LocalMusicRepository.dedupeByFingerprint）。
     */
    private fun documentUriToPath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null

        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return "$base/$relative"
    }

    private data class AudioMeta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
    )

    /** SAF 侧必须自己读标签：这些文件可能压根不在媒体库里 */
    private fun readMetadata(uri: Uri): AudioMeta? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            AudioMeta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun isAudio(name: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("audio/") == true) return true
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }

    private companion object {
        /** 短于 10 秒的不当作"音乐"（音效/提示音碎片） */
        const val MIN_DURATION_MS = 10_000L
        const val UNKNOWN_ARTIST = "未知艺术家"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus",
            "wma", "ape", "aiff", "aif", "mp2", "mka", "alac",
        )
    }
}