package com.lxpro.core.database.di

import android.content.Context
import androidx.room.Room
import com.lxpro.core.database.LXProDatabase
import com.lxpro.core.database.MIGRATION_1_2
import com.lxpro.core.database.MIGRATION_2_3
import com.lxpro.core.database.MIGRATION_3_4
import com.lxpro.core.database.MIGRATION_4_5
import com.lxpro.core.database.MIGRATION_5_6
import com.lxpro.core.database.MIGRATION_6_7
import com.lxpro.core.database.MIGRATION_7_8
import com.lxpro.core.database.MIGRATION_8_9
import com.lxpro.core.database.MIGRATION_9_10
import com.lxpro.core.database.MIGRATION_10_11
import com.lxpro.core.database.MIGRATION_11_12
import com.lxpro.core.database.MIGRATION_12_13
import com.lxpro.core.database.MIGRATION_13_14
import com.lxpro.core.database.MIGRATION_14_15
import com.lxpro.core.database.MIGRATION_15_16
import com.lxpro.core.database.MIGRATION_16_17
import com.lxpro.core.database.MIGRATION_17_18
import com.lxpro.core.database.MIGRATION_18_19
import com.lxpro.core.database.MIGRATION_19_20
import com.lxpro.core.database.MIGRATION_20_21
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LXProDatabase =
        Room.databaseBuilder(context, LXProDatabase::class.java, "lxpro.db")
            // ⚠️ 显式迁移，绝不 fallbackToDestructiveMigration（03 §6.3）
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
            )
            .build()

    @Provides
    fun provideSongDao(database: LXProDatabase): SongDao = database.songDao()

    @Provides
    fun provideLocalTrackDao(database: LXProDatabase): LocalTrackDao = database.localTrackDao()

    @Provides
    fun provideSafRootDao(database: LXProDatabase): SafRootDao = database.safRootDao()

    @Provides
    fun providePlaylistDao(database: LXProDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideSongOverrideDao(database: LXProDatabase): SongOverrideDao = database.songOverrideDao()

    @Provides
    fun providePlayHistoryDao(database: LXProDatabase): PlayHistoryDao = database.playHistoryDao()

    @Provides
    fun provideStatsEventDao(database: LXProDatabase): StatsEventDao = database.statsEventDao()

    @Provides
    fun provideDownloadTaskDao(database: LXProDatabase): DownloadTaskDao = database.downloadTaskDao()

    @Provides
    fun provideArtistIconOverrideDao(database: LXProDatabase): ArtistIconOverrideDao =
        database.artistIconOverrideDao()

    @Provides
    fun provideUserApiScriptDao(database: LXProDatabase): UserApiScriptDao =
        database.userApiScriptDao()

    // ── M4 声音层（17 全篇）──

    @Provides
    fun provideNoisePresetDao(database: LXProDatabase): NoisePresetDao = database.noisePresetDao()

    @Provides
    fun provideNoiseUserComboDao(database: LXProDatabase): NoiseUserComboDao =
        database.noiseUserComboDao()

    @Provides
    fun provideEqAliasDao(database: LXProDatabase): EqAliasDao = database.eqAliasDao()

    @Provides
    fun provideEqFavoriteCollectionDao(database: LXProDatabase): EqFavoriteCollectionDao =
        database.eqFavoriteCollectionDao()

    @Provides
    fun provideEqFavoriteItemDao(database: LXProDatabase): EqFavoriteItemDao =
        database.eqFavoriteItemDao()

    @Provides
    fun provideEqUserPresetDao(database: LXProDatabase): EqUserPresetDao =
        database.eqUserPresetDao()

    @Provides
    fun provideTimerStateDao(database: LXProDatabase): TimerStateDao = database.timerStateDao()

    @Provides
    fun provideNoiseUserSourceDao(database: LXProDatabase): NoiseUserSourceDao =
        database.noiseUserSourceDao()

    @Provides
    fun provideNoiseUserPackDao(database: LXProDatabase): NoiseUserPackDao =
        database.noiseUserPackDao()

    @Provides
    fun provideNoiseEventDao(database: LXProDatabase): NoiseEventDao =
        database.noiseEventDao()

    @Provides
    fun provideSongTagDao(database: LXProDatabase): SongTagDao =
        database.songTagDao()

    @Provides
    fun provideLyricCacheDao(database: LXProDatabase): LyricCacheDao =
        database.lyricCacheDao()
}
