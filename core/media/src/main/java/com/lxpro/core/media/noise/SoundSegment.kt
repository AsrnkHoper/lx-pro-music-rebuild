package com.lxpro.core.media.noise

/**
 * 一段声音的元信息（17 §2.3，照 Noice `SoundSegment.kt` 移植）。
 *
 * ⚠️ **2026-10-20 重构**：把 URL 构造从 [LocalSoundPlayer] / [NoiseEngine] 上移到段层，
 * 让 CDN 远端段、本地导入段、未来的远端音源包段共用同一套播放器逻辑——
 * 播放器只播 [url]，不关心它是 `https://` 还是 `content://`。
 *
 * 三种段类型由 [isBridgeSegment] / [from] / [to] 区分：
 * - **连续型**：[isBridgeSegment]=false 且 [maxSilenceSeconds]==0 —— 无缝循环，首尾可拼接
 *   （雨、海浪、溪流、风、风扇）
 * - **事件型**：[isBridgeSegment]=false 且 [maxSilenceSeconds]>0 —— 两次发声之间随机静音
 *   （远雷 30–90s · 近雷 20–60s · 鸟鸣 5–25s · 咖啡机 15–45s · 翻书 3–12s）
 * - **桥接段**：[isBridgeSegment]=true —— 从 A 段过渡到 B 段的专用短音，避免硬切
 *
 * ⚠️ 桥接段必须带 [from] / [to]（构造期断言，与 Noice 一致）。
 *
 * @param name 段名（同音源内唯一）
 * @param url 完整可播 URL。CDN 音源由 [SoundMetadataRepository] 拼 `https://cdn.trynoice.com/library/...`；
 *   本地导入由导入模块直接填 `content://` 或 `file://`。
 * @param basePath 音源内部的相对路径（**仅用于 CDN 段的缓存 key**；本地导入段留空）
 */
data class SoundSegment(
    val name: String,
    val url: String,
    val basePath: String = "",
    val isBridgeSegment: Boolean = false,
    val from: String? = null,
    val to: String? = null,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        if (isBridgeSegment) {
            require(from != null) { "from must not be null for bridge segments" }
            require(to != null) { "to must not be null for bridge segments" }
        }
    }

    /** 缓存 key（CDN 段用 basePath/bitrate.mp3；本地导入段走 url hash，缓存层自己决定） */
    fun cacheKey(bitrate: String): String = if (basePath.isNotEmpty()) "$basePath/$bitrate.mp3" else url
}

/**
 * 一个音源的完整元信息（17 §2.2 28 音源，照 Noice `Sound` 模型简化）。
 *
 * @param id 音源唯一 id（如 `rain_light`）
 * @param displayName 显示名（如「小雨」）
 * @param category 六类之一（17 §2.2 天气/自然/生活/交通/机械/噪音）
 * @param isRemote true=远端音源包（17 §2.2 7 个标 `远端`），false=内置本地
 * @param minSilenceSeconds 事件型随机静音下限（秒），仅 [maxSilenceSeconds]>0 时有效
 * @param maxSilenceSeconds 0=连续型；>0=事件型两次发声间随机静音上限（秒）
 * @param segments 段清单（⚠️ CDN free 层无桥接段文件，实测 404，故不含桥接段）
 */
data class NoiseSource(
    val id: String,
    val displayName: String,
    val category: NoiseCategory,
    val isRemote: Boolean = true,
    val minSilenceSeconds: Int = 0,
    val maxSilenceSeconds: Int = 0,
    val segments: List<SoundSegment> = emptyList(),
    /**
     * true = 流式源（在线搜索得到的直链）：**不进预下载队列**，直接交给播放器边下边播。
     * 内置 CDN 段 / 本地导入 / 远端音源包都是 pre-download（false）。
     */
    val streaming: Boolean = false,
)

enum class NoiseCategory(val label: String) {
    WEATHER("天气"),
    NATURE("自然"),
    LIFE("生活"),
    TRANSIT("交通"),
    MACHINE("机械"),
    NOISE("噪音"),
}

/**
 * 一个混音场景（17 §2.1，场景=1-6 图层叠加）。
 *
 * @param id 预设场景 id 固定；用户副本另起 id
 * @param name 显示名；改动预设后 UI 自动改「XX 副本」并解除绑定（17 §2.1）
 * @param layers 图层列表（每层 = 音源 id + 0..1 独立音量）
 * @param isBuiltin true=预设场景（8 个），false=用户自建「我的组合」
 */
data class NoiseScene(
    val id: String,
    val name: String,
    val layers: List<NoiseLayer>,
    val isBuiltin: Boolean = false,
)

/** 一个图层 = 音源 id + 独立音量（0..1） */
data class NoiseLayer(
    val sourceId: String,
    val volume: Float,
)
