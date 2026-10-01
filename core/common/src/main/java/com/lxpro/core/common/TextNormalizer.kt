package com.lxpro.core.common

/**
 * 搜索关键词归一化。
 *
 * 规则来自 UI 阶段实测的真 bug（06 §M1.2）：
 * - 用户打 `luvsicpt3` 要能搜到 `Luv(sic.)pt3` → 去掉标点与空白
 * - 归一化后**为空**（纯符号输入）必须回落初始态，**不能**匹配全部
 */
object TextNormalizer {

    private val punctuation = Regex("[\\p{P}\\p{S}\\s]+")

    /** 归一化用于匹配；返回值可能为空串，调用方须自行回落初始态。 */
    fun normalizeForSearch(input: String): String = punctuation
        .replace(input.lowercase(), "")

    /**
     * 判定输入是否「有效」：归一化后非空才算有效。
     * 纯符号（如 "!!!"）返回 false，调用方应显示初始态而非全量结果。
     */
    fun isSearchable(input: String): Boolean = normalizeForSearch(input).isNotEmpty()

    /** 宽松匹配：归一化后互相包含即认为命中。 */
    fun matches(candidate: String, keyword: String): Boolean {
        val normalizedKeyword = normalizeForSearch(keyword)
        if (normalizedKeyword.isEmpty()) return false
        return normalizeForSearch(candidate).contains(normalizedKeyword)
    }
}