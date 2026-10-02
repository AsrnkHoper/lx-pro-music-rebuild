package com.lxpro.core.designsystem.theme

/**
 * 动效令牌（依据 `16-动效与转场`）。
 *
 * ⚠️ 基础令牌来自 16 §1（沿用 11 §7.3），**不要自造时长**：
 * instant / quick / standard / emphasis 四档覆盖全部功能性动效。
 */
object LXMotion {
    // ── 基础令牌（16 §1）──
    const val instant = 100
    const val quick = 200
    const val standard = 300
    const val emphasis = 400

    /** 背景色渐变 */
    const val ambient = 800

    /** 列表项入场逐项步进（16 §4） */
    const val staggerStep = 40

    /** 呼吸光斑周期（双光斑 15s : 19s，见 16 §2） */
    const val leakPrimaryPeriod = 15_000
    const val leakSecondaryPeriod = 19_000

    /** 颗粒抖动周期 */
    const val grainPeriod = 700

    // ── 转场（16 §4）──
    /** Tab 切换：交叉淡入淡出（不做横向滑动，避免与返回手势冲突） */
    const val tabCrossFade = quick

    /** 进详情（歌单/歌手/专辑）：从右侧滑入 */
    const val enterDetail = standard

    /** 返回：向右滑出 */
    const val exitDetail = quick

    /** 打开播放页：从底部滑入全屏（对齐「从迷你条上拉」的直觉） */
    const val playerEnter = standard

    /** 关闭播放页：向下滑出 */
    const val playerExit = quick

    /** 底部弹层 */
    const val sheetEnter = standard

    /** 弹层遮罩淡入 */
    const val scrimFade = 260

    /** 返回视差比例（16 §4）：上一页轻微左移 */
    const val parallaxReturn = 0.28f
}