package com.lxpro.core.database.di

import android.content.Context
import androidx.room.Room
import com.lxpro.core.database.LXProDatabase
import com.lxpro.core.database.MIGRATION_1_2
import com.lxpro.core.database.dao.LocalTrackDao
import com.lxpro.core.database.dao.SongDao
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
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideSongDao(database: LXProDatabase): SongDao = database.songDao()

    @Provides
    fun provideLocalTrackDao(database: LXProDatabase): LocalTrackDao = database.localTrackDao()
}