package com.lxpro.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5 启动硬指标（06 §M5）：冷启动 < 1s、热启动 < 300ms。
 *
 * CompilationMode.DEFAULT = 以设备当前编译状态测量（release 装机即 R8+JIT/AOT 混合），
 * 与真实用户场景一致；Baseline Profile 生成后复跑本类可对比收益。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = startup(StartupMode.COLD)

    @Test
    fun warmStartup() = startup(StartupMode.WARM)

    private fun startup(mode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = "com.lxpro.music",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = mode,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
