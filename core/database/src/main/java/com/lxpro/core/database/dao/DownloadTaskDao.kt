package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

/** 下载任务的读写（下载页与下载引擎共用） */
@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks ORDER BY addedAt DESC")
    suspend fun all(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun find(id: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query(
        "UPDATE download_tasks SET progress = :progress, downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateProgress(
        id: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        updatedAt: Long,
    )

    @Query(
        "UPDATE download_tasks SET state = :state, error = :error, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateState(id: String, state: String, error: String?, updatedAt: Long)

    /** 完成时一并写回文件路径与最终大小 */
    @Query(
        "UPDATE download_tasks SET state = :state, progress = 100, downloadedBytes = :size, " +
            "totalBytes = :size, filePath = :filePath, error = NULL, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun markCompleted(id: String, filePath: String, size: Long, state: String, updatedAt: Long)

    /** 批量换状态（全部暂停 / 全部继续）；返回受影响行数 */
    @Query("UPDATE download_tasks SET state = :to, updatedAt = :updatedAt WHERE state = :from")
    suspend fun updateStateBulk(from: String, to: String, updatedAt: Long): Int

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM download_tasks WHERE state = :state")
    suspend fun deleteByState(state: String)

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM download_tasks WHERE state = :state")
    suspend fun sumBytesByState(state: String): Long

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM download_tasks WHERE state = :state")
    fun observeBytesByState(state: String): Flow<Long>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE state IN (:states)")
    fun observeCountByStates(states: List<String>): Flow<Int>
}