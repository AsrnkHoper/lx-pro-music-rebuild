package cn.lyric.getter.api

import cn.lyric.getter.api.data.ExtraData

/**
 * Lyric Getter API（vendored 空壳，协议版本 6）。
 *
 * 来源：https://github.com/xiaowine/Lyric-Getter-Api （MIT，2025-04 归档）
 *
 * 原理：普通环境下这些方法全是空实现；装了「墨·状态栏歌词」等 Xposed 模块后，
 * 模块 hook 本类的 [sendLyric] / [clearLyric] / [hasEnable]，把歌词接管到系统侧展示。
 * 因此**必须保持包名 / 类名 / 方法签名一字不差**，且 release 混淆要 keep（见 proguard-rules.pro）。
 */
class API {
    /**
     * 是否启用：普通环境下恒 false，被模块 Hook 后为 true。
     * 不强制按它门控——未 hook 时 [sendLyric] 本来就是空调用。
     */
    val hasEnable = false

    /**
     * 发送歌词（全参数）。
     *
     * @param lyric [String] 歌词
     * @param extra [ExtraData] 额外参数（包名 / 行时长 delay 等）
     */
    fun sendLyric(lyric: String, extra: ExtraData = ExtraData()) {}

    /** 清除歌词 */
    fun clearLyric() {}

    companion object {
        /** 协议版本（上游 BuildConfig.API_VERSION = 6，vendored 固化为常量） */
        const val API_VERSION = 6
    }
}
