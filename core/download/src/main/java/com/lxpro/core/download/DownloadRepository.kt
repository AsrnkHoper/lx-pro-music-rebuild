package com.lxpro.core.download

import com.lxpro.core.database.dao.DownloadTaskDao
import com.lxpro.core.database.entity.DownloadState
import com.lxpro.core.database.entity.DownloadTaskEntity
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 下载任务的键：`source:songId`（同一首歌重复下载直接覆盖，不产生两条） */
fun downloadKey(song: Song): String = "${song.source.value}:${song.id}"

/** 任务行 + 反序列化好的歌曲（UI 直接可用） */
data class DownloadItem(
    val task: DownloadTaskEntity,
    val song: Song,
) {
    val state: String get() = task.state
    val isRunning: Boolean get() = task.state == DownloadState.DOWNLOADING
    val isQueued: Boolean get() = task.state == DownloadState.QUEUED
    val isPaused: Boolean get() = task.state == DownloadState.PAUSED
    val isDone: Boolean get() = task.state == DownloadState.COMPLETED
    val isFailed: Boolean get() = task.state == DownloadState.FAILED
    val quality: Quality? get() = Quality.entries.firstOrNull { it.value == task.quality }
}

/**
 * 下载任务的仓储（**只管数据，不管网络**；网络与调度见 [DownloadEngine]）。
 *
 * 下载目录固定在**应用私有目录** `filesDir/downloads`：免掉 Android 10+ 分区存储授权，
 * 且「删任务 = 删文件」完全可控。
 */
@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadTaskDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun downloadDir(root: File): File = File(root, "downloads").apply { if (!exists()) mkdirs() }

    fun observeTasks(): Flow<List<DownloadTaskEntity>> = dao.observeAll()

    fun observeCompletedBytes(): Flow<Long> = dao.observeBytesByState(DownloadState.COMPLETED)

    fun observeActiveCount(): Flow<Int> =
        dao.observeCountByStates(listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING))

    suspend fun all(): List<DownloadTaskEntity> = dao.all()

    suspend fun find(id: String): DownloadTaskEntity? = dao.find(id)

    /** 入队：已存在则保留进度重新排队（而不是清零重下） */
    suspend fun enqueue(song: Song, quality: Quality, overwrite: Boolean = false): Boolean {
        val id = downloadKey(song)
        val now = System.currentTimeMillis()
        val existing = dao.find(id)
        if (existing != null && existing.state == DownloadState.COMPLETED && !overwrite) return false

        dao.upsert(
            DownloadTaskEntity(
                id = id,
                songId = song.id,
                source = song.source.value,
                name = song.name,
                singer = song.singer,
                albumName = song.albumName,
                coverUrl = song.picUrl,
                songJson = json.encodeToString(Song.serializer(), song),
                quality = quality.value,
                state = DownloadState.QUEUED,
                progress = if (existing != null && existing.quality == quality.value) existing.progress else 0,
                downloadedBytes = if (existing != null && existing.quality == quality.value) {
                    existing.downloadedBytes
                } else {
                    0L
                },
                totalBytes = existing?.takeIf { it.quality == quality.value }?.totalBytes ?: -1L,
                filePath = existing?.filePath,
                error = null,
                addedAt = existing?.addedAt ?: now,
                updatedAt = now,
            ),
        )
        return true
    }

    suspend fun updateProgress(id: String, progress: Int, downloaded: Long, total: Long) {
        dao.updateProgress(id, progress, downloaded, total, System.currentTimeMillis())
    }

    suspend fun markQueued(id: String) =
        dao.updateState(id, DownloadState.QUEUED, null, System.currentTimeMillis())

    suspend fun markDownloading(id: String) =
        dao.updateState(id, DownloadState.DOWNLOADING, null, System.currentTimeMillis())

    suspend fun markPaused(id: String) =
        dao.updateState(id, DownloadState.PAUSED, null, System.currentTimeMillis())

    suspend fun markFailed(id: String, reason: String?) =
        dao.updateState(id, DownloadState.FAILED, reason ?: "下载失败", System.currentTimeMillis())

    suspend fun markCompleted(id: String, file: File) =
        dao.markCompleted(id, file.absolutePath, file.length(), DownloadState.COMPLETED, System.currentTimeMillis())

    /** 全部暂停：排队中与下载中都转为暂停（下载中的由引擎取消 job 后落库） */
    suspend fun pauseAll(): Int = dao.updateStateBulk(
        from = DownloadState.QUEUED,
        to = DownloadState.PAUSED,
        updatedAt = System.currentTimeMillis(),
    )

    suspend fun runningIds(): List<String> =
        dao.all().filter { it.state == DownloadState.DOWNLOADING }.map { it.id }

    /** 全部继续：暂停的全部回到排队 */
    suspend fun resumeAll(): Int = dao.updateStateBulk(
        from = DownloadState.PAUSED,
        to = DownloadState.QUEUED,
        updatedAt = System.currentTimeMillis(),
    )

    suspend fun remove(id: String) = dao.delete(id)

    suspend fun completedTasks(): List<DownloadTaskEntity> =
        dao.all().filter { it.state == DownloadState.COMPLETED }

    suspend fun deleteCompleted() = dao.deleteByState(DownloadState.COMPLETED)

    /** 应用启动时把上次崩在「下载中」的任务复位成排队，否则它们永远停在那 */
    suspend fun resetStuck(): Int = dao.updateStateBulk(
        from = DownloadState.DOWNLOADING,
        to = DownloadState.QUEUED,
        updatedAt = System.currentTimeMillis(),
    )
}

internal fun DownloadTaskEntity.toSong(): Song? =
    runCatching { Json.decodeFromString(Song.serializer(), songJson) }.getOrNull()

/** 任务行 → UI 可直接用的条目；歌曲快照损坏时返回 null（跳过该行而不是崩） */
fun DownloadTaskEntity.toItemOrNull(): DownloadItem? = toSong()?.let { DownloadItem(this, it) }