package com.lxpro.core.media

import com.lxpro.core.model.Song

/**
 * 「播放事件」的落点。
 *
 * 为什么要这层接口：记录历史需要数据库，而 `core:media` 只该管播放。
 * 由更上层（`core:stats`）实现并在 DI 里绑定，播放器只管往这里丢事件。
 */
interface PlaybackHistorySink {

    /** 一首歌**开始**播放（所有起播的唯一入口 `playAt`） */
    suspend fun onSongStarted(song: Song)

    /**
     * 一首歌**结束 / 被切走**时回填真实播放时长。
     *
     * @param playedMs 实际播放时长（暂停/缓冲不计；切歌时会把上一首累计的一并交出来）
     * @param totalMs  播放器报告的总时长；0 表示未知，由实现方回落到歌曲自身的 interval
     */
    suspend fun onSongPlayed(song: Song, playedMs: Long, totalMs: Long)
}