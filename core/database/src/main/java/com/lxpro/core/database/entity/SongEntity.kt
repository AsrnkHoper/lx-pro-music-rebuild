package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/** 歌曲表：`(source, id)` 复合主键（02 §6.3 关键设计） */
@Entity(
    tableName = "songs",
    primaryKeys = ["source", "id"],
    indices = [Index("fingerprint")],
)
data class SongEntity(
    val source: String,
    val id: String,
    val name: String,
    val singer: String,
    val albumName: String?,
    val picUrl: String?,
    val interval: Long?,
    /** 跨源识别指纹（03 §4） */
    val fingerprint: String,
    /** 源特有字段 JSON */
    val raw: String,
    val updatedAt: Long,
)