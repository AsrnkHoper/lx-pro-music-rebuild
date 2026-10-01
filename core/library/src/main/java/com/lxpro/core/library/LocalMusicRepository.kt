package com.lxpro.core.library

import android.net.Uri
import com.lxpro.core.common.TextNormalizer
import com.lxpro.core.database.dao.LocalTrackDao
import com.lxpro.core.database.dao.SafRootDao
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.database.entity.SafRootEntity
import com.lxpro.core.model.LOCAL_ART_PREFIX
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.LocalSourceKind
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 一次扫描的结果。`failedRoots` > 0 时**没有**清除那些目录的索引（避免权限临时失效误删）。 */
data class ScanOutcome(
    val mediaStoreFound: Int,
    val safFound: Int,
    val failedRoots: Int,
    val scannedAt: Long,
) {
    val found: Int get() = mediaStoreFound + safFound
}

/** 本地曲库（索引 + 查询 + 扫描同步）。 */
@Singleton
class LocalMusicRepository @Inject constructor(
    private val dao: LocalTrackDao,
    private val safRootDao: SafRootDao,
    private val scanner: LocalMusicScanner,
) {

    val trackCount: Flow<Int> = dao.observeCount()

    fun observeTracks(): Flow<List<LocalTrackEntity>> = dao.observeAll()

    val safRoots: Flow<List<SafRootEntity>> = safRootDao.observeAll()

    /** 内存缓存：本地库规模有界，放内存里筛才谈得上「和在线一样的标点容错」 */
    private val cachedTracks = MutableStateFlow<List<LocalTrackEntity>>(emptyList())
    val allTracks: StateFlow<List<LocalTrackEntity>> = cachedTracks.asStateFlow()

    suspend fun refreshCache() {
        cachedTracks.value = dao.all()
    }

    /**
     * 本地检索。
     * ⚠️ 复用 [TextNormalizer]，与在线搜索**同一套标点容错语义**——
     * 否则「luvsicpt3」在在线能搜到、切到本地却搜不到，用户会认为本地模式是坏的。
     */
    fun searchInMemory(keyword: String): List<LocalTrackEntity> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        return cachedTracks.value
            .filter { TextNormalizer.matches(it.title, query) || TextNormalizer.matches(it.artist, query) }
            .sortedBy { it.title.lowercase() }
    }

    /** 用户新增授权目录：先落库再立刻扫一遍，让他马上看到东西 */
    suspend fun addSafRoot(treeUri: String, displayName: String) {
        safRootDao.insert(
            SafRootEntity(treeUri = treeUri, displayName = displayName, addedAt = System.currentTimeMillis()),
        )
        scanSafChannel(treeUri)
        refreshCache()
    }

    /** 移除授权目录：连带清掉它贡献的索引行 */
    suspend fun removeSafRoot(treeUri: String) {
        safRootDao.delete(treeUri)
        dao.deleteBySafRoot(treeUri)
        refreshCache()
    }

    /** 全量扫描：媒体库 + 所有已授权目录。 */
    suspend fun scan(): ScanOutcome {
        val scannedAt = System.currentTimeMillis()
        val mediaStoreFound = scanMediaStoreChannel()
        var safFound = 0
        var failedRoots = 0

        safRootDao.all().forEach { root ->
            val count = scanSafChannel(root.treeUri)
            if (count == null) failedRoots++ else safFound += count
        }

        refreshCache()
        return ScanOutcome(
            mediaStoreFound = mediaStoreFound,
            safFound = safFound,
            failedRoots = failedRoots,
            scannedAt = scannedAt,
        )
    }

    private suspend fun scanMediaStoreChannel(): Int {
        val batch = System.currentTimeMillis()
        // 扫描失败（如未授权）时**不清除**已有索引，否则一次失败就把库清空了
        val scanned = runCatching { scanner.scanMediaStore() }.getOrElse { return 0 }
        if (scanned.isNotEmpty()) {
            dao.upsertAll(scanned.map { it.copy(lastScanBatch = batch) })
        }
        dao.deleteStaleByKind(LocalSourceKind.MEDIA_STORE, batch)
        return scanned.size
    }

    /** @return 扫到的条数；null 表示该目录扫描失败（此时不做清除） */
    private suspend fun scanSafChannel(treeUri: String): Int? {
        val batch = System.currentTimeMillis()
        val scanned = runCatching { scanner.scanSafRoot(treeUri) }.getOrNull() ?: return null
        if (scanned.isNotEmpty()) {
            dao.upsertAll(scanned.map { it.copy(lastScanBatch = batch) })
        }
        dao.deleteStaleBySafRoot(treeUri, batch)
        return scanned.size
    }
}

/** 本地曲目 → 统一 Song 模型（`raw["uri"]` 是播放器直接播放用的文件地址） */
fun LocalTrackEntity.toSong(): Song = Song(
    id = uri,
    source = LOCAL_SOURCE_ID,
    name = title,
    singer = artist,
    albumName = album,
    interval = durationMs / 1000,
    // 封面走伪 URL：真实取图由 UI 层用 loadThumbnail 完成（见 LOCAL_ART_PREFIX 注释）
    picUrl = LOCAL_ART_PREFIX + Uri.encode(uri),
    raw = mapOf("uri" to uri),
)