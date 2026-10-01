package com.lxpro.core.media

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 前台播放服务：一次性解决通知栏 / 锁屏控制 / 耳机按键 / 后台不被杀（03 §5.1）。
 *
 * ⚠️ 播放器是 Hilt 单例（[MediaModule]），服务**不负责 release 它** ——
 * 否则服务销毁后播放器就废了，后续播放会直接抛异常。
 */
@AndroidEntryPoint
class LXProMediaService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 用户划掉 App 后：没在播就自己退出，在播则继续（由设置决定，见 02 §6.2） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = mediaSession?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}