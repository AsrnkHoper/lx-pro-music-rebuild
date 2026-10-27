package com.lxpro.core.media.noise

/**
 * 白噪音目录（17 §2.2 的 28 音源 → Noice CDN 实测可播映射，2026-10-14 实测）。
 *
 * ⚠️ 两条用户裁决（不可推翻）：
 * 1. 白噪音**不内置音频文件**，全部走远端 CDN（`https://cdn.trynoice.com/library/`）
 * 2. 规格 28 音源中 CDN free 层供给不足的 5 个**缺位不进目录**，后续由
 *    「本地导入 / 在线搜索 / 远端音源包」（17 §3）补齐
 *
 * 缺位 5 个：rain_h（大雨）、rain_eave（屋檐滴水）、waterfall（瀑布）、coffee（咖啡机）、ac（空调）
 *
 * 实测依据（/tmp/noice_manifest.json 2026-10-14）：
 * - Noice manifest 共 31 音源，free 段仅 22 个音源有供给
 * - **桥接段文件 CDN 404**（thunder_1_thunder_3 实测 404），故只保留 free 普通段
 *
 * @param id 我方 key（与 mockup_noise.html SOURCES 的 k 一致）
 * @param displayName 中文显示名
 * @param category 六类之一（17 §2.2）
 * @param noiceSoundId Noice manifest `sounds[].id`
 * @param iconKey 图标 key（与 mockup SOURCES 的 i 一致，UI 层映射绘制）
 * @param minSilenceSeconds 事件型随机静音下限（秒），0=连续型
 * @param maxSilenceSeconds 事件型随机静音上限（秒），0=连续型；gap 值照 mockup（远雷 30–90 等）
 */
data class NoiseCatalogEntry(
    val id: String,
    val displayName: String,
    val category: NoiseCategory,
    val noiceSoundId: String,
    val iconKey: String,
    val minSilenceSeconds: Int = 0,
    val maxSilenceSeconds: Int = 0,
) {
    /** true=事件型（随机静音间隔），false=连续型（无缝循环） */
    val isEventGap: Boolean get() = maxSilenceSeconds > 0
}

object NoiseCatalog {

    /** 23 个 CDN 可播音源（28 - 5 缺位），顺序照 17 §2.2 六类排列 */
    val entries: List<NoiseCatalogEntry> = listOf(
        // ── 天气 ──
        NoiseCatalogEntry("rain_l", "小雨", NoiseCategory.WEATHER, "rain", "rain"),
        NoiseCatalogEntry("thunder_f", "远雷", NoiseCategory.WEATHER, "thunder", "storm",
            minSilenceSeconds = 30, maxSilenceSeconds = 90),
        NoiseCatalogEntry("thunder_n", "近雷", NoiseCategory.WEATHER, "thunder", "storm",
            minSilenceSeconds = 20, maxSilenceSeconds = 60),
        NoiseCatalogEntry("wind_l", "微风", NoiseCategory.WEATHER, "soft_wind", "wind"),
        NoiseCatalogEntry("wind_h", "大风", NoiseCategory.WEATHER, "wind_through_palm_trees", "wind"),

        // ── 自然 ──
        NoiseCatalogEntry("wave", "海浪", NoiseCategory.NATURE, "seashore", "wave"),
        NoiseCatalogEntry("stream", "溪流", NoiseCategory.NATURE, "water_stream", "stream"),
        NoiseCatalogEntry("bird", "鸟鸣", NoiseCategory.NATURE, "birds", "bird",
            minSilenceSeconds = 5, maxSilenceSeconds = 25),
        NoiseCatalogEntry("bug", "虫鸣", NoiseCategory.NATURE, "crickets", "bug"),
        // 近似映射：Noice 无蛙鸣，night（夜晚环境音）语义最接近
        NoiseCatalogEntry("frog", "蛙鸣", NoiseCategory.NATURE, "night", "bug"),

        // ── 生活 ──
        NoiseCatalogEntry("fire", "壁炉", NoiseCategory.LIFE, "campfire", "fire"),
        NoiseCatalogEntry("cat", "猫呼噜", NoiseCategory.LIFE, "purring_cat", "cat"),
        NoiseCatalogEntry("crowd", "咖啡馆人群", NoiseCategory.LIFE, "coffee_shop", "crowd"),
        // 近似映射：Noice 无纯键盘声，office（办公室环境音）含键盘声
        NoiseCatalogEntry("keys", "键盘", NoiseCategory.LIFE, "office", "keys"),
        // 近似映射：Noice 无纯翻书声，public_library（图书馆环境音）含翻书声；
        // 环境音必须连续，故不套 mockup 的 gap[3,12]（断续的图书馆环境音听感错误）
        NoiseCatalogEntry("book", "翻书", NoiseCategory.LIFE, "public_library", "book"),
        NoiseCatalogEntry("clock", "钟表", NoiseCategory.LIFE, "wall_clock", "clock"),

        // ── 交通 ──
        NoiseCatalogEntry("train", "火车车厢", NoiseCategory.TRANSIT, "train", "train"),
        NoiseCatalogEntry("plane", "飞机舱", NoiseCategory.TRANSIT, "air_travel", "plane"),

        // ── 机械 ──
        NoiseCatalogEntry("fan", "风扇", NoiseCategory.MACHINE, "fan", "fan"),

        // ── 噪音 ──
        NoiseCatalogEntry("white", "白噪音", NoiseCategory.NOISE, "white_noise", "noise"),
        NoiseCatalogEntry("pink", "粉噪音", NoiseCategory.NOISE, "pink_noise", "noise"),
        NoiseCatalogEntry("brown", "棕噪音", NoiseCategory.NOISE, "brownian_noise", "noise"),
        NoiseCatalogEntry("heart", "心跳", NoiseCategory.NOISE, "heartbeat", "heart"),
    )

    private val entryById: Map<String, NoiseCatalogEntry> = entries.associateBy { it.id }

    fun entryOf(id: String): NoiseCatalogEntry? = entryById[id]

    /**
     * 8 个预设场景（17 §2.1，图层与音量原样照 mockup_noise.html SCENES）。
     *
     * ⚠️ cafe「咖啡馆」原三层 [crowd.56, coffee.4, keys.5]：coffee（咖啡机）CDN 缺位，
     * 跳过该层，余 crowd + keys 两层。
     */
    val presetScenes: List<NoiseScene> = listOf(
        NoiseScene("study", "雨夜书房", listOf(
            NoiseLayer("rain_l", .72f), NoiseLayer("thunder_f", .34f),
            NoiseLayer("fire", .48f), NoiseLayer("book", .4f),
        ), isBuiltin = true),
        NoiseScene("sea", "海边清晨", listOf(
            NoiseLayer("wave", .8f), NoiseLayer("bird", .42f), NoiseLayer("wind_l", .34f),
        ), isBuiltin = true),
        NoiseScene("cafe", "咖啡馆", listOf(
            NoiseLayer("crowd", .56f), NoiseLayer("keys", .5f),
        ), isBuiltin = true),
        NoiseScene("forest", "森林深处", listOf(
            NoiseLayer("stream", .6f), NoiseLayer("bird", .5f),
            NoiseLayer("bug", .28f), NoiseLayer("wind_l", .3f),
        ), isBuiltin = true),
        NoiseScene("hearth", "壁炉边", listOf(
            NoiseLayer("fire", .78f), NoiseLayer("wind_h", .24f), NoiseLayer("cat", .36f),
        ), isBuiltin = true),
        NoiseScene("train", "深夜列车", listOf(
            NoiseLayer("train", .62f), NoiseLayer("rain_l", .42f), NoiseLayer("clock", .2f),
        ), isBuiltin = true),
        NoiseScene("pure", "纯色噪音", listOf(
            NoiseLayer("white", .66f),
        ), isBuiltin = true),
        NoiseScene("sleep", "助眠", listOf(
            NoiseLayer("brown", .52f), NoiseLayer("heart", .44f), NoiseLayer("rain_l", .34f),
        ), isBuiltin = true),
    )
}
