package com.lxpro.core.media

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import java.io.File

/**
 * 播放器构造（03 §5.3）。关键点：
 * - 请求头走 [HeaderAwareDataSource]（**per-request**，不是全局 setDefaultRequestProperties）；
 * - `bufferForPlaybackMs` 调低到 1000ms —— 这是"起播快"的关键；
 * - `setHandleAudioBecomingNoisy(true)` —— 拔耳机自动暂停。
 */
object PlayerFactory {

    private const val CACHE_SIZE = 512L * 1024 * 1024

    fun create(context: Context, okHttpClient: OkHttpClient): ExoPlayer {
        val cache = SimpleCache(
            File(context.cacheDir, "media_cache"),
            LeastRecentlyUsedCacheEvictor(CACHE_SIZE),
            StandaloneDatabaseProvider(context),
        )

        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(okHttpFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))

        // ⚠️⚠️ 关键：必须把「带缓存的网络源」作为 DefaultDataSource 的 **base**，
        //    而不是反过来。DefaultDataSource 按 scheme 分派：
        //      · content:// → ContentDataSource（本地曲目，**不经过缓存**，否则播一首就把整首歌复制进缓存目录）
        //      · file://    → FileDataSource（同样不吃缓存）
        //      · http(s):// → 我们给的 base（缓存 + OkHttp，B 站取流仍走这里）
        //    M2-A 的「本地曲目无法播放」就是这个层级放反了：OkHttpDataSource 不认 content scheme。
        val routedFactory = DefaultDataSource.Factory(context, cacheFactory)

        return ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(HeaderAwareDataSource.factory(routedFactory)),
            )
            .setLoadControl(
                DefaultLoadControl.Builder().setBufferDurationsMs(
                    /* minBufferMs = */ 15_000,
                    /* maxBufferMs = */ 50_000,
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                ).build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}