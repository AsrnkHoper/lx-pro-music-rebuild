package com.lxpro.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lxpro.core.database.dao.SongDao
import com.lxpro.core.database.entity.SongEntity

/**
 * 本地库。
 * ⚠️ **禁止** `fallbackToDestructiveMigration()`（会丢用户数据，03 §6.3）；
 * schema 导出到本模块 `schemas/` 并提交 git。
 */
@Database(
    entities = [SongEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LXProDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
}