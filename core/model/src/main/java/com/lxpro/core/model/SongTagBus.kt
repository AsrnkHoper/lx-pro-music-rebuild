package com.lxpro.core.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「查看歌曲标签」的事件总线（2026-10-26 用户要求第 8 项：每首歌都要有标签）。
 *
 * 为什么需要它：标签弹层需要数据库与统计账本，只有 app 层能同时看到两者；
 * 而歌曲操作弹层出现在 app（跨页面共用）与 feature:playlist（歌单详情自有那份）。
 * feature 模块不能反向依赖 app，故用进程内单例把「要看哪首歌的标签」传上去，
 * 由 app 层统一渲染一个 [com.lxpro.music.tags.SongTagsSheet]。
 */
object SongTagBus {

    private val _songs = MutableSharedFlow<Song>(extraBufferCapacity = 4)

    val songs: SharedFlow<Song> = _songs.asSharedFlow()

    fun emit(song: Song) {
        _songs.tryEmit(song)
    }
}
