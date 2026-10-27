package com.lxpro.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.media.lyric.DesktopLyricOverlay
import com.lxpro.core.media.lyric.LyricsCenter
import com.lxpro.core.media.noise.NoiseEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 前台播放服务：一次性解决通知栏 / 锁屏控制 / 耳机按键 / 后台不被杀（03 §5.1）。
 *
 * ⚠️ 播放器是 Hilt 单例（[MediaModule]），服务**不负责 release 它** ——
 * 否则服务销毁后播放器就废了，后续播放会直接抛异常。
 *
 * ⚠️ **命令拦截（交叉淡变状态机）**：通知栏 / 耳机线控 / 锁屏的 play/pause/seek
 * 走 MediaSession **直通 player**，会绕过 [PlayerController] 的淡变状态机
 * （fade 中按暂停 = 旧歌硬停、新歌继续响）。这里把命令全部转交状态机处理。
 *
 * ⚠️ **M4 批一·地基**：双轨托管铺路（17 §1 硬约束③：两条音轨各有独立音量与开关）
 * - song [player]：走 [mediaSession]（通知栏 / 锁屏 / 耳机按键）
 * - noise [noiseEngine]：**不注册 MediaSession**（无 chrome，UI 内开关）；
 *   批二扩展为 foreground 由任一在播驱动，都停时降级
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class LXProMediaService : MediaSessionService() {

    @Inject
    lateinit var player: SwappablePlayer

    @Inject
    lateinit var controller: PlayerController

    /** 白噪音独立音轨（17 §1，批一·地基注入；批二实际启用） */
    @Inject
    lateinit var noiseEngine: NoiseEngine

    @Inject
    lateinit var settings: SettingsRepository

    /** 歌词中心：注入即启动全局取词与状态栏歌词推送（Lyric Getter 协议） */
    @Inject
    lateinit var lyricsCenter: LyricsCenter

    /** 桌面歌词悬浮窗：注入即开始跟随「桌面歌词」开关显示（传递创建 lyricsCenter） */
    @Inject
    lateinit var desktopLyricOverlay: DesktopLyricOverlay

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSession? = null

    /** CPU 保持运行（屏幕关了播放继续）。PARTIAL_WAKE_LOCK 不亮屏，只防 CPU 休眠。 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** NotificationId：前台服务通知固定 id，不能跟其他通知重号。 */
    private val notificationId = 1001

    /**
     * 队列由 [PlayerController] 手动管理（每个 ExoPlayer 时间线只有 1 首），
     * media3 会按 hasNext/hasPrevious/live 动态剥掉 seek 命令，藏掉通知栏按钮。
     * 这里在连接时显式补发四个 seek 命令（SwappablePlayer 侧也已常驻补发，双保险）；
     * 按钮点击仍走 commandInterceptor → 交叉淡变状态机，链路不变。
     */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = session.player.availableCommands.buildUpon()
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            // 桌面歌词的自定义会话命令必须显式授权，否则自定义按钮在系统侧是禁用态
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(LYRIC_SESSION_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        /** 系统媒体卡片上的「LRC」按钮 → 切换桌面歌词（media3 转成框架 CustomAction 送达这里） */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_DESKTOP_LYRIC) {
                serviceScope.launch {
                    // 无悬浮窗权限则不写入（保持关，图标保持带斜杠）；
                    // 锁屏/通知栏场景无法弹系统授权页，用户回设置页会有引导
                    if (Settings.canDrawOverlays(this@LXProMediaService)) {
                        settings.setDesktopLyricEnabled(!settings.desktopLyricEnabled.first())
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** 桌面歌词按钮（系统媒体卡片 / 通知栏共用），按开关状态换图标 */
    private fun lyricButton(desktopLyricOn: Boolean): CommandButton =
        CommandButton.Builder()
            .setSessionCommand(LYRIC_SESSION_COMMAND)
            .setDisplayName("桌面歌词")
            .setIconResId(
                if (desktopLyricOn) R.drawable.ic_media_lyric_on else R.drawable.ic_media_lyric_off,
            )
            .setEnabled(true)
            .build()

    private fun publishLyricButton(desktopLyricOn: Boolean) {
        mediaSession?.setMediaButtonPreferences(ImmutableList.copyOf(listOf(lyricButton(desktopLyricOn))))
    }

    /** 「耳机拔出时暂停」开关（默认开；只对插线耳机生效） */
    @Volatile
    private var pauseOnHeadphoneUnplug = true

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (!pauseOnHeadphoneUnplug || !controller.state.value.isPlaying) return
            serviceScope.launch { controller.togglePlayPause() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // === 前台服务保命三件套 ===
        // 1. NotificationChannel（Android 8.0+ 强制要求，没有 channel 调 startForeground 直接崩）
        createNotificationChannel()
        // 2. 立即 startForeground —— 不等播放就前台保活，避免用户刚进应用还没点播放就被系统杀
        startForeground(notificationId, buildPlaceholderNotification())
        // 3. WakeLock：PARTIAL_WAKE_LOCK 让 CPU 不休眠（屏幕关了继续播）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LXProMediaService::PlaybackWakeLock",
        ).apply { setReferenceCounted(false) }

        // 播放时 acquire WakeLock，暂停/停止时 release —— 只在真的需要时持锁省电
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    wakeLock?.acquire()
                } else {
                    wakeLock?.release()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                    wakeLock?.release()
                }
            }
        })

        // 通知栏/耳机/锁屏命令 → PlayerController 状态机（交叉淡变感知）
        player.commandInterceptor = { command ->
            when (command) {
                SwappablePlayer.Command.Play -> {
                    if (!controller.state.value.isPlaying) {
                        serviceScope.launch { controller.togglePlayPause() }
                    }
                    true
                }
                SwappablePlayer.Command.Pause -> {
                    if (controller.state.value.isPlaying) {
                        serviceScope.launch { controller.togglePlayPause() }
                    }
                    true
                }
                SwappablePlayer.Command.SeekNext -> {
                    serviceScope.launch { controller.next() }
                    true
                }
                SwappablePlayer.Command.SeekPrevious -> {
                    serviceScope.launch { controller.previous() }
                    true
                }
                is SwappablePlayer.Command.SeekTo -> {
                    serviceScope.launch { controller.seekTo(command.positionMs) }
                    true
                }
            }
        }
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .apply {
                // 点通知本体拉起应用（core:media 不依赖 app 的 MainActivity，用 launch intent 兜底）
                packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(
                            this@LXProMediaService, 0, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
            .build()

        // 耳机拔出（拔线耳机触发 ACTION_AUDIO_BECOMING_NOISY）→ 按设置暂停
        serviceScope.launch {
            settings.pauseOnHeadphoneUnplug.collect { pauseOnHeadphoneUnplug = it }
        }
        // 系统媒体卡片「LRC」按钮：随桌面歌词开关换图标（首次 collect 即完成初始发布）
        serviceScope.launch {
            settings.desktopLyricEnabled.collect { publishLyricButton(it) }
        }
        // 系统受保护广播，动态注册无需 RECEIVER_NOT_EXPORTED（兼容旧 core）
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 用户划掉 App 后：没在播就自己退出，在播则继续（由设置决定，见 02 §6.2） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = mediaSession?.player
        // ⚠️ M4 批一·地基：白噪音独立音轨也需考虑（17 §1 硬约束③）；
        // 批二扩展为「任一在播则继续」
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisyReceiver) }
        // 释放 WakeLock（持锁到进程死系统会警告，正常 release）
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        serviceScope.cancel()
        // 服务没了状态栏不该残留最后一行歌词（桌面悬浮窗随进程/设置自行收敛，不用管）
        lyricsCenter.clearStatusBarLyric()
        mediaSession?.release()
        mediaSession = null
        // 批二扩展：noiseEngine.stopAll(immediate = true) 释放白噪音资源
        super.onDestroy()
    }

    /** Android 8.0+ 创建前台服务必须的 NotificationChannel。
     *  IMORTANCE_LOW = 不弹横幅、不响铃、不打扰；只常驻通知栏。 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "播放服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "音乐播放期间常驻通知栏，保证后台播放不被系统终止"
                    setShowBadge(false)
                    setBypassDnd(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    /** startForeground 必须有一个有效 Notification。media3 会在 startSession 后自动用
     *  MediaStyle 替换这条占位通知，所以这里只要能过系统前台服务校验就行。 */
    private fun buildPlaceholderNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("阿玉音乐")
            .setContentText("播放服务运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private companion object {
        /** 前台服务通知的 Channel ID */
        private const val CHANNEL_ID = "lxpro_playback_channel"
        /** 系统媒体卡片「桌面歌词」自定义动作（media3 → 框架 PlaybackState.CustomAction 的 action id） */
        const val ACTION_TOGGLE_DESKTOP_LYRIC = "com.lxpro.core.media.action.TOGGLE_DESKTOP_LYRIC"
        val LYRIC_SESSION_COMMAND = SessionCommand(ACTION_TOGGLE_DESKTOP_LYRIC, Bundle.EMPTY)
    }
}