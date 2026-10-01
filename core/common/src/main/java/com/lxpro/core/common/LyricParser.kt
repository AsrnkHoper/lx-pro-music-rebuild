package com.lxpro.core.common

/**
 * 一行歌词。
 *
 * @param timeMs 该行开始时间（毫秒）
 * @param text 主歌词
 * @param translation 翻译（来自 `tlyric`，同一时间点则合并到同一行）
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
)

/**
 * LRC 解析。
 *
 * 支持：
 * - 时间戳 `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss:xx]`（`.` 与 `:` 两种分隔都见于真实歌词文件）
 * - **一行多时间戳**（`[00:12.00][01:30.00]副歌`），展开为多行
 * - 元信息标签（`[ti:]` `[ar:]` `[offset:]` `[by:]`）自动忽略
 * - 翻译：主歌词与翻译按时间点合并到同一行
 *
 * ⚠️ 解析结果是**按时间升序**的；输入顺序不可信（部分歌词源乱序输出）。
 */
object LyricParser {

    /** `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss:xx]`；元信息标签的第一段不是纯数字，天然不匹配 */
    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(lyric: String, translation: String? = null): List<LyricLine> {
        val main = parseSingle(lyric)
        if (main.isEmpty()) return emptyList()

        val translated = parseSingle(translation).associate { it.timeMs to it.text }
        return main
            .map { line -> line.copy(translation = translated[line.timeMs]) }
            .sortedBy { it.timeMs }
    }

    /**
     * 当前应高亮的行号；还没到第一行（前奏）时返回 -1。
     * 用二分查找——进度每 500ms 更新一次，线性扫也行，但没必要。
     */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun parseSingle(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()

        val result = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { line ->
            val matches = TIMESTAMP.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach

            matches.forEach { match ->
                result += LyricLine(timeMs = toMillis(match), text = text)
            }
        }
        return result
    }

    private fun toMillis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]

        val fractionMs = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLong() * 100
            fraction.length == 2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + fractionMs
    }
}