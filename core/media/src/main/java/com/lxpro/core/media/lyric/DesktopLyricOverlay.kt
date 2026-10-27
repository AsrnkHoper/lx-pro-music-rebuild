package com.lxpro.core.media.lyric

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.lxpro.core.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 桌面歌词悬浮窗（对齐 LX Music desktopLyric.* 全集）。
 *
 * 配置项：
 * - enable（开关）/ isLock（锁定防误触）/ fontSize / alignment / opacity
 * - playedColor（主行=当前高亮行）/ pendingColor（翻译/占位）
 * 所有配置变化实时生效，不用重建窗口。
 */
@Singleton
class DesktopLyricOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lyricsCenter: LyricsCenter,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var container: LinearLayout? = null
    private var mainText: TextView? = null
    private var translationText: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    init {
        android.util.Log.i(TAG, "DesktopLyricOverlay created, subscribing config flows")
        // 开关 → 加/减悬浮窗
        scope.launch {
            settings.desktopLyricEnabled.collectLatest { enabled ->
                if (enabled) show() else hide()
            }
        }
        // 歌词行 → 刷新文本与可见性
        scope.launch {
            lyricsCenter.state.collectLatest { st -> render(st) }
        }
        // 6 个外观配置各自独立 collect —— 任一 Flow 变化都触发 applyConfig
        // 不用嵌套 combine（实测 combine 在 DataStore 全量 emit 场景下会漏触发）
        applyOnChange(settings.desktopLyricLock, "lock")
        applyOnChange(settings.desktopLyricFontSize, "fontSize")
        applyOnChange(settings.desktopLyricAlignment, "alignment")
        applyOnChange(settings.desktopLyricOpacity, "opacity")
        applyOnChange(settings.desktopLyricPlayedColor, "playedColor")
        applyOnChange(settings.desktopLyricPendingColor, "pendingColor")
    }

    /** 订阅单个外观配置 Flow，变化时读所有最新值统一 applyConfig */
    private fun <T> applyOnChange(flow: kotlinx.coroutines.flow.Flow<T>, name: String) {
        scope.launch {
            flow.collect { _ ->
                applyConfigNow()
            }
        }
    }

    /** 直接从 DataStore 读所有最新值并 apply —— 所有外观配置的统一入口 */
    private suspend fun applyConfigNow() {
        applyConfig(
            lock = settings.desktopLyricLock.first(),
            fontSize = settings.desktopLyricFontSize.first(),
            alignment = settings.desktopLyricAlignment.first(),
            opacity = settings.desktopLyricOpacity.first(),
            playedColor = settings.desktopLyricPlayedColor.first(),
            pendingColor = settings.desktopLyricPendingColor.first(),
        )
    }

    /** 直接用回调传来的值更新悬浮窗，不用再 DataStore.first() 重读（避免时序上读到旧值） */
    private suspend fun applyConfig(
        lock: Boolean,
        fontSize: Int,
        alignment: Int,
        opacity: Int,
        playedColor: Int,
        pendingColor: Int,
    ) {
        val main = mainText ?: run {
            android.util.Log.w(TAG, "applyConfig: container null, skipping")
            return
        }
        val translation = translationText ?: return
        val column = container ?: return
        val lp = params ?: return

        // 1. 字号（翻译行比主行小 4sp，最小 10 防过小）
        main.textSize = fontSize.toFloat()
        translation.textSize = (fontSize - 4).coerceAtLeast(10).toFloat()

        // 2. 颜色（主行=已播放行，翻译行=未播放行）
        main.setTextColor(playedColor)
        translation.setTextColor(pendingColor)

        // 3. 透明度（整个 column 一起调，含文字 + 阴影）
        column.alpha = opacity / 100f

        // 4. 对齐（同时影响 TextView 内部文字对齐 + LinearLayout 内子 view 的水平位置）
        val textGravity = when (alignment) {
            0 -> Gravity.CENTER_VERTICAL or Gravity.LEFT
            2 -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
            else -> Gravity.CENTER
        }
        main.gravity = textGravity
        translation.gravity = textGravity
        // LinearLayout 里子 view 的水平位置：必须改 LayoutParams.gravity 才能真的移动 TextView
        val childGravity = when (alignment) {
            0 -> Gravity.START
            2 -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        (main.layoutParams as? LinearLayout.LayoutParams)?.gravity = childGravity
        (translation.layoutParams as? LinearLayout.LayoutParams)?.gravity = childGravity

        // 5. 悬浮窗屏幕位置（WindowManager.LayoutParams）
        lp.gravity = Gravity.TOP or childGravity
        runCatching { windowManager.updateViewLayout(column, lp) }
        column.requestLayout()
        main.requestLayout()
        translation.requestLayout()

        // 6. 锁定位置（开了清 OnTouchListener，禁止拖动）
        if (lock) {
            column.setOnTouchListener(null)
        } else {
            attachDrag(column, lp)
        }
    }

    private suspend fun show() {
        if (!Settings.canDrawOverlays(context)) return
        if (container != null) return

        val density = context.resources.displayMetrics.density
        // 先读一遍 DataStore 所有外观值——applyConfig 需要参数（不再 .first() 重读）
        val fontSize = settings.desktopLyricFontSize.first()
        val align = settings.desktopLyricAlignment.first()
        val played = settings.desktopLyricPlayedColor.first()
        val pending = settings.desktopLyricPendingColor.first()
        val opacity = settings.desktopLyricOpacity.first()
        val lock = settings.desktopLyricLock.first()

        val textGravity = when (align) {
            0 -> Gravity.CENTER_VERTICAL or Gravity.LEFT
            2 -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
            else -> Gravity.CENTER
        }

        val main = TextView(context).apply {
            textSize = fontSize.toFloat()
            setTextColor(played)
            setShadowLayer(4f, 0f, 2f, 0x80000000.toInt())
            gravity = textGravity
            text = PLACEHOLDER
        }
        val translation = TextView(context).apply {
            textSize = (fontSize - 4).coerceAtLeast(10).toFloat()
            setTextColor(pending)
            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
            gravity = textGravity
            visibility = View.GONE
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (16 * density).toInt()
            setPadding(pad, (6 * density).toInt(), pad, (6 * density).toInt())
            addView(main)
            addView(translation)
        }

        val childGravity = when (align) {
            0 -> Gravity.START
            2 -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or childGravity
            y = settings.desktopLyricOffsetY.first() ?: (DEFAULT_TOP_OFFSET_DP * density).toInt()
        }

        attachDrag(column, lp)
        runCatching { windowManager.addView(column, lp) }
        container = column
        mainText = main
        translationText = translation
        params = lp

        // 所有外观（含 opacity、lock、对齐 LayoutParams）交给 applyConfig 统一设置
        // 避免 show() 和 applyConfig 各自设置、容易漏项
        applyConfig(
            lock = lock,
            fontSize = fontSize,
            alignment = align,
            opacity = opacity,
            playedColor = played,
            pendingColor = pending,
        )
    }

    private fun hide() {
        container?.let { runCatching { windowManager.removeView(it) } }
        container = null
        mainText = null
        translationText = null
        params = null
    }

    private fun render(st: LyricsCenter.State) {
        val main = mainText ?: return
        val translation = translationText ?: return
        val column = container ?: return
        val line = st.currentLine
        when {
            line != null -> {
                column.visibility = View.VISIBLE
                main.text = lyricsCenter.convertForDisplay(line.text)
                if (line.translation.isNullOrBlank()) {
                    translation.visibility = View.GONE
                } else {
                    translation.visibility = View.VISIBLE
                    val t = line.translation
                    translation.text = if (t != null) lyricsCenter.convertForDisplay(t) else ""
                }
            }
            // 在播但当前无行（前奏/纯音乐/无歌词）：整窗隐藏，不闪现占位
            st.songKey.isNotEmpty() -> column.visibility = View.GONE
            // 没在播：占位提示，顺便让用户确认位置（对齐 LX 的桌面歌词占位）
            else -> {
                column.visibility = View.VISIBLE
                main.text = PLACEHOLDER
                translation.visibility = View.GONE
            }
        }
    }

    /** 垂直拖动调位置；松手写回 DataStore（下次启动还在原处） */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var downRawY = 0f
        var downParamY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downParamY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.y = downParamY + (event.rawY - downRawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (lp.y != downParamY) {
                        scope.launch { settings.setDesktopLyricOffsetY(lp.y) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val TAG = "DesktopLyricOverlay"
        const val PLACEHOLDER = "桌面歌词已开启，可拖动调整位置"
        const val DEFAULT_TOP_OFFSET_DP = 48
    }
}
