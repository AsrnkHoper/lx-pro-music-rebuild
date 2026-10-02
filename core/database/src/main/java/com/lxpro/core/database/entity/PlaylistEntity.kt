package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 收藏歌单的固定 id：它是系统歌单，不可改名/删除（02 §6.3） */
const val LIKED_PLAYLIST_ID = "liked"

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 系统歌单（收藏）——不可删除、不可改名 */
    val isSystem: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 歌单条目。
 *
 * ⚠️ 存**整份 `Song` 快照的 JSON**（`Song` 是 `@Serializable`）而不是拆成一堆列：
 * 在线歌曲来自音源脚本，`Song.raw` 里是取流必需的源特有字段（如 B 站的 bvid），
 * 拆列会漏；而且以后 `Song` 加字段也不必再迁移一次。
 *
 * `songName` / `singer` 单独冗余出来，只为列表展示与歌单内检索（不必反序列化全量）。
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "songId", "source"], unique = true),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: String,
    val songId: String,
    val source: String,
    val songJson: String,
    val songName: String,
    val singer: String,
    /** 冗余封面：网格页取「歌单内第一首有封面的歌」，纯 SQL 从 JSON 里掏不出来 */
    val coverUrl: String?,
    /** 歌单内顺序（从 0 递增） */
    val position: Int,
    val addedAt: Long,
)