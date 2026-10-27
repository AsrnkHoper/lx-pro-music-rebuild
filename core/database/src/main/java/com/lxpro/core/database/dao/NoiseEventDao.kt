package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lxpro.core.database.entity.NoiseEventEntity
import kotlinx.coroutines.flow.Flow

/** 白噪音收听账本的读写（统计页「白噪音」子页的唯一数据源） */
@Dao
interface NoiseEventDao {

    @Insert
    suspend fun insert(row: NoiseEventEntity): Long

    @Query("SELECT * FROM noise_events ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<NoiseEventEntity>>

    /** 全量备份导出用（一次性） */
    @Query("SELECT * FROM noise_events ORDER BY startedAt ASC")
    suspend fun getAll(): List<NoiseEventEntity>

    /** 全量备份导入：整批写入 */
    @Insert
    suspend fun insertAll(rows: List<NoiseEventEntity>)

    @Query("SELECT COUNT(*) FROM noise_events")
    suspend fun count(): Int

    /** 与听歌账本同一套保留策略：超量时裁掉最旧的 */
    @Query(
        "DELETE FROM noise_events WHERE id NOT IN " +
            "(SELECT id FROM noise_events ORDER BY startedAt DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM noise_events")
    suspend fun clear()
}
