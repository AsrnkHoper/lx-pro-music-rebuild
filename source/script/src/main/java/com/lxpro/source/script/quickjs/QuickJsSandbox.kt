package com.lxpro.source.script.quickjs

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单个 lx 2.0 脚本的 QuickJS 沙箱（walnut 版 lx-music-mobile 的 QuickJS.java Kotlin 化）。
 *
 * 线程模型：每个沙箱一条专用 [HandlerThread]（"lxpro-sandbox-{id}"）——
 * wrapper 的 QuickJSContext **非线程安全**，所有 JS 调用（evaluate / callJS / destroy）
 * 与定时器回调都串行到该线程；独立线程也让单脚本死循环不阻塞其他脚本
 * （04 §3.4 线程规则的落地）。
 *
 * 桥接协议（preload.js 定义，与 walnut 一字不差）：
 * - JS → 宿主：全局 `__lx_native_call__(key, action, dataJson)`，dataJson 由 JS 侧
 *   `JSON.stringify` 产生，宿主解析后分发到 [host]
 * - 宿主 → JS：全局 `__lx_native__` 函数，参数 `(key, action, dataJson)`，JS 侧
 *   `JSON.parse(dataJson)`；action ∈ { request, response, __set_timeout__, __run_error__ }
 * - 原生工具函数：`__lx_native_call__utils_*` 系列（[LxCrypto]）与 `set_timeout`
 *   （postDelayed 回同线程触发 `__set_timeout__`）
 *
 * 生命周期：[load] → 若干 [callJS]（都经 [post] 进沙箱线程）→ [destroy]。
 */
class QuickJsSandbox(
    val scriptId: String,
    private val appContext: Context,
    private val host: Host,
) {
    /** JS → 宿主的 action 回调；在沙箱线程被调用，实现方自行做线程调度 */
    fun interface Host {
        fun onAction(action: String, dataJson: String)
    }

    private val thread = HandlerThread("lxpro-sandbox-$scriptId").apply { start() }
    private val handler = Handler(thread.looper)
    private val destroyed = AtomicBoolean(false)

    /** 桥接密钥：宿主→JS 调用必须携带，防脚本伪造宿主调用 */
    private val key = UUID.randomUUID().toString()

    @Volatile
    private var jsContext: QuickJSContext? = null

    private val preloadScript: String by lazy {
        appContext.assets.open("script/preload.js").use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    /** 进程级一次性加载 libquickjs.so */
    private val engineReady = run {
        QuickJSLoader.init()
        true
    }

    /**
     * 加载脚本：eval preload → lx_setup(元信息) → eval 用户脚本。
     * **只能在沙箱线程调用**（由 [post] 保证）。
     * @return null=成功；非 null=错误信息（脚本执行异常等）
     */
    fun load(meta: LxScriptMeta, script: String): String? {
        check(engineReady)
        jsContext?.destroy()
        val context = QuickJSContext.create()
        jsContext = context
        context.setConsole(console)
        injectNatives(context)
        context.evaluate(preloadScript)
        context.getGlobalObject().getJSFunction("lx_setup").call(
            key, meta.id, meta.name, meta.description, meta.version, meta.author, meta.homepage, script,
        )
        return try {
            context.evaluate(script)
            null
        } catch (e: Exception) {
            val message = e.message ?: "unknown script error"
            runCatching { callJSInternal("__run_error__", null) }
            Log.e(TAG, "load script failed: $message")
            message
        }
    }

    /** 宿主 → JS：调全局 `__lx_native__(key, action, dataJson)`；**只能在沙箱线程调用** */
    fun callJS(action: String, dataJson: String?): Any? = callJSInternal(action, dataJson)

    private fun callJSInternal(action: String, dataJson: String?): Any? {
        val context = jsContext ?: return null
        return try {
            if (dataJson == null) {
                context.getGlobalObject().getJSFunction("__lx_native__").call(key, action)
            } else {
                context.getGlobalObject().getJSFunction("__lx_native__").call(key, action, dataJson)
            }
        } catch (e: Exception) {
            val message = (e.message ?: "call script error").take(1024)
            Log.e(TAG, "callJS $action error: $message")
            host.onAction("log", "{\"type\":\"error\",\"log\":\"Call script error: $message\"}")
            null
        }
    }

    /** 销毁沙箱（幂等）：释放 context + 退出线程 */
    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        handler.post {
            runCatching { jsContext?.destroy() }
            jsContext = null
        }
        thread.quitSafely()
    }

    /** 沙箱线程执行入口（Manager 与 Host 都经此进入，保证串行） */
    fun post(block: () -> Unit) {
        if (!destroyed.get()) handler.post(block)
    }

    // ── 原生函数注入（preload.js 期望的全局集合）──

    private fun injectNatives(context: QuickJSContext) {
        val globals = context.getGlobalObject()

        // 通用桥：JS → 宿主（action + JSON 载荷）
        globals.setProperty("__lx_native_call__", JSCallFunction { args ->
            if (args.size >= 3 && args[0] == key) {
                host.onAction(args[1] as String, args[2] as String)
            }
            null
        })

        globals.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { args ->
            LxCrypto.str2b64(args[0] as String)
        })

        globals.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { args ->
            LxCrypto.b642buf(args[0] as String)
        })

        globals.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { args ->
            LxCrypto.str2md5(args[0] as String)
        })

        globals.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { args ->
            LxCrypto.aesEncrypt(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
        })

        globals.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { args ->
            LxCrypto.rsaEncrypt(args[0] as String, args[1] as String, args[2] as String)
        })

        // 定时器：postDelayed 回沙箱线程触发 __set_timeout__（wrapper 要求同线程）
        globals.setProperty("__lx_native_call__set_timeout", JSCallFunction { args ->
            val id = (args[0] as Number).toLong()
            val delay = (args[1] as Number).toLong()
            handler.postDelayed({ callJSInternal("__set_timeout__", id.toString()) }, delay)
            null
        })
    }

    private val console = object : QuickJSContext.Console {
        override fun log(message: String) = forward("log", message)
        override fun info(message: String) = forward("info", message)
        override fun warn(message: String) = forward("warn", message)
        override fun error(message: String) = forward("error", message)

        private fun forward(type: String, message: String) {
            host.onAction(
                "log",
                "{\"type\":\"$type\",\"log\":${kotlinx.serialization.json.JsonPrimitive(message)}}",
            )
        }
    }

    companion object {
        private const val TAG = "LxScript"
    }
}

/** 脚本元信息（导入时从头部注释解析；lx_setup 注入用） */
data class LxScriptMeta(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
)
