package com.lxpro.feature.noise

import android.content.Context
import android.net.Uri
import com.lxpro.core.media.noise.NoiseCategory
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Noice 式白噪音声音包导入（17 §3 第三种获取方式）。
 *
 * 包格式（zip）：
 * ```
 * manifest.json
 * rain_light.mp3
 * rain_heavy.mp3
 * rain_bridge.mp3
 * ```
 * `manifest.json`：
 * ```json
 * {
 *   "name": "雨声包",
 *   "category": "天气",
 *   "minSilence": 0,
 *   "maxSilence": 0,
 *   "segments": [
 *     {"name": "rain_light", "file": "rain_light.mp3"},
 *     {"name": "rain_bridge", "file": "rain_bridge.mp3",
 *      "bridge": true, "from": "rain_light", "to": "rain_heavy"},
 *     {"name": "rain_heavy", "file": "rain_heavy.mp3"}
 *   ]
 * }
 * ```
 * 没有 manifest.json 时降级：zip 里每个音频文件各算一段，包名取 zip 文件名。
 *
 * ⚠️ 只写应用私有目录，不碰 SAF 授权：包内文件解压后就是 `file://`，离线可用。
 */
object NoisePackImporter {

    private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")

    data class SegmentSpec(
        val name: String,
        val file: String,
        val bridge: Boolean,
        val from: String?,
        val to: String?,
    )

    data class Parsed(
        val name: String,
        val category: String,
        val minSilence: Int,
        val maxSilence: Int,
        val segments: List<SegmentSpec>,
    )

    /**
     * 解压到 [targetDir] 并解析元数据。
     * @throws IllegalArgumentException 包不合法（无音频 / 路径越界 / 读不出来）
     */
    fun import(context: Context, uri: Uri, targetDir: File): Parsed {
        targetDir.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val rawName = entry.name.replace('\\', '/')
                    val out = File(targetDir, rawName)
                    // zip-slip 防护：解压路径必须落在 targetDir 内
                    if (!out.canonicalPath.startsWith(targetDir.canonicalPath + File.separator) &&
                        out.canonicalPath != targetDir.canonicalPath
                    ) {
                        throw IllegalArgumentException("声音包里有非法路径：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalArgumentException("无法读取这个文件")

        val audioFiles = targetDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXT }
            .toList()
        if (audioFiles.isEmpty()) throw IllegalArgumentException("声音包里没有音频文件")

        val manifest = targetDir.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
        return if (manifest != null) {
            parseManifest(manifest, audioFiles)
        } else {
            fallback(targetDir.name, audioFiles)
        }
    }

    private fun parseManifest(manifest: File, audioFiles: List<File>): Parsed {
        val json = runCatching { JSONObject(manifest.readText()) }
            .getOrElse { throw IllegalArgumentException("manifest.json 不是合法 JSON") }
        val root = manifest.parentFile ?: manifest

        val segmentsArray = json.optJSONArray("segments")
        val specs = if (segmentsArray != null && segmentsArray.length() > 0) {
            (0 until segmentsArray.length()).mapNotNull { i ->
                val o = segmentsArray.optJSONObject(i) ?: return@mapNotNull null
                val file = o.optString("file").trim()
                if (file.isEmpty()) return@mapNotNull null
                if (!File(root, file).isFile) return@mapNotNull null
                val from = o.optString("from").trim().ifEmpty { null }
                val to = o.optString("to").trim().ifEmpty { null }
                val bridge = o.optBoolean("bridge", false) && from != null && to != null
                SegmentSpec(
                    name = o.optString("name").trim().ifEmpty { File(file).nameWithoutExtension },
                    file = file,
                    bridge = bridge,
                    from = from,
                    to = to,
                )
            }
        } else {
            audioFiles.map {
                SegmentSpec(it.nameWithoutExtension, it.relativeToFile(root), false, null, null)
            }
        }
        if (specs.isEmpty()) throw IllegalArgumentException("manifest.json 里没有可用的段")

        val category = json.optString("category").trim()
            .takeIf { c -> NoiseCategory.entries.any { it.label == c } }
            ?: NoiseCategory.NATURE.label
        return Parsed(
            name = json.optString("name").trim().ifEmpty { root.name },
            category = category,
            minSilence = json.optInt("minSilence", 0).coerceAtLeast(0),
            maxSilence = json.optInt("maxSilence", 0).coerceAtLeast(0),
            segments = specs,
        )
    }

    private fun fallback(name: String, audioFiles: List<File>): Parsed {
        val root = audioFiles.first().parentFile ?: audioFiles.first()
        return Parsed(
            name = name,
            category = NoiseCategory.NATURE.label,
            minSilence = 0,
            maxSilence = 0,
            segments = audioFiles.map {
                SegmentSpec(it.nameWithoutExtension, it.relativeToFile(root), false, null, null)
            },
        )
    }

    private fun File.relativeToFile(root: File): String =
        runCatching { relativeTo(root).path.replace('\\', '/') }.getOrDefault(name)
}
