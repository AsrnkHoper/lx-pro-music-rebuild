package com.lxpro.core.media

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import kotlinx.serialization.json.Json

/**
 * 把请求头编码进 MediaItem 的 URI，在 `ResolvingDataSource` 中解出（03 §5.2）。
 *
 * ⚠️⚠️ 关键正确性要求：**不得**用 `OkHttpDataSource.setDefaultRequestProperties()`
 * （全局可变状态，并发预加载/切歌时会串 Referer/UA）。必须用 `DataSpec.withRequestHeaders()`
 * 让 headers 随 spec 走，天然线程安全。
 *
 * ⚠️⚠️⚠️ cacheKey 机制（2026-10-27 新增）：音源签名 URL 每次取流都变（token/ts/sign），
 * 导致 SimpleCache 用完整 URI 当 key 时永远 miss。[buildUri] 接受可选 cacheKey（稳定键如
 * "source:id:quality"），[factory] 的 resolver 用 `spec.withKey(cacheKey)` 让 CacheDataSource
 * 按稳定键查找缓存 —— 即使签名 URL 过期重新取流，音频数据缓存仍能命中。
 *
 * ⚠️ 上游是**已按 scheme 路由**的工厂（见 PlayerFactory）：
 * `content://` / `file://` 由 `DefaultDataSource` 处理，不经过缓存，也不会碰到 OkHttp。
 */
object HeaderAwareDataSource {

    /** 🚧 不会出现在正常 URL 中 */
    private const val SPLIT_TAG = "\uD83D\uDEA7"
    /** 🗝️ cacheKey 分隔符 —— 不会出现在 URL 或 JSON headers 中 */
    private const val KEY_TAG = "\uD83D\uDDDD"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 把 [url] + [headers]（+ 可选 [cacheKey]）编码进 URI。
     * 格式：`$url🚧{headersJson}[🗝️{cacheKey}]`
     */
    fun buildUri(url: String, headers: Map<String, String>, cacheKey: String? = null): Uri {
        val headersJson = json.encodeToString(headers)
        val encoded = if (cacheKey != null) "$url$SPLIT_TAG$headersJson$KEY_TAG$cacheKey"
                      else "$url$SPLIT_TAG$headersJson"
        return Uri.parse(encoded)
    }

    fun factory(upstreamFactory: DataSource.Factory): ResolvingDataSource.Factory {
        val resolver = ResolvingDataSource.Resolver { spec ->
            val raw = spec.uri.toString()
            if (!raw.contains(SPLIT_TAG)) {
                spec
            } else {
                val afterHeaderSplit = raw.split(SPLIT_TAG, limit = 2)
                val url = afterHeaderSplit[0]
                val rest = afterHeaderSplit[1]
                // rest = headersJson [+ KEY_TAG + cacheKey]
                val headersJson: String
                val cacheKey: String?
                if (rest.contains(KEY_TAG)) {
                    val parts = rest.split(KEY_TAG, limit = 2)
                    headersJson = parts[0]
                    cacheKey = parts[1]
                } else {
                    headersJson = rest
                    cacheKey = null
                }
                val headers: Map<String, String> = json.decodeFromString(headersJson)
                spec.withUri(Uri.parse(url))
                    .withRequestHeaders(headers)
            }
        }
        return ResolvingDataSource.Factory(upstreamFactory, resolver)
    }
}