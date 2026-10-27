package com.lxpro.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5：生成应用专属 Baseline Profile。
 *
 * 旅程仅覆盖「启动 → 首页首帧稳定」（稳优先）：启动路径是冷启动收益最大的区间，
 * 复杂旅程易因选择器漂移导致生成失败。产物 baseline-prof.txt 由 Gradle 拉到
 * benchmark/build/outputs/ 下，手动拷入 app/src/main/baselineProfiles/ 打包。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "com.lxpro.music",
    ) {
        pressHome()
        startActivityAndWait()
    }
}
