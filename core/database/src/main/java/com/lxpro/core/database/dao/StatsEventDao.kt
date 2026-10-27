package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.lxpro.core.database.entity.StatsEventEntity
import kotlinx.coroutines.flow.Flow

/** 听歌统计账本的读写（统计页唯一数据源，与播放历史分表） */
@Dao
interface StatsEventDao {

    @Insert
    suspend fun insert(row: StatsEventEntity): Long

    @Insert
    suspend fun insertAll(rows: List<StatsEventEntity>)

    /** 回填真实播放时长（与播放历史同款：按 (source, songId) 取最近一行） */
    @Query(
        "UPDATE stats_events SET playedDurationMs = :playedMs, totalDurationMs = :totalMs, " +
            "isEffective = :effective WHERE id = " +
            "(SELECT MAX(id) FROM stats_events WHERE songId = :songId AND source = :source)",
    )
    suspend fun updatePlayed(
        songId: String,
        source: String,
        playedMs: Long,
        totalMs: Long,
        effective: Boolean,
    )

    @Query("SELECT COUNT(*) FROM stats_events")
    suspend fun count(): Int

    @Query(
        "DELETE FROM stats_events WHERE id NOT IN " +
            "(SELECT id FROM stats_events ORDER BY playedAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    @Query("SELECT * FROM stats_events ORDER BY playedAt DESC")
    fun observeAll(): Flow<List<StatsEventEntity>>

    /** 单曲流水（歌曲标签页的「软件自贴标签」实时聚合用，2026-10-26 第 8 项） */
    @Query("SELECT * FROM stats_events WHERE songId = :songId AND source = :source ORDER BY playedAt ASC")
    fun observeForSong(songId: String, source: String): Flow<List<StatsEventEntity>>

    @Query("DELETE FROM stats_events")
    suspend fun clear()

    /** 「导入听歌统计」的语义 = **覆盖整个账本**（旧项目 importStatsData 也是直接覆盖三层存储） */
    @Transaction
    suspend fun replaceAll(rows: List<StatsEventEntity>) {
        clear()
        insertAll(rows)
    }
}