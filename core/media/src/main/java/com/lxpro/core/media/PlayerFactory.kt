package com.lxpro.core.media

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import java.io.File

/**
 * 播放器构造（03 §5.3）。关键点：
 * - 请求头走 [HeaderAwareDataSource]（**per-request**，不是全局 setDefaultRequestProperties）；
 * - `bufferForPlaybackMs` 调低到 1000ms —— 这是"起播快"的关键；
 * - `setHandleAudioBecomingNoisy(true)` —— 拔耳机自动暂停。
 *
 * 批三·双 player 架构：[cache] 必须由调用方传入（MediaModule 提供 SimpleCache 单例），
 * 两个 ExoPlayer 共用同一个缓存目录，否则 SimpleCache 会抛
 * "Another SimpleCache instance uses the folder"。
 *
 * ⚠️ EQ 处理器只挂 playerA（MediaSession 绑定的那个），playerB（交叉淡变备用）不挂。
 * 这样交叉淡变期间 EQ 短暂不生效可接受，playerB fade 完成后 MediaItem 复制回 playerA，EQ 自动恢复。
 */
object PlayerFactory {

    fun create(
        context: Context,
        okHttpClient: OkHttpClient,
        cache: Cache,
        audioProcessors: Array<AudioProcessor> = emptyArray(),
        handleAudioFocus: Boolean = true,
    ): ExoPlayer {
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)

        // 内层：DefaultDataSource 按 scheme 路由
        //   · content:// → ContentDataSource（本地曲目，不走缓存）
        //   · file://    → FileDataSource（同样不吃缓存）
        //   · http(s):// → HeaderAwareDataSource（解出 headers + cacheKey）→ OkHttpDataSource
        //
        // ⚠️ 缓存 key 机制（2026-10-27 新增）：
        //    CacheDataSource 必须**包在 DefaultDataSource 外面**，才能在 ResolvingDataSource
        //    剥离 headers/cacheKey 之前看到原始 URI。用 setCacheKeyFactory 从原始 URI
        //    提取 🗝️ 之后的稳定键（source:id:quality），而不是用易变的签名 URL 做 key。
        //
        //    CacheDataSource 对 content:// / file:// scheme 只是多一次空查缓存（不会命中），
        //    miss 后 upstream 由 DefaultDataSource 正确路由到 ContentDataSource / FileDataSource，
        //    不写入缓存，安全。
        val headerAwareFactory = HeaderAwareDataSource.factory(okHttpFactory)
        val routedFactory = DefaultDataSource.Factory(context, headerAwareFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(routedFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setCacheKeyFactory(CacheKeyFactory { spec ->
                val rawUri = spec.uri.toString()
                // 非 http(s) scheme（content:// 本地曲目、file://）：用原始 URI 做 key，
                // 缓存量很小，512MB LRU 自动管理，不影响网络歌曲缓存效果
                val scheme = spec.uri.scheme
                if (scheme != "http" && scheme != "https") return@CacheKeyFactory rawUri
                // http(s):// 从 buildUri 编码的原始 URI 里提取 🗝️ 之后的稳定键
                // —— 音源签名 URL 每次取流都变，用稳定键才能命中 SimpleCache
                val keyTag = "\uD83D\uDDDD"
                if (rawUri.contains(keyTag)) rawUri.substringAfter(keyTag) else rawUri
            })

        // ⚠️ EQ 挂接（批三实装）：Media3 1.11 没有 Builder.setAudioProcessors，
        //    正确路径 = 自定义 RenderersFactory 重写 buildAudioSink（protected，
        //    签名为 (Context, enableFloatOutput, enableAudioTrackPlaybackParams)，
        //    javap 已验证），把处理器挂进 DefaultAudioSink。
        //    只挂 song player；白噪音 LocalSoundPlayer 用裸 Builder，物理直通（17 §1 硬约束①）。
        val renderersFactory = if (audioProcessors.isEmpty()) {
            DefaultRenderersFactory(context)
        } else {
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(audioProcessors)
                    .build()
            }
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(cacheFactory),
            )
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 2_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                ).build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

/** Media 模块共享的 SimpleCache 实例（单例，两个 ExoPlayer 共用） */
object MediaCacheProvider {
    private const val CACHE_SIZE = 512L * 1024 * 1024  // 512MB
    /** 缓存版本号：改了 key 策略（签名 URL → 稳定 source:id:quality）后 bump，首次启动清旧缓存 */
    private const val CACHE_VERSION = 2
    private const val PREF_VERSION = "media_cache_version"

    @Volatile
    private var cache: SimpleCache? = null

    @Suppress("DEPRECATION")
    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: run {
            val cacheDir = File(context.cacheDir, "media_cache")
            // 旧缓存（v1）用签名 URL 做 key，新版稳定 key 永远命不中 → 清掉释放空间
            val sp = context.getSharedPreferences("media_cache_meta", Context.MODE_PRIVATE)
            if (sp.getInt(PREF_VERSION, 0) != CACHE_VERSION) {
                cacheDir.deleteRecursively()
                sp.edit().putInt(PREF_VERSION, CACHE_VERSION).apply()
            }
            SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE),
                StandaloneDatabaseProvider(context),
            ).also { cache = it }
        }
    }
}
