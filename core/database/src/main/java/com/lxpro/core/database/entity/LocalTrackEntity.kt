package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 本地曲库索引（M2 双模式支柱之一）。
 *
 * ⚠️ 这是**派生数据**：丢了只要重新扫描即可，但它仍存在 Room 里，走显式迁移不破坏用户数据。
 *
 * 去重键 = `uri`（MediaStore 的 content uri 稳定）；`lastScanBatch` 用于「标记-清除」式增量同步，
 * 避免用 `NOT IN (几千个 uri)` 触碰 SQLite 变量上限。
 */
@Entity(
    tableName = "local_tracks",
    indices = [Index("fingerprint"), Index("folder")],
)
data class LocalTrackEntity(
    /** MediaStore content uri，同时是主键 */
    @androidx.room.PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val folder: String?,
    val dateAddedSec: Long,
    /** 跨源识别指纹（03 §4），用于把本地文件与在线曲目认成同一首 */
    val fingerprint: String,
    val indexedAt: Long,
    /** 本次扫描批次；与当前批次不一致的行说明文件已被删除 */
    val lastScanBatch: Long,
)