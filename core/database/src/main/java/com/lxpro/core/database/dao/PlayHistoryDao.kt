package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lxpro.core.database.entity.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

/** 「常听歌手」的一行：歌手名 + 去重后的曲目数 */
data class TopArtistRow(
    val singer: String,
    val trackCount: Int,
)

/** 封面候选的一行：singer + 该行封面（非空），供「常听歌手」自定义图标挑选 */
data class SingerCoverRow(
    val singer: String,
    val coverUrl: String,
)

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(row: PlayHistoryEntity): Long

    /** 备份导入：一次性灌入（事务由 Room 保证同一 @Insert 的批量语义） */
    @Insert
    suspend fun insertAll(rows: List<PlayHistoryEntity>)

    /**
     * 回填真实播放时长（切歌/播完时调）。
     *
     * ⚠️ 按 `(source, songId)` 取**最近一行**更新，而不是按 id ——
     * 播放器起播时插入行、结束时回填，中间可能已经插入了同一首的其它行（点两次同一首）。
     */
    @Query(
        "UPDATE play_history SET playedDurationMs = :playedMs, totalDurationMs = :totalMs, " +
            "isEffective = :effective WHERE id = " +
            "(SELECT MAX(id) FROM play_history WHERE songId = :songId AND source = :source)",
    )
    suspend fun updatePlayed(
        songId: String,
        source: String,
        playedMs: Long,
        totalMs: Long,
        effective: Boolean,
    )

    /** 表只增不减会一直长大；超过上限才裁（统计页「全部」档要看尽量长的历史，故上限远大于 500） */
    @Query("SELECT COUNT(*) FROM play_history")
    suspend fun count(): Int

    @Query(
        "DELETE FROM play_history WHERE id NOT IN " +
            "(SELECT id FROM play_history ORDER BY playedAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    /** 最近播放：**同一首歌只留最近一次**（货架语义是「继续听」，不是流水账） */
    @Query(
        "SELECT * FROM play_history WHERE id IN " +
            "(SELECT MAX(id) FROM play_history GROUP BY source, songId) " +
            "ORDER BY playedAt DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<PlayHistoryEntity>>

    /**
     * 常听歌手：按**曲目数**排序（`mockup_home.html` 货架标题就写着「按曲目数」），
     * 不是按播放次数 —— 否则单曲循环会把榜单刷成一个人。
     */
    @Query(
        "SELECT singer, COUNT(*) AS trackCount FROM " +
            "(SELECT DISTINCT singer, source, songId FROM play_history WHERE singer != '') " +
            "GROUP BY singer ORDER BY trackCount DESC LIMIT :limit",
    )
    fun observeTopArtists(limit: Int): Flow<List<TopArtistRow>>

    /**
     * 封面候选的一次性查询：singer + 非空封面，按最近播放在前。
     *
     * 表有 2000 行上限，一次性取出后在 Kotlin 侧按归并键过滤、去重即可
     *（归并口径在 `StatsCalculator.normalizeArtist`，SQL 表达不了）。
     */
    @Query(
        "SELECT singer, coverUrl FROM play_history " +
            "WHERE singer != '' AND coverUrl IS NOT NULL AND coverUrl != '' ORDER BY playedAt DESC",
    )
    suspend fun listSingerCovers(): List<SingerCoverRow>

    /**
     * 统计页全量流水（按时间倒序）。聚合放在 `core:stats` 的纯函数里做 ——
     * 十区块、六档时间的口径要可单测，不适合拆成十几条 SQL。
     */
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    fun observeAll(): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    suspend fun getAll(): List<PlayHistoryEntity>

    @Query("SELECT COUNT(*) FROM play_history")
    fun observeTotalPlays(): Flow<Int>

    @Query("DELETE FROM play_history")
    suspend fun clear()
}