package com.lxpro.core.designsystem.theme

/** 动效令牌（依据 16-动效与转场）。M3 转场引擎落地时按此取值。 */
object LXMotion {
    /** 环境动效（配色切换、氛围）时长 */
    const val ambient = 800

    /** 呼吸光斑周期（双光斑 15s : 19s，见 16 §2） */
    const val leakPrimaryPeriod = 15_000
    const val leakSecondaryPeriod = 19_000

    /** 颗粒抖动周期 */
    const val grainPeriod = 700

    /** 转场时长 */
    const val enterDetail = 320
    const val exitDetail = 260
    const val playerSlide = 380
    const val tabCrossFade = 220

    /** 返回视差比例（16 §4） */
    const val parallaxReturn = 0.28f
}