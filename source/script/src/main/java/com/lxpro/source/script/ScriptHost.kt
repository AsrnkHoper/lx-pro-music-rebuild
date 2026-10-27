package com.lxpro.source.script

import android.util.Base64
import android.util.Log
import com.lxpro.source.script.quickjs.QuickJsSandbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 沙箱宿主：实现 preload.js 的 `api-action` 协议（04 §2.6）——
 * walnut 版 RN 胶水层的 Kotlin/OkHttp 等价物。
 *
 * 两层 requestKey 注册表（名字相同但空间独立，勿混）：
 * 1. **业务请求**（App → 脚本）：[pendingActions]，[callAction] 发起，脚本经
 *    `response` action 回传结果（preload 已按 04 §2.7 校验/截断）；
 * 2. **网络请求**（脚本 → 宿主）：[pendingHttp]，脚本 `lx.request` 发起，
 *    OkHttp 完成后经 `__lx_native__('response', ...)` 回灌。
 *
 * 线程：[QuickJsSandbox.Host.onAction] 在沙箱线程被调；OkHttp 回调在自己的线程，
 * 回灌 JS 一律 `sandbox.post` 回沙箱线程（wrapper 的 context 非线程安全）。
 */
class ScriptHost(
    private val okHttpClient: OkHttpClient,
    private val sandbox: QuickJsSandbox,
) : QuickJsSandbox.Host {

    /** 脚本 INIT 事件（sources 声明）的等待句柄 */
    val initResult = CompletableDeferred<LxInitInfo?>()

    /** 业务请求（App → 脚本）：requestKey → 等待脚本 response 的 deferred */
    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    /** 网络请求（脚本 → 宿主）：requestKey → 进行中的 OkHttp Call */
    private val pendingHttp = ConcurrentHashMap<String, Call>()

    private val json = Json { ignoreUnknownKeys = true }

    // ── JS → 宿主 action 分发（沙箱线程）──

    override fun onAction(action: String, dataJson: String) {
        when (action) {
            "init" -> handleInit(dataJson)
            "request" -> handleHttpRequest(dataJson)
            "cancelRequest" -> handleCancelRequest(dataJson)
            "response" -> handleActionResponse(dataJson)
            "log" -> Log.d(TAG, "script log: $dataJson")
            "showUpdateAlert" -> Log.w(TAG, "script update alert: $dataJson")
            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    /** init：脚本声明了哪些源（kw/kg/tx/wy/mg…）、各支持什么 action 与音质 */
    private fun handleInit(dataJson: String) {
        val result = runCatching {
            val obj = json.parseToJsonElement(dataJson).jsonObject
            LxInitInfo.parse(obj)
        }.getOrNull()
        initResult.complete(result)
    }

    /** request：脚本要发网络请求（宿主代理，脚本无网络权限） */
    private fun handleHttpRequest(dataJson: String) {
        val payload = runCatching { json.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return
        val requestKey = payload["requestKey"]?.jsonPrimitive?.content ?: return
        val url = payload["url"]?.jsonPrimitive?.content ?: return
        val options = payload["options"]?.jsonObject ?: JsonObject(emptyMap())

        val call = try {
            buildCall(url, options)
        } catch (e: Exception) {
            respondToScript(requestKey, error = e.message ?: "bad request", response = null)
            return
        }
        pendingHttp[requestKey] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                sandbox.post {
                    if (pendingHttp.remove(requestKey, call)) {
                        respondToScript(requestKey, error = e.message ?: "network error", response = null)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val responseJson = runCatching { responseToJson(resp, options) }
                        .getOrElse { e ->
                            respondToScript(requestKey, error = e.message ?: "read error", response = null)
                            return
                        }
                    sandbox.post {
                        if (pendingHttp.remove(requestKey, call)) {
                            respondToScript(requestKey, error = null, response = responseJson)
                        }
                    }
                }
            }
        })
    }

    /** cancelRequest：脚本调用了 lx.request 返回的取消函数（载荷是纯 requestKey 字符串） */
    private fun handleCancelRequest(dataJson: String) {
        val requestKey = runCatching { json.parseToJsonElement(dataJson).jsonPrimitive.content }
            .getOrElse { dataJson.trim('"') }
        pendingHttp.remove(requestKey)?.cancel()
    }

    /** response：脚本对业务请求（musicUrl/lyric/pic）的应答 */
    private fun handleActionResponse(dataJson: String) {
        val payload = runCatching { json.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return
        val requestKey = payload["requestKey"]?.jsonPrimitive?.content ?: return
        val deferred = pendingActions.remove(requestKey) ?: return
        if (payload["status"]?.jsonPrimitive?.booleanOrNull == true) {
            // preload 的应答包是 {requestKey, status, result:{source, action, data}}——
            // 业务层要的是 result（callAction 文档语义），不是整个应答包
            deferred.complete(payload["result"] ?: payload)
        } else {
            deferred.completeExceptionally(
                ScriptException(payload["errorMessage"]?.jsonPrimitive?.content ?: "script request failed"),
            )
        }
    }

    // ── 业务入口（App → 脚本）──

    /**
     * 调用脚本的 request 回调（`lx.on(lx.EVENT_NAMES.request)`）并等待结果。
     * 返回 preload 校验后的完整响应 `{ source, action, data }`，调用方按 action 取 data。
     * @param info 请求载荷（musicInfo / type 等脚本侧字段）
     */
    suspend fun callAction(source: String, action: String, info: JsonElement): JsonElement {
        val requestKey = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonElement>()
        pendingActions[requestKey] = deferred
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("data", buildJsonObject {
                put("source", source)
                put("action", action)
                put("info", info)
            })
        }
        sandbox.post { sandbox.callJS("request", payload.toString()) }
        return try {
            withTimeout(ACTION_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingActions.remove(requestKey)
            throw ScriptException("script action timeout (${ACTION_TIMEOUT_MS / 1000}s)")
        } finally {
            pendingActions.remove(requestKey)
        }
    }

    // ── lx.request 的 OkHttp 实现（语义 = walnut request.js）──

    /**
     * 构造 OkHttp Call。header 合并（walnut 语义）：脚本 headers 覆盖默认 Accept，
     * 再整体覆盖默认 UA；POST 无 Content-Type 时按 form → urlencoded /
     * formData → multipart / json + stringify 推断。
     */
    private fun buildCall(url: String, options: JsonObject): Call {
        val method = (options["method"]?.jsonPrimitive?.content ?: "get").uppercase()
        val timeoutMs = (options["timeout"]?.jsonPrimitive?.longOrNull ?: 13_000L)
            .coerceIn(1L, MAX_REQUEST_TIMEOUT_MS)

        val headers = LinkedHashMap<String, String>()
        headers[DEFAULT_USER_AGENT_KEY] = DEFAULT_USER_AGENT
        options["headers"]?.jsonObject?.forEach { (name, value) ->
            headers[name] = value.jsonPrimitive.content
        }
        headers.putIfAbsent("Accept", "application/json")

        var formBodyText: String? = null
        var multipart: JsonObject? = null
        if (method == "POST" && !headers.containsKey("Content-Type")) {
            when {
                options["form"] is JsonObject -> {
                    headers["Content-Type"] = "application/x-www-form-urlencoded"
                    formBodyText = formEncode(options["form"]!!.jsonObject)
                }
                options["formData"] is JsonObject -> {
                    headers["Content-Type"] = "multipart/form-data"
                    multipart = options["formData"]!!.jsonObject
                }
                else -> headers["Content-Type"] = "application/json"
            }
        }

        val contentType = headers["Content-Type"]
        val requestBody: okhttp3.RequestBody? = when {
            method == "GET" || method == "HEAD" -> null
            multipart != null -> multipartBody(multipart)
            else -> {
                val text = formBodyText ?: options["body"]?.let { body ->
                    // json body 自动 stringify（walnut 语义）；字符串原样
                    if (body is JsonPrimitive) body.content else body.toString()
                } ?: ""
                text.toRequestBody(contentType?.toMediaType())
            }
        }

        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(method, requestBody)
            .build()

        // lx.request 的 per-request 超时：newBuilder 只建视图不新建连接池
        val timedClient = okHttpClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return timedClient.newCall(request)
    }

    /** 响应转 JS 对象：{ statusCode, statusMessage, headers, body }；文本自动尝试 JSON.parse（失败保留原文），binary → base64 */
    private fun responseToJson(resp: Response, options: JsonObject): JsonElement {
        val binary = options["binary"]?.jsonPrimitive?.booleanOrNull == true
        val bodyElement: JsonElement = resp.body?.let { body ->
            if (binary) {
                JsonPrimitive(Base64.encodeToString(body.bytes(), Base64.NO_WRAP))
            } else {
                val text = body.string()
                runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
            }
        } ?: JsonNull
        return buildJsonObject {
            put("statusCode", resp.code)
            put("statusMessage", resp.message)
            put(
                "headers",
                buildJsonObject {
                    for ((name, value) in resp.headers) {
                        // RN fetch 的 Headers.map 键为小写——脚本按小写取值，对齐
                        put(name.lowercase(), value) // 同名多值取首值（脚本只用单值场景）
                    }
                },
            )
            // walnut RN fetchData 语义补齐：ok（2xx）与最终 URL（重定向后）
            put("ok", resp.code in 200..299)
            put("url", resp.request.url.toString())
            put("body", bodyElement)
        }
    }

    private fun formEncode(form: JsonObject): String =
        form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v.jsonPrimitive.content, "UTF-8")
        }

    private fun multipartBody(formData: JsonObject): MultipartBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        formData.forEach { (name, value) ->
            builder.addFormDataPart(name, value.jsonPrimitive.content)
        }
        return builder.build()
    }

    /** 宿主 → JS：HTTP 响应回灌（`__lx_native__('response', ...)`） */
    private fun respondToScript(requestKey: String, error: String?, response: JsonElement?) {
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
            put("response", response ?: JsonNull)
        }
        sandbox.post { sandbox.callJS("response", payload.toString()) }
    }

    companion object {
        private const val TAG = "LxScript"

        /** 业务请求（musicUrl 等）兜底超时：walnut 同款 20s */
        private const val ACTION_TIMEOUT_MS = 20_000L

        /** lx.request 超时上限（preload.js 同样限制 60s） */
        private const val MAX_REQUEST_TIMEOUT_MS = 60_000L

        private const val DEFAULT_USER_AGENT_KEY = "User-Agent"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/69.0.3497.100 Safari/537.36"
    }
}

/** 脚本业务失败（脚本返回 errorMessage / 超时 / 网络失败） */
class ScriptException(message: String) : Exception(message)

/**
 * 脚本 INIT 声明（preload.js handleInit 输出）：
 * `{ status, info: { sources: { kw: { type, actions: [], qualitys: [] } } } }`
 */
data class LxInitInfo(
    val sources: Map<String, LxSourceInfo>,
) {
    data class LxSourceInfo(
        val type: String,
        val actions: List<String>,
        val qualitys: List<String>,
    )

    companion object {
        fun parse(obj: JsonObject): LxInitInfo? {
            val sourcesObj = obj["info"]?.jsonObject?.get("sources")?.jsonObject ?: return null
            val sources = buildMap {
                sourcesObj.forEach { (id, value) ->
                    val vo = value.jsonObject
                    put(
                        id,
                        LxSourceInfo(
                            type = vo["type"]?.jsonPrimitive?.content ?: "music",
                            actions = vo.stringList("actions"),
                            qualitys = vo.stringList("qualitys"),
                        ),
                    )
                }
            }
            return LxInitInfo(sources)
        }

        private fun JsonObject.stringList(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    }
}
