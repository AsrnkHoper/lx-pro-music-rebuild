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
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 一次扫描的结果。`failedRoots` > 0 时**没有**清除那些目录的索引（避免权限临时失效误删）。 */
data class ScanOutcome(
    val mediaStoreFound: Int,
    val safFound: Int,
    val skippedAsDuplicate: Int,
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

    /**
     * 曲目列表。**已按指纹去重**：同一首歌既被媒体库索引、又被 SAF 目录扫到时只显示一条。
     *
     * ⚠️ 去重发生在展示层而不是删数据：DB 里两条 uri 都保留（来源不同、互不代表对方不存在），
     * 未来可作「这条来源播不了就换另一条」的后备。
     */
    fun observeTracks(): Flow<List<LocalTrackEntity>> = dao.observeAll().map { it.dedupeByFingerprint() }

    val safRoots: Flow<List<SafRootEntity>> = safRootDao.observeAll()

    /** 内存缓存：本地库规模有界，放内存里筛才谈得上「和在线一样的标点容错」 */
    private val cachedTracks = MutableStateFlow<List<LocalTrackEntity>>(emptyList())
    val allTracks: StateFlow<List<LocalTrackEntity>> = cachedTracks.asStateFlow()

    suspend fun refreshCache() {
        cachedTracks.value = dao.all().dedupeByFingerprint()
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
        // 需要媒体库已索引的路径集合来做排除，因此这里先跑一次媒体库查询（只取路径）
        val mediaScan = runCatching { scanner.scanMediaStore() }.getOrNull()
        scanSafChannel(treeUri, mediaScan?.paths.orEmpty())
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

        val mediaScan = runCatching { scanner.scanMediaStore() }.getOrNull()
        val mediaStoreFound = persistMediaStore(mediaScan)

        var safFound = 0
        var skipped = 0
        var failedRoots = 0
        safRootDao.all().forEach { root ->
            val result = scanSafChannel(root.treeUri, mediaScan?.paths.orEmpty())
            if (result == null) {
                failedRoots++
            } else {
                safFound += result.tracks.size
                skipped += result.skippedAsDuplicate
            }
        }

        refreshCache()
        return ScanOutcome(
            mediaStoreFound = mediaStoreFound,
            safFound = safFound,
            skippedAsDuplicate = skipped,
            failedRoots = failedRoots,
            scannedAt = scannedAt,
        )
    }

    /** @return 入库条数；媒体库扫描失败（如未授权）时返回 0 且**不清除**已有索引 */
    private suspend fun persistMediaStore(mediaScan: MediaStoreScan?): Int {
        if (mediaScan == null) return 0
        val batch = System.currentTimeMillis()
        if (mediaScan.tracks.isNotEmpty()) {
            dao.upsertAll(mediaScan.tracks.map { it.copy(lastScanBatch = batch) })
        }
        dao.deleteStaleByKind(LocalSourceKind.MEDIA_STORE, batch)
        return mediaScan.tracks.size
    }

    /** @return 扫到的条数与被跳过（媒体库已收录）的条数；null 表示该目录扫描失败（此时不做清除） */
    private suspend fun scanSafChannel(
        treeUri: String,
        excludePaths: Set<String>,
    ): SafScan? {
        val batch = System.currentTimeMillis()
        val scanned = runCatching { scanner.scanSafRoot(treeUri, excludePaths) }.getOrNull()
            ?: return null
        if (scanned.tracks.isNotEmpty()) {
            dao.upsertAll(scanned.tracks.map { it.copy(lastScanBatch = batch) })
        }
        dao.deleteStaleBySafRoot(treeUri, batch)
        return scanned
    }
}

/**
 * 按指纹去重，同指纹**优先保留 media_store 行**。
 *
 * 为什么优先媒体库：它的元数据由系统解析、更规整，且封面走 `loadThumbnail` 稳定拿得到；
 * SAF 行的标签要靠自己读 tag，封面还得退回读内嵌图。
 *
 * ⚠️ 这是兜底：主路径是扫描时就按文件路径排除重复（见 LocalMusicScanner.scanSafRoot）。
 * 兜底存在的原因是——某些机型上媒体库的 `DATA` 列可能为空，路径比对会失效。
 */
private fun List<LocalTrackEntity>.dedupeByFingerprint(): List<LocalTrackEntity> {
    if (size < 2) return this
    val merged = LinkedHashMap<String, LocalTrackEntity>(size)
    forEach { row ->
        val existing = merged[row.fingerprint]
        val preferRow = existing == null ||
            (existing.sourceKind != LocalSourceKind.MEDIA_STORE &&
                row.sourceKind == LocalSourceKind.MEDIA_STORE)
        if (preferRow) merged[row.fingerprint] = row
    }
    return merged.values.sortedByDescending { it.dateAddedSec }
}

/** 本地曲目 → 统一 Song 模型（`raw["uri"]` 是播放器直接播放用的文件地址） */
fun LocalTrackEntity.toSong(): Song = Song(
    id = uri,
    source = LOCAL_SOURCE_ID,
    name = title,
    singer = artist,
    albumName = album,
    interval = durationMs / 1000,
    // 封面走伪 URL：真实取图由 UI 层用 loadThumbnail / 内嵌图完成（见 LOCAL_ART_PREFIX 注释）
    picUrl = LOCAL_ART_PREFIX + Uri.encode(uri),
    raw = mapOf("uri" to uri),
)