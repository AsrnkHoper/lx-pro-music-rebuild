package com.lxpro.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxpro.core.database.dao.LocalTrackDao
import com.lxpro.core.database.dao.SongDao
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.database.entity.SongEntity

/**
 * 本地库。
 *
 * ⚠️ **禁止** `fallbackToDestructiveMigration()`（会丢用户数据，03 §6.3）；
 * schema 导出到本模块 `schemas/` 并提交 git。
 *
 * v2：新增 `local_tracks`（M2 本地音乐索引）。
 */
@Database(
    entities = [SongEntity::class, LocalTrackEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class LXProDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun localTrackDao(): LocalTrackDao
}

/**
 * v1 → v2：建 `local_tracks` 表与其两个索引。
 *
 * ⚠️ SQL 必须与 Room 生成的 schema **逐字一致**（列类型、NOT NULL、索引名）。
 * 校验方法：构建后比对 `schemas/com.lxpro.core.database.LXProDatabase/2.json` 里的 `createSql`。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_tracks` (" +
                "`uri` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`artist` TEXT NOT NULL, " +
                "`album` TEXT, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`mimeType` TEXT, " +
                "`folder` TEXT, " +
                "`dateAddedSec` INTEGER NOT NULL, " +
                "`fingerprint` TEXT NOT NULL, " +
                "`indexedAt` INTEGER NOT NULL, " +
                "`lastScanBatch` INTEGER NOT NULL, " +
                "PRIMARY KEY(`uri`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_tracks_fingerprint` " +
                "ON `local_tracks` (`fingerprint`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_tracks_folder` " +
                "ON `local_tracks` (`folder`)",
        )
    }
}