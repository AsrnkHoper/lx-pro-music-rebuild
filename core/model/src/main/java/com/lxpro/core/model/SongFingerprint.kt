package com.lxpro.core.model

import java.security.MessageDigest

/**
 * 跨源歌曲指纹：归一化（歌名 + 主歌手）后哈希（03 §4）。
 * 用于收藏去重、跨源迁移、播放历史合并。
 *
 * ⚠️ 指纹是**启发式**，可能误判（同一首歌的不同版本）；UI 上必须允许「取消合并」。
 */
object SongFingerprint {

    fun of(song: Song): String = of(song.name, song.singer)

    fun of(name: String, singer: String): String {
        val normalizedName = normalize(name)
        val primarySinger = normalize(
            singer.split("、", "&", "/", ",", "，").firstOrNull().orEmpty(),
        )
        return sha1("$normalizedName|$primarySinger")
    }

    private fun normalize(raw: String): String = raw
        .lowercase()
        // 去括号内容：(Live)、(Remix)、【…】
        .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
        .replace(Regex("\\s+"), "")
        .replace(Regex("[《》\"'·・,，.。!！?？\\-—]"), "")
        .trim()

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}