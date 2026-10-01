package com.lxpro.core.media

import android.net.Uri
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.serialization.json.Json

/**
 * 把请求头编码进 MediaItem 的 URI，在 `ResolvingDataSource` 中解出（03 §5.2）。
 *
 * ⚠️⚠️ 关键正确性要求：**不得**用 `OkHttpDataSource.setDefaultRequestProperties()`
 * （全局可变状态，并发预加载/切歌时会串 Referer/UA）。必须用 `DataSpec.withRequestHeaders()`
 * 让 headers 随 spec 走，天然线程安全。
 */
object HeaderAwareDataSource {

    /** 🚧 不会出现在正常 URL 中 */
    private const val SPLIT_TAG = "\uD83D\uDEA7"

    private val json = Json { ignoreUnknownKeys = true }

    fun buildUri(url: String, headers: Map<String, String>): Uri =
        Uri.parse("$url$SPLIT_TAG${json.encodeToString(headers)}")

    fun factory(cacheFactory: CacheDataSource.Factory): ResolvingDataSource.Factory {
        val resolver = ResolvingDataSource.Resolver { spec ->
            val raw = spec.uri.toString()
            if (!raw.contains(SPLIT_TAG)) {
                spec
            } else {
                val (url, headersJson) = raw.split(SPLIT_TAG, limit = 2)
                val headers: Map<String, String> = json.decodeFromString(headersJson)
                spec.withUri(Uri.parse(url))
                    .withRequestHeaders(headers)
            }
        }
        return ResolvingDataSource.Factory(cacheFactory, resolver)
    }
}