package com.lxpro.core.media

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.ExoPlayer
import com.lxpro.core.common.FlowRandom
import com.lxpro.core.media.crossfade.Crossfader
import com.lxpro.core.media.eq.EqConfig
import com.lxpro.core.media.eq.EqPresetRepository
import com.lxpro.core.media.eq.ParametricEqAudioProcessor
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.DataSaver
import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.network.RequestHeaderStrategy
import com.lxpro.source.api.CrossSourceFinder
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双 ExoPlayer 交叉淡变（DJ 式真重叠）+ **SwappablePlayer 转正架构（零交接）**。
 *
 * 为什么彻底放弃「交接」（「开头播一会儿后忽然卡顿」的根治）：
 * - 任何把新歌搬回 MediaSession 绑定 player 的交接（setMediaItem+seekTo）都必然伴随
 *   A 的 seek 重缓冲（handoverPos 在 A 缓冲区外）→ B 已停 = 200~500ms 空窗。
 *   交接在架构上无法同时消灭 gap 和 repeat —— 用户四轮真机反馈实锤。
 * - 解法：MediaSession 绑定的不是裸 ExoPlayer，而是 [SwappablePlayer]（可换 delegate）。
 *   fade 结束后**新歌 player 直接转正**（swap），MediaSession/UI 监听器自动跟随 ——
 *   没有 setMediaItem、没有 seek、没有重缓冲，物理上不存在空窗与回跳。
 * - playerA/playerB 轮流当「主/备」：每次 fade 从当前 active 淡出到另一个 player，
 *   淡完转正，旧 player 退役。焦点在转正时一并转移（旧 player 已 retire，无冲突）。
 *
 * - 等功率曲线（cos/sin）：重叠期感知响度恒定 → 丝滑渐变
 * - 预缓冲到安全水位（fade 时长+3s）后才起跑，防网络 stall 位置落后
 * - 暂停/继续只操作 active，独立 200ms 短淡变。白噪音不参与（17 §1 硬约束②）
 */
@Singleton
class PlayerControllerImpl @Inject constructor(
    private val playerA: ExoPlayer,               // 初始 active（sessionPlayer 的初始 delegate）
    private val sessionPlayer: SwappablePlayer,   // MediaSession 绑定；swap 后跟随 active
    @ApplicationContext context: Context,
    okHttpClient: OkHttpClient,
    cache: Cache,
    private val sources: SourceRegistry,
    private val headerStrategy: RequestHeaderStrategy,
    private val historySink: PlaybackHistorySink,
    private val settings: SettingsRepository,
    private val eqConfig: EqConfig,             // EQ 配置（17 §1 硬约束①：只挂 song 双轨，白噪音直通）
    private val eqPresets: EqPresetRepository,  // EQ 曲线仓库（当前曲线名 → 32 段增益 → EqConfig）
    private val crossSourceFinder: CrossSourceFinder,  // 换源（04 · 换源）：跨源同名检索 + 匹配排序
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // playerB：交叉淡变备用 player（不持音频焦点；焦点由 requestFocus() 统一管理）。
    // ⚠️ 必须同挂 EQ（独立实例、共享 EqConfig）：swap 架构下新歌转正后一直在 B 上播，
    // B 不挂 EQ 则切一次歌 EQ 永久失效；处理器实例有内部缓冲状态，A/B 不可共享同一实例
    private val playerB: ExoPlayer =
        PlayerFactory.create(
            context, okHttpClient, cache,
            arrayOf(ParametricEqAudioProcessor(eqConfig)),
            handleAudioFocus = false,
        )

    /** 焦点接管用（promote 时对新 active 开启 handleAudioFocus，旧 player 已 retire 无冲突） */
    private val focusAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** 当前出声的 player（fade 完成即转正切换） */
    @Volatile
    private var active: ExoPlayer = playerA

    /** 当前切歌淡变时长（毫秒），DataStore 默认 4000（17 §6.1） */
    @Volatile
    private var crossfadeMs: Long = 4_000L
    private var currentSpeed: Float = 1.0f

    /** 暂停/继续是否走淡变 */
    @Volatile
    private var pauseResumeFadeEnabled: Boolean = true

    /** 记忆上次播放位置开关 + 位置表（songKey → 毫秒） */
    @Volatile
    private var rememberPlaybackPosition: Boolean = true

    @Volatile
    private var savedPositions: Map<String, Long> = emptyMap()

    /** 正在进行的切歌并行 fade job（cancel 会连 children 一起取消） */
    private var currentFadeJob: Job? = null

    // ── URL 缓存（解决「每次 getMusicUrl 返回新签名 URL → SimpleCache 永远 miss」）──
    // 签名 URL 有效期通常几分钟到几小时，内存 LruCache 足够；杀进程自动清空
    private data class CachedUri(val uri: Uri, val expireAt: Long)
    private val playUrlCache = android.util.LruCache<String, CachedUri>(200)

    private fun cacheKey(song: Song, quality: Quality): String =
        "${song.source.value}:${song.id}:${quality.name}"

    /** 命中 URL 缓存直接返回；未命中调 getMusicUrl 后写入 */
    private suspend fun fetchUriCached(song: Song, quality: Quality): Uri {
        val key = cacheKey(song, quality)
        val cached = playUrlCache.get(key)
        if (cached != null && cached.expireAt > System.currentTimeMillis()) {
            return cached.uri
        }
        val fresh = fetchUri(song, quality)
        playUrlCache.put(key, CachedUri(fresh, System.currentTimeMillis() + 10 * 60 * 1000L))
        return fresh
    }

    /** 正在进行的暂停/继续淡变 job（快速连点时取消上一个，防音量打架） */
    private var pauseFadeJob: Job? = null

    // ── 音频焦点（统一手动管理）─────────────────────────────────────────────
    // ⚠️ 日志+听感实锤：ExoPlayer 的 setAudioAttributes（切焦点用）会 flush AudioTrack
    // 缓冲 —— 每次 promote 调用都在新歌开头「卡」一下。所以两个 player 全部
    // handleAudioFocus=false（AudioTrack 零扰动），焦点由这里用系统 API 直接管理。
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** 「与其他应用同时播放」：开 = 不请求/不响应焦点，与其他应用混音同播 */
    @Volatile
    private var playWithOtherApps = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (playWithOtherApps) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> scope.launch {
                pauseFadeJob?.cancel()
                currentFadeJob?.cancel()
                currentFadeJob = null
                val other = otherOf(active)
                if (other.mediaItemCount > 0) retire(other)
                active.pause()
                active.volume = _volumeFlow.value
                saveActivePosition()
                _state.update { it.copy(isPlaying = false) }
                trackListening(false)
            }
            else -> Unit
        }
    }

    /** 请求音频焦点（幂等）。已在持有时不重复请求；「与其他应用同时播放」开启时不请求 */
    private fun requestFocus() {
        if (playWithOtherApps || focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    /**
     * 加载代际：任何异步加载（fade 预载 / hard cut / 换歌）启动时自增，
     * 完成时校验——不匹配说明期间有更新的播放意图，本次结果作废，防止旧加载覆盖新状态。
     */
    private var loadGeneration = 0L

    // ── 状态流 ──
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    override val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _volumeFlow = MutableStateFlow(1f)
    override val volumeFlow: StateFlow<Float> = _volumeFlow.asStateFlow()
    override val volume: Float get() = _volumeFlow.value

    private var repeatMode: RepeatMode = RepeatMode.ALL
    private var currentQuality: Quality = Quality.K128

    /**
     * 「播完当前歌曲后停止」拦截标志。
     *
     * - SongSleepTimer.setAfterCurrentTrack() 立刻置 true → 下一次 STATE_ENDED 时
     *   [handleEnded] 直接 pause 不调 next()（下一首不会 start）
     * - SongSleepTimer 分钟/小时定时 + finishSongFirst → fire() 置 true 而非 fadeOutPause
     *
     * fadeOutPause / pause 后清掉（避免残留影响后续正常播放）。
     */
    @Volatile
    private var stopAfterCurrentTrack = false

    // ── 随机序列 ──
    private var shuffleOrder: List<Int> = emptyList()
    private var shuffleCursor: Int = 0

    // ── 真实播放时长累计 ──
    private var listenedMs = 0L
    private var countingSince = 0L

    /** 设置页「默认音质」（preferred_quality 的内存快照）；null = 用户从未设置 */
    @Volatile
    private var preferredQualityValue: String? = null

    init {
        scope.launch { settings.crossfadeMs.collect { ms -> crossfadeMs = ms.coerceIn(0L, 8_000L) } }
        scope.launch { settings.pauseResumeFadeEnabled.collect { pauseResumeFadeEnabled = it } }
        scope.launch { settings.playbackSpeed.collect { speed ->
            currentSpeed = speed.coerceIn(0.5f, 2.0f)
            sessionPlayer.setPlaybackSpeed(currentSpeed)
        } }
        scope.launch { settings.playWithOtherApps.collect { enabled ->
            val was = playWithOtherApps
            playWithOtherApps = enabled
            if (!was && enabled) {
                // 切到「同播」：释放焦点，其他应用不再被我们压制
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else if (was && !enabled && _state.value.isPlaying) {
                // 切回「独占」：播放中立刻收回焦点（压掉其他应用声音）
                requestFocus()
            }
        } }
        // 记忆上次播放位置（17 §6 播放行为）：开关 + 位置表都在内存留一份，避免每次加载都读盘
        scope.launch { settings.rememberPlaybackPosition.collect { rememberPlaybackPosition = it } }
        scope.launch { settings.playbackPositions.collect { savedPositions = it } }
        // EQ 直通开关（17 §5.2：切「正常」= 彻底还原）
        scope.launch { settings.eqBypass.collect { eqConfig.bypass = it } }
        // EQ 当前曲线：名字 → 仓库查出 32 段增益写入 EqConfig（处理器实时比对快照）
        scope.launch { settings.eqCurrentPresetName.collect { name -> eqPresets.applyToConfig(name) } }
        // 默认音质（省流量模式 #125：play() 的 effectiveQuality 消费）
        scope.launch { settings.preferredQuality.collect { preferredQualityValue = it } }
        sessionPlayer.addListener(makePlayerListener())

        // 进度轮询：fade 期间读淡入中的 player（新歌正在其上出声），其余时候读 active
        scope.launch {
            while (isActive) {
                val fading = otherOf(active)
                val p = if (currentFadeJob?.isActive == true && fading.isPlaying) fading else active
                if (p.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = p.currentPosition,
                            bufferedMs = p.bufferedPosition,
                            durationMs = p.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun otherOf(p: ExoPlayer): ExoPlayer = if (p === playerA) playerB else playerA

    private fun makePlayerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // fade 期间监听器只收得到「淡出方（旧歌）」的事件：
            // 旧歌播完/被打断的 false 会污染图标（新歌正在 B 上出声）——忽略，
            // fade 完成后 promote→swap 会用新 player 的初始状态 post 恢复真实值
            if (!isPlaying && currentFadeJob?.isActive == true) return
            _state.update { it.copy(isPlaying = isPlaying) }
            trackListening(isPlaying)
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> _state.update {
                    it.copy(isBuffering = false, durationMs = active.duration.coerceAtLeast(0L))
                }
                Player.STATE_ENDED -> {
                    // ⚠️ 关键竞态修复：fade 期间旧歌播到结尾的 ENDED 绝不能触发 handleEnded——
                    // 否则 next() 会把正在淡入的新歌杀掉再硬切下下首（「过渡消失/连跳」的根因）。
                    // 新歌转正后（currentFadeJob 已置 null）的 ENDED 才是正常的播完切歌。
                    if (currentFadeJob?.isActive == true) return
                    _state.update { it.copy(isBuffering = false) }
                    scope.launch { handleEnded() }
                }
                else -> Unit
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(isBuffering = false, error = error.message ?: "播放失败") }
        }
    }

    override suspend fun play(song: Song, quality: Quality, queue: List<Song>) {
        val list = if (queue.isEmpty()) listOf(song) else queue
        _queue.value = list
        currentQuality = effectiveQuality(quality)
        if (repeatMode == RepeatMode.SHUFFLE_ORDER) rebuildShuffleOrder()
        val index = list.indexOfFirst { it.source == song.source && it.id == song.id }.coerceAtLeast(0)
        playAt(index)
    }

    /**
     * 实际取流音质（2026-10-26 省流量模式：灵活的音质自动切换）：
     * - 省流中（总开关开 + 计费网络 + 子开关开）→ 一律 128k，**覆盖一切请求**；
     * - 否则用设置页「默认音质」（preferred_quality，此前该键从未被播放链路消费）；
     * - 没设置过 → 维持调用方传入值（现状 K320）。
     * 用户在播放页音质菜单的显式切换走 [switchQuality]，不经这里、不受降档覆盖。
     */
    private fun effectiveQuality(requested: Quality): Quality {
        if (DataSaver.qualityDowngrade) return Quality.K128
        val preferred = preferredQualityValue ?: return requested
        return Quality.entries.firstOrNull { it.value == preferred } ?: requested
    }

    override fun togglePlayPause() {
        // fade 进行中：快速接管而不是杀 fade 回退到旧歌（否则 UI 显示新歌、暂停的却是旧歌）
        if (currentFadeJob?.isActive == true) {
            if (resolveCrossfade(fadeUp = false)) {
                // active 已转正为新歌（在播）→ 短淡出后暂停
                val p = active
                _state.update { it.copy(isPlaying = false) }
                saveActivePosition()
                if (pauseResumeFadeEnabled) {
                    pauseFadeJob = Crossfader.fadePause(scope, p, p.volume, 0f) { p.pause() }
                } else {
                    p.pause()
                }
            } else {
                // 新歌还在预缓冲从未出声：放弃 fade，硬切到新歌并保持暂停态
                active.pause()
                _state.update { it.copy(isPlaying = false) }
                hardCutToCurrentSong(resumePlayback = false)
            }
            return
        }
        pauseFadeJob?.cancel()
        val p = active
        if (p.isPlaying) {
            _state.update { it.copy(isPlaying = false) }   // 图标即时反馈（不等 200ms 淡出后的事件回调）
            saveActivePosition()
            if (pauseResumeFadeEnabled) {
                pauseFadeJob = Crossfader.fadePause(scope, p, p.volume, 0f) { p.pause() }
            } else {
                p.pause()
            }
        } else {
            _state.update { it.copy(isPlaying = true) }
            requestFocus()
            p.play()
            if (pauseResumeFadeEnabled) {
                pauseFadeJob = Crossfader.fadePause(scope, p, 0f, _volumeFlow.value)
            }
        }
    }

    override suspend fun playAt(index: Int) {
        val song = _queue.value.getOrNull(index) ?: return
        val wasPlaying = active.isPlaying
        requestFocus()
        abandonCrossfade()

        flushListening(_state.value.current)
        _currentIndex.value = _queue.value.indexOfFirst { it.source == song.source && it.id == song.id }.coerceAtLeast(0)
        if (repeatMode == RepeatMode.SHUFFLE_ORDER && shuffleOrder.isNotEmpty()) {
            shuffleCursor = shuffleOrder.indexOf(_currentIndex.value).coerceAtLeast(0)
        }
        // ⚠️ isPlaying/repeatMode 必须保留（日志实锤的「图标灵敏度低」根因）：
        // 整体重置会把 isPlaying 清成默认 false —— 切歌后图标显示「未播放」
        // 直到 promote 才恢复（1~3.5s）；repeatMode 同理会被切歌重置
        val prevSong = _state.value.current   // 失败回滚锚点：实际还在播的歌
        saveActivePosition()                  // 切歌前把上一首的进度落盘
        _state.value = PlayerState(
            current = song,
            isPlaying = wasPlaying,
            isBuffering = true,
            quality = currentQuality,
            repeatMode = repeatMode,
        )
        runCatching { historySink.onSongStarted(song) }

        if (wasPlaying && crossfadeMs > 0L) {
            startCrossfade(song, prevSong)   // isBuffering 由 fade 起跑（B.play 前）收尾
        } else {
            // ⚠️ autoplay 恒 true：点歌本身就是播放意图（wasPlaying 只决定走不走 fade）。
            // 曾误绑 wasPlaying —— 空闲态点歌只加载不播放（用户反馈回归）
            hardCutOrRollback(song, prevSong, autoplay = true)
        }
    }

    /**
     * 硬切加载目标歌到 active；失败时把 UI **回滚到实际在播的歌**（声画一致）。
     * 场景：音源脚本超时/取流失败（日志实锤）——旧歌还在响，UI 不能停在新歌上。
     */
    private suspend fun hardCutOrRollback(song: Song, prevSong: Song?, autoplay: Boolean) {
        runCatching { loadToPlayer(active, song, autoplay = autoplay) }
            .onFailure { t ->
                Log.w(LOG_TAG, "hardCut failed, rollback UI to playing song", t)
                if (prevSong != null && active.mediaItemCount > 0 && active.currentPosition >= 0L) {
                    _state.update {
                        it.copy(
                            current = prevSong,
                            positionMs = active.currentPosition.coerceAtLeast(0L),
                            durationMs = active.duration.coerceAtLeast(0L),
                            isPlaying = active.isPlaying,
                            isBuffering = false,
                            error = "《${song.name}》取流失败，已恢复原歌播放（${t.message ?: "未知错误"}）",
                        )
                    }
                } else {
                    _state.update { it.copy(isBuffering = false, error = t.message ?: "加载失败") }
                }
                return
            }
        _state.update { it.copy(isBuffering = false) }
    }

    /**
     * 真重叠交叉淡变（整体跑在 currentFadeJob 里，abandonCrossfade 一并取消）：
     * 1. 备用 player 预载新歌（volume=0 不播；不参与焦点，不会打断 active）
     * 2. **预缓冲到安全水位**（淡变时长+3s 余量）—— READY 只要 1s 缓冲，若不等够就开播，
     *    网络抖动会让新歌在淡变中途 stall（位置冻结）→ 淡完时间与新歌实际进度脱节
     * 3. 并行等功率淡变：fadeOut(active·cos) + fadeIn(backup·sin)，重叠期感知响度恒定
     * 4. 淡完 → [promote]：新歌 player 直接转正，零交接零 gap 零 repeat
     */
    private fun startCrossfade(song: Song, prevSong: Song?) {
        val targetVol = _volumeFlow.value
        val outPlayer = active
        val inPlayer = otherOf(outPlayer)
        val gen = ++loadGeneration

        currentFadeJob = scope.launch {
            runCatching { loadToPlayer(inPlayer, song, autoplay = false) }
                .onFailure { throwable ->
                    Log.w(LOG_TAG, "crossfadeTo: load backup failed", throwable)
                    // ⚠️ 音源脚本取流失败（日志实锤：baimusic QQ 获取播放链接失败）：
                    // 不能只置 error——UI 已显示新歌、旧歌还在响 = 声画脱节。
                    // 回退 hard cut 到旧歌所在 player；再失败则回滚 UI 到旧歌。
                    hardCutOrRollback(song, prevSong, autoplay = true)
                    return@launch
                }
            if (gen != loadGeneration) { retire(inPlayer); return@launch }  // 期间有更新的播放意图
            if (!awaitPlayerReady(inPlayer, READY_TIMEOUT_MS)) {
                Log.w(LOG_TAG, "crossfadeTo: backup ready timeout, hard cut")
                retire(inPlayer)
                hardCutOrRollback(song, prevSong, autoplay = true)
                return@launch
            }

            // 预缓冲：promote 架构下无交接回跳风险，水位只需覆盖 fade 期 + 小余量
            // （过低会在 fade 尾端 stall——表现为「淡变到一半卡一下」；过高会拉长 UI 与声音的脱节窗口）
            val needMs = crossfadeMs + 1_500L
            var waited = 0L
            while (waited < PREBUFFER_TIMEOUT_MS) {
                if (gen != loadGeneration) { retire(inPlayer); return@launch }
                val duration = inPlayer.duration
                val buffered = inPlayer.bufferedPosition
                if (buffered >= needMs || (duration > 0 && buffered >= duration)) break
                delay(100)
                waited += 100
            }

            val startVol = outPlayer.volume
            inPlayer.volume = 0f
            inPlayer.play()
            // ⚠️ 等新歌**真正出声**（AudioTrack 起跑）再同时起跑双曲线：
            // play() 到实际出声有 0.3~0.8s AudioTrack 启动延迟，不等的话淡入曲线
            // 爬到 50%+ 时声音才出来 =「上一首渐弱、下一首直接出来」
            var waitedStart = 0L
            while (!inPlayer.isPlaying && waitedStart < 1_000L) {
                if (gen != loadGeneration) { retire(inPlayer); return@launch }
                delay(20)
                waitedStart += 20
            }
            if (gen == loadGeneration) _state.update { it.copy(isBuffering = false) }

            // ⚠️ UI 灵敏度修复：所有失败检查都通过、AudioTrack 已起跑后，**在 fade 启动前**立刻 swap ——
            // 让 MediaSession（锁屏/通知栏卡片、蓝牙远端）瞬间收到新歌的 metadata 事件，
            // 歌名/封面/进度条立刻切过去；声音照常走 4s 淡变，fade 结束再 retire 旧 player。
            // 语义安全：所有 commandInterceptor 入口（pause/seek/next/prev）最终都走 controller → active（旧 player），
            // swap 只影响 MediaSession 监听谁的状态事件，不碰播放控制。
            sessionPlayer.swap(inPlayer)

            val outJob = launch { Crossfader.fadeCrossfadeOut(outPlayer, startVol, crossfadeMs) }
            val inJob = launch { Crossfader.fadeCrossfadeIn(inPlayer, targetVol, crossfadeMs) }
            outJob.join()
            inJob.join()
            if (gen != loadGeneration) return@launch  // fade 中被新意图取代
            promote(inPlayer)
        }
    }

    /**
     * fade 完成：新歌 player 转正为 session player（swap 迁移全部监听器），
     * 旧 player 退役、焦点转给新 active —— 无 setMediaItem、无 seek、无重缓冲，
     * 物理上不存在空窗与回跳。
     */
    private fun promote(newActive: ExoPlayer) {
        val old = active
        active = newActive
        sessionPlayer.swap(newActive)
        retire(old)
        // ⚠️ 焦点已由 requestFocus() 统一持有，这里绝不能 setAudioAttributes
        //（会 flush AudioTrack —— 转正瞬间新歌开头「卡」一下 = 用户反馈的「卡卡的」）
        currentFadeJob = null
        // 真实播放时长按 player 边界切账：旧 player 的段落结算，从现在起计入新歌
        settleListening()
        if (newActive.isPlaying) countingSince = SystemClock.elapsedRealtime()
        // ⚠️ 状态快照强制对齐真相（图标/进度条错乱的根治）：
        // swap 迁移监听器后新 player 的初始状态 post 是异步的，窗口期内 UI 读到的是
        // 旧 player 的过期状态（图标=未播放、时长/位置=旧歌）——同步先拉正
        _state.update {
            it.copy(
                isPlaying = newActive.isPlaying,
                positionMs = newActive.currentPosition.coerceAtLeast(0L),
                bufferedMs = newActive.bufferedPosition,
                durationMs = newActive.duration.coerceAtLeast(0L),
            )
        }
    }

    /** 打断进行中的淡变（任何控制操作前调用）：取消 fade、退役淡入中的备用 player、恢复 active 音量 */
    private fun abandonCrossfade() {
        currentFadeJob?.cancel()
        currentFadeJob = null
        ++loadGeneration  // 使所有在途异步加载（fade/hard cut）作废
        val other = otherOf(active)
        if (other.mediaItemCount > 0) retire(other)
        active.volume = _volumeFlow.value
    }

    /**
     * fade 进行中控制操作的快速接管（不杀 fade 回退，否则 UI/声音脱节）：
     * - 新歌已出声 → 立即转正为新 active，返回 true（调用方把操作作用到新歌）
     * - 新歌还在预缓冲从未出声 → 放弃 fade，active（旧歌）保持，返回 false
     *   （调用方需跟 [hardCutToCurrentSong] 消除 UI/声音脱节）
     *
     * [fadeUp]：接管时新歌音量往往停在淡入半路——继续播放的场景用 200ms 快速补完
     * 淡入（不突兀）；即将暂停的场景直接恢复满音量（反正马上淡出）。
     */
    private fun resolveCrossfade(fadeUp: Boolean = true): Boolean {
        val job = currentFadeJob ?: return false
        currentFadeJob = null
        val inPlayer = otherOf(active)
        job.cancel()
        return if (inPlayer.isPlaying) {
            promote(inPlayer)
            if (fadeUp) {
                pauseFadeJob?.cancel()
                val p = inPlayer
                pauseFadeJob = Crossfader.fadePause(scope, p, p.volume, _volumeFlow.value)
            } else {
                inPlayer.volume = _volumeFlow.value
            }
            true
        } else {
            retire(inPlayer)
            active.volume = _volumeFlow.value
            false
        }
    }

    /**
     * 硬切到 UI 当前显示的歌曲（消除「UI 显示新歌、active 播的还是旧歌」的脱节）。
     * 用 UI 状态里的歌与位置重载 active；[resumePlayback]=false 时加载完保持暂停态。
     */
    private fun hardCutToCurrentSong(resumePlayback: Boolean = true) {
        val song = _state.value.current ?: return
        val targetPos = _state.value.positionMs
        val gen = ++loadGeneration
        _state.update { it.copy(isBuffering = true) }
        scope.launch {
            runCatching { loadToPlayer(active, song, autoplay = false) }
                .onFailure { throwable ->
                    if (gen == loadGeneration) {
                        _state.update { it.copy(isBuffering = false, error = throwable.message ?: "加载失败") }
                    }
                    return@launch
                }
            if (gen != loadGeneration) return@launch  // 期间有更新的播放意图，放弃写入
            active.seekTo(targetPos)
            active.volume = _volumeFlow.value
            if (resumePlayback) active.play() else active.pause()
            _state.update { it.copy(isBuffering = false, positionMs = targetPos) }
        }
    }

    /** 释放一个 player（stop + 清队列） */
    private fun retire(p: ExoPlayer) {
        runCatching {
            p.stop()
            p.clearMediaItems()
        }
    }

    /** 等待 player 进入 STATE_READY（缓存命中基本瞬时）；超时/出错返回 false */
    private suspend fun awaitPlayerReady(player: ExoPlayer, timeoutMs: Long): Boolean {
        if (player.playbackState == Player.STATE_READY) return true
        val deferred = CompletableDeferred<Boolean>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { deferred.complete(true); player.removeListener(this) }
            }
            override fun onPlayerError(error: PlaybackException) {
                deferred.complete(false); player.removeListener(this)
            }
        }
        player.addListener(listener)
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            player.removeListener(listener)
        }
    }

    /** 把歌曲加载到指定 player（autoplay=false 时仅 prepare，供预载） */
    private suspend fun loadToPlayer(player: ExoPlayer, song: Song, autoplay: Boolean = true) {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.name)
            .setArtist(song.singer)
            .apply { song.picUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        val mediaUri: Uri = resolvePlayUri(song)

        player.setMediaItem(
            MediaItem.Builder()
                .setUri(mediaUri)
                .setMediaId("${song.source.value}:${song.id}")
                .setMediaMetadata(metadata)
                .build(),
        )
        player.prepare()
        player.setPlaybackSpeed(currentSpeed)  // 切歌预载也要继承当前倍速，淡变期间速度不跳变
        applySavedPosition(player, song)
        if (autoplay) {
            player.play()
            player.volume = _volumeFlow.value
        }
    }

    /**
     * 取流链（04 · 换源，对齐 LX）：钉住的换源目标 → 原源 → 自动跨源兜底。
     * 队列与 UI 始终显示原 [song]，只有实际 URL 可能来自候选源。
     */
    private suspend fun resolvePlayUri(song: Song): Uri {
        // 本地曲目且未钉换源目标：文件直读，不走任何在线链
        if (song.source == LOCAL_SOURCE_ID && song.toggleSong == null) {
            return Uri.parse(song.raw["uri"] ?: error("本地曲目缺少文件地址"))
        }
        // 省流兜底：即使 currentQuality 还是高音质（Wi-Fi 下起的播、切到蜂窝），取流时也钳回 128k
        val fetchQuality = if (DataSaver.qualityDowngrade) Quality.K128 else currentQuality

        // ① 用户钉住的换源目标优先（对齐 LX toggleMusicInfo；失败回落原链）
        song.toggleSong?.let { target ->
            runCatching {
                return fetchUriCached(target, CrossSourceFinder.pickQuality(fetchQuality, target.qualitys))
            }.onFailure { Log.w(LOG_TAG, "toggleSong fetch failed, fallback to original source", it) }
        }

        // ② 原源（含「本地歌钉了目标但目标失败」时回落本地文件的情形）
        if (song.source == LOCAL_SOURCE_ID) {
            return Uri.parse(song.raw["uri"] ?: error("本地曲目缺少文件地址"))
        }
        return try {
            fetchUriCached(song, fetchQuality)
        } catch (originalError: Exception) {
            if (originalError is CancellationException) throw originalError
            fetchViaOtherSource(song, fetchQuality, originalError)
        }
    }

    private suspend fun fetchUri(song: Song, quality: Quality): Uri {
        val source = sources.byId(song.source) ?: error("音源不可用：${song.source.value}")
        val musicUrl = source.getMusicUrl(song, quality)
        val headers = headerStrategy.headersFor(song) +
            ("User-Agent" to headerStrategy.userAgentFor(song.source))
        val cacheKey = cacheKey(song, quality)
        return HeaderAwareDataSource.buildUri(musicUrl.url, headers, cacheKey)
    }

    /**
     * ③ 自动换源兜底（对齐 LX findMusic 链路）：跨源匹配同名歌曲，
     * 逐候选（跳过已试过的源）按偏好降级取流，首个成功即止；全部失败抛回原异常，
     * 由上层 hardCutOrRollback 走原有回滚。
     */
    private suspend fun fetchViaOtherSource(song: Song, preferred: Quality, originalError: Exception): Uri {
        val candidates = crossSourceFinder.findMatched(song)
        val tried = mutableSetOf(song.source)
        for (candidate in candidates) {
            if (candidate.source in tried) continue
            tried.add(candidate.source)
            runCatching {
                return fetchUriCached(candidate, CrossSourceFinder.pickQuality(preferred, candidate.qualitys)).also {
                    val sourceName = sources.byId(candidate.source)?.name ?: candidate.source.value
                    _state.update { it.copy(error = "《${song.name}》已自动换源到「$sourceName」") }
                }
            }.onFailure { Log.w(LOG_TAG, "toggle candidate failed: ${candidate.source.value}", it) }
        }
        throw originalError
    }

    override suspend fun next() {
        val list = _queue.value
        if (list.isEmpty()) return
        when (repeatMode) {
            RepeatMode.SHUFFLE_ORDER -> {
                ensureShuffleOrder()
                if (shuffleOrder.isEmpty()) return
                shuffleCursor = (shuffleCursor + 1) % shuffleOrder.size
                playAt(shuffleOrder[shuffleCursor])
            }
            RepeatMode.SHUFFLE_RANDOM -> playAt(FlowRandom.nextIndex(list.size, _currentIndex.value))
            else -> {
                val target = _currentIndex.value + 1
                if (target < list.size) playAt(target)
                else if (repeatMode == RepeatMode.ALL) playAt(0)
                else active.pause()
            }
        }
    }

    override suspend fun previous() {
        val list = _queue.value
        if (list.isEmpty()) return
        if (active.currentPosition > RESTART_THRESHOLD_MS) {
            active.seekTo(0)
            return
        }
        when (repeatMode) {
            RepeatMode.SHUFFLE_ORDER -> {
                ensureShuffleOrder()
                if (shuffleOrder.isEmpty()) return
                shuffleCursor = if (shuffleCursor - 1 < 0) shuffleOrder.lastIndex else shuffleCursor - 1
                playAt(shuffleOrder[shuffleCursor])
            }
            RepeatMode.SHUFFLE_RANDOM -> playAt(FlowRandom.nextIndex(list.size, _currentIndex.value))
            else -> {
                val target = _currentIndex.value - 1
                if (target >= 0) playAt(target)
                else if (repeatMode == RepeatMode.ALL) playAt(list.lastIndex)
                else active.seekTo(0)
            }
        }
    }

    private fun rebuildShuffleOrder() {
        val indices = _queue.value.indices.toList()
        shuffleOrder = if (indices.size <= 1) indices else FlowRandom.shuffled(indices)
        shuffleCursor = shuffleOrder.indexOf(_currentIndex.value).coerceAtLeast(0)
    }

    private fun ensureShuffleOrder() {
        if (shuffleOrder.size != _queue.value.size) rebuildShuffleOrder()
    }

    override fun seekTo(positionMs: Long) {
        if (currentFadeJob?.isActive == true) {
            _state.update { it.copy(positionMs = positionMs) }
            if (resolveCrossfade()) {
                active.seekTo(positionMs)   // 已转正：seek 直接作用新歌
            } else {
                hardCutToCurrentSong()      // 预缓冲中：硬切新歌到目标位置（内部读 state.positionMs）
            }
            return
        }
        active.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        if (mode == RepeatMode.SHUFFLE_ORDER) rebuildShuffleOrder() else shuffleOrder = emptyList()
        _state.update { it.copy(repeatMode = mode) }
        playerA.repeatMode = Player.REPEAT_MODE_OFF
        playerB.repeatMode = Player.REPEAT_MODE_OFF
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        if (currentFadeJob?.isActive == true && !resolveCrossfade()) hardCutToCurrentSong()
        currentSpeed = clamped
        active.setPlaybackSpeed(clamped)
        scope.launch { settings.setPlaybackSpeed(clamped) }
    }

    override fun setCrossfadeMs(ms: Long) {
        crossfadeMs = ms.coerceIn(0L, 8_000L)
        scope.launch { settings.setCrossfadeMs(crossfadeMs) }
    }

    override fun currentCrossfadeMs(): Long = crossfadeMs

    override fun currentSpeed(): Float = currentSpeed

    override fun clearError() {
        _state.update { if (it.error == null) it else it.copy(error = null) }
    }

    override fun fadeOutPause(fadeMs: Long) {
        pauseFadeJob?.cancel()
        stopAfterCurrentTrack = false   // 清标志（避免后续残留拦截）
        // fade 进行中先接管（新歌已出声就转正，否则回退），保证淡出作用在出声的那条轨
        if (currentFadeJob?.isActive == true && !resolveCrossfade(fadeUp = false)) hardCutToCurrentSong()
        val p = active
        _state.update { it.copy(isPlaying = false) }
        saveActivePosition()
        pauseFadeJob = scope.launch {
            Crossfader.fade(p, p.volume, 0f, fadeMs)
            p.pause()
            p.volume = _volumeFlow.value   // 音量恢复，下次起播不受影响
        }
    }

    override fun setStopAfterCurrentTrack(enable: Boolean) {
        stopAfterCurrentTrack = enable
    }

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _volumeFlow.value = v
        if (currentFadeJob?.isActive == true && !resolveCrossfade()) hardCutToCurrentSong()
        active.volume = v
    }

    override suspend fun switchQuality(quality: Quality) {
        // ExoPlayer 是 main 线程对象：省流门闸（DataSaverMonitor，Dispatchers.Default）
        // 也会调到这里，必须自己切主线程，否则 getCurrentPosition 抛
        // "Player is accessed on the wrong thread"
        withContext(Dispatchers.Main.immediate) {
            currentQuality = quality
            if (currentFadeJob?.isActive == true) {
                if (!resolveCrossfade()) hardCutToCurrentSong()
            }
            val song = _state.value.current ?: return@withContext
            val position = active.currentPosition
            val wasPlaying = active.isPlaying
            runCatching {
                loadToPlayer(active, song, autoplay = false)
                active.seekTo(position)
                active.volume = _volumeFlow.value
                if (wasPlaying) active.play()
            }.onFailure { throwable ->
                _state.update { it.copy(error = throwable.message ?: "切换音质失败") }
            }
        }
    }

    private fun handleEnded() {
        val list = _queue.value
        if (list.isEmpty()) return
        if (repeatMode == RepeatMode.ONE) {
            flushListening(_state.value.current)
            _state.value.current?.let { clearSavedPosition(it) }
            active.seekTo(0)
            active.play()
            return
        }
        // 「播完当前歌曲后停止」拦截（SongSleepTimer.setAfterCurrentTrack 或分钟定时 + finishSongFirst）
        // —— 直接清标志 + pause，不走 next()（下一首不会 start）
        if (stopAfterCurrentTrack) {
            stopAfterCurrentTrack = false
            saveActivePosition()
            _state.update { it.copy(isPlaying = false) }
            return
        }
        scope.launch { next() }
    }

    // ── 记忆上次播放位置（17 §6 播放行为）──
    // 语义：再次播放同一首时从上次停下的位置继续；播完 / 停在开头附近则清掉记录。

    /** 把当前 active 的真实进度记到「当前 UI 显示的歌」名下 */
    private fun saveActivePosition() {
        if (!rememberPlaybackPosition) return
        val song = _state.value.current ?: return
        if (active.currentMediaItem?.mediaId != mediaIdOf(song)) return
        val position = active.currentPosition.coerceAtLeast(0L)
        val totalMs = totalMsOf(song, active.duration)
        if (position < MIN_RESUME_MS || (totalMs > 0L && position >= totalMs - END_MARGIN_MS)) {
            clearSavedPosition(song)
            return
        }
        scope.launch { runCatching { settings.savePlaybackPosition(mediaIdOf(song), position) } }
    }

    private fun clearSavedPosition(song: Song) {
        if (!rememberPlaybackPosition) return
        scope.launch { runCatching { settings.clearPlaybackPosition(mediaIdOf(song)) } }
    }

    /** 加载完新歌后按记录 seek（太靠前 / 太靠结尾的记录直接忽略） */
    private fun applySavedPosition(player: ExoPlayer, song: Song) {
        if (!rememberPlaybackPosition) return
        val saved = savedPositions[mediaIdOf(song)] ?: return
        if (saved < MIN_RESUME_MS) return
        val totalMs = totalMsOf(song, player.duration)
        if (totalMs > 0L && saved >= totalMs - END_MARGIN_MS) return
        player.seekTo(saved)
    }

    private fun mediaIdOf(song: Song): String = "${song.source.value}:${song.id}"

    /** 优先用 player 报出的时长；还没 READY 时退回歌曲元数据 */
    private fun totalMsOf(song: Song, playerDurationMs: Long): Long =
        playerDurationMs.takeIf { it > 0L } ?: ((song.interval ?: 0L) * 1000L)

    private fun trackListening(isPlaying: Boolean) {
        if (isPlaying) {
            if (countingSince == 0L) countingSince = SystemClock.elapsedRealtime()
        } else {
            settleListening()
        }
    }

    private fun settleListening() {
        if (countingSince != 0L) {
            listenedMs += (SystemClock.elapsedRealtime() - countingSince).coerceAtLeast(0L)
            countingSince = 0L
        }
    }

    private fun flushListening(song: Song?) {
        settleListening()
        val played = listenedMs
        listenedMs = 0L
        if (song != null && played > 0L) {
            val total = active.duration.takeIf { it > 0L } ?: ((song.interval ?: 0L) * 1000L)
            scope.launch { runCatching { historySink.onSongPlayed(song, played, total) } }
        }
        if (active.isPlaying) countingSince = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
        const val RESTART_THRESHOLD_MS = 3_000L

        /** 小于这个秒数不值得记忆；距结尾不到 5s 的记录也视为已听完 */
        const val MIN_RESUME_MS = 5_000L
        const val END_MARGIN_MS = 5_000L

        private const val LOG_TAG = "LXCrossfade"
        private const val READY_TIMEOUT_MS = 10_000L
        private const val PREBUFFER_TIMEOUT_MS = 5_000  // 预缓冲等待上限（超时就按现状开播）
    }
}
