package com.lxpro.core.network

import com.lxpro.core.model.SourceId

/**
 * 请求头策略（03 §2.3）。
 *
 * ⚠️ 本项目**只内置 B 站音源**；其他商业平台音源由用户导入脚本，
 * 其请求头通过脚本的 `lx.request` 自行传递，这里的分支仅作兜底。
 */
interface RequestHeaderStrategy {
    fun headersFor(source: SourceId): Map<String, String>
    fun userAgentFor(source: SourceId): String
}

object DefaultHeaderStrategy : RequestHeaderStrategy {

    const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override fun headersFor(source: SourceId): Map<String, String> = when (source.value) {
        "bi" -> mapOf(
            "Referer" to "https://www.bilibili.com",
            "Origin" to "https://www.bilibili.com",
        )

        "wy" -> mapOf(
            "Referer" to "https://music.163.com",
            "Origin" to "https://music.163.com",
        )

        else -> emptyMap()
    }

    override fun userAgentFor(source: SourceId): String = CHROME_UA
}