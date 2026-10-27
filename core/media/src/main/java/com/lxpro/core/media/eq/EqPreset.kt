package com.lxpro.core.media.eq

/**
 * 耳机校正 EQ 数据模型（17 §5，数据资产 `core/media/src/main/assets/eq_presets.json`）。
 *
 * 实测结论（2026-10-16，python 全量校验 779 条）：
 * - 三层目录 `raw`(260) / `raw100`(100) / `raw400`(419) 共 779 条记录，
 *   **型号名跨层零重叠**，按名称去重后正好 142 个唯一型号（58+21+63）。
 * - 同层内同名变体（如 16 条 "1MORE Triple Driver"）曲线互不相同；
 *   定稿原型 mockup_eq.html 的取舍 = **每层内首条留存**（142 行，与原型一致）。
 * - **142 个首条全部使用同一固定频率网格**（非标准网格只出现在被折叠的变体里），
 *   所以这里把 32 个峰值段频率存成全局常量，预设只带 32 个增益。
 * - 全部 779 条：低架(90Hz)/高架(10kHz)/前级增益恒为 0，校正完全由 32 峰值段表达
 *   → DSP 层不实现 shelf，只做 32 个 peaking biquad。
 * - 峰值段 q 字段恒为 0。lanmeipai 的 `Number(b.q) || 1` 把 0 折叠成 **Q=1**（Web Audio），
 *   本实现同样取 Q=1。
 */

/** 32 个峰值段的固定频率网格（20Hz → 19871Hz，对数等分，约 1/3 倍频程） */
val EQ_PEAK_FREQS = doubleArrayOf(
    20.0, 25.0, 31.0, 39.0, 49.0, 61.0, 76.0, 95.0,
    119.0, 148.0, 185.0, 231.0, 289.0, 361.0, 451.0, 564.0,
    705.0, 880.0, 1100.0, 1374.0, 1717.0, 2145.0, 2680.0, 3348.0,
    4182.0, 5225.0, 6528.0, 8156.0, 10190.0, 12731.0, 15905.0, 19871.0,
)

/** peaking biquad 的 Q（lanmeipai `Number(b.q) || 1` 的折叠结果，q=0 → 1） */
const val EQ_PEAK_Q = 1.0

/**
 * 三层内置目录（2026-10-16 用户裁决：精度切换 = **目录筛选器**，默认「全部」）。
 * 因为型号跨层零重叠，「精度」无法对同一型号切档，只能筛列表。
 *
 * [CUSTOM] 不属于内置资产：用户导入的预设（17 §5.3）用这一层标记，列表内与内置曲线同列展示。
 */
enum class EqLayer(val layerKey: String, val label: String) {
    RAW("raw", "快速"),
    RAW100("raw100", "中精度"),
    RAW400("raw400", "高精度"),
    CUSTOM("custom", "自定义"),
    ;

    companion object {
        /** 筛选器「全部」的持久化值（DataStore eq_precision_layer 默认） */
        const val FILTER_ALL = "all"

        fun fromKey(key: String): EqLayer? = entries.firstOrNull { it.layerKey == key }
    }
}

/**
 * 一条耳机校正曲线（已按定稿原型去重：型号首条留存，共 142 条）。
 *
 * @param name 原始型号名（别名的非破坏性映射锚点，17 §5.6）
 * @param layer 来源目录（三层零重叠，每个型号只属于一层；行内小标记用）
 * @param gains 32 个峰值段的增益 dB（与 [EQ_PEAK_FREQS] 一一对应）
 */
data class EqPreset(
    val name: String,
    val layer: EqLayer,
    val gains: FloatArray,
) {
    /** 品牌 = 型号名首词，特例「64」→「64 Audio」（mockup_eq.html brandOf 原样） */
    val brand: String
        get() {
            val first = name.split(' ')[0]
            return if (first == "64") "64 Audio" else first
        }

    /** 曲线峰值 / 谷值（参数小标与 dB 显示范围用） */
    val peakDb: Float get() = gains.max()
    val dipDb: Float get() = gains.min()

    override fun equals(other: Any?): Boolean =
        other is EqPreset && other.name == name && other.layer == layer

    override fun hashCode(): Int = 31 * name.hashCode() + layer.hashCode()
}
