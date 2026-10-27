package com.lxpro.core.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 让系统「看见」正在播放：MediaSessionService 必须先被启动/连接，
 * 才会把 MediaSession 发布给系统（通知栏、锁屏、蓝牙、耳机按键都依赖它）。
 *
 * 本应用是「Activity 内直接持有 ExoPlayer」的架构，此前**没有任何地方连过服务**，
 * 因此服务从未启动 —— 这就是「系统识别不到正在播放」的根因（2026-10-25 反馈）。
 *
 * 这里在播放器开始播放时连一个常驻 [MediaController]（不参与任何播放控制），
 * 服务随之启动并把 session 暴露给系统。断开（如划掉 App 后服务自杀）会重连。
 */
@Singleton
class MediaSessionKeeper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var controllerFuture: ListenableFuture<MediaController>? = null

    /** 幂等：已连上就什么都不做；连接失败/断开时重建一次 */
    fun ensureConnected() {
        val current = controllerFuture
            ?.takeIf { it.isDone }
            ?.let { runCatching { it.get() }.getOrNull() }
        if (current != null && current.isConnected) return

        val token = SessionToken(context, ComponentName(context, LXProMediaService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ runCatching { future.get() } }, { it.run() })
        controllerFuture = future
    }
}
