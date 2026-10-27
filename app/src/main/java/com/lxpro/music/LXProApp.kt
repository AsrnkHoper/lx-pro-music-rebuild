package com.lxpro.music

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import com.lxpro.core.media.lyric.DesktopLyricOverlay
import com.lxpro.core.media.lyric.LyricsCenter
import com.lxpro.core.media.noise.NoiseMusicCoordinator
import com.lxpro.core.media.timer.NoiseSleepTimer
import com.lxpro.core.media.timer.SongSleepTimer
import com.lxpro.core.stats.NoiseStatsTracker
import com.lxpro.core.model.DataSaver
import com.lxpro.source.script.ScriptSourceManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import java.io.IOException
import javax.inject.Inject
import okhttp3.OkHttpClient

/** 应用入口。⛔ 这里禁止做 IO（见 03 §8.3 启动优化清单）。 */
@HiltAndroidApp
class LXProApp : Application() {
    @Inject
    lateinit var scriptSourceManager: ScriptSourceManager

    /** 歌词中心：启动即订阅取词 + 状态栏歌词推送 + MediaMetadata.title 改写 + 繁体转换。
     *  必须在 Application 创建（不等 MediaService），否则设置页改繁体开关/桌面歌词外观时没人响应。 */
    @Inject
    lateinit var lyricsCenter: LyricsCenter

    /** 桌面歌词悬浮窗：启动即订阅开关与外观配置，进程活就活（不等 MediaService）。 */
    @Inject
    lateinit var desktopLyricOverlay: DesktopLyricOverlay

    /** 歌曲定时关闭（17 §7）：启动恢复未到点的定时（内部自行走 IO 协程） */
    @Inject
    lateinit var songSleepTimer: SongSleepTimer

    /** 白噪音睡眠定时（17 §7）：启动恢复（同上） */
    @Inject
    lateinit var noiseSleepTimer: NoiseSleepTimer

    /** 「与歌曲同时播放」关闭时的双轨互斥联动（17 §4） */
    @Inject
    lateinit var noiseMusicCoordinator: NoiseMusicCoordinator

    /** 白噪音收听记账（统计页「白噪音」子页）：订阅引擎与歌曲状态，落盘 noise_events */
    @Inject
    lateinit var noiseStatsTracker: NoiseStatsTracker

    /** 省流量模式状态源：设置三开关 + 网络计费状态 → DataSaver 门闸（#125） */
    @Inject
    lateinit var dataSaverMonitor: DataSaverMonitor

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // 规避 foundation 1.11.4 已知问题：combinedClickable 的非挂起重写
        // （CombinedClickableNode，flag 默认开启）会在 DOWN 时立即消费事件，
        // 导致同节点外层的 detectDragGesturesAfterLongPress 长按永远无法成立
        // ——「我的歌单」与「歌单详情」的拖拽排序（2026-10-10 批次三 #4/#5）
        // 在两页同时失效即此根因。官方规避方案：关闭 flag 回退旧的挂起实现
        //（字节码验证：isSuspendingPointerInputEnabled = !flag，运行时读取）。
        ComposeFoundationFlags.isNonSuspendingPointerInputInCombinedClickableEnabled = false

        // 恢复用户导入的音源脚本（内部自行走 IO 协程；坏脚本被吞掉不影响其他，见类注释）
        scriptSourceManager.start()

        // 恢复歌曲定时（未到点的重挂倒计时；已过点的作废不追杀）
        songSleepTimer.start()

        // 恢复白噪音睡眠定时（同上），并启动「与歌曲同时播放」互斥联动监听
        noiseSleepTimer.start()
        noiseMusicCoordinator.start()

        // 白噪音收听记账：进程活就记，不要求用户打开白噪音页
        noiseStatsTracker.start()

        // 省流量模式：网络计费回调 + 设置监听（非 Wi-Fi 时降音质 / 全无图）
        dataSaverMonitor.start()

        // 省流量模式的网络硬拦截（#128）：自定义 Coil ImageLoader，省流生效期间
        // **一切图片网络请求直接失败**——「有图模式」下只有内存/磁盘缓存命中的图能显示，
        // 「全无图」下 AsyncArtwork 根本不渲染、请求都不会发出。缓存命中不经过此拦截器。
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(this)
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = {
                                OkHttpClient.Builder()
                                    .addInterceptor { chain ->
                                        if (DataSaver.active) {
                                            throw IOException("省流量模式生效中：图片网络请求已拦截")
                                        }
                                        chain.proceed(chain.request())
                                    }
                                    .build()
                            },
                        ),
                    )
                }
                .build(),
        )
    }
}
