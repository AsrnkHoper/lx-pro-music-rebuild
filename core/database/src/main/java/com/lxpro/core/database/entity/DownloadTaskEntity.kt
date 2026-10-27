package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 下载任务状态（15 §6.2 的状态机：排队 → 下载中 ⇄ 暂停 → 已完成 / 失败） */
object DownloadState {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

/**
 * 下载任务（M3 任务 8）。
 *
 * 为什么落库而不是只放内存：暂停/失败要跨进程存活，且「存储占用」要能算出来。
 * 主键用 `source:songId` —— 同一首歌重复下载直接覆盖，不会出现两条同曲任务。
 *
 * [filePath] 是**应用私有目录**里的绝对路径（`filesDir/downloads/…`）：
 * 不写公共存储可以免掉 Android 10+ 的分区存储授权，也让「删任务 = 删文件」可控。
 * 完成后由 [com.lxpro.core.library.LocalMusicRepository] 登记进本地曲库（双模式闭环）。
 */
@Entity(
    tableName = "download_tasks",
    indices = [Index("state"), Index("addedAt")],
)
data class DownloadTaskEntity(
    /** `source:songId` */
    @PrimaryKey val id: String,
    val songId: String,
    val source: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val coverUrl: String?,
    /** 整份歌曲快照：完成后要登记进本地库、也要能直接起播 */
    val songJson: String,
    /** [com.lxpro.core.model.Quality.value]，如 `flac` / `320k` */
    val quality: String,
    /** [DownloadState] */
    val state: String,
    /** 0–100 */
    val progress: Int = 0,
    val downloadedBytes: Long = 0L,
    /** 服务端返回的总字节；-1 = 未知（部分 CDN 不给 content-length） */
    val totalBytes: Long = -1L,
    val filePath: String? = null,
    /** 失败原因（如「音源不可用」） */
    val error: String? = null,
    val addedAt: Long,
    val updatedAt: Long,
)