package com.lxpro.core.stats

import com.lxpro.core.media.PlaybackHistorySink
import com.lxpro.core.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把一个播放事件**同时**投给两套存储。
 *
 * 为什么需要：统计（LX-Pro 新增）与播放历史（LX-Music 自带）是两个功能、两张表
 * （见 [StatsLedgerRepository] 的注释），而 `core:media` 只认一个 [PlaybackHistorySink] 出口 ——
 * 由本类做扇出，播放器不必知道下游有几套存储。
 */
@Singleton
class PlaybackSinkRouter @Inject constructor(
    private val history: PlayHistoryRepository,
    private val ledger: StatsLedgerRepository,
) : PlaybackHistorySink {

    override suspend fun onSongStarted(song: Song) {
        history.onSongStarted(song)
        ledger.onSongStarted(song)
    }

    override suspend fun onSongPlayed(song: Song, playedMs: Long, totalMs: Long) {
        history.onSongPlayed(song, playedMs, totalMs)
        ledger.onSongPlayed(song, playedMs, totalMs)
    }
}