package com.lxpro.core.designsystem.tutorial

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 新手教程的高亮目标注册表（2026-10-27 用户要求：游戏式实机引导）。
 *
 * 真实控件只需挂 [Modifier.tutorialTarget] 把自己报上来，教程遮罩层就能在窗口坐标系里
 * 找到它的位置挖洞高亮 —— 页面本身完全不知道教程的存在，教程也不需要给页面传任何参数。
 *
 * 用 [mutableStateMapOf] 而不是普通 Map：遮罩层在 Composition 里读 Rect，控件移动/出现
 * 会触发重组，高亮框跟着走。
 */
object TutorialTargets {

    private val bounds = mutableStateMapOf<String, Rect>()

    /** 目标当前在窗口中的位置；没挂载（例如 Tab 栏被键盘藏起）时返回 null，遮罩会退化为全屏拦截 */
    fun rectOf(id: String): Rect? = bounds[id]

    fun update(id: String, rect: Rect) {
        if (bounds[id] != rect) bounds[id] = rect
    }

    fun clear(id: String) {
        bounds.remove(id)
    }

    fun clearAll() {
        bounds.clear()
    }
}

/**
 * 把这个控件注册为教程高亮目标。
 *
 * 位置用 [boundsInWindow]（窗口坐标），遮罩层自己也在窗口坐标里换算，跨页面不用关心偏移。
 * 控件离开组合（切页、Tab 栏被藏）时自动注销，避免拿旧位置挖错洞。
 */
fun Modifier.tutorialTarget(id: String): Modifier = composed {
    DisposableEffect(id) {
        onDispose { TutorialTargets.clear(id) }
    }
    onGloballyPositioned { coordinates ->
        TutorialTargets.update(id, coordinates.boundsInWindow())
    }
}
