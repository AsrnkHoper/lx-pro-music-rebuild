package com.lxpro.feature.eq

import com.lxpro.core.media.eq.EQ_PEAK_FREQS
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 导入的 EQ 曲线文件解析（17 §5.3：AutoEQ / PowerAMP 曲线文件）。
 *
 * 支持两种实际最常见的文本格式（按内容嗅探，不看扩展名）：
 *
 * 1. **GraphicEQ**（AutoEQ 的标准导出，也是 PowerAMP「图形均衡器」预设的等价形态）
 *    ```
 *    Preamp: -6.4 dB
 *    GraphicEQ: 20 -1.2; 25 -1.1; 31 -1.0; ... ; 20000 -2.4
 *    ```
 *    → 在 log10(f) 轴上线性插值到本项目的 32 点网格。
 *
 * 2. **ParametricEQ**（AutoEQ 的参数导出）
 *    ```
 *    Preamp: -6.4 dB
 *    Filter 1: ON PK Fc 105 Hz Gain 5.5 dB Q 0.70
 *    ```
 *    → 按 RBJ peaking biquad 的**幅度响应**算到 32 点网格上，然后叠加。
 *    ⚠️ 本项目的 DSP 只有 32 段 peaking（内置资产同样如此，见 EqPreset.kt），
 *    所以 LSC/HSC 这类 shelf 行也按同 Q 的 peaking 近似（系数公式与 DSP 层一致，A = 10^(gain/40)）。
 *
 * ⚠️ `Preamp` 一律**忽略**：本项目 DSP 没有前级增益环节（内置 142 条的 preamp 恒为 0），
 * 处理器末端已做 16bit 硬限幅，过量增益不会爆音。
 */
object EqPresetFileParser {

    /** 试算采样率（只用于算频响形状；本项目频响与采样率无关的部分由 DSP 在播放采样率下重算） */
    private const val PROBE_SAMPLE_RATE = 48_000.0

    sealed interface Result {
        /** @param source 溯源标记（写进库，列表里区分来源） */
        data class Success(val name: String, val gains: FloatArray, val source: String, val format: String) : Result
        data class Failure(val reason: String) : Result
    }

    /** 解析一份曲线文件文本；[fileName] 只用来兜底命名 */
    fun parse(fileName: String, text: String): Result {
        if (text.isBlank()) return Result.Failure("文件是空的")
        val graphicMark = Regex("GraphicEQ\\s*:", RegexOption.IGNORE_CASE).find(text)
        if (graphicMark != null) {
            val gains = parseGraphicEq(text.substring(graphicMark.range.last + 1))
                ?: return Result.Failure("GraphicEQ 数据点太少（至少要有两组 频率 增益）")
            return Result.Success(nameFrom(fileName, "GraphicEQ"), gains, "graphic_eq", "AutoEQ GraphicEQ")
        }
        val filters = parseFilters(text)
        if (filters.isNotEmpty()) {
            return Result.Success(
                nameFrom(fileName, "ParametricEQ"),
                responseOf(filters),
                "parametric_eq",
                "AutoEQ ParametricEQ",
            )
        }
        return Result.Failure("无法识别：既没有 GraphicEQ 数据，也没有「Fc … Hz Gain … dB」滤波行")
    }

    // ── GraphicEQ ─────────────────────────────────────────────────────────────

    private fun parseGraphicEq(body: String): FloatArray? {
        val points = ArrayList<Pair<Double, Double>>(64)
        body.split(';').forEach { chunk ->
            // 行内注释（# 之后）与换行都要剔掉
            val nums = chunk.substringBefore('#')
                .split(Regex("[\\s,]+"))
                .mapNotNull { it.trim().toDoubleOrNull() }
            if (nums.size >= 2) points += nums[0] to nums[1]
        }
        if (points.size < 2) return null
        val sorted = points.sortedBy { it.first }
        return FloatArray(EQ_PEAK_FREQS.size) { i ->
            interpolateLog(sorted, EQ_PEAK_FREQS[i]).toFloat()
        }
    }

    /** 在 log10(频率) 轴上线性插值；两端取端点值（不外推） */
    private fun interpolateLog(sorted: List<Pair<Double, Double>>, freq: Double): Double {
        if (freq <= sorted.first().first) return sorted.first().second
        if (freq >= sorted.last().first) return sorted.last().second
        for (i in 0 until sorted.size - 1) {
            val (f0, g0) = sorted[i]
            val (f1, g1) = sorted[i + 1]
            if (freq in f0..f1 && f1 > f0) {
                val t = (ln(freq) - ln(f0)) / (ln(f1) - ln(f0))
                return g0 + (g1 - g0) * t
            }
        }
        return sorted.last().second
    }

    // ── ParametricEQ ──────────────────────────────────────────────────────────

    private data class PeakFilter(val freq: Double, val gainDb: Double, val q: Double)

    /**
     * 抓取所有含 `Fc … Gain …` 的滤波行（PK / LS / HS 一视同仁，后者按 peaking 近似）。
     * `Filter N: OFF …` 与缺参行直接跳过。
     */
    private fun parseFilters(text: String): List<PeakFilter> {
        val out = ArrayList<PeakFilter>(16)
        text.lineSequence().forEach { line ->
            if (line.contains("OFF", ignoreCase = true)) return@forEach
            val fc = numberAfter(line, "Fc") ?: return@forEach
            val gain = numberAfter(line, "Gain") ?: return@forEach
            val q = numberAfter(line, "Q") ?: 1.0
            if (fc <= 0.0 || q <= 0.0) return@forEach
            out += PeakFilter(fc, gain, q)
        }
        return out
    }

    /** 取标签后第一个数字（单位 Hz/dB 之类夹在中间不影响） */
    private fun numberAfter(line: String, label: String): Double? {
        val idx = line.indexOf(label, ignoreCase = true)
        if (idx < 0) return null
        val rest = line.substring(idx + label.length)
        return Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
            .find(rest)?.value?.toDoubleOrNull()
    }

    /** 各滤波在 32 点网格上的 dB 响应叠加 */
    private fun responseOf(filters: List<PeakFilter>): FloatArray =
        FloatArray(EQ_PEAK_FREQS.size) { i ->
            filters.sumOf { peakResponseDb(it, EQ_PEAK_FREQS[i]) }.toFloat()
        }

    /**
     * RBJ peaking biquad 在 [freq] 处的幅度响应（dB）。
     * 系数公式与 DSP 层（ParametricEqAudioProcessor）保持同一套：A = 10^(gain/40)。
     */
    private fun peakResponseDb(filter: PeakFilter, freq: Double): Double {
        val a = Math.pow(10.0, filter.gainDb / 40.0)
        val w0 = 2 * PI * filter.freq / PROBE_SAMPLE_RATE
        val alpha = sin(w0) / (2 * filter.q)
        val b0 = 1 + alpha * a
        val b1 = -2 * cos(w0)
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cos(w0)
        val a2 = 1 - alpha / a

        val w = 2 * PI * freq / PROBE_SAMPLE_RATE
        val cw1 = cos(w)
        val sw1 = sin(w)
        val cw2 = cos(2 * w)
        val sw2 = sin(2 * w)
        // e^{-jw} = cos w - j sin w
        val numRe = b0 + b1 * cw1 + b2 * cw2
        val numIm = -(b1 * sw1 + b2 * sw2)
        val denRe = a0 + a1 * cw1 + a2 * cw2
        val denIm = -(a1 * sw1 + a2 * sw2)
        val mag = sqrt(numRe * numRe + numIm * numIm) / sqrt(denRe * denRe + denIm * denIm)
        return 20 * log10(maxOf(mag, 1e-9))
    }

    // ── 命名 ─────────────────────────────────────────────────────────────────

    /** 文件名 → 预设名：去扩展名、去格式标记、下划线连字符转空格 */
    private fun nameFrom(fileName: String, fallback: String): String {
        var base = fileName.substringBeforeLast('.').trim()
        listOf("GraphicEQ", "ParametricEQ", "AutoEQ", "autoeq").forEach { tag ->
            base = base.replace(tag, " ", ignoreCase = true)
        }
        base = base.replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return base.ifBlank { fallback }
    }
}
