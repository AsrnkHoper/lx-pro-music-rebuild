package com.lxpro.core.media

import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi

/**
 * 可换 delegate 的 Player 包装（Media3 自带 ForwardingPlayer 的 delegate 是 immutable 的，
 * 无法满足交叉淡变「新歌 player 转正」的需求）。
 *
 * - [swap] 把 delegate 切到新 player，并把所有已注册 Listener 迁移过去 ——
 *   MediaSession / UI 监听器无感知，自动跟随当前出声的 player
 * - [commandInterceptor]：通知栏 / 耳机线控 / 锁屏的命令拦截钩子。MediaSession 对命令是
 *   **直通 delegate** 的，会绕过 PlayerController 的交叉淡变状态机（fade 中按暂停 =
 *   淡入中的新歌继续响、旧歌硬停）——返回 true 表示上层状态机已处理，不再直通
 * - 其余全部方法纯转发（由 Player 接口方法表机械生成，勿手改）
 */
@OptIn(UnstableApi::class)
class SwappablePlayer(initial: Player) : Player {

    /** MediaSession 直通命令的语义类型（拦截钩子用） */
    sealed interface Command {
        data object Play : Command
        data object Pause : Command
        data object SeekNext : Command
        data object SeekPrevious : Command
        data class SeekTo(val positionMs: Long) : Command
    }

    /** 返回 true = 命令已被上层（PlayerController 状态机）处理，本类不再转发 delegate */
    var commandInterceptor: ((Command) -> Boolean)? = null

    private var delegate: Player = initial

    private val listeners = mutableListOf<Player.Listener>()

    private fun intercept(command: Command): Boolean = commandInterceptor?.invoke(command) == true

    /** 切换 delegate：监听器从旧 player 迁移到新 player。
     *
     * ⚠️ loadToPlayer(新 player, 新歌) 一定在 swap 之前完成 ——
     * 新 player 的 onMediaItemChanged / onPlaybackStateChanged 等事件在 swap 之前就发完了，
     * 但那时 MediaSession 等 Listener 还绑在旧 player 上，**永远错过**。
     * 迁完之后手动补发关键事件，让 MediaSession 重新读取新 delegate 的 metadata/state，
     * 这样锁屏/通知栏卡片会立刻显示正确的歌名、封面、进度。
     */
    fun swap(newDelegate: Player) {
        if (newDelegate === delegate) return
        listeners.forEach { old ->
            runCatching { delegate.removeListener(old) }
            runCatching { newDelegate.addListener(old) }
        }
        delegate = newDelegate
        // 补发事件：Player.Listener 是普通接口，直接回调当前 delegate 的状态即可。
        // 1.11.1 里方法名是 onMediaItemTransition / onPlayWhenReadyChanged 等，
        // 和 2.x 的 onMediaItemChanged 不同，别写错了
        val currentItem = delegate.currentMediaItem
        val md = delegate.mediaMetadata
        val playbackState = delegate.playbackState
        val params = delegate.playbackParameters
        val repeatMode = delegate.repeatMode
        val shuffleMode = delegate.shuffleModeEnabled
        val timeline = delegate.currentTimeline
        listeners.forEach { listener ->
            runCatching { listener.onMediaItemTransition(currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
            runCatching { listener.onMediaMetadataChanged(md) }
            runCatching { listener.onPlaybackStateChanged(playbackState) }
            runCatching { listener.onPlayWhenReadyChanged(delegate.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            runCatching { listener.onPlaybackParametersChanged(params) }
            runCatching { listener.onRepeatModeChanged(repeatMode) }
            runCatching { listener.onShuffleModeEnabledChanged(shuffleMode) }
            runCatching { listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        }
    }

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        delegate.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        delegate.removeListener(listener)
    }

    override fun getApplicationLooper(): Looper = delegate.getApplicationLooper()
    override fun setMediaItems(p0: MutableList<MediaItem>) = delegate.setMediaItems(p0)
    override fun setMediaItems(p0: MutableList<MediaItem>, p1: Boolean) = delegate.setMediaItems(p0, p1)
    override fun setMediaItems(p0: MutableList<MediaItem>, p1: Int, p2: Long) = delegate.setMediaItems(p0, p1, p2)
    override fun setMediaItem(p0: MediaItem) = delegate.setMediaItem(p0)
    override fun setMediaItem(p0: MediaItem, p1: Long) = delegate.setMediaItem(p0, p1)
    override fun setMediaItem(p0: MediaItem, p1: Boolean) = delegate.setMediaItem(p0, p1)
    override fun addMediaItem(p0: MediaItem) = delegate.addMediaItem(p0)
    override fun addMediaItem(p0: Int, p1: MediaItem) = delegate.addMediaItem(p0, p1)
    override fun addMediaItems(p0: MutableList<MediaItem>) = delegate.addMediaItems(p0)
    override fun addMediaItems(p0: Int, p1: MutableList<MediaItem>) = delegate.addMediaItems(p0, p1)
    override fun moveMediaItem(p0: Int, p1: Int) = delegate.moveMediaItem(p0, p1)
    override fun moveMediaItems(p0: Int, p1: Int, p2: Int) = delegate.moveMediaItems(p0, p1, p2)
    override fun replaceMediaItem(p0: Int, p1: MediaItem) = delegate.replaceMediaItem(p0, p1)
    override fun replaceMediaItems(p0: Int, p1: Int, p2: MutableList<MediaItem>) = delegate.replaceMediaItems(p0, p1, p2)
    override fun removeMediaItem(p0: Int) = delegate.removeMediaItem(p0)
    override fun removeMediaItems(p0: Int, p1: Int) = delegate.removeMediaItems(p0, p1)
    override fun clearMediaItems() = delegate.clearMediaItems()
    override fun isCommandAvailable(p0: Int): Boolean = p0 in PINNED_COMMANDS || delegate.isCommandAvailable(p0)
    override fun canAdvertiseSession(): Boolean = delegate.canAdvertiseSession()

    // ⚠️ 队列由 PlayerController 手动管理，delegate ExoPlayer 的时间线永远只有 1 首，
    // Util.getAvailableCommands 会把 seek 四命令（6/7/8/9）按 hasNext/hasPrevious/live 动态剥掉
    // （实测 1.11.1：在线曲目时长未解析时被当成 live，只剩「上一首」）——
    // 通知栏按钮与框架 PlaybackState 的 skip 动作都从这个集合推导，
    // 所以在这里常驻补发；点击仍走 intercept → 交叉淡变状态机，语义不变
    override fun getAvailableCommands(): Player.Commands =
        delegate.getAvailableCommands().buildUpon().addAll(*PINNED_COMMANDS).build()

    private companion object {
        /**
         * 队列由 PlayerController 手动管理，delegate 永远只有 1 首时间线 ——
         * media3 的 Util.getAvailableCommands 会按 hasNext/hasPrevious/live 动态剥掉这些命令，
         * 导致通知栏按钮消失 / PlaybackState 缺少对应 ACTION。
         * 在这里常驻补发；交互仍走 commandInterceptor → 交叉淡变状态机，语义不变。
         */
        val PINNED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,             // 7   上一首按钮 + ACTION_SKIP_TO_PREVIOUS
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,  // 6   legacy 变体（媒体库场景）
            Player.COMMAND_SEEK_TO_NEXT,                 // 9   下一首按钮 + ACTION_SKIP_TO_NEXT
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,      // 8   legacy 变体
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,   // 5   进度条拖动 + ACTION_SEEK_TO_POSITION
        )
    }
    override fun prepare() = delegate.prepare()
    override fun getPlaybackState(): Int = delegate.getPlaybackState()
    override fun getPlaybackSuppressionReason(): Int = delegate.getPlaybackSuppressionReason()
    override fun isPlaying(): Boolean = delegate.isPlaying()
    override fun getPlayerError(): PlaybackException? = delegate.getPlayerError()
    override fun play() { if (!intercept(Command.Play)) delegate.play() }
    override fun pause() { if (!intercept(Command.Pause)) delegate.pause() }
    override fun setPlayWhenReady(p0: Boolean) {
        // MediaSession 部分路径用 setPlayWhenReady 表达 play/pause 意图，一并纳入拦截
        if (!intercept(if (p0) Command.Play else Command.Pause)) delegate.setPlayWhenReady(p0)
    }
    override fun getPlayWhenReady(): Boolean = delegate.getPlayWhenReady()
    override fun setRepeatMode(p0: Int) = delegate.setRepeatMode(p0)
    override fun getRepeatMode(): Int = delegate.getRepeatMode()
    override fun setShuffleModeEnabled(p0: Boolean) = delegate.setShuffleModeEnabled(p0)
    override fun getShuffleModeEnabled(): Boolean = delegate.getShuffleModeEnabled()
    override fun isLoading(): Boolean = delegate.isLoading()
    override fun seekToDefaultPosition() = delegate.seekToDefaultPosition()
    override fun seekToDefaultPosition(p0: Int) = delegate.seekToDefaultPosition(p0)
    override fun seekTo(p0: Long) { if (!intercept(Command.SeekTo(p0))) delegate.seekTo(p0) }
    override fun seekTo(p0: Int, p1: Long) { if (!intercept(Command.SeekTo(p1))) delegate.seekTo(p0, p1) }
    override fun getSeekBackIncrement(): Long = delegate.getSeekBackIncrement()
    override fun seekBack() = delegate.seekBack()
    override fun getSeekForwardIncrement(): Long = delegate.getSeekForwardIncrement()
    override fun seekForward() = delegate.seekForward()
    override fun hasPreviousMediaItem(): Boolean = delegate.hasPreviousMediaItem()
    override fun seekToPreviousMediaItem() { if (!intercept(Command.SeekPrevious)) delegate.seekToPreviousMediaItem() }
    override fun getMaxSeekToPreviousPosition(): Long = delegate.getMaxSeekToPreviousPosition()
    override fun seekToPrevious() { if (!intercept(Command.SeekPrevious)) delegate.seekToPrevious() }
    override fun hasNextMediaItem(): Boolean = delegate.hasNextMediaItem()
    override fun seekToNextMediaItem() { if (!intercept(Command.SeekNext)) delegate.seekToNextMediaItem() }
    override fun seekToNext() { if (!intercept(Command.SeekNext)) delegate.seekToNext() }
    override fun setPlaybackParameters(p0: PlaybackParameters) = delegate.setPlaybackParameters(p0)
    override fun setPlaybackSpeed(p0: Float) = delegate.setPlaybackSpeed(p0)
    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()
    override fun stop() = delegate.stop()
    override fun release() = delegate.release()
    override fun getCurrentTracks(): Tracks = delegate.getCurrentTracks()
    override fun getTrackSelectionParameters(): TrackSelectionParameters = delegate.getTrackSelectionParameters()
    override fun setTrackSelectionParameters(p0: TrackSelectionParameters) = delegate.setTrackSelectionParameters(p0)
    override fun getMediaMetadata(): MediaMetadata = delegate.getMediaMetadata()
    override fun getPlaylistMetadata(): MediaMetadata = delegate.getPlaylistMetadata()
    override fun setPlaylistMetadata(p0: MediaMetadata) = delegate.setPlaylistMetadata(p0)
    override fun getCurrentManifest(): Any? = delegate.getCurrentManifest()
    override fun getCurrentTimeline(): Timeline = delegate.getCurrentTimeline()
    override fun getCurrentPeriodIndex(): Int = delegate.getCurrentPeriodIndex()
    override fun getCurrentWindowIndex(): Int = delegate.getCurrentWindowIndex()
    override fun getCurrentMediaItemIndex(): Int = delegate.getCurrentMediaItemIndex()
    override fun getNextWindowIndex(): Int = delegate.getNextWindowIndex()
    override fun getNextMediaItemIndex(): Int = delegate.getNextMediaItemIndex()
    override fun getPreviousWindowIndex(): Int = delegate.getPreviousWindowIndex()
    override fun getPreviousMediaItemIndex(): Int = delegate.getPreviousMediaItemIndex()
    override fun getCurrentMediaItem(): MediaItem? = delegate.getCurrentMediaItem()
    override fun getMediaItemCount(): Int = delegate.getMediaItemCount()
    override fun getMediaItemAt(p0: Int): MediaItem = delegate.getMediaItemAt(p0)
    override fun getDuration(): Long = delegate.getDuration()
    override fun getCurrentPosition(): Long = delegate.getCurrentPosition()
    override fun getBufferedPosition(): Long = delegate.getBufferedPosition()
    override fun getBufferedPercentage(): Int = delegate.getBufferedPercentage()
    override fun getTotalBufferedDuration(): Long = delegate.getTotalBufferedDuration()
    override fun isCurrentWindowDynamic(): Boolean = delegate.isCurrentWindowDynamic()
    override fun isCurrentMediaItemDynamic(): Boolean = delegate.isCurrentMediaItemDynamic()
    override fun isCurrentWindowLive(): Boolean = delegate.isCurrentWindowLive()
    override fun isCurrentMediaItemLive(): Boolean = delegate.isCurrentMediaItemLive()
    override fun getCurrentLiveOffset(): Long = delegate.getCurrentLiveOffset()
    override fun isCurrentWindowSeekable(): Boolean = delegate.isCurrentWindowSeekable()
    override fun isCurrentMediaItemSeekable(): Boolean = delegate.isCurrentMediaItemSeekable()
    override fun isPlayingAd(): Boolean = delegate.isPlayingAd()
    override fun getCurrentAdGroupIndex(): Int = delegate.getCurrentAdGroupIndex()
    override fun getCurrentAdIndexInAdGroup(): Int = delegate.getCurrentAdIndexInAdGroup()
    override fun getContentDuration(): Long = delegate.getContentDuration()
    override fun getContentPosition(): Long = delegate.getContentPosition()
    override fun getContentBufferedPosition(): Long = delegate.getContentBufferedPosition()
    override fun getAudioAttributes(): AudioAttributes = delegate.getAudioAttributes()
    override fun getAudioSessionId(): Int = delegate.getAudioSessionId()
    override fun setVolume(p0: Float) = delegate.setVolume(p0)
    override fun getVolume(): Float = delegate.getVolume()
    override fun mute() = delegate.mute()
    override fun unmute() = delegate.unmute()
    override fun clearVideoSurface() = delegate.clearVideoSurface()
    override fun clearVideoSurface(p0: Surface?) = delegate.clearVideoSurface(p0)
    override fun setVideoSurface(p0: Surface?) = delegate.setVideoSurface(p0)
    override fun setVideoSurfaceHolder(p0: SurfaceHolder?) = delegate.setVideoSurfaceHolder(p0)
    override fun clearVideoSurfaceHolder(p0: SurfaceHolder?) = delegate.clearVideoSurfaceHolder(p0)
    override fun setVideoSurfaceView(p0: SurfaceView?) = delegate.setVideoSurfaceView(p0)
    override fun clearVideoSurfaceView(p0: SurfaceView?) = delegate.clearVideoSurfaceView(p0)
    override fun setVideoTextureView(p0: TextureView?) = delegate.setVideoTextureView(p0)
    override fun clearVideoTextureView(p0: TextureView?) = delegate.clearVideoTextureView(p0)
    override fun getVideoSize(): VideoSize = delegate.getVideoSize()
    override fun getSurfaceSize(): Size = delegate.getSurfaceSize()
    override fun getCurrentCues(): CueGroup = delegate.getCurrentCues()
    override fun getDeviceInfo(): DeviceInfo = delegate.getDeviceInfo()
    override fun getDeviceVolume(): Int = delegate.getDeviceVolume()
    override fun isDeviceMuted(): Boolean = delegate.isDeviceMuted()
    override fun setDeviceVolume(p0: Int) = delegate.setDeviceVolume(p0)
    override fun setDeviceVolume(p0: Int, p1: Int) = delegate.setDeviceVolume(p0, p1)
    override fun increaseDeviceVolume() = delegate.increaseDeviceVolume()
    override fun increaseDeviceVolume(p0: Int) = delegate.increaseDeviceVolume(p0)
    override fun decreaseDeviceVolume() = delegate.decreaseDeviceVolume()
    override fun decreaseDeviceVolume(p0: Int) = delegate.decreaseDeviceVolume(p0)
    override fun setDeviceMuted(p0: Boolean) = delegate.setDeviceMuted(p0)
    override fun setDeviceMuted(p0: Boolean, p1: Int) = delegate.setDeviceMuted(p0, p1)
    override fun setAudioAttributes(p0: AudioAttributes, p1: Boolean) = delegate.setAudioAttributes(p0, p1)
}
