package com.lxpro.core.common

/**
 * 内嵌歌词提取（从音频文件字节里读）。
 *
 * ⚠️ 为什么不用现成的 `MetadataRetriever`：Media3 的 ID3 解码器**只处理 `COMM` 帧、不处理 `USLT`**
 * （已核对 `Id3Decoder.java` 源码），而 `USLT` 恰恰是 MP3 内嵌歌词最常见的载体。
 * 所以这里自己做解析，格式支持：
 *
 * - **ID3v2（MP3）**：`USLT`（无时间轴）/ `SYLT`（有时间轴，转成 LRC 更准）/ `TXXX`（描述含 LYRICS）
 * - **FLAC / Ogg**：Vorbis comment 的 `LYRICS` / `UNSYNCEDLYRICS` 等键
 *
 * 只做**开头一段**的解析（标签必在文件头部），因此调用方读前若干字节即可，不必整文件读入。
 */
object EmbeddedLyrics {

    /** 有 BOM 时按 BOM 走，否则按大端 */
    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRIC")

    /** @return 歌词原文（可能是 LRC 或纯文本）；没有则返回 null */
    fun extract(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        return when {
            bytes.matches(0, "ID3") -> fromId3(bytes)
            bytes.matches(0, "fLaC") -> fromFlac(bytes)
            else -> null
        }
    }

    // ---------------------------------------------------------------- ID3v2

    private fun fromId3(bytes: ByteArray): String? {
        val major = bytes[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = syncSafeInt(bytes, 6)

        var offset = 10
        if (flags and 0x40 != 0) {
            // 扩展头：v2.4 的 size 含自身，v2.3 的不含
            if (major >= 4) {
                offset += syncSafeInt(bytes, offset)
            } else {
                offset += beInt(bytes, offset) + 4
            }
        }

        val idLength = if (major == 2) 3 else 4
        val headerSize = if (major == 2) 6 else 10
        val end = minOf(bytes.size, 10 + tagSize)

        var unsynced: String? = null
        var synced: String? = null
        var fromTxxx: String? = null

        while (offset + headerSize <= end) {
            val id = String(bytes, offset, idLength, Charsets.ISO_8859_1)
            // 全 0 说明已进入 padding 区
            if (id[0] == '\u0000') break

            val size = when {
                major == 2 -> be24(bytes, offset + 3)
                major >= 4 -> syncSafeInt(bytes, offset + 4)
                else -> beInt(bytes, offset + 4)
            }
            val bodyStart = offset + headerSize
            val bodyEnd = bodyStart + size
            if (size <= 0 || bodyEnd > bytes.size) break

            when (id) {
                "USLT", "ULT" -> if (unsynced == null) unsynced = decodeLyricFrame(bytes, bodyStart, bodyEnd)
                "SYLT", "SLT" -> if (synced == null) synced = decodeSyncedFrame(bytes, bodyStart, bodyEnd)
                "TXXX", "TXX" -> if (fromTxxx == null) fromTxxx = decodeTxxxFrame(bytes, bodyStart, bodyEnd)
            }
            offset = bodyEnd
        }

        // 有时间轴的优先——它直接给出同步歌词，比纯文本强
        return synced ?: unsynced ?: fromTxxx
    }

    /** `USLT`：encoding(1) + language(3) + descriptor(终止符结尾) + 歌词正文 */
    private fun decodeLyricFrame(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 4 > end) return null
        val encoding = bytes[start].toInt() and 0xFF
        val textStart = skipTerminator(bytes, start + 4, end, encoding)
        return decodeText(bytes, textStart, end, encoding).trim().ifEmpty { null }
    }

    /** `TXXX`：encoding(1) + 描述(终止符结尾) + 值；只有描述像歌词字段时才当歌词 */
    private fun decodeTxxxFrame(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 1 > end) return null
        val encoding = bytes[start].toInt() and 0xFF
        val descriptionEnd = findTerminator(bytes, start + 1, end, encoding)
        if (descriptionEnd >= end) return null
        val description = decodeText(bytes, start + 1, descriptionEnd, encoding).uppercase()
        if (!description.contains("LYRIC")) return null
        val valueStart = skipTerminator(bytes, start + 1, end, encoding)
        return decodeText(bytes, valueStart, end, encoding).trim().ifEmpty { null }
    }

    /**
     * `SYLT`：encoding(1) + language(3) + 时间格式(1) + 内容类型(1) + 描述(终止符结尾) + 若干「文本+时间戳」。
     * 转成 LRC 文本返回，复用同一个 [LyricParser]。
     */
    private fun decodeSyncedFrame(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 6 > end) return null
        val encoding = bytes[start].toInt() and 0xFF
        val timeFormat = bytes[start + 4].toInt() and 0xFF
        // 1 = MPEG 帧计（需帧表换算），2 = 毫秒。只支持毫秒制
        if (timeFormat != 2) return null

        var pos = skipTerminator(bytes, start + 6, end, encoding)
        val builder = StringBuilder()
        while (pos < end) {
            val textEnd = findTerminator(bytes, pos, end, encoding)
            if (textEnd >= end) break
            val timePos = skipTerminator(bytes, pos, end, encoding)
            if (timePos + 4 > end) break

            val text = decodeText(bytes, pos, textEnd, encoding)
            // SYLT 的时间戳是 32 位无符号毫秒，这里按有符号读足够用（歌词不会超过 24 天）
            val timeMs = beInt(bytes, timePos).toLong() and 0xFFFFFFFFL
            if (text.isNotBlank()) {
                builder.append('[').append(formatTimestamp(timeMs)).append(']').append(text).append('\n')
            }
            pos = timePos + 4
        }
        return builder.toString().trim().ifEmpty { null }
    }

    // ------------------------------------------------------------ FLAC / Ogg

    private fun fromFlac(bytes: ByteArray): String? {
        var offset = 4
        while (offset + 4 <= bytes.size) {
            val header = bytes[offset].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val blockType = header and 0x7F
            val length = be24(bytes, offset + 1)
            val bodyStart = offset + 4
            val bodyEnd = bodyStart + length
            if (bodyEnd > bytes.size) return null

            // 4 = VORBIS_COMMENT
            if (blockType == 4) return parseVorbisComment(bytes, bodyStart, bodyEnd)
            if (isLast) return null
            offset = bodyEnd
        }
        return null
    }

    private fun parseVorbisComment(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        if (pos + 4 > end) return null
        val vendorLength = leInt(bytes, pos)
        pos += 4 + vendorLength

        if (pos + 4 > end) return null
        val count = leInt(bytes, pos)
        pos += 4

        repeat(count) {
            if (pos + 4 > end) return null
            val length = leInt(bytes, pos)
            pos += 4
            if (length < 0 || pos + length > end) return null

            val entry = String(bytes, pos, length, Charsets.UTF_8)
            pos += length

            val separator = entry.indexOf('=')
            if (separator > 0) {
                val key = entry.substring(0, separator).uppercase()
                if (key in LYRIC_KEYS) {
                    return entry.substring(separator + 1).trim().ifEmpty { null }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ 工具

    private fun ByteArray.matches(offset: Int, magic: String): Boolean {
        if (offset + magic.length > size) return false
        return magic.indices.all { this[offset + it] == magic[it].code.toByte() }
    }

    /** ID3 的长度字段是「同步安全整数」：每字节只用低 7 位 */
    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun be24(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** 终止符起点下标（没找到则返回 end） */
    private fun findTerminator(bytes: ByteArray, start: Int, end: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var index = start
            while (index + 1 < end) {
                if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) return index
                index += 2
            }
            return end
        }
        var index = start
        while (index < end) {
            if (bytes[index] == 0.toByte()) return index
            index++
        }
        return end
    }

    /** 跳过终止符，返回正文起点 */
    private fun skipTerminator(bytes: ByteArray, start: Int, end: Int, encoding: Int): Int {
        val index = findTerminator(bytes, start, end, encoding)
        if (index >= end) return end
        val terminatorLength = if (encoding == 1 || encoding == 2) 2 else 1
        return (index + terminatorLength).coerceAtMost(end)
    }

    private fun decodeText(bytes: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end) return ""
        val slice = bytes.copyOfRange(start, end)
        return when (encoding) {
            0 -> String(slice, Charsets.ISO_8859_1)
            1 -> String(slice, Charsets.UTF_16)
            2 -> String(slice, Charsets.UTF_16BE)
            else -> String(slice, Charsets.UTF_8)
        }
    }

    private fun formatTimestamp(timeMs: Long): String {
        val minutes = timeMs / 60_000
        val seconds = (timeMs % 60_000) / 1000
        val centis = (timeMs % 1000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }
}