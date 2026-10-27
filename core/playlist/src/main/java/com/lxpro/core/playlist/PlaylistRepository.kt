package com.lxpro.core.playlist

import com.lxpro.core.database.dao.PlaylistDao
import com.lxpro.core.database.dao.PlaylistSummaryRow
import com.lxpro.core.database.entity.LIKED_PLAYLIST_ID
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.database.entity.PlaylistItemEntity
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 歌单 + 收藏（02 §6.3：收藏是 id 固定为 `liked` 的系统歌单，不可删除）。 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val overrides: SongOverrideRepository,
) {

    private val json = Json { encodeDefaults = true }

    fun observePlaylists(): Flow<List<PlaylistEntity>> = dao.observePlaylists()

    fun observePlaylist(id: String): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun observeItemCount(playlistId: String): Flow<Int> = dao.observeItemCount(playlistId)

    /** 网格页用：playlistId → (条目数, 封面) */
    fun observeSummaries(): Flow<Map<String, PlaylistSummaryRow>> =
        dao.observeSummaries().map { rows -> rows.associateBy { it.playlistId } }

    /** 一级页「含本地」筛选：至少含一首本地曲目的歌单 */
    fun observePlaylistIdsWithLocal(): Flow<Set<String>> =
        dao.observePlaylistIdsWithSource(LOCAL_SOURCE_ID.value).map { it.toSet() }

    /** 歌单内歌曲（**已套用「重命名」修正**，与统计归并口径一致） */
    fun observeSongs(playlistId: String): Flow<List<Song>> =
        combine(dao.observeItems(playlistId), overrides.observe()) { rows, overrideMap ->
            rows.map { row ->
                row.toSong().withOverride(overrideMap[songOverrideKey(row.songId, row.source)])
            }
        }

    fun observeLiked(songId: String, source: String): Flow<Boolean> =
        dao.observeContains(LIKED_PLAYLIST_ID, songId, source)

    /** 保证「收藏」这个系统歌单存在（首次进入歌单页时调用）；系统歌单固定 position = 0 */
    suspend fun ensureSystemPlaylists() {
        if (dao.findPlaylist(LIKED_PLAYLIST_ID) == null) {
            val now = System.currentTimeMillis()
            dao.insertPlaylist(
                PlaylistEntity(
                    id = LIKED_PLAYLIST_ID,
                    name = "收藏",
                    isSystem = true,
                    createdAt = now,
                    updatedAt = now,
                    position = 0,
                ),
            )
        }
    }

    /**
     * @return 新建歌单的 id；名称为空时自动取一个不重名的默认名
     *
     * 排序位：插在系统歌单（position 0）之后 —— 其余歌单整体后移一位，保持「新建的在最前」
     * 与迁移前 `updatedAt DESC` 的观感一致。
     *
     * ⚠️ 默认名必须在**这里**查库算：早期由调用方传 `existingNames`，但界面上那份列表
     * 要么被筛选/搜索筛短、要么在 ViewModel 重建后是空的 —— 结果所有自动命名都叫「新歌单 1」
     * （2026-10-02 真机反馈）。
     */
    suspend fun createPlaylist(rawName: String): String {
        val name = PlaylistNames.normalize(rawName)
            ?: PlaylistNames.nextDefaultName(dao.getPlaylistNames())
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.shiftPositionsForInsert()
        dao.insertPlaylist(
            PlaylistEntity(
                id = id,
                name = name,
                isSystem = false,
                createdAt = now,
                updatedAt = now,
                position = 1,
            ),
        )
        return id
    }

    /** @return false 表示名称为空（调用方应给出提示），或该歌单是系统歌单 */
    suspend fun rename(id: String, rawName: String): Boolean {
        val name = PlaylistNames.normalize(rawName) ?: return false
        dao.rename(id, name, System.currentTimeMillis())
        return true
    }

    /** 系统歌单（收藏）不可删除 —— 这条约束写在 SQL 的 `isSystem = 0` 条件里 */
    suspend fun deletePlaylist(id: String) = dao.deletePlaylist(id)

    /** 自定义封面：用歌单内某首歌的封面（null 传回 = 清除歌曲封面） */
    suspend fun setCoverFromSong(playlistId: String, songId: String, source: String) =
        dao.updateCover(playlistId, songId, source, null, System.currentTimeMillis())

    /** 自定义封面：本地图片 uri（调用方须已 takePersistableUriPermission）；优先级最高 */
    suspend fun setCoverFromImage(playlistId: String, imageUri: String) =
        dao.updateCover(playlistId, null, null, imageUri, System.currentTimeMillis())

    /** 清除自定义封面，回落到「歌单内第一首有封面的歌」 */
    suspend fun clearCover(playlistId: String) =
        dao.updateCover(playlistId, null, null, null, System.currentTimeMillis())

    /**
     * 一级页拖拽排序：按给定 id 顺序整体重写 position（下标即新顺序）。
     *
     * ⚠️ 调用方必须传**全量**歌单 id（一级页搜索/筛选把列表筛短时不得调用）。
     * 系统歌单（收藏）固定首位：即使传入顺序有误也会被钳回 0 位。
     */
    suspend fun reorderPlaylists(orderedIds: List<String>) {
        val all = dao.getPlaylists()
        val systemId = all.firstOrNull { it.isSystem }?.id
        val reordered = buildList {
            if (systemId != null) add(systemId)
            orderedIds.filterTo(this) { it != systemId }
        }
        val targetById = reordered.withIndex().associate { (index, id) -> id to index }
        all.forEach { playlist ->
            val target = targetById[playlist.id] ?: return@forEach
            if (playlist.position != target) dao.updatePlaylistPosition(playlist.id, target)
        }
    }

    /** @return false 表示这首歌已在该歌单里（不重复添加） */
    suspend fun addSong(playlistId: String, song: Song): Boolean {
        val row = PlaylistItemEntity(
            playlistId = playlistId,
            songId = song.id,
            source = song.source.value,
            songJson = json.encodeToString(Song.serializer(), song),
            songName = song.name,
            singer = song.singer,
            coverUrl = song.picUrl,
            // 新歌置顶（2026-10-27 用户要求）：position 取当前最小值 -1，
            // 后续 moveSong/reorder 会把它归一化回 0..n 连续索引
            position = dao.topPosition(playlistId),
            addedAt = System.currentTimeMillis(),
        )
        return dao.insertItem(row) != -1L
    }

    suspend fun removeSong(playlistId: String, songId: String, source: String) =
        dao.deleteSong(playlistId, songId, source)

    /**
     * 批量入歌（**备份导入专用**）：一次算好 position 起点，整批写入。
     *
     * 不走 [addSong] 循环是因为 `nextPosition` 每条都要查一次库 ——
     * 导入一份 3000 首的列表会多出 3000 次查询。
     *
     * @return 成功写入的条数（重复的会被 IGNORE）
     */
    suspend fun addSongs(playlistId: String, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val start = dao.nextPosition(playlistId)
        val now = System.currentTimeMillis()
        val rows = songs.mapIndexed { index, song ->
            PlaylistItemEntity(
                playlistId = playlistId,
                songId = song.id,
                source = song.source.value,
                songJson = json.encodeToString(Song.serializer(), song),
                songName = song.name,
                singer = song.singer,
                coverUrl = song.picUrl,
                position = start + index,
                addedAt = now,
            )
        }
        dao.insertItems(rows)
        return rows.size
    }

    /** 备份导入判重：这个名字的歌单是否已经存在 */
    suspend fun existingNames(): Set<String> = dao.getPlaylistNames().toSet()

    /**
     * 移动位置（`mockup_playlist.html` 第 7 项：输入序号精确定位）。
     *
     * @param targetIndex 0 基序号，越界会被夹到合法区间
     * @return false 表示这首歌不在该歌单里
     */
    suspend fun moveSong(
        playlistId: String,
        songId: String,
        source: String,
        targetIndex: Int,
    ): Boolean {
        val items = dao.getItems(playlistId)
        val from = items.indexOfFirst { it.songId == songId && it.source == source }
        if (from < 0) return false

        val target = targetIndex.coerceIn(0, items.size - 1)
        if (from == target) return true

        // 先整体重排再写回：position 是唯一排序依据，中间态不能留下空洞
        val reordered = items.toMutableList().apply { add(target, removeAt(from)) }
        reordered.forEachIndexed { index, item ->
            if (item.position != index) dao.updatePosition(item.id, index)
        }
        return true
    }

    /**
     * 拖动排序：把 `position` 按给定顺序整体重写（列表下标即新顺序）。
     *
     * 只有出现在 [ordered] 里的条目会被改动 —— 调用方必须传**完整**歌单顺序，
     * 页内搜索把列表筛短时不得调用，否则会与未展示的条目撞 position。
     */
    suspend fun reorder(playlistId: String, ordered: List<Pair<String, String>>) {
        val targetByKey = ordered.withIndex().associate { (index, key) -> key to index }
        dao.getItems(playlistId).forEach { item ->
            val target = targetByKey[item.songId to item.source] ?: return@forEach
            if (item.position != target) dao.updatePosition(item.id, target)
        }
    }

    /** 收藏/取消收藏（播放页与列表长按都用它） */
    suspend fun toggleLiked(song: Song): Boolean {
        ensureSystemPlaylists()
        return if (dao.insertItem(
                PlaylistItemEntity(
                    playlistId = LIKED_PLAYLIST_ID,
                    songId = song.id,
                    source = song.source.value,
                    songJson = json.encodeToString(Song.serializer(), song),
                    songName = song.name,
                    singer = song.singer,
                    coverUrl = song.picUrl,
                    position = dao.topPosition(LIKED_PLAYLIST_ID),
                    addedAt = System.currentTimeMillis(),
                ),
            ) != -1L
        ) {
            true
        } else {
            dao.deleteSong(LIKED_PLAYLIST_ID, song.id, song.source.value)
            false
        }
    }
}

/** 条目 → Song：反序列化整份快照，`raw` 随之还原（在线取流要用） */
internal fun PlaylistItemEntity.toSong(): Song =
    Json.decodeFromString(Song.serializer(), songJson)