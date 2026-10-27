package com.lxpro.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lxpro.core.database.entity.SongTagEntity
import kotlinx.coroutines.flow.Flow

/** 歌曲用户标签（2026-10-26 用户要求第 8 项） */
@Dao
interface SongTagDao {

    @Query("SELECT * FROM song_tags WHERE songId = :songId AND source = :source ORDER BY createdAt ASC")
    fun observeFor(songId: String, source: String): Flow<List<SongTagEntity>>

    /** 全部标签（「我的标签」建议位按最近使用排序用） */
    @Query("SELECT * FROM song_tags ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SongTagEntity>>

    /** 全部标签（一次性；全量备份导出用） */
    @Query("SELECT * FROM song_tags ORDER BY createdAt ASC")
    suspend fun getAll(): List<SongTagEntity>

    /** 重复标签（同 source+songId+tag）静默忽略，返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SongTagEntity): Long

    /** 全量备份导入：整批写入，重复的静默忽略（合并语义） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SongTagEntity>)

    @Query("DELETE FROM song_tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM song_tags WHERE songId = :songId AND source = :source AND tag = :tag")
    suspend fun deleteByTag(songId: String, source: String, tag: String)

    @Query("SELECT COUNT(*) FROM song_tags WHERE songId = :songId AND source = :source")
    suspend fun countFor(songId: String, source: String): Int
}
