package com.lxpro.core.media.di

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.PlayerControllerImpl
import com.lxpro.core.media.PlayerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    /**
     * 播放器为**应用级单例**：服务与 UI 共用同一实例，
     * 这样通知栏、锁屏、UI 看到的是同一个播放状态。
     */
    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): ExoPlayer = PlayerFactory.create(context, okHttpClient)

    @Provides
    @Singleton
    fun providePlayerController(impl: PlayerControllerImpl): PlayerController = impl
}