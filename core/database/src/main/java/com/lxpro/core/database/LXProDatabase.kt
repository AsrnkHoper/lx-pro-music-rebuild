package com.lxpro.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxpro.core.database.dao.LocalTrackDao
import com.lxpro.core.database.dao.SafRootDao
import com.lxpro.core.database.dao.SongDao
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.database.entity.SafRootEntity
import com.lxpro.core.database.entity.SongEntity

/**
 * 本地库。
 *
 * ⚠️ **禁止** `fallbackToDestructiveMigration()`（会丢用户数据，03 §6.3）；
 * schema 导出到本模块 `schemas/` 并提交 git。
 *
 * - v2：新增 `local_tracks`（M2 本地音乐索引）
 * - v3：`local_tracks` 增加来源通道（media_store / saf）+ `saf_roots`（M2 SAF 目录授权）
 * - v4：`local_tracks` 增加 `lrcUri`（M2 本地歌词：同名 .lrc / 内嵌）
 */
@Database(
    entities = [SongEntity::class, LocalTrackEntity::class, SafRootEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class LXProDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun localTrackDao(): LocalTrackDao

    abstract fun safRootDao(): SafRootDao
}

/**
 * v1 → v2：建 `local_tracks` 表与其两个索引。
 *
 * ⚠️ SQL 必须与 Room 生成的 schema **逐字一致**（列类型、NOT NULL、默认值、索引名）。
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

/**
 * v2 → v3：本地音乐加「来源通道」（MediaStore / SAF）+ SAF 授权目录表。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../3.json` 逐字一致。
 * 存量行一律视为 media_store（它们在 v2 时只可能来自媒体库扫描）。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `local_tracks` ADD COLUMN `sourceKind` TEXT NOT NULL DEFAULT 'media_store'",
        )
        db.execSQL("ALTER TABLE `local_tracks` ADD COLUMN `safRootUri` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_tracks_sourceKind` " +
                "ON `local_tracks` (`sourceKind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_tracks_safRootUri` " +
                "ON `local_tracks` (`safRootUri`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `saf_roots` (" +
                "`treeUri` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`treeUri`))",
        )
    }
}

/**
 * v3 → v4：本地曲目的同名 `.lrc` 歌词 uri。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../4.json` 逐字一致。存量行为 null，
 * 用户重扫一次即可把已有 .lrc 关联上。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `local_tracks` ADD COLUMN `lrcUri` TEXT")
    }
}