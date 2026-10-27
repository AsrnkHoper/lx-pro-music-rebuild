package com.lxpro.core.media.eq

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * EQ 共享配置（17 §5）：A/B 双 song player 各持一个处理器实例，配置全部走这里同步。
 *
 * ⚠️ **为什么不能共享处理器实例**：BaseAudioProcessor 有内部状态
 * （inputAudioFormat / outputBuffer），同一实例挂到两个 AudioSink 会交替
 * flush/configure 互相污染 —— 所以配置共享、实例独立。
 */
@Singleton
class EqConfig @Inject constructor() {

    /** 直通开关（17 §5.2：切「正常」= 彻底还原，信号原样输出不滤波） */
    @Volatile
    var bypass: Boolean = true
        set(value) {
            field = value
        }

    /** 当前应用的曲线名（null = 未套用）；[EqPresetRepository.applyToConfig] 写入 */
    @Volatile
    var presetName: String? = null

    /**
     * 当前曲线的 32 个峰值段增益（与 [EQ_PEAK_FREQS] 一一对应；null = 不加载曲线）。
     * 处理器每次 queueInput 比对引用，变了就重算 biquad 系数。
     */
    @Volatile
    var currentGains: FloatArray? = null
}

/**
 * 歌曲音轨的耳机校正 EQ（17 §5）：32 个 peaking biquad 级联。
 *
 * **系数公式**：与 lanmeipai 实际使用的 Web Audio BiquadFilter 完全一致
 * （RBJ Audio EQ Cookbook peaking，A = 10^(gain/40)，Q=1 —— 数据里 q 恒为 0，
 * lanmeipai 的 `Number(b.q) || 1` 把它折叠成 1）。
 *
 * **数据前提**（779 条全量实测，见 [EqPreset] 文档）：
 * 低架 90Hz / 高架 10kHz / 前级增益恒为 0 —— 校正完全由 32 峰值段表达，
 * 所以这里**不实现 shelf**，只做 peaking 级联，不是简化。
 *
 * **三条硬约束**（17 §1）：
 * - **只对歌曲生效**：只挂在 song player 上；NoiseEngine 的 ExoPlayer 不挂任何处理器
 * - **白噪音直通**：白噪音 ExoPlayer 不挂任何 AudioProcessor，物理上不经过 EQ
 * - **直通=彻底还原**：bypass = 字节级原样拷贝（不是把 32 段拉平再过滤波链）
 *
 * **数值设计**：系数与滤波状态都用 double（Web Audio 内部为 float32，
 * 但 20Hz 这种低频段 float 状态容易累计误差，double 状态更稳，算力开销可忽略
 * —— 32 段 × 双声道 × 44.1kHz ≈ 2.8M 次乘加/秒）。
 */
class ParametricEqAudioProcessor(
    private val config: EqConfig,
) : BaseAudioProcessor() {

    val bypass: Boolean get() = config.bypass

    val currentPresetName: String? get() = config.presetName

    // ── biquad 系数（32 段，全部声道共用）与滤波状态（每声道独立） ──
    private val b0 = DoubleArray(BAND_COUNT) { 1.0 }
    private val b1 = DoubleArray(BAND_COUNT)
    private val b2 = DoubleArray(BAND_COUNT)
    private val a1 = DoubleArray(BAND_COUNT)
    private val a2 = DoubleArray(BAND_COUNT)

    /** z1/z2[声道][段]：DF2T 记忆态。声道数在 onConfigure 时确定 */
    private var z1 = Array(0) { DoubleArray(BAND_COUNT) }
    private var z2 = Array(0) { DoubleArray(BAND_COUNT) }

    /** 已按之算系数的增益快照（引用比对，变了重算） */
    private var appliedGains: FloatArray? = null

    /** 已按之算系数的采样率（onConfigure 变轨时重算） */
    private var appliedSampleRate: Int = 0

    /** 输入是否 PCM 16bit（DefaultRenderersFactory 默认 enableFloatOutput=false，恒为 16bit；
     *  万一遇到其他编码，整条处理器退化为字节级直通，绝不让播放失败） */
    private var pcm16: Boolean = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        if (!pcm16) {
            Log.w(TAG, "非 16bit 输入(${inputAudioFormat.encoding})，本轨退化为直通")
        }
        // 采样率 / 声道数变化：重算系数 + 清滤波记忆态（旧状态对应旧网格，留着会出瞬态杂声）
        if (inputAudioFormat.sampleRate != appliedSampleRate ||
            inputAudioFormat.channelCount != z1.size
        ) {
            z1 = Array(inputAudioFormat.channelCount) { DoubleArray(BAND_COUNT) }
            z2 = Array(inputAudioFormat.channelCount) { DoubleArray(BAND_COUNT) }
            appliedGains = null   // 强制重算
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val gains = config.currentGains
        val bypassNow = config.bypass || gains == null || !pcm16
        val bytes = inputBuffer.remaining()
        val srcPos = inputBuffer.position()
        val out = replaceOutputBuffer(bytes)

        if (out === inputBuffer) {
            // ⚠️ Media3 1.11 别名路径（2026-10-16 真机实测 ExoPlaybackException
            // "The source buffer is this buffer"，整个播放链瘫痪）：AudioProcessingPipeline
            // 在特定时序会把本处理器上一轮 getOutput() 交出去的实例缓冲当作输入喂回来。
            // replaceOutputBuffer 的 clear() 只重置 position/limit 不擦数据 ——
            // 原始字节仍在数组 [srcPos, srcPos+bytes)，前向逐字节搬回 0 偏移即可
            // （srcPos>0 时源区间恒在目标右侧，无重叠覆盖）。
            if (srcPos != 0) {
                for (i in 0 until bytes) out.put(i, out.get(srcPos + i))
            }
            out.position(0)
            out.limit(bytes)
        }

        // 直通两条路径：① 用户切「正常」（17 §5.2 彻底还原）② 没套用任何曲线
        if (bypassNow) {
            if (out !== inputBuffer) {
                out.put(inputBuffer)
                out.flip()
            }
            // 别名时数据已在 out 内（上面已搬回 0 偏移并设好窗口），无需再拷
            return
        }

        // 快照变了（切曲线 / 切采样率）→ 重算 32 段系数。
        // 记忆态保留：系数切换时旧 z 仍近似有效，瞬态远小于清零重来的爆音
        if (gains !== appliedGains || appliedSampleRate != inputAudioFormat.sampleRate) {
            computeCoefficients(gains, inputAudioFormat.sampleRate)
            appliedGains = gains
            appliedSampleRate = inputAudioFormat.sampleRate
        }

        val channels = inputAudioFormat.channelCount
        val frames = (bytes / 2) / channels

        // ⚠️ 别名时 in-place 滤波是安全的：每个位置的 sample 都是「先读后写」，
        // DF2T 只依赖历史样本，读写指针同步前进不会读到被污染的数据。
        repeat(frames) {
            for (ch in 0 until channels) {
                var x = inputBuffer.short.toDouble() * SHORT_TO_DOUBLE
                // 32 段 peaking 级联（DF2T）
                for (band in 0 until BAND_COUNT) {
                    val y = b0[band] * x + z1[ch][band]
                    z1[ch][band] = b1[band] * x - a1[band] * y + z2[ch][band]
                    z2[ch][band] = b2[band] * x - a2[band] * y
                    x = y
                }
                // 回写 16bit（削波硬限幅：数据前级恒 0，校正曲线最大 +8dB 量级，
                // 极端满幅信号叠加正增益可能触顶 —— 限幅比溢出回绕的听感损伤小得多）
                val s = (x * DOUBLE_TO_SHORT).toInt()
                out.putShort(s.coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }

    override fun onFlush() {
        // seek / 换轨的缓冲重置：清记忆态，避免旧音频的滤波残留糊到新位置开头
        resetState()
    }

    override fun onReset() {
        resetState()
        appliedGains = null
        appliedSampleRate = 0
    }

    private fun resetState() {
        z1.forEach { it.fill(0.0) }
        z2.forEach { it.fill(0.0) }
    }

    /** RBJ Audio EQ Cookbook peaking（与 Web Audio BiquadFilter peaking 同式），Q=1 */
    private fun computeCoefficients(gains: FloatArray, sampleRate: Int) {
        for (band in 0 until BAND_COUNT) {
            val gainDb = gains[band].toDouble()
            if (gainDb == 0.0) {
                // 0dB 段 = 恒等（省一次无意义滤波，也避免引入相位偏移）
                b0[band] = 1.0; b1[band] = 0.0; b2[band] = 0.0
                a1[band] = 0.0; a2[band] = 0.0
                continue
            }
            val amplitude = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * EQ_PEAK_FREQS[band] / sampleRate
            val alpha = sin(w0) / (2.0 * EQ_PEAK_Q)
            val cs = cos(w0)
            val a0 = 1.0 + alpha / amplitude
            b0[band] = (1.0 + alpha * amplitude) / a0
            b1[band] = (-2.0 * cs) / a0
            b2[band] = (1.0 - alpha * amplitude) / a0
            a1[band] = (-2.0 * cs) / a0
            a2[band] = (1.0 - alpha / amplitude) / a0
        }
    }

    companion object {
        const val TAG = "ParametricEqProcessor"

        private const val BAND_COUNT = 32
        private const val SHORT_TO_DOUBLE = 1.0 / 32768.0
        private const val DOUBLE_TO_SHORT = 32767.0
    }
}
