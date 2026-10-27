package com.lxpro.core.media

import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song

/** 播放状态（03 §2.2） */
data class PlayerState(
    val current: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val quality: Quality? = null,
    /** 循环模式：播放页的循环按钮要能读到**当前**模式再轮换，不能靠界面自己猜 */
    val repeatMode: RepeatMode = RepeatMode.ALL,
)

/**
 * 播放模式（用户 2026-10-03 要求补上两种随机）。
 *
 * - [ALL] 列表循环
 * - [ONE] 单曲循环
 * - [OFF] 顺序播放：播到列表末尾就停
 * - [SHUFFLE_ORDER] **随机列表**：进入模式时打乱出**一份固定序列**，之后
 *   上一首 / 下一首都在这一份序列上走 —— 所以「上一首」是可预期的回来
 * - [SHUFFLE_RANDOM] **强随机**：每次上一首 / 下一首都重新随机，
 *   不存在固定序列（种子取时间，见 `FlowRandom`）
 */
enum class RepeatMode { OFF, ONE, ALL, SHUFFLE_ORDER, SHUFFLE_RANDOM }

/**
 * 播放器门面。UI 只认这个接口（03 §2.2）。
 *
 * ⚠️ M1 的实现每首歌**单独取流并 setMediaItem**（不做预解析整队列）——
 * 无缝播放本来就在 P2 之外（02 §5.2）。
 */
interface PlayerController {
    val state: kotlinx.coroutines.flow.StateFlow<PlayerState>
    val queue: kotlinx.coroutines.flow.StateFlow<List<Song>>
    val currentIndex: kotlinx.coroutines.flow.StateFlow<Int>

    suspend fun play(song: Song, quality: Quality = Quality.K128, queue: List<Song> = emptyList())

    suspend fun playAt(index: Int)

    fun togglePlayPause()

    suspend fun next()

    suspend fun previous()

    fun seekTo(positionMs: Long)

    fun setRepeatMode(mode: RepeatMode)

    fun setSpeed(speed: Float)

    /** 当前倍速（供 UI 显示当前状态；null=未播放过） */
    fun currentSpeed(): Float

    /** 歌曲切换淡变时长（0=即切；播放页「更多」菜单可调，持久化到 DataStore） */
    fun setCrossfadeMs(ms: Long)

    /** 当前切换淡变时长（ms；0=即切）。UI 显示用（val 会与 setter JVM 冲突，故用函数） */
    fun currentCrossfadeMs(): Long

    /**
     * 消费掉 [PlayerState.error]（UI 弹出提示后调用）。
     *
     * ⚠️ 必须消费：否则同一个错误文本连续发生时 StateFlow 值不变，
     * `LaunchedEffect(error)` 不会再次触发 —— 表现为「再点一次歌就再也没有提示」。
     */
    fun clearError()

    /** 睡眠定时到点：歌曲轨淡出 [fadeMs]（规格 17 §7 = 3s）后暂停，音量恢复 */
    fun fadeOutPause(fadeMs: Long = 3_000L)

    /**
     * 「播完当前歌曲后停止」模式。
     *
     * 设为 true 后：当前 ExoPlayer.State_ENDED 触发时，[handleEnded] 会**直接 pause
     * （可选淡出）而不调 next()** —— 下一首不会自动开始，从根源消除了「先淡出下一首再停」
     * 的竞态（SongSleepTimer 的 fire() 发生在 state.collect 之后，那时下一首已经 start）。
     *
     * 同一套机制供两条路径触发：
     * - SongSleepTimer.setAfterCurrentTrack()：**立刻**设 flag=true，删去旧的 armTrackWatch
     *   反应式监听（它依赖 current 变化做判断，永远慢一拍）
     * - SongSleepTimer 分钟/小时型定时 + [finishSongFirst=true]：fire() 不直接 fadeOutPause，
     *   而是设 flag=true，等歌曲自然结束由 handleEnded 触发停
     */
    fun setStopAfterCurrentTrack(enable: Boolean)

    fun setVolume(volume: Float)

    /** 当前歌曲轨音量（0..1，供白噪音页双轨独立音量滑块用） */
    val volume: Float

    /** 歌曲轨音量变化流，外部可订阅实时更新 */
    val volumeFlow: kotlinx.coroutines.flow.StateFlow<Float>

    /** 切换音质：重新取流并保持播放位置 */
    suspend fun switchQuality(quality: Quality)
}