package com.lxpro.core.library

import android.content.Context
import android.net.Uri
import com.lxpro.core.common.EmbeddedLyrics
import com.lxpro.core.database.entity.LocalTrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地曲目的歌词来源，两条通道：
 *
 * 1. **同名 `.lrc` 文件**（扫描期已解析好 uri，见 [LocalTrackEntity.lrcUri]）
 * 2. **音频内嵌歌词**（ID3v2 的 USLT/SYLT、FLAC 的 Vorbis comment，见 [EmbeddedLyrics]）
 *
 * ⚠️ 编码不能想当然：中文 `.lrc` **大量是 GBK/GB18030 而非 UTF-8**，
 * 直接按 UTF-8 解会整片乱码。这里按 BOM → 严格 UTF-8 → GBK 的顺序判定。
 */
@Singleton
class LocalLyricsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun lyricsFor(track: LocalTrackEntity): String? = withContext(Dispatchers.IO) {
        val fromFile = track.lrcUri
            ?.let { readText(it) }
            ?.takeIf { it.isNotBlank() }
        // 有独立歌词文件就用它——文件通常比内嵌的更全（含翻译、逐字时间轴）
        fromFile ?: readEmbedded(track.uri)
    }

    private fun readText(uriString: String): String? = runCatching {
        val bytes = openStream(uriString)?.use { it.readFully(MAX_LRC_BYTES) } ?: return null
        decodeSmartly(bytes)
    }.getOrNull()

    private fun readEmbedded(audioUri: String): String? = runCatching {
        // 标签必在文件头部，所以只读开头一段，不必整文件读入
        val head = openStream(audioUri)?.use { it.readHead(MAX_TAG_BYTES) } ?: return null
        EmbeddedLyrics.extract(head)
    }.getOrNull()

    private fun openStream(uriString: String): InputStream? {
        val uri = Uri.parse(uriString)
        return if (uri.scheme == "file") {
            uri.path?.let { File(it) }?.takeIf { it.isFile }?.inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    /**
     * 编码判定：BOM 优先 → 严格 UTF-8 → GBK → Latin-1 兜底。
     *
     * 严格模式很关键：用宽松模式解 UTF-8 永远不报错，GBK 的字节会被替换成一片 ``，
     * 就再也判断不出该回退了。
     */
    private fun decodeSmartly(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            runCatching { String(bytes, charset("GBK")) }
                .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        }
    }

    /** 读满 limit 字节；文件更短则返回实际长度（.lrc 一般很小，不会触发上限） */
    private fun InputStream.readFully(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return if (total == buffer.size) buffer else buffer.copyOf(total)
    }

    private fun InputStream.readHead(limit: Int): ByteArray = readFully(limit)

    private companion object {
        const val MAX_LRC_BYTES = 2 * 1024 * 1024
        const val MAX_TAG_BYTES = 2 * 1024 * 1024
    }
}