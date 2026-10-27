package com.lxpro.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lxpro.core.database.dao.ArtistIconOverrideDao
import com.lxpro.core.database.dao.DownloadTaskDao
import com.lxpro.core.database.dao.EqAliasDao
import com.lxpro.core.database.dao.EqFavoriteCollectionDao
import com.lxpro.core.database.dao.EqFavoriteItemDao
import com.lxpro.core.database.dao.EqUserPresetDao
import com.lxpro.core.database.dao.LyricCacheDao
import com.lxpro.core.database.dao.LocalTrackDao
import com.lxpro.core.database.dao.NoiseEventDao
import com.lxpro.core.database.dao.NoisePresetDao
import com.lxpro.core.database.dao.NoiseUserComboDao
import com.lxpro.core.database.dao.NoiseUserPackDao
import com.lxpro.core.database.dao.NoiseUserSourceDao
import com.lxpro.core.database.dao.PlayHistoryDao
import com.lxpro.core.database.dao.PlaylistDao
import com.lxpro.core.database.dao.SafRootDao
import com.lxpro.core.database.dao.SongDao
import com.lxpro.core.database.dao.SongOverrideDao
import com.lxpro.core.database.dao.SongTagDao
import com.lxpro.core.database.dao.StatsEventDao
import com.lxpro.core.database.dao.TimerStateDao
import com.lxpro.core.database.dao.UserApiScriptDao
import com.lxpro.core.database.entity.ArtistIconOverrideEntity
import com.lxpro.core.database.entity.DownloadTaskEntity
import com.lxpro.core.database.entity.EqAliasEntity
import com.lxpro.core.database.entity.EqFavoriteCollectionEntity
import com.lxpro.core.database.entity.EqFavoriteItemEntity
import com.lxpro.core.database.entity.EqUserPresetEntity
import com.lxpro.core.database.entity.LocalTrackEntity
import com.lxpro.core.database.entity.LyricCacheEntity
import com.lxpro.core.database.entity.NoiseEventEntity
import com.lxpro.core.database.entity.NoisePresetEntity
import com.lxpro.core.database.entity.NoiseUserComboEntity
import com.lxpro.core.database.entity.NoiseUserPackEntity
import com.lxpro.core.database.entity.NoiseUserSourceEntity
import com.lxpro.core.database.entity.PlayHistoryEntity
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.database.entity.PlaylistItemEntity
import com.lxpro.core.database.entity.SafRootEntity
import com.lxpro.core.database.entity.SongEntity
import com.lxpro.core.database.entity.SongOverrideEntity
import com.lxpro.core.database.entity.SongTagEntity
import com.lxpro.core.database.entity.StatsEventEntity
import com.lxpro.core.database.entity.TimerStateEntity
import com.lxpro.core.database.entity.UserApiScriptEntity

/**
 * 本地库。
 *
 * ⚠️ **禁止** `fallbackToDestructiveMigration()`（会丢用户数据，03 §6.3）；
 * schema 导出到本模块 `schemas/` 并提交 git。
 *
 * - v2：新增 `local_tracks`（M2 本地音乐索引）
 * - v3：`local_tracks` 增加来源通道（media_store / saf）+ `saf_roots`（M2 SAF 目录授权）
 * - v4：`local_tracks` 增加 `lrcUri`（M2 本地歌词：同名 .lrc / 内嵌）
 * - v5：新增 `playlists` + `playlist_items`（M3 歌单与收藏）
 * - v6：新增 `song_overrides`（M3 重命名歌曲 / 作者，供显示与统计归并）
 * - v7：新增 `play_history`（M3 首页「最近播放 / 常听歌手」+ 统计页的数据源）
 * - v8：`play_history` 增加真实播放时长与入账标记（统计口径继承前作：≥50% 且 ≥30 秒）
 * - v9：新增 `stats_events`（**统计账本独立成表** —— 统计与播放历史是两个功能，见实体注释）
 * - v10：新增 `download_tasks`（M3 任务 8 下载管理）
 * - v11：`playlists` 增加自定义封面（coverSongId/coverSource/coverImageUri）与拖拽排序位（position）；
 *   新增 `artist_icon_overrides`（首页常听歌手图标自定义）
 * - v12：新增 `user_api_scripts`（M2 #6 用户音源脚本）
 * - v13（M4 批一·地基）：新增 6 张声音层表 ——
 *   `noise_presets`（17 §2.1 8 预设 + 用户副本）
 *   `noise_user_combos`（17 §2.1 我的组合）
 *   `eq_aliases`（17 §5.6 非破坏性别名映射）
 *   `eq_favorite_collections` + `eq_favorite_items`（17 §5.7 集合 vs 扁平喜欢列表）
 *   `timer_states`（17 §7 双独立定时器）
 * - v18：新增 `noise_events`（白噪音收听账本）
 * - v19：新增 `song_tags`（用户给歌曲贴的文字标签；软件自贴标签由 stats_events 实时聚合，不落库）
 * - v20：`song_overrides` 增加 `toggleSongJson`（换源目标持久化，对齐 LX `meta.toggleMusicInfo`）
 * - v21：新增 `lyric_cache`（在线歌曲歌词持久化缓存，2026-10-27 用户反馈「音频和歌词不缓存」）
 */
@Database(
    entities = [
        SongEntity::class,
        LocalTrackEntity::class,
        SafRootEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        SongOverrideEntity::class,
        PlayHistoryEntity::class,
        StatsEventEntity::class,
        DownloadTaskEntity::class,
        ArtistIconOverrideEntity::class,
        UserApiScriptEntity::class,
        NoisePresetEntity::class,
        NoiseUserComboEntity::class,
        EqAliasEntity::class,
        EqFavoriteCollectionEntity::class,
        EqFavoriteItemEntity::class,
        EqUserPresetEntity::class,
        TimerStateEntity::class,
        NoiseUserSourceEntity::class,
        NoiseUserPackEntity::class,
        NoiseEventEntity::class,
        SongTagEntity::class,
        LyricCacheEntity::class,
    ],
    version = 21,
    exportSchema = true,
)
abstract class LXProDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    abstract fun localTrackDao(): LocalTrackDao

    abstract fun safRootDao(): SafRootDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun songOverrideDao(): SongOverrideDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun statsEventDao(): StatsEventDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun artistIconOverrideDao(): ArtistIconOverrideDao

    abstract fun userApiScriptDao(): UserApiScriptDao

    // ── M4 声音层（17 全篇）──
    abstract fun noisePresetDao(): NoisePresetDao
    abstract fun noiseUserComboDao(): NoiseUserComboDao
    abstract fun eqAliasDao(): EqAliasDao
    abstract fun eqFavoriteCollectionDao(): EqFavoriteCollectionDao
    abstract fun eqFavoriteItemDao(): EqFavoriteItemDao
    abstract fun eqUserPresetDao(): EqUserPresetDao
    abstract fun timerStateDao(): TimerStateDao

    abstract fun noiseUserSourceDao(): NoiseUserSourceDao
    abstract fun noiseUserPackDao(): NoiseUserPackDao

    /** 白噪音收听账本（统计页「白噪音」子页） */
    abstract fun noiseEventDao(): NoiseEventDao

    /** 用户给歌曲贴的文字标签（2026-10-26 第 8 项） */
    abstract fun songTagDao(): SongTagDao

    /** 在线歌曲歌词持久化缓存（2026-10-27） */
    abstract fun lyricCacheDao(): LyricCacheDao
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

/**
 * v4 → v5：歌单与歌单条目。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../5.json` 逐字一致。
 * 收藏不做成单独的表：它是 `playlists` 里 id 固定为 `liked` 的系统行（02 §6.3）。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlists` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`isSystem` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`playlistId` TEXT NOT NULL, " +
                "`songId` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`songJson` TEXT NOT NULL, " +
                "`songName` TEXT NOT NULL, " +
                "`singer` TEXT NOT NULL, " +
                "`coverUrl` TEXT, " +
                "`position` INTEGER NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_items_playlistId` " +
                "ON `playlist_items` (`playlistId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_items_playlistId_songId_source` " +
                "ON `playlist_items` (`playlistId`, `songId`, `source`)",
        )
    }
}

/**
 * v5 → v6：用户对歌曲名 / 作者名的本地修正。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../6.json` 逐字一致。
 * 存量行为空 —— 没有任何修正记录，显示保持音源原值。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `song_overrides` (" +
                "`songId` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`name` TEXT, " +
                "`singer` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`songId`, `source`))",
        )
    }
}

/**
 * v6 → v7：播放历史。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../7.json` 逐字一致。
 * 存量行为空 —— 迁移前的播放没有记录，货架从下一次起播开始填。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `play_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`songId` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`singer` TEXT NOT NULL, " +
                "`coverUrl` TEXT, " +
                "`songJson` TEXT NOT NULL, " +
                "`playedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` " +
                "ON `play_history` (`playedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_play_history_singer` " +
                "ON `play_history` (`singer`)",
        )
    }
}

/**
 * v7 → v8：播放历史增加真实播放时长与入账标记。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../8.json` 逐字一致。
 * 存量行（迁移前只记了「起播」）播放时长未知 → 0 且 `isEffective = 0`，
 * 即**不进入统计口径**（宁缺勿假）；用户重新播放后自然产生带时长的记录。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `play_history` ADD COLUMN `albumName` TEXT")
        db.execSQL(
            "ALTER TABLE `play_history` ADD COLUMN `playedDurationMs` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `play_history` ADD COLUMN `totalDurationMs` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `play_history` ADD COLUMN `isEffective` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * v8 → v9：把统计账本从 `play_history` 里**拆出来**独立成表。
 *
 * 理由见 [StatsEventEntity]：统计（LX-Pro 新增）与播放历史（LX-Music 自带）是两个功能，
 * 共用一张表会让「导入听歌统计」与「导入歌单备份」互相覆盖。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../9.json` 逐字一致。
 * 存量行会**整表回填**到 `stats_events` —— 拆分前它们既是货架也是统计，
 * 不回填会让用户已有的统计凭空消失。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `stats_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`songId` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`singer` TEXT NOT NULL, " +
                "`albumName` TEXT, " +
                "`coverUrl` TEXT, " +
                "`songJson` TEXT NOT NULL, " +
                "`playedAt` INTEGER NOT NULL, " +
                "`playedDurationMs` INTEGER NOT NULL, " +
                "`totalDurationMs` INTEGER NOT NULL, " +
                "`isEffective` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_stats_events_playedAt` " +
                "ON `stats_events` (`playedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_stats_events_singer` " +
                "ON `stats_events` (`singer`)",
        )
        db.execSQL(
            "INSERT INTO `stats_events` " +
                "(`songId`, `source`, `name`, `singer`, `albumName`, `coverUrl`, `songJson`, " +
                "`playedAt`, `playedDurationMs`, `totalDurationMs`, `isEffective`) " +
                "SELECT `songId`, `source`, `name`, `singer`, `albumName`, `coverUrl`, `songJson`, " +
                "`playedAt`, `playedDurationMs`, `totalDurationMs`, `isEffective` FROM `play_history`",
        )
    }
}

/**
 * v9 → v10：下载任务表（M3 任务 8）。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../10.json` 逐字一致。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `download_tasks` (" +
                "`id` TEXT NOT NULL, " +
                "`songId` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`singer` TEXT NOT NULL, " +
                "`albumName` TEXT, " +
                "`coverUrl` TEXT, " +
                "`songJson` TEXT NOT NULL, " +
                "`quality` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`progress` INTEGER NOT NULL, " +
                "`downloadedBytes` INTEGER NOT NULL, " +
                "`totalBytes` INTEGER NOT NULL, " +
                "`filePath` TEXT, " +
                "`error` TEXT, " +
                "`addedAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_tasks_state` " +
                "ON `download_tasks` (`state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_tasks_addedAt` " +
                "ON `download_tasks` (`addedAt`)",
        )
    }
}

/**
 * v10 → v11：歌单自定义封面 + 一级页拖拽排序位；常听歌手图标覆盖表。
 *
 * ⚠️ 同样必须与生成的 `schemas/.../11.json` 逐字一致。
 *
 * position 回填口径与旧排序（`isSystem DESC, updatedAt DESC`）一致：
 * 系统歌单 = 0；其余按 updatedAt 降序从 1 递增 —— 迁移后用户看到的一级页顺序不变。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `coverSongId` TEXT")
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `coverSource` TEXT")
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `coverImageUri` TEXT")
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `playlists` SET `position` = CASE WHEN `isSystem` != 0 THEN 0 ELSE (" +
                "SELECT COUNT(*) + 1 FROM `playlists` p2 WHERE p2.`isSystem` = 0 " +
                "AND (p2.`updatedAt` > `playlists`.`updatedAt` " +
                "OR (p2.`updatedAt` = `playlists`.`updatedAt` AND p2.`id` < `playlists`.`id`))) END",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `artist_icon_overrides` (" +
                "`artistKey` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`artistKey`))",
        )
    }
}

/**
 * v11 → v12：新增 `user_api_scripts`（M2 #6 用户音源脚本）。
 * ⚠️ SQL 必须与生成的 `schemas/.../12.json` 逐字一致（03 §6.3）。
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_api_scripts` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`version` TEXT NOT NULL, " +
                "`author` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, " +
                "`filePath` TEXT NOT NULL, " +
                "`sources` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`importedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * v12 → v13：M4 批一·地基 —— 6 张声音层表（17 全篇）。
 *
 * ⚠️ SQL 必须与生成的 `schemas/.../13.json` 逐字一致（03 §6.3）。
 *
 * - `noise_presets`：8 预设场景 + 用户副本（17 §2.1）。`parentPresetId` 在副本上保留原预设 id
 * - `noise_user_combos`：用户自建「我的组合」（17 §2.1）
 * - `eq_aliases`：非破坏性别名映射，原始型号名不在本表（17 §5.6）
 * - `eq_favorite_collections` + `eq_favorite_items`：收藏集合（17 §5.7）vs 扁平喜欢列表
 * - `timer_states`：双独立定时器（17 §7，song / noise 各一行）
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 白噪音预设（17 §2.1 8 个内置 + 用户副本）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `noise_presets` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`layersJson` TEXT NOT NULL, " +
                "`isBuiltin` INTEGER NOT NULL, " +
                "`parentPresetId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        // 我的组合
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `noise_user_combos` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`layersJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        // EQ 别名（非破坏性，17 §5.6）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `eq_aliases` (" +
                "`presetName` TEXT NOT NULL, " +
                "`alias` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`presetName`))",
        )
        // EQ 收藏集合（17 §5.7）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `eq_favorite_collections` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        // EQ 收藏集合内条目（17 §5.7，一对多）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `eq_favorite_items` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`collectionId` TEXT NOT NULL, " +
                "`presetName` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`collectionId`) REFERENCES `eq_favorite_collections`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_eq_favorite_items_collectionId` " +
                "ON `eq_favorite_items` (`collectionId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_eq_favorite_items_collectionId_presetName` " +
                "ON `eq_favorite_items` (`collectionId`, `presetName`)",
        )
        // 双独立定时器（17 §7，song / noise 各一行；M4 批四 落地 UI）
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `timer_states` (" +
                "`id` TEXT NOT NULL, " +
                "`targetKind` TEXT NOT NULL, " +
                "`valueMinutes` INTEGER, " +
                "`targetHour` INTEGER, " +
                "`stopNoiseToo` INTEGER NOT NULL, " +
                "`setAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * v13 → v14：timer_states 加 finishSongFirst（歌曲定时到点后等歌自然结束再停，用户 2026-10-20 要求）。
 *
 * 同时修正 MIGRATION_12_13 里建表漏了 finishSongFirst 列 —— 这里用 ALTER TABLE 补上，
 * 已在 v13 表结构里落盘的 timer_states 也会自动获得新列（默认 false）。
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `timer_states` ADD COLUMN `finishSongFirst` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * v14 → v15：建 `eq_user_presets` 表（17 §5.3 导入预设：AutoEQ / PowerAMP 曲线文件）。
 *
 * 用户导入的曲线与 assets 内置 142 条分开存，升级不丢、可单独删。
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `eq_user_presets` (" +
                "`name` TEXT NOT NULL, " +
                "`gains` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`name`))",
        )
    }
}

/**
 * v15 → v16：建 `noise_user_sources` 表（17 §3「我的白噪音 · 本地导入」）。
 *
 * 本地导入的音源与 23 条内置 CDN 音源同权：可叠进混音、可存组合、不经过 EQ。
 * 绝大多数为连续型（minSilence/maxSilence 留 0）。
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `noise_user_sources` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`uri` TEXT NOT NULL, " +
                "`minSilenceSeconds` INTEGER NOT NULL, " +
                "`maxSilenceSeconds` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * v16 → v17：建 `noise_user_packs`（17 §3 远端音源包）。
 * ⚠️ 建表 SQL 必须与 Room 导出的 17.json 逐字一致。
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `noise_user_packs` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`dirPath` TEXT NOT NULL, " +
                "`segmentsJson` TEXT NOT NULL, " +
                "`minSilenceSeconds` INTEGER NOT NULL, " +
                "`maxSilenceSeconds` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * v17 → v18：建 `noise_events`（白噪音收听账本，2026-10-26 用户裁决「白噪音也要被统计」）。
 *
 * ⚠️ 建表 SQL 必须与 Room 导出的 18.json 逐字一致。
 * 存量行为空 —— 升级前的白噪音收听没有记录，从下一段起填。
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `noise_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`musicMs` INTEGER NOT NULL, " +
                "`soundIds` TEXT NOT NULL, " +
                "`soundNames` TEXT NOT NULL, " +
                "`sceneName` TEXT)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_noise_events_startedAt` " +
                "ON `noise_events` (`startedAt`)",
        )
    }
}

/**
 * v18 → v19：建 `song_tags`（用户给歌曲贴的文字标签，2026-10-26 用户要求第 8 项）。
 *
 * ⚠️ 建表 SQL 必须与 Room 导出的 19.json 逐字一致。
 * 存量行为空 —— 升级前用户没贴过标签；「软件自贴标签」不落库，由 `stats_events` 实时聚合。
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `song_tags` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`songId` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`tag` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_song_tags_source_songId_tag` " +
                "ON `song_tags` (`source`, `songId`, `tag`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_song_tags_songId` " +
                "ON `song_tags` (`songId`)",
        )
    }
}

/**
 * v19 → v20：`song_overrides` 加 `toggleSongJson`（换源目标，04 · 换源）。
 * 存量行为 null = 未换源。
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `song_overrides` ADD COLUMN `toggleSongJson` TEXT")
    }
}

/**
 * v20 → v21：建 `lyric_cache`（在线歌曲歌词持久化缓存）。
 *
 * 存量行为空 —— 升级前没有任何缓存歌词，从下一次取词开始落盘。
 * 换源钉住后目标源会写新行，原源行留着待清理。
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lyric_cache` (" +
                "`source` TEXT NOT NULL, " +
                "`songId` TEXT NOT NULL, " +
                "`lyric` TEXT NOT NULL, " +
                "`tlyric` TEXT, " +
                "`cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`source`, `songId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_lyric_cache_cachedAt` " +
                "ON `lyric_cache` (`cachedAt`)",
        )
    }
}