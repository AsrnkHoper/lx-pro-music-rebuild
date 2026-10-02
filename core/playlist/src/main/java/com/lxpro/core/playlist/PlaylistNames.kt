package com.lxpro.core.playlist

/**
 * 歌单名的纯逻辑（可单测，不碰数据库）。
 *
 * 规格没有要求唯一名，但**空白名与重名**是用户最容易撞上的两种情况：
 * 撞上了要么建出一个看不见名字的歌单，要么一堆「新歌单」长得一模一样分不清。
 */
object PlaylistNames {

    const val MAX_LENGTH = 40
    private const val DEFAULT_PREFIX = "新歌单"

    /** 归一化：去首尾空白；空白或超长返回 null / 截断 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= MAX_LENGTH) trimmed else trimmed.take(MAX_LENGTH)
    }

    /** 未命名时给一个不与现有重名的默认名：新歌单 1 / 2 / 3… */
    fun nextDefaultName(existing: List<String>): String {
        val taken = existing.toSet()
        var index = 1
        while (true) {
            val candidate = "$DEFAULT_PREFIX $index"
            if (candidate.length <= MAX_LENGTH && candidate !in taken) return candidate
            index++
        }
    }
}