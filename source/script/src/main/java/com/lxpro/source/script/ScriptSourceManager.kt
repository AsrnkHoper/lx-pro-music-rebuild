package com.lxpro.source.script

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lxpro.core.database.dao.UserApiScriptDao
import com.lxpro.core.database.entity.UserApiScriptEntity
import com.lxpro.core.model.Quality
import com.lxpro.core.model.SourceLabels
import com.lxpro.source.api.SourceAction
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 设置页「我的脚本」一行的状态（真实数据：Room + 沙箱加载结果） */
data class ScriptEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    /** 开关 = 是否加载并注册到音源表（落 Room.enabled） */
    val enabled: Boolean,
    /** 源描述（"酷我 · 网易 · QQ"）；加载失败为空 + [loadError] 非空 */
    val sourcesDesc: String,
    val loadError: String?,
)

/**
 * 脚本音源编排器（M2 #6-7）：导入 / 启停 / 删除 / 启动恢复 + 沙箱与注册表的粘合。
 *
 * 数据流：
 * - 导入：SAF uri 读文本 → [ScriptSandboxManager.parseScriptMeta] → 脚本正文落
 *   `files/scripts/{id}.js` → Room 插入 → 立即加载注册（错误也保留记录，UI 可见）
 * - 启停：Room.enabled 翻转 → load+register / unload+unregister
 * - 启动恢复：[start] 由 App 冷启时调一次，逐个加载 enabled 脚本（坏脚本不影响其他）
 *
 * 每个脚本按 INIT 声明注册 N 个 [ScriptSourceApi]（kw/wy/tx…各一）。
 */
@Singleton
class ScriptSourceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val dao: UserApiScriptDao,
    private val registry: SourceRegistry,
) {
    private val sandbox = ScriptSandboxManager(context, okHttpClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 已注册的脚本源（scriptId → apis），unload 时反注册 */
    private val registeredApis = ConcurrentHashMap<String, List<ScriptSourceApi>>()

    /** 脚本加载错误（scriptId → 消息），驱动 UI 的「加载失败」标记 */
    private val loadErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _scripts = MutableStateFlow<List<ScriptEntry>>(emptyList())

    /** 设置页状态流：Room 为主，叠加内存中的加载结果 */
    val scripts: StateFlow<List<ScriptEntry>> = _scripts.asStateFlow()

    init {
        scope.launch {
            dao.observeAll().collect { entities ->
                val errors = loadErrors.value
                _scripts.value = entities.map { e -> e.toEntry(errors) }
            }
        }
    }

    /** App 冷启时调一次：恢复全部 enabled 脚本（坏脚本吞掉并记错误，不影响其他） */
    fun start() {
        scope.launch {
            dao.enabledScripts().forEach { entity ->
                loadSafely(entity)
            }
        }
    }

    /**
     * 内置源的取流委托（批十八）：找到声明了 [sourceId] 的已加载脚本源。
     * 与 walnut 一致 —— 内置源只有数据接口，getMusicUrl 交给 user api 脚本。
     */
    fun apiForSource(sourceId: String): ScriptSourceApi? =
        registeredApis.values.asSequence().flatten().firstOrNull { it.id.value == sourceId }

    /**
     * 从 SAF uri 导入脚本。
     * @return null=成功；非 null=失败原因（UI 直接展示）
     */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        val script = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return@withContext "无法读取所选文件"

        val meta = ScriptSandboxManager.parseScriptMeta(script, fallbackId = "user_api_${System.currentTimeMillis()}")
        // 同名/同 id 覆盖导入：先卸载旧的
        dao.byId(meta.id)?.let { old -> unloadAndUnregister(old.id) }

        val dir = File(context.filesDir, "scripts").apply { mkdirs() }
        val file = File(dir, "${meta.id}.js")
        file.writeText(script)

        val entity = UserApiScriptEntity(
            id = meta.id,
            name = meta.name,
            version = meta.version,
            author = meta.author,
            description = meta.description,
            filePath = file.absolutePath,
            sources = "",
            enabled = true,
            importedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)

        // 立即加载（失败保留记录：用户能看到具体错误，修好脚本后重开开关即可重试）
        val error = loadSafely(entity)
        if (error != null) Log.w(TAG, "script import load failed: $error")
        return@withContext error
    }

    /** 启停脚本：开 = 加载并注册；关 = 卸载并反注册 */
    suspend fun setEnabled(scriptId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entity = dao.byId(scriptId) ?: return@withContext
        dao.update(entity.copy(enabled = enabled))
        if (enabled) loadSafely(entity) else unloadAndUnregister(scriptId)
    }

    /**
     * 单选激活脚本（批二十）：启用一个脚本时自动停用其余脚本。
     * 用于「当前取流脚本」的唯一性约束 —— 播放时只能有一个脚本负责 getMusicUrl。
     * @return 被停用的脚本名列表（用于 UI 提示）；脚本不存在返回 null
     */
    suspend fun setActive(scriptId: String): List<String>? = withContext(Dispatchers.IO) {
        val entity = dao.byId(scriptId) ?: return@withContext null
        // 先停用其他所有脚本
        val others = dao.enabledScripts().filter { it.id != scriptId }
        val stoppedNames = others.map { it.name }
        others.forEach { other ->
            dao.update(other.copy(enabled = false))
            unloadAndUnregister(other.id)
        }
        // 再启用目标脚本
        dao.update(entity.copy(enabled = true))
        loadSafely(entity)
        stoppedNames
    }

    /** 删除脚本：卸载 + 删文件 + 删记录 */
    suspend fun remove(scriptId: String) = withContext(Dispatchers.IO) {
        unloadAndUnregister(scriptId)
        dao.byId(scriptId)?.let { entity ->
            dao.delete(entity)
            runCatching { File(entity.filePath).delete() }
        }
    }

    /**
     * 测试脚本是否可用（批十九）：重新加载脚本并检查 INIT 是否成功。
     * @return null=成功；非 null=失败原因
     */
    suspend fun testScript(scriptId: String): String? = withContext(Dispatchers.IO) {
        val entity = dao.byId(scriptId) ?: return@withContext "脚本不存在"
        // 先卸载再重新加载，验证脚本能否正常初始化
        unloadAndUnregister(scriptId)
        loadSafely(entity)
    }

    // ── 内部 ──

    /** 加载一个脚本并注册其全部源；失败记入 loadErrors（返回错误信息） */
    private suspend fun loadSafely(entity: UserApiScriptEntity): String? {
        val meta = com.lxpro.source.script.quickjs.LxScriptMeta(
            id = entity.id, name = entity.name, description = entity.description,
            version = entity.version, author = entity.author,
        )
        return try {
            val initInfo = sandbox.load(meta, File(entity.filePath).readText())
            val apis = initInfo.sources.map { (sourceId, info) ->
                ScriptSourceApi(
                    scriptId = entity.id,
                    id = com.lxpro.core.model.SourceId(sourceId),
                    name = SourceLabels.of(sourceId),
                    declaredActions = info.actions.mapNotNull(::parseAction).toSet(),
                    qualities = info.qualitys.mapNotNull(::parseQuality),
                    manager = sandbox,
                )
            }
            registeredApis[entity.id] = apis
            apis.forEach { registry.register(it) }
            loadErrors.value = loadErrors.value - entity.id
            // 回填 sources 供 UI 摘要（失败重试后也走这里）
            dao.byId(entity.id)?.let { current ->
                dao.update(current.copy(sources = initInfo.sources.keys.joinToString(",")))
            }
            Log.i(TAG, "script registered: ${entity.name} → ${initInfo.sources.keys}")
            null
        } catch (e: Exception) {
            val message = e.message ?: "加载失败"
            loadErrors.value = loadErrors.value + (entity.id to message)
            Log.e(TAG, "script load failed: ${entity.name}: $message")
            message
        }
    }

    private fun unloadAndUnregister(scriptId: String) {
        registeredApis.remove(scriptId)?.forEach { registry.unregister(it.id) }
        sandbox.unload(scriptId)
        loadErrors.value = loadErrors.value - scriptId
    }

    private fun UserApiScriptEntity.toEntry(errors: Map<String, String>): ScriptEntry {
        val sourcesDesc = if (sources.isBlank()) "" else
            sources.split(",").joinToString(" · ") { SourceLabels.of(it.trim()) }
        return ScriptEntry(
            id = id,
            name = name,
            version = version,
            author = author,
            description = description,
            enabled = enabled,
            sourcesDesc = sourcesDesc,
            loadError = errors[id],
        )
    }

    companion object {
        private const val TAG = "LxScript"

        /** lx 协议的 action 字符串 → SourceAction */
        private fun parseAction(value: String): SourceAction? = when (value) {
            "musicUrl" -> SourceAction.MUSIC_URL
            "lyric" -> SourceAction.LYRIC
            "pic" -> SourceAction.PIC
            else -> null
        }

        /** lx 协议的音质字符串 → Quality（未知档位丢弃，不猜） */
        private fun parseQuality(value: String): Quality? = Quality.entries.firstOrNull { it.value == value }

        /** 解析 Room 里回填的 sources JSON（当前仅存 key 列表，留 JSON 以便扩展） */
        private val json = Json { ignoreUnknownKeys = true }

        @Suppress("unused")
        private fun parseSourcesJson(raw: String): Map<String, Unit> = runCatching {
            json.parseToJsonElement(raw).jsonObject.keys.associateWith { }
        }.getOrDefault(emptyMap())
    }
}
