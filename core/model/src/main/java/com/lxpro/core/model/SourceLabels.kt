package com.lxpro.core.model

/**
 * 音源展示名。
 *
 * 为什么要内置这张表：历史/下载/歌单里的歌可能来自**没装脚本**的音源（wy/qq/kg…），
 * 那时注册表里查不到名字，但界面上不能显示裸的 `wy`（15 §5：不得出现不知道来源的歌曲行）。
 */
object SourceLabels {

    private val builtin = mapOf(
        "wy" to "网易云",
        "qq" to "QQ音乐",
        "tx" to "QQ音乐",
        "kw" to "酷我",
        "kg" to "酷狗",
        "mg" to "咪咕",
        "bi" to "B站",
        "bilibili" to "B站",
        "local" to "本地",
    )

    /**
     * 平台别名 → 权威 id。
     *
     * 同一个音乐平台，内置源与第三方脚本的 id 可能不一样：内置 QQ 是 `tx`，
     * LX 脚本普遍写 `qq`；内置 B 站是 `bi`，脚本常写 `bilibili`。
     * 判断「是不是同一个平台」一律走这里 —— 否则同一个平台会被当成两个平台，
     * 既在「搜索平台」里重复出现，也会被重复搜索（2026-10-27 用户反馈）。
     */
    private val alias = mapOf(
        "qq" to "tx",
        "bilibili" to "bi",
    )

    /** 归一化后的平台 id：`qq` → `tx`，`bilibili` → `bi`，其余原样返回 */
    fun canonical(source: String): String = alias[source] ?: source

    /** @param override 已装音源的展示名（优先于内置表） */
    fun of(source: String, override: Map<String, String> = emptyMap()): String =
        override[source] ?: builtin[source] ?: source.uppercase()
}