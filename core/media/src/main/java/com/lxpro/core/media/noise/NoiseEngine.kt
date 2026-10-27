package com.lxpro.core.media.noise

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 白噪音混音引擎（17 §2.1，照 Noice `SoundPlayerManager.kt` 移植的简化版）。
 *
 * - 管理多个 [LocalSoundPlayer]（每层一个 ExoPlayer，17 §2.1：场景=1-6 图层叠加）
 * - 全局音量 × 各层独立音量 → 求和混音（17 §2.4：实际振幅 = (滑块值/100)²）
 * - play/stop/pause 各自走淡变（17 §2.5：淡入 5s、淡出 1.5s、音量变化 1.5s）
 * - **不经过 EQ**（17 §1 硬约束①）：每层 ExoPlayer 不挂任何 AudioProcessor
 * - **不参与歌曲切歌淡入淡出**（17 §1 硬约束②）：跨曲无缝，跨曲时不淡变
 *
 * ⚠️ 与 [com.lxpro.core.media.PlayerController] **独立**：白噪音是平行音轨，不共享队列、
 * 不共享 MediaSession、不共享通知栏控制（17 §1 硬约束③）。LXProMediaService 在批一·地基
 * 中扩展为双轨托管（song 走 MediaSession，noise 不注册）。
 */
@Singleton
class NoiseEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 默认淡入淡出（17 §2.5：Noice 默认 fadeIn=5s、fadeOut=1.5s） */
    var fadeInMs: Long = 5_000L
    var fadeOutMs: Long = 1_500L

    /** 音频属性（17 §1：白噪音与歌曲走同一种媒体类型，但 EQ 不作用于白噪音） */
    private var audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_SYSTEM)
        .build()

    private var totalVolume: Float = 1f

    private val players = mutableMapOf<String, LocalSoundPlayer>()
    private val layerVolumes = mutableMapOf<String, Float>()

    /**
     * 全局段缓存（CDN 在境外，下载仅 ~46KB/s；播过的段落盘后循环直接读本地，秒开不卡）。
     * 用 NoOpCacheEvictor 不主动清理，白噪音段总量很小。
     */
    private val cache: SimpleCache by lazy {
        val cacheDir = File(context.cacheDir, "noise_segments").apply { mkdirs() }
        SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }
    private val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 当前混音场景状态（17 §4「当前混音」卡用） */
    private val _state = MutableStateFlow(NoiseEngineState())
    val state: StateFlow<NoiseEngineState> = _state.asStateFlow()

    /** 全局音量（17 §2.4：走平方曲线） */
    fun setTotalVolume(volume: Float) {
        totalVolume = volume.coerceIn(0f, 1f)
        players.forEach { (id, p) -> p.setVolume(totalVolume * (layerVolumes[id] ?: 1f)) }
        _state.update { it.copy(totalVolume = totalVolume) }
    }

    /** 单层音量（17 §2.4：独立音量，乘以全局音量后送入播放器；走平方曲线由播放器内部完成） */
    fun setLayerVolume(sourceId: String, volume: Float) {
        layerVolumes[sourceId] = volume.coerceIn(0f, 1f)
        players[sourceId]?.setVolume(totalVolume * (layerVolumes[sourceId] ?: 1f))
        _state.update { s ->
            s.copy(layers = s.layers.toMutableMap().apply { this[sourceId] = volume.coerceIn(0f, 1f) })
        }
    }

    /**
     * 把一个音源的所有非桥接段预下载到本地缓存（CDN 仅 ~46KB/s，多图层并发抢带宽会卡顿，
     * 故串行预下载全部段后再播放）。已缓存的段会秒过。
     */
    private suspend fun prefetchSource(source: NoiseSource) {
        val segments = source.segments.filter { !it.isBridgeSegment }
        for (seg in segments) {
            // 2026-10-20 重构：url 由 SoundSegment 携带，CDN / 本地 / 包 统一入口
            val ds = cacheDataSourceFactory.createDataSource()
            try {
                ds.open(DataSpec(Uri.parse(seg.url)))
                val buf = ByteArray(16 * 1024)
                while (ds.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT) { /* drain → 写入 SimpleCache */ }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "prefetch ${source.id}/${seg.name} failed: ${e.message}")
            } finally {
                runCatching { ds.close() }
            }
        }
    }

    /** 启动一个图层（17 §2.1：从音源库点一个音源 = 直接叠进当前混音）。先预下载段再播放。 */
    fun playLayer(source: NoiseSource, volume: Float = 1f) {
        val existing = players[source.id]
        if (existing != null && existing.state != LocalSoundPlayer.State.STOPPED) {
            setLayerVolume(source.id, volume)
            return
        }
        _state.update { it.copy(loading = true) }
        scope.launch {
            // 流式源（在线搜索）不预下载：边下边播，否则用户要等整首拉完才出声
            if (!source.streaming) prefetchSource(source)
            startPlayer(source, volume)
        }
    }

    /** 创建播放器并启动播放（调用前应已完成预下载） */
    private suspend fun startPlayer(source: NoiseSource, volume: Float) {
        val player = LocalSoundPlayer(
            context = context,
            source = source,
            dataSourceFactory = cacheDataSourceFactory,
            defaultScope = scope,
            fadeInMs = fadeInMs,
            fadeOutMs = fadeOutMs,
        ).also {
            it.setAudioAttributes(audioAttrs)
            it.setVolume(totalVolume * volume)
        }
        players[source.id] = player
        layerVolumes[source.id] = volume
        player.play()
        _state.update { s ->
            s.copy(
                loading = false,
                playing = true,
                activeSourceIds = s.activeSourceIds + source.id,
                layers = s.layers.toMutableMap().apply { this[source.id] = volume },
            )
        }
    }

    /** 停止一个图层（带淡出，17 §2.5） */
    fun stopLayer(sourceId: String) {
        players[sourceId]?.stop(immediate = false)
        layerVolumes.remove(sourceId)
        _state.update { s ->
            s.copy(
                activeSourceIds = s.activeSourceIds - sourceId,
                layers = s.layers.toMutableMap().apply { remove(sourceId) },
            )
        }
    }

    /**
     * 切换整套预设场景（17 §2.1：场景=图层叠加）。
     *
     * 串行预下载所有图层段到缓存（避免多播放器并发抢 46KB/s 带宽卡顿），再启动播放。
     *
     * @param autoPlay 是否自动起播。用户在**暂停态**增删图层时应传 false——只同步混音结构
     *   （已有图层改音量、多余图层停掉），绝不把暂停的混音重新响起来。
     */
    fun playScene(
        scene: NoiseScene,
        sourcesById: Map<String, NoiseSource>,
        autoPlay: Boolean = true,
    ) {
        val targetIds = scene.layers.map { it.sourceId }.toSet()
        if (autoPlay) _state.update { it.copy(loading = true) }
        scope.launch {
            if (autoPlay) {
                // 串行预下载所有需要的图层段（已缓存秒过）
                scene.layers.forEach { layer ->
                    val src = sourcesById[layer.sourceId] ?: return@forEach
                    if (src.streaming) return@forEach   // 流式源跳过预下载
                    if (players[src.id]?.state !in setOf(LocalSoundPlayer.State.PLAYING, LocalSoundPlayer.State.PAUSED)) {
                        prefetchSource(src)
                    }
                }
            }
            // 统一启动（已预下载，直接创建播放器）；暂停态只处理已有图层
            scene.layers.forEach { layer ->
                val src = sourcesById[layer.sourceId] ?: return@forEach
                if (players[src.id]?.state in setOf(LocalSoundPlayer.State.PLAYING, LocalSoundPlayer.State.PAUSED)) {
                    setLayerVolume(src.id, layer.volume)
                } else if (autoPlay) {
                    startPlayer(src, layer.volume)
                }
            }
            // 停止不需要的
            (players.keys - targetIds).forEach(::stopLayer)
            _state.update {
                it.copy(
                    loading = if (autoPlay) false else it.loading,
                    playing = autoPlay && scene.layers.isNotEmpty(),
                    currentSceneId = scene.id,
                    currentSceneName = scene.name,
                )
            }
        }
    }

    /** 暂停全部（白噪音定时器到点用，17 §7：先淡出 3s 再暂停） */
    fun pauseAll(immediate: Boolean = false) {
        players.values.forEach { it.pause(immediate) }
        _state.update { it.copy(playing = false) }
    }

    /** 睡眠定时到点：全部图层按指定时长淡出后暂停（17 §7：白噪音淡出 3 秒再暂停，歌曲照常播） */
    fun fadeOutPauseAll(durationMs: Long) {
        players.values.forEach { it.pauseWithFade(durationMs) }
        _state.update { it.copy(playing = false) }
    }

    /** 恢复全部已暂停的图层（批二：混音卡暂停后再播放，不打断段调度） */
    fun resumeAll() {
        players.values.forEach { p ->
            if (p.state == LocalSoundPlayer.State.PAUSED || p.state == LocalSoundPlayer.State.PAUSING) {
                p.play()
            }
        }
        if (players.isNotEmpty()) _state.update { it.copy(playing = true) }
    }

    /** 全停（释放资源；保留全局音量，避免再次播放时音量跳回 100%） */
    fun stopAll(immediate: Boolean = false) {
        players.values.forEach { it.stop(immediate) }
        players.clear()
        layerVolumes.clear()
        _state.value = NoiseEngineState(totalVolume = totalVolume)
    }

    /** 当前在播的混音（用于持久化「我的组合」） */
    fun getCurrentScene(): Map<String, Float> = layerVolumes.toMap()

    companion object {
        private const val LOG_TAG = "NoiseEngine"
    }
}

/** 引擎对外的 UI 状态（17 §4「当前混音」卡用） */
data class NoiseEngineState(
    val currentSceneId: String? = null,
    val currentSceneName: String? = null,
    /** 播放/暂停指示（17 §4 混音卡播放态；暂停后 activeSourceIds 仍保留以便恢复） */
    val playing: Boolean = false,
    /** 段预下载中（CDN 慢，多图层首次播放需串行缓存，UI 显示加载指示） */
    val loading: Boolean = false,
    val totalVolume: Float = 1f,
    val activeSourceIds: Set<String> = emptySet(),
    val layers: Map<String, Float> = emptyMap(),
)
