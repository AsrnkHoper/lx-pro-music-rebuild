package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lxpro.core.database.entity.TimerStateEntity
import kotlinx.coroutines.flow.Flow

/** 双定时器状态（17 §7：歌曲定时关闭 / 白噪音睡眠定时，互不合并） */
@Dao
interface TimerStateDao {
    @Query("SELECT * FROM timer_states")
    fun observeAll(): Flow<List<TimerStateEntity>>

    @Query("SELECT * FROM timer_states WHERE id = :id")
    suspend fun get(id: String): TimerStateEntity?

    @Query("SELECT * FROM timer_states WHERE id = :id")
    fun observe(id: String): Flow<TimerStateEntity?>

    @Upsert
    suspend fun upsert(entity: TimerStateEntity)

    /** 一键取消（17 §11 验收项 #14） */
    @Query("DELETE FROM timer_states WHERE id = :id")
    suspend fun delete(id: String)
}
