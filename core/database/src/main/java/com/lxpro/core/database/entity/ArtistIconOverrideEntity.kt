package com.lxpro.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 首页「常听歌手」图标的用户自定义（默认是 token 底色 + 首字）。
 *
 * @property artistKey 归一化后的歌手名（与统计归并 `StatsCalculator.normalizeArtist` 同一个键）——
 *   「DJ Okawari」被拆成 8 个条目的情况只设一次图标
 * @property kind `song_cover`（用某首歌的封面，value = 封面 url）/ `local_image`（本地图片 content uri）
 * @property value kind 对应的取值
 */
@Entity(tableName = "artist_icon_overrides")
data class ArtistIconOverrideEntity(
    @PrimaryKey val artistKey: String,
    val kind: String,
    val value: String,
    val updatedAt: Long,
) {
    companion object {
        const val KIND_SONG_COVER = "song_cover"
        const val KIND_LOCAL_IMAGE = "local_image"
    }
}
