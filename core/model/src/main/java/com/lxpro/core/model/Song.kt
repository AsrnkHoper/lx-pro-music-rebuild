package com.lxpro.core.model

import kotlinx.serialization.Serializable

/** 音源标识（内置 + 脚本动态扩展），如 "bi" | "kw" | "kg"（02 §6.1） */
@JvmInline
@Serializable
value class SourceId(val value: String)

/** 本地音乐的伪音源 id（不走音源脚本，直接播放本地文件） */
val LOCAL_SOURCE_ID = SourceId("local")

const val LOCAL_SOURCE_NAME = "本地"

/**
 * 本地曲目封面的伪 URL 前缀。
 *
 * 本地封面不是可下载的 URL：Android 10+ 已对三方应用关闭 `audio/albumart` content URI，
 * 必须用 `ContentResolver.loadThumbnail(fileUri)` 取。因此约定 `picUrl` 形如
 * `localart://<urlencode(fileUri)>`，由 UI 层的 `AsyncArtwork` 识别并走缩略图通道。
 */
const val LOCAL_ART_PREFIX = "localart://"

/**
 * 双模式（15 §4）。全局单例、跨页面保持，且首页与搜索页共用同一份状态。
 *
 * ⚠️ 切换模式必须**保留搜索关键词并立即重搜** —— 用户常「在线搜不到 → 切本地看看」。
 */
enum class SearchMode {
    ONLINE,
    LOCAL,
    ;

    companion object {
        fun fromId(value: String?): SearchMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ONLINE
    }
}

/** 音质档位（02 §6.1） */
@Serializable
enum class Quality(val value: String) {
    K128("128k"),
    K320("320k"),
    FLAC("flac"),
    HIRES("hires"),
    ATMOS("atmos"),
    ATMOS_PLUS("atmos_plus"),
    MASTER("master"),
}

/** 统一歌曲模型（02 §6.1） */
@Serializable
data class Song(
    val id: String,
    val source: SourceId,
    val name: String,
    /** 展示用，多歌手用 "、" 连接 */
    val singer: String,
    val albumName: String? = null,
    val albumId: String? = null,
    val picUrl: String? = null,
    /** 时长（秒） */
    val interval: Long? = null,
    val releaseDate: String? = null,
    val qualitys: List<Quality> = emptyList(),
    val qualitySizes: Map<String, Long> = emptyMap(),
    val fee: Int = 0,
    /** 源特有字段（hash / copyrightId / strMediaMid ...） */
    val raw: Map<String, String> = emptyMap(),
)

/** 歌词（02 §6.1） */
@Serializable
data class LyricInfo(
    val lyric: String,
    val tlyric: String? = null,
    val rlyric: String? = null,
    val lxlyric: String? = null,
)

/** 播放地址（02 §6.1） */
@Serializable
data class MusicUrl(
    val url: String,
    val quality: Quality,
)

/**
 * 歌曲来源标记（15 §5）：UI 层**不得出现「不知道来源」的歌曲行**。
 */
@Serializable
sealed interface MusicOrigin {
    @Serializable
    data class Online(val source: SourceId, val sourceName: String) : MusicOrigin

    @Serializable
    data object Local : MusicOrigin
}