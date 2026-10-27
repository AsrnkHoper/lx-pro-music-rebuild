package com.lxpro.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5 帧率硬指标：EQ 校正曲线目录（142 行，全项目最重列表）滚动。
 *
 * ⚠️ 不用 FrameTimingMetric：本机（HyperOS/MIUI）perfetto trace 缺 frame id，
 * benchmark-macro 1.4.1 / 1.5.0-alpha05 均抛「Observed frame in trace missing id」。
 * 方案：本测试只负责「导航 + 滚动」，帧数据由宿主机
 * `adb shell dumpsys gfxinfo com.lxpro.music framestats` 采集（同一份 HWUI 帧统计）。
 */
@RunWith(AndroidJUnit4::class)
class EqScrollBenchmarks {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun scrollEqCatalog() {
        // ⚠️ 必须 force-stop 冷启动：singleTop 下若任务栈残留，am start 会恢复
        // 上次停留的全屏路由（EQ 页/播放页），底部 Tab 不存在，「设置」永远找不到
        device.executeShellCommand("am force-stop com.lxpro.music")
        device.executeShellCommand("am start -n com.lxpro.music/.MainActivity")
        device.wait(Until.hasObject(By.pkg("com.lxpro.music")), 5_000)
        // 等底部 Tab 渲染（首帧后再找「设置」，双保险）
        if (!device.wait(Until.hasObject(By.text("设置")), 10_000)) {
            error("冷启动后未落在主页：找不到底部 Tab「设置」")
        }

        clickText("设置", 8_000)
        clickText("播放与音效", 8_000)
        clickText("均衡器", 8_000)

        if (!device.wait(Until.hasObject(By.text("正常（直通）")), 10_000)) {
            error("EQ 目录未渲染：找不到「正常（直通）」置顶行")
        }

        // 滚动前清掉历史帧统计，保证 gfxinfo 只含本次滚动
        device.executeShellCommand("dumpsys gfxinfo com.lxpro.music reset")

        // ⚠️ 不持有 UiObject2 跨帧复用：Compose 重组替换无障碍节点会 StaleObjectException
        fun flingOnce(dir: Direction) {
            val list = device.wait(Until.findObject(By.scrollable(true)), 5_000)
                ?: error("找不到可滚动列表")
            list.setGestureMargin(device.displayWidth / 20)
            list.fling(dir)
            device.waitForIdle()
        }
        repeat(6) { flingOnce(Direction.DOWN) }
        repeat(6) { flingOnce(Direction.UP) }

        // 等最后一帧落盘，宿主随后拉 gfxinfo
        Thread.sleep(1_000)
    }

    private fun clickText(text: String, timeout: Long) {
        val obj = device.wait(Until.findObject(By.text(text)), timeout)
            ?: error("找不到「$text」")
        obj.click()
        device.waitForIdle()
    }
}
