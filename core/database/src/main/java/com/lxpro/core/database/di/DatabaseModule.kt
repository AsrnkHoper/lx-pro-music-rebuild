package com.lxpro.core.database.di

import android.content.Context
import androidx.room.Room
import com.lxpro.core.database.LXProDatabase
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
            // 单进程，无需多实例失效通知（03 §8.3）
            .build()

    @Provides
    fun provideSongDao(database: LXProDatabase): SongDao = database.songDao()
}