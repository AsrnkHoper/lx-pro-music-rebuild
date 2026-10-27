package com.lxpro.core.stats

import com.lxpro.core.database.dao.ArtistIconOverrideDao
import com.lxpro.core.database.entity.ArtistIconOverrideEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首页「常听歌手」图标的用户自定义覆盖（默认是 token 底色 + 首字）。
 *
 * 键的口径 = 统计歌手归并的键（[StatsCalculator.normalizeArtist]）——
 * 同名异写被归并成一行时，只需要设一次图标。
 */
@Singleton
class ArtistIconOverrideRepository @Inject constructor(
    private val dao: ArtistIconOverrideDao,
) {

    /** artistKey → 覆盖项 */
    fun observeAll(): Flow<Map<String, ArtistIconOverrideEntity>> =
        dao.observeAll().map { rows -> rows.associateBy { it.artistKey } }

    /** 用某首歌的封面作图标 */
    suspend fun setSongCover(artistKey: String, coverUrl: String) = dao.upsert(
        ArtistIconOverrideEntity(
            artistKey = artistKey,
            kind = ArtistIconOverrideEntity.KIND_SONG_COVER,
            value = coverUrl,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    /** 用本地图片作图标（调用方须已 takePersistableUriPermission） */
    suspend fun setLocalImage(artistKey: String, imageUri: String) = dao.upsert(
        ArtistIconOverrideEntity(
            artistKey = artistKey,
            kind = ArtistIconOverrideEntity.KIND_LOCAL_IMAGE,
            value = imageUri,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    /** 恢复默认（token 底色 + 首字） */
    suspend fun clear(artistKey: String) = dao.delete(artistKey)
}
