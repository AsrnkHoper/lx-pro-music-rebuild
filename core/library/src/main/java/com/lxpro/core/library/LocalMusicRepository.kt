package com.lxpro.core.library

import com.lxpro.core.common.TextNormalizer
import android.net.Uri
import com.lxpro.core.database.dao.LocalTrackDao
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.model.Song
import com.lxpro.core.model.LOCAL_ART_PREFIX
import com.lxpro.core.model.LOCAL_SOURCE_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ScanOutcome(
    val found: Int,
    val scannedAt: Long,
)

/** 本地曲库（索引 + 查询 + 扫描同步）。 */
@Singleton
class LocalMusicRepository @Inject constructor(
    private val dao: LocalTrackDao,
    private val scanner: LocalMusicScanner,
) {

    val trackCount: Flow<Int> = dao.observeCount()

    fun observeTracks(): Flow<List<LocalTrackEntity>> = dao.observeAll()

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

    /**
     * 扫描并同步索引。
     *
     * 「标记-清除」：本次扫到的行打上当前批次，扫完后删掉批次不一致的行 ——
     * 既做到重复扫描不产生重复条目（主键 uri + upsert），也能感知文件被删除，
     * 且**不会**因为 `NOT IN (几千个 uri)` 撞上 SQLite 的变量上限。
     */
    suspend fun scan(): ScanOutcome {
        val batch = System.currentTimeMillis()
        val scanned = scanner.scan()
        if (scanned.isNotEmpty()) {
            dao.upsertAll(scanned.map { it.copy(lastScanBatch = batch) })
        }
        dao.deleteStale(batch)
        refreshCache()
        return ScanOutcome(found = scanned.size, scannedAt = batch)
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