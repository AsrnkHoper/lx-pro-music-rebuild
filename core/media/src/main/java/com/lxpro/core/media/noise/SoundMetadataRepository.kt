package com.lxpro.core.media.noise

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 白噪音元数据仓库（用户裁决：白噪音不内置，全走远端 CDN）。
 *
 * 职责：
 * 1. 拉取 Noice library-manifest.json（[MANIFEST_URL]）
 * 2. 按 [NoiseCatalog.entries] 的 23 个映射，组装我方目录的 [NoiseSource]（只保留 free 段）
 * 3. 三级缓存：内存 → 磁盘（cacheDir/noice_manifest.json）→ 网络
 *
 * 实测依据（2026-10-14）：
 * - manifest 565KB / 31 音源，free 段仅 22 个音源有供给
 * - **桥接段文件 CDN 404**（thunder_1_thunder_3 实测），故不生成桥接段
 * - 段 URL = `https://cdn.trynoice.com/library/segments/{soundId}/{segName}/{bitrate}.mp3`
 */
@Singleton
class SoundMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) {
    @Volatile
    private var memoryCache: Map<String, NoiseSource>? = null

    private val diskCacheFile: File
        get() = File(context.cacheDir, "noice_manifest.json")

    /**
     * 加载目录（id → NoiseSource）。
     * 顺序：内存 → 磁盘 → 网络；网络成功后回写磁盘。
     * [forceRefresh] 为 true 时跳过内存与磁盘，直接拉网络。
     */
    suspend fun loadCatalog(forceRefresh: Boolean = false): Result<Map<String, NoiseSource>> =
        withContext(Dispatchers.IO) {
            memoryCache?.takeIf { !forceRefresh }?.let { return@withContext Result.success(it) }

            if (!forceRefresh) {
                runCatching { diskCacheFile.takeIf { it.exists() }?.readText() }
                    .getOrNull()
                    ?.let { json -> runCatching { parse(json) }.getOrNull() }
                    ?.let { catalog ->
                        memoryCache = catalog
                        return@withContext Result.success(catalog)
                    }
            }

            runCatching {
                val body = okHttp.newCall(Request.Builder().url(MANIFEST_URL).get().build())
                    .execute().use { resp ->
                        check(resp.isSuccessful) { "manifest HTTP ${resp.code}" }
                        resp.body?.string() ?: error("manifest empty body")
                    }
                val catalog = parse(body)
                runCatching { diskCacheFile.writeText(body) }
                    .onFailure { Log.w(LOG_TAG, "磁盘缓存写入失败", it) }
                memoryCache = catalog
                catalog
            }
        }

    /**
     * 解析 manifest → 我方 23 音源目录。
     * 只组装 [NoiseCatalog.entries] 里声明的音源；manifest 里找不到或 free 段为空的跳过。
     */
    private fun parse(json: String): Map<String, NoiseSource> {
        val root = JSONObject(json)
        val segmentsBasePath = root.getString("segmentsBasePath")
        val sounds = root.getJSONArray("sounds")

        // manifest soundId → free 段名清单
        val freeSegsBySound = HashMap<String, List<String>>(sounds.length())
        for (i in 0 until sounds.length()) {
            val sound = sounds.getJSONObject(i)
            val soundId = sound.getString("id")
            val segs = sound.getJSONArray("segments")
            val freeNames = ArrayList<String>(segs.length())
            for (j in 0 until segs.length()) {
                val seg = segs.getJSONObject(j)
                if (seg.optBoolean("isFree")) freeNames += seg.getString("name")
            }
            if (freeNames.isNotEmpty()) freeSegsBySound[soundId] = freeNames
        }

        val result = LinkedHashMap<String, NoiseSource>(NoiseCatalog.entries.size)
        for (entry in NoiseCatalog.entries) {
            val freeNames = freeSegsBySound[entry.noiceSoundId]
            if (freeNames.isNullOrEmpty()) {
                Log.w(LOG_TAG, "音源 ${entry.id}（Noice: ${entry.noiceSoundId}）无 free 段，跳过")
                continue
            }
            val segments = freeNames.map { segName ->
                val basePath = "$segmentsBasePath/${entry.noiceSoundId}/$segName"
                // ⚠️ 2026-10-20 重构：完整 URL 上移到段层，播放器直接播 seg.url
                SoundSegment(
                    name = segName,
                    url = "https://cdn.trynoice.com/library/$basePath/128k.mp3",
                    basePath = basePath,
                )
            }
            result[entry.id] = NoiseSource(
                id = entry.id,
                displayName = entry.displayName,
                category = entry.category,
                isRemote = true,
                minSilenceSeconds = entry.minSilenceSeconds,
                maxSilenceSeconds = entry.maxSilenceSeconds,
                segments = segments,
            )
        }
        check(result.isNotEmpty()) { "manifest 解析后目录为空" }
        return result
    }

    companion object {
        private const val LOG_TAG = "SoundMetadataRepo"
        const val MANIFEST_URL = "https://cdn.trynoice.com/library/library-manifest.json"
    }
}
