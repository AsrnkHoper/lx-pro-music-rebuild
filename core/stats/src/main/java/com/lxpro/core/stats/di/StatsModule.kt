package com.lxpro.core.stats.di

import com.lxpro.core.media.PlaybackHistorySink
import com.lxpro.core.stats.PlaybackSinkRouter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 把播放事件接到实现方。
 *
 * ⚠️ 绑定必须放在实现方（`core:stats`）这一侧：`core:media` 不认识数据库，
 * 若把绑定写进 media，就会形成 media → stats → media 的环。
 *
 * 出口只有 [PlaybackSinkRouter] 一个 —— 它把事件扇出给**播放历史**与**统计账本**两套存储。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StatsModule {

    @Binds
    abstract fun bindPlaybackHistorySink(impl: PlaybackSinkRouter): PlaybackHistorySink
}