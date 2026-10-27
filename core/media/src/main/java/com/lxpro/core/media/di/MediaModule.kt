package com.lxpro.core.media.di

import android.content.Context
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import com.lxpro.core.media.MediaCacheProvider
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.PlayerControllerImpl
import com.lxpro.core.media.PlayerFactory
import com.lxpro.core.media.SwappablePlayer
import com.lxpro.core.media.eq.EqConfig
import com.lxpro.core.media.eq.ParametricEqAudioProcessor
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

    /** EQ 配置单例（17 §5）。EqConfig 本身是 @Singleton @Inject constructor，
     *  Hilt 直接提供；A/B 双 player 各 new 一个处理器实例（实例有状态不可共享，
     *  配置走 EqConfig 同步）。白噪音 ExoPlayer 不挂任何 AudioProcessor（17 §1 硬约束①） */

    /** 共享 SimpleCache（两个 ExoPlayer 必须共用，否则撞目录崩） */
    @Provides
    @Singleton
    fun provideMediaCache(@ApplicationContext context: Context): Cache = MediaCacheProvider.get(context)

    /**
     * playerA（歌曲轨 ExoPlayer 单例 + MediaSession 初始 delegate）。
     * PlayerControllerImpl 注入它作为初始 activePlayer，内部另创建 playerB 做交叉淡变备用。
     * 两个 player 共用同一个 Cache + OkHttpClient + EqConfig。
     * ⚠️ handleAudioFocus=false：焦点统一由 PlayerControllerImpl 手动管理
     *（setAudioAttributes 切焦点会 flush AudioTrack，转正瞬间卡顿）
     */
    @Provides
    @Singleton
    fun provideSongExoPlayer(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        eqConfig: EqConfig,
        cache: Cache,
    ): ExoPlayer = PlayerFactory.create(
        context, okHttpClient, cache,
        arrayOf(ParametricEqAudioProcessor(eqConfig)),
        handleAudioFocus = false,
    )

    /**
     * MediaSession 绑定的 player（初始 delegate = playerA）。
     * 交叉淡变完成后 PlayerControllerImpl.promote() 把新歌 player swap 进来 ——
     * MediaSession/UI 监听器自动跟随，零交接（无 seek 重缓冲 = 无空窗无回跳）。
     */
    @Provides
    @Singleton
    fun provideSessionPlayer(playerA: ExoPlayer): SwappablePlayer = SwappablePlayer(playerA)

    @Provides
    @Singleton
    fun providePlayerController(impl: PlayerControllerImpl): PlayerController = impl
}
