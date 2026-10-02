package com.lxpro.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌歌词解析的单测。
 *
 * ⚠️ 这里**手工合成 ID3v2 / FLAC 字节流**，而不是依赖某个真实文件——
 * 解析逻辑是纯字节处理，应该在主机侧被确定性地验证，不该靠真机"试出来"。
 */
class EmbeddedLyricsTest {

    @Test
    fun `ID3v2_3 USLT 的 UTF8 歌词`() {
        val bytes = id3v23("USLT" to usltFrame("第一行\n第二行"))

        val result = EmbeddedLyrics.extract(bytes)

        assertEquals("第一行\n第二行", result)
    }

    @Test
    fun `ID3v2_3 USLT 的 UTF16 歌词带 BOM`() {
        val bytes = id3v23("USLT" to usltFrame("你好世界", encoding = 1))

        assertEquals("你好世界", EmbeddedLyrics.extract(bytes))
    }

    @Test
    fun `ID3v2_2 的三字节帧 ID ULT`() {
        val body = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) +
            "老版本标签".toByteArray(Charsets.UTF_8)
        val frame = "ULT".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) +
            body
        val bytes = "ID3".toByteArray() +
            byteArrayOf(2, 0, 0) + syncSafe(frame.size) + frame

        assertEquals("老版本标签", EmbeddedLyrics.extract(bytes))
    }

    @Test
    fun `ID3v2_4 用同步安全整数表示帧长`() {
        val body = usltFrame("v4 歌词")
        val frame = "USLT".toByteArray(Charsets.ISO_8859_1) +
            syncSafe(body.size) + byteArrayOf(0, 0) + body
        val bytes = "ID3".toByteArray() +
            byteArrayOf(4, 0, 0) + syncSafe(frame.size) + frame

        assertEquals("v4 歌词", EmbeddedLyrics.extract(bytes))
    }

    @Test
    fun `SYLT 带时间轴_转成 LRC 后可被 LyricParser 解析`() {
        val bytes = id3v23(
            "SYLT" to syltFrame(listOf(1_000L to "第一句", 5_500L to "第二句")),
        )

        val raw = EmbeddedLyrics.extract(bytes)
        assertTrue("应输出 LRC 文本：$raw", raw!!.startsWith("[00:01.00]"))

        // 交叉验证：解析结果必须与写入的时间戳一致
        val lines = LyricParser.parse(raw)
        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(5_500L, lines[1].timeMs)
        assertEquals("第二句", lines[1].text)
    }

    @Test
    fun `有时间轴时优先于无时间轴`() {
        val bytes = id3v23(
            "USLT" to usltFrame("纯文本歌词"),
            "SYLT" to syltFrame(listOf(2_000L to "同步歌词")),
        )

        val raw = EmbeddedLyrics.extract(bytes)!!
        assertTrue("应取 SYLT：$raw", raw.contains("同步歌词"))
    }

    @Test
    fun `TXXX 只在描述像歌词字段时才当歌词`() {
        val lyrics = id3v23("TXXX" to txxxFrame("UNSYNCEDLYRICS", "放在 TXXX 里的歌词"))
        assertEquals("放在 TXXX 里的歌词", EmbeddedLyrics.extract(lyrics))

        val comment = id3v23("TXXX" to txxxFrame("COMMENT", "这只是个评论"))
        assertNull(EmbeddedLyrics.extract(comment))
    }

    @Test
    fun `FLAC 的 Vorbis comment 取 LYRICS 键`() {
        val bytes = flacWithComments(
            listOf("ARTIST" to "某人", "LYRICS" to "FLAC 内嵌歌词"),
        )

        assertEquals("FLAC 内嵌歌词", EmbeddedLyrics.extract(bytes))
    }

    @Test
    fun `FLAC 的 UNSYNCEDLYRICS 键也能识别`() {
        val bytes = flacWithComments(listOf("UnsyncedLyrics" to "大小写无关"))

        assertEquals("大小写无关", EmbeddedLyrics.extract(bytes))
    }

    @Test
    fun `没有歌词时返回 null_不误报`() {
        assertNull(EmbeddedLyrics.extract(ByteArray(0)))
        assertNull(EmbeddedLyrics.extract("不认识的文件头".toByteArray()))
        assertNull(EmbeddedLyrics.extract(id3v23("TIT2" to byteArrayOf(3) + "歌名".toByteArray())))
        assertNull(EmbeddedLyrics.extract(flacWithComments(listOf("ARTIST" to "只有歌手"))))
    }

    @Test
    fun `帧长越界时安全退出_不抛异常`() {
        // 手工把帧长写成远大于实际长度
        val body = usltFrame("歌词")
        val frame = "USLT".toByteArray(Charsets.ISO_8859_1) +
            be32(999_999) + byteArrayOf(0, 0) + body
        val bytes = "ID3".toByteArray() +
            byteArrayOf(3, 0, 0) + syncSafe(frame.size) + frame

        assertNull(EmbeddedLyrics.extract(bytes))
    }

    // ------------------------------------------------------------ 合成用工具

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun be32(value: Int) = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun le32(value: Int) = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte(),
    )

    private fun id3v23(vararg frames: Pair<String, ByteArray>): ByteArray {
        val body = frames
            .flatMap { (id, data) ->
                id.toByteArray(Charsets.ISO_8859_1).toList() +
                    be32(data.size).toList() +
                    listOf(0, 0) +
                    data.toList()
            }
            .toByteArray()
        return "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncSafe(body.size) + body
    }

    private fun usltFrame(text: String, encoding: Int = 3): ByteArray {
        val textBytes = when (encoding) {
            0 -> text.toByteArray(Charsets.ISO_8859_1)
            1 -> text.toByteArray(Charsets.UTF_16)
            else -> text.toByteArray(Charsets.UTF_8)
        }
        val descriptor = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
        return byteArrayOf(encoding.toByte()) + "eng".toByteArray() + descriptor + textBytes
    }

    private fun txxxFrame(description: String, value: String): ByteArray =
        byteArrayOf(3) + "eng".toByteArray() +
            description.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            value.toByteArray(Charsets.UTF_8)

    private fun syltFrame(entries: List<Pair<Long, String>>): ByteArray {
        val out = mutableListOf<Byte>()
        out += 3.toByte() // UTF-8
        out += "eng".toByteArray().toList()
        out += 2.toByte() // 时间格式：毫秒
        out += 1.toByte() // 内容类型：歌词
        out += 0.toByte() // 空描述
        entries.forEach { (timeMs, text) ->
            out += text.toByteArray(Charsets.UTF_8).toList()
            out += 0.toByte()
            out += be32(timeMs.toInt()).toList()
        }
        return out.toByteArray()
    }

    private fun flacWithComments(comments: List<Pair<String, String>>): ByteArray {
        val vendor = "test".toByteArray(Charsets.UTF_8)
        val body = mutableListOf<Byte>()
        body += le32(vendor.size).toList()
        body += vendor.toList()
        body += le32(comments.size).toList()
        comments.forEach { (key, value) ->
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            body += le32(entry.size).toList()
            body += entry.toList()
        }
        val bodyBytes = body.toByteArray()
        return "fLaC".toByteArray() +
            byteArrayOf((0x80 or 4).toByte()) +
            byteArrayOf(
                (bodyBytes.size shr 16).toByte(),
                (bodyBytes.size shr 8).toByte(),
                bodyBytes.size.toByte(),
            ) +
            bodyBytes
    }
}