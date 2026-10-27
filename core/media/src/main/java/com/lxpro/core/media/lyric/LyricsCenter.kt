package com.lxpro.core.media.lyric

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lxpro.core.common.LyricLine
import com.lxpro.core.common.LyricParser
import com.lxpro.core.database.dao.LyricCacheDao
import com.lxpro.core.database.entity.LyricCacheEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.PlayerState
import com.lxpro.core.media.SwappablePlayer
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.Song
import com.lxpro.source.api.SourceAction
import com.lxpro.source.api.SourceRegistry
import com.github.houbb.opencc4j.util.ZhConverterUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌词中心（系统识别补齐批）：全局唯一的「当前歌词行」供给者。
 *
 * 三条出口：
 * 1. **MediaMetadata.TITLE 动态改写** —— 播放中把当前行塞进 MediaMetadata.title，
 *    让系统状态栏/锁屏/蓝牙远端**直接**读到（主流方案，不依赖任何 Xposed 模块）；
 *    暂停或无歌词 → title 恢复为歌名。
 * 2. **桌面歌词** —— [state] 被 [DesktopLyricOverlay] 订阅，悬浮窗跟随显示当前行；
 * 3. **状态栏歌词（Lyric Getter）** —— 按墨·状态栏歌词协议逐行推送（装了模块时额外生效）。
 *
 * ⚠️ 与播放页 LyricsViewModel 的分工：那边只在播放页打开时取词（页面作用域）；
 * 这里服务系统侧，只要「桌面歌词 / 状态栏歌词」任一开就全局取词。两处各自独立，
 * 都不为对方白烧请求。
 */
@Singleton
class LyricsCenter @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val playerController: PlayerController,
    private val sourceRegistry: SourceRegistry,
    private val localMusicRepository: LocalMusicRepository,
    private val settings: SettingsRepository,
    private val sessionPlayer: SwappablePlayer,
    private val lyricCacheDao: LyricCacheDao,
) {

    data class State(
        /** 当前曲目的稳定标识（source:id）；空 = 没在播 / 未取词 */
        val songKey: String = "",
        val lines: List<LyricLine> = emptyList(),
        /** 当前行下标（前奏 / 纯音乐为 -1） */
        val currentIndex: Int = -1,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    ) {
        val currentLine: LyricLine? get() = lines.getOrNull(currentIndex)
    }

    /**
     * scope 用 Main dispatcher —— sessionPlayer（ExoPlayer 包装）的所有 API 必须主线程访问。
     * 取词 I/O 用 withContext(Dispatchers.IO) 切走。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 歌名缓存：换歌后恢复 MediaMetadata.title 用 */
    private var currentSong: Song? = null

    /** 繁体转换开关（默认开） */
    private var traditionalEnabled = true

    /** 行文本转换缓存：original → converted，避免每行调用 opencc4j */
    private val convertedCache = HashMap<String, String>()

    init {
        scope.launch {
            settings.traditionalLyricEnabled.collect { traditionalEnabled = it }
        }

        scope.launch {
            // 两个开关任一打开才工作；全关 → 清空并彻底停取（不白烧网络/IO）
            combine(settings.desktopLyricEnabled, settings.statusBarLyricEnabled) { d, s -> d to s }
                .distinctUntilChanged()
                .collectLatest { (desktop, statusBar) ->
                    if (!desktop && !statusBar) {
                        runCatching { cn.lyric.getter.api.API().clearLyric() }
                        restoreSongTitle()
                        return@collectLatest
                    }
                    // 换歌 → 重新取词（collectLatest 取消上一首未完成的请求）
                    launch {
                        playerController.state
                            .map { it.current }
                            .distinctUntilChanged()
                            .collectLatest { song -> load(song) }
                    }
                    // 进度 / 播放态 → 行跟踪 + MediaMetadata + 状态栏逐行推送
                    launch {
                        playerController.state.collect { ps -> onProgress(ps) }
                    }
                }
        }
    }

    private suspend fun load(song: Song?) {
        convertedCache.clear()
        currentSong = song
        if (song == null) {
            _state.value = State()
            runCatching { cn.lyric.getter.api.API().clearLyric() }
            restoreSongTitle()
            return
        }
        val songKey = "${song.source}:${song.id}"
        // 取词是文件 I/O / 网络请求 —— 必须切到 IO dispatcher，
        // 现在 scope 是 Main（Player API 强制主线程），直接跑会卡主线程
        val lines: List<LyricLine> = withContext(Dispatchers.IO) {
            // 换源钉住的歌：实际出声的是目标源，歌词必须跟目标源走（时间轴才对齐），
            // 目标源取不到再回落原源（04 · 换源，2026-10-27 用户反馈「歌词怎么没识别出来」）
            val pinned = lyricLinesFor(song.toggleSong ?: song)
            if (pinned.isEmpty() && song.toggleSong != null) lyricLinesFor(song) else pinned
        }
        if (lines.isEmpty()) runCatching { cn.lyric.getter.api.API().clearLyric() }
        _state.value = _state.value.copy(songKey = songKey, lines = lines, currentIndex = -1)
    }

    /** 单源取词：本地 → 同目录 .lrc / 音频内嵌；在线 → lyric_cache 先查，未命中再在线请求写回。 */
    private suspend fun lyricLinesFor(song: Song): List<LyricLine> {
        if (song.source == LOCAL_SOURCE_ID) {
            val raw = runCatching { localMusicRepository.lyricsForTrackUri(song.id) }.getOrNull()
            return LyricParser.parse(raw)
        }
        // 1) 本地缓存命中 → 直接 parse 返回（零网络）
        val cache = runCatching { lyricCacheDao.get(song.source.value, song.id) }.getOrNull()
        if (cache != null) {
            val lines = LyricParser.parse(cache.lyric, cache.tlyric)
            if (lines.isNotEmpty()) {
                return lines
            }
        }
        // 2) 未命中 → 在线请求
        val source = sourceRegistry.byId(song.source) ?: return emptyList()
        if (SourceAction.LYRIC !in source.actions) {
            return emptyList()
        }
        val info = runCatching { source.getLyric(song) }.getOrNull() ?: return emptyList()
        val lines = LyricParser.parse(info.lyric, info.tlyric)
        if (lines.isNotEmpty()) {
            // 3) 成功落盘缓存（TTL 7 天）
            runCatching {
                lyricCacheDao.upsert(
                    LyricCacheEntity(
                        source = song.source.value,
                        songId = song.id,
                        lyric = info.lyric,
                        tlyric = info.tlyric,
                        cachedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { android.util.Log.e("LyricCache", "upsert FAILED ${it.message}") }
        }
        return lines
    }

    private fun onProgress(ps: PlayerState) {
        val st = _state.value
        val song = ps.current ?: return
        val songKey = "${song.source}:${song.id}"
        // 取词还没落定（切歌瞬间进度先到）——等 load 完成，别拿上一首的行乱推
        if (st.songKey != songKey) return

        val index = LyricParser.indexAt(st.lines, ps.positionMs)
        val newState = st.copy(
            currentIndex = index,
            isPlaying = ps.isPlaying,
            positionMs = ps.positionMs,
            durationMs = ps.durationMs,
        )
        _state.value = newState

        // ⚠️ MediaMetadata.TITLE 动态改写：系统状态栏/锁屏/蓝牙远端直接读 MediaMetadata，
        // 播放中把当前行塞进去让它实时滚动显示歌词；暂停或无行恢复歌名。
        // 这才是 LX-Pro/LX-Music 让系统显示当前行的主流方案（不是 Lyric Getter）。
        syncMetadataTitle(newState)

        // 状态栏歌词推送（Lyric Getter 协议，装了 Xposed 模块才生效）
        if (!ps.isPlaying || index < 0) return
        val line = st.lines[index]
        runCatching {
            cn.lyric.getter.api.API().sendLyric(
                convertForDisplay(line.text),
                cn.lyric.getter.api.data.ExtraData().apply {
                    packageName = context.packageName
                    useOwnMusicController = false
                    delay = if (index + 1 < st.lines.size) {
                        (st.lines[index + 1].timeMs - line.timeMs).coerceAtLeast(0L)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else 3000
                },
            )
        }
    }

    /** 把 MediaMetadata.title 改成当前行（播放中）或歌名（暂停/无歌词）。
     *  调 replaceMediaItem(0, newItem) —— 只替换时间线首项，ExoPlayer 不重缓冲，
     *  但会发 onMediaMetadataChanged 事件 → MediaSession 监听到后自动更新 PlaybackState。
     */
    private fun syncMetadataTitle(st: State) {
        val item = sessionPlayer.currentMediaItem ?: return
        val title = when {
            !st.isPlaying || st.currentIndex < 0 -> currentSong?.name ?: ""
            else -> convertForDisplay(st.lines[st.currentIndex].text).ifBlank { currentSong?.name ?: "" }
        }
        // title 没变就不 replace，避免 500ms 一次的无意义操作
        if (item.mediaMetadata.title?.toString() == title) return
        val newMeta = item.mediaMetadata.buildUpon()
            .setTitle(title)
            .build()
        val newItem = item.buildUpon().setMediaMetadata(newMeta).build()
        // 时间线永远只有 1 首，index 固定为 0
        runCatching { sessionPlayer.replaceMediaItem(0, newItem) }
    }

    private fun restoreSongTitle() {
        val item = sessionPlayer.currentMediaItem ?: return
        val name = currentSong?.name ?: return
        if (item.mediaMetadata.title?.toString() == name) return
        val newMeta = item.mediaMetadata.buildUpon().setTitle(name).build()
        val newItem = item.buildUpon().setMediaMetadata(newMeta).build()
        runCatching { sessionPlayer.replaceMediaItem(0, newItem) }
    }

    /** 对外暴露的繁体转换：internal 改 public，feature:player 等播放页歌词渲染点可用 */
    fun convertForDisplay(text: String): String {
        if (!traditionalEnabled || text.isBlank()) return text
        return convertedCache.getOrPut(text) {
            runCatching { ZhConverterUtil.toTraditional(text) }.getOrDefault(text)
        }
    }

    /** 播放服务销毁时清掉系统侧歌词 */
    fun clearStatusBarLyric() {
        runCatching { cn.lyric.getter.api.API().clearLyric() }
        restoreSongTitle()
    }
}
