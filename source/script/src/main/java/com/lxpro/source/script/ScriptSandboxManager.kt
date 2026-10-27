package com.lxpro.source.script

import android.content.Context
import android.util.Log
import com.lxpro.source.script.quickjs.LxScriptMeta
import com.lxpro.source.script.quickjs.QuickJsSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 脚本沙箱管理器：多个 lx 2.0 脚本的生命周期与业务调用入口（04 §3.4 骨架落地）。
 *
 * - 每个脚本一条独立沙箱线程 + 独立 QuickJSContext（隔离死循环与崩溃）
 * - [load]：eval preload + 用户脚本 → 等 INIT（[ScriptHost.initResult]）→ 返回源声明
 * - [callAction]：App 侧发起 musicUrl / lyric / pic 请求（挂起，20s 兜底）
 * - [unload]：销毁沙箱（幂等）
 *
 * 线程：load/unload 在 IO 调度器等待沙箱线程完成；callAction 任意线程可调
 * （内部经 [QuickJsSandbox.post] 回沙箱线程）。
 */
class ScriptSandboxManager(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
) {
    private class Managed(
        val sandbox: QuickJsSandbox,
        val host: ScriptHost,
        val initInfo: LxInitInfo,
    )

    private val sandboxes = ConcurrentHashMap<String, Managed>()

    /** 加载脚本：返回 INIT 声明的源信息；失败（eval 异常 / INIT 超时 / 声明为空）抛 [ScriptException] */
    suspend fun load(meta: LxScriptMeta, script: String): LxInitInfo = withContext(Dispatchers.IO) {
        unload(meta.id)
        var host: ScriptHost? = null
        val sandbox = QuickJsSandbox(meta.id, appContext) { action, data ->
            host?.onAction(action, data)
        }
        host = ScriptHost(okHttpClient, sandbox)

        val loadError = CompletableResult<String?>()
        sandbox.post { loadError.complete(sandbox.load(meta, script)) }

        val error = loadError.await()
        if (error != null) {
            sandbox.destroy()
            throw ScriptException(error)
        }
        val initInfo = try {
            withTimeout(INIT_TIMEOUT_MS) { host.initResult.await() }
        } catch (e: Exception) {
            sandbox.destroy()
            throw ScriptException("script init timeout or failed")
        }
        if (initInfo == null || initInfo.sources.isEmpty()) {
            sandbox.destroy()
            throw ScriptException("script declared no usable sources")
        }
        sandboxes[meta.id] = Managed(sandbox, host, initInfo)
        Log.i(TAG, "script loaded: ${meta.name} sources=${initInfo.sources.keys}")
        initInfo
    }

    /** 卸载脚本（幂等） */
    fun unload(scriptId: String) {
        sandboxes.remove(scriptId)?.sandbox?.destroy()
    }

    /** 已加载脚本的 INIT 源声明 */
    fun initInfoOf(scriptId: String): LxInitInfo? = sandboxes[scriptId]?.initInfo

    /** 业务调用：`action` ∈ musicUrl / lyric / pic（返回 preload 校验后的完整响应，取 data 字段用） */
    suspend fun callAction(
        scriptId: String,
        source: String,
        action: String,
        info: JsonElement,
    ): JsonElement {
        val managed = sandboxes[scriptId]
            ?: throw ScriptException("script not loaded: $scriptId")
        return managed.host.callAction(source, action, info)
    }

    /** 取响应里的 `data` 字段（musicUrl → {type,url}；pic → url 字符串；lyric → 歌词对象） */
    fun responseData(response: JsonElement): JsonElement? =
        runCatching { response.jsonObject["data"] }.getOrNull()

    /** 取 musicUrl 响应里的 URL（preload 已校验 http(s) 前缀与长度） */
    fun musicUrlOf(response: JsonElement): String? = runCatching {
        responseData(response)?.jsonObject?.get("url")?.jsonPrimitive?.content
    }.getOrNull()

    /** 取 pic 响应里的 URL 字符串 */
    fun picUrlOf(response: JsonElement): String? = runCatching {
        responseData(response)?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        private const val TAG = "LxScript"

        /** 等 INIT 的兜底（脚本加载后不调 lx.send('inited') 就失败） */
        private const val INIT_TIMEOUT_MS = 10_000L

        /**
         * 解析脚本头部注释的元信息（walnut data.ts 同款规则：取首个块注释，
         * 逐行匹配 `@key: value`，LX-Music 社区脚本的标准头——块注释内
         * 每行形如「@name 音源名」「@version 1.0.0」等）
         */
        fun parseScriptMeta(script: String, fallbackId: String): LxScriptMeta {
            val header = Regex("^/\\*[\\S\\s]+?\\*/").find(script)?.value ?: ""
            val fields = mutableMapOf<String, String>()
            Regex("^\\s*\\*?\\s*@([\\w-]+)\\s*[::]?\\s*(.+)$", RegexOption.MULTILINE)
                .findAll(header)
                .forEach { match -> fields[match.groupValues[1]] = match.groupValues[2].trim() }
            return LxScriptMeta(
                id = fields["id"] ?: fallbackId,
                name = fields["name"] ?: "未命名脚本",
                description = fields["description"] ?: "",
                version = fields["version"] ?: "",
                author = fields["author"] ?: "",
                homepage = fields["homepage"] ?: "",
            )
        }
    }
}

/** load 结果的简单句柄 */
private class CompletableResult<T> {
    private val deferred = kotlinx.coroutines.CompletableDeferred<T>()
    fun complete(value: T): Boolean = deferred.complete(value)
    suspend fun await(): T = deferred.await()
}
