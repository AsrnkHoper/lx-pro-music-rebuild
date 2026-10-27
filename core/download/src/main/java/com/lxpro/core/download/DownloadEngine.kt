package com.lxpro.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.lxpro.core.database.entity.DownloadState
import com.lxpro.core.database.entity.DownloadTaskEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.model.Quality
import com.lxpro.core.network.RequestHeaderStrategy
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * 下载引擎（15 §6.2 的状态机）。
 *
 * - **并发上限**：取设置里的「同时下载数」（默认 3），超出的留在排队
 * - **断点续传**：带 `Range` 头续写；服务端不支持（返回 200 而非 206）则从头重写
 * - **失败可见**：把原因写进任务的 `error`（如「音源不可用」）
 * - **完成后自动入本地库**：登记进 `local_tracks`（`sourceKind=download`），双模式闭环
 *
 * 调度方式：一个常驻协程按 tick 巡检（有活儿 700ms 一次、空闲 2.5s 一次），
 * 入队/继续时用 [wake] 立刻唤醒 —— 不用 Flow 收集是为了避免「进度写库 → Flow 触发 → 再调度」的风暴。
 */
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: DownloadRepository,
    private val sources: SourceRegistry,
    private val headerStrategy: RequestHeaderStrategy,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val library: LocalMusicRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            // 上次崩在「下载中」的任务复位成排队，否则永远停在那
            runCatching { repo.resetStuck() }
            while (isActive) {
                val busy = runCatching { tick() }.getOrDefault(false)
                withTimeoutOrNull(if (busy) TICK_ACTIVE_MS else TICK_IDLE_MS) { wake.receive() }
            }
        }
    }

    // ── 对外动作 ──

    /** 入队；@return false = 这首歌已经下过了 */
    suspend fun enqueue(song: com.lxpro.core.model.Song, quality: Quality): Boolean {
        val added = repo.enqueue(song, quality)
        wake.trySend(Unit)
        return added
    }

    fun pause(id: String) {
        scope.launch {
            jobs[id]?.cancel()
            jobs.remove(id)
            repo.markPaused(id)
        }
    }

    fun resume(id: String) {
        scope.launch {
            repo.markQueued(id)
            wake.trySend(Unit)
        }
    }

    /** 失败重试：清掉错误、回到排队（进度按已下载字节续传） */
    fun retry(id: String) {
        scope.launch {
            repo.markQueued(id)
            wake.trySend(Unit)
        }
    }

    fun remove(id: String) {
        scope.launch {
            jobs[id]?.cancel()
            jobs.remove(id)
            repo.find(id)?.filePath?.let { path ->
                val file = File(path)
                runCatching { library.unregisterDownloadedFile(file) }
                runCatching { file.delete() }
            }
            repo.remove(id)
        }
    }

    /** 全部暂停：排队中的直接落库，下载中的先取消再落库 */
    fun pauseAll() {
        scope.launch {
            repo.pauseAll()
            jobs.keys.toList().forEach { id ->
                jobs[id]?.cancel()
                jobs.remove(id)
                repo.markPaused(id)
            }
        }
    }

    fun resumeAll() {
        scope.launch {
            repo.resumeAll()
            wake.trySend(Unit)
        }
    }

    /** 清空已完成：连带删文件与本地库索引行 */
    fun clearCompleted() {
        scope.launch {
            repo.completedTasks().forEach { task ->
                task.filePath?.let { path ->
                    val file = File(path)
                    runCatching { library.unregisterDownloadedFile(file) }
                    runCatching { file.delete() }
                }
            }
            repo.deleteCompleted()
        }
    }

    // ── 调度 ──

    private suspend fun tick(): Boolean {
        val concurrency = settings.downloadConcurrency.first()
        val tasks = repo.all()
        // 仅 Wi-Fi：移动网络下不开始新任务（已开始的照常跑完，避免半截文件）
        val networkBlocked = settings.downloadWifiOnly.first() && !isOnUnmeteredNetwork()
        val running = tasks.count { it.state == DownloadState.DOWNLOADING && jobs.containsKey(it.id) }
        val free = (concurrency - running).coerceAtLeast(0)

        if (free > 0 && !networkBlocked) {
            tasks.asSequence()
                .filter { it.state == DownloadState.QUEUED && !jobs.containsKey(it.id) }
                .sortedBy { it.addedAt }
                .take(free)
                .forEach { launchTask(it.id) }
        }
        return jobs.isNotEmpty() ||
            (tasks.any { it.state == DownloadState.QUEUED } && !networkBlocked)
    }

    /** 当前网络是否「非计费」（Wi-Fi / 以太网）；取不到时按可用处理，不误伤下载 */
    private fun isOnUnmeteredNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false)
            ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun launchTask(id: String) {
        if (jobs.containsKey(id)) return
        val job = scope.launch {
            val task = repo.find(id) ?: return@launch
            repo.markDownloading(id)
            runCatching { perform(task) }.onFailure { error ->
                // 被取消（暂停）不算失败：状态已由 pause() 落库
                if (coroutineContext[Job]?.isActive == true) {
                    repo.markFailed(id, error.message)
                }
            }
            jobs.remove(id)
        }
        jobs[id] = job
    }

    // ── 单任务下载 ──

    private suspend fun perform(task: DownloadTaskEntity) {
        val self = coroutineContext[Job]
        val song = task.toSong() ?: error("任务缺少歌曲信息")
        val quality = Quality.entries.firstOrNull { it.value == task.quality } ?: Quality.K320
        val source = sources.byId(song.source)
            ?: error("音源不可用（${song.source.value} 未安装脚本）")

        val url = runCatching { source.getMusicUrl(song, quality).url }
            .getOrElse { error("取流失败：${it.message ?: "音源无响应"}") }

        val dir = repo.downloadDir(context.filesDir)
        val file = File(dir, fileNameFor(task, url))
        val existing = if (file.exists()) file.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply {
                headerStrategy.headersFor(song).forEach { (key, value) -> header(key, value) }
                header("User-Agent", headerStrategy.userAgentFor(song.source))
                if (existing > 0L) header("Range", "bytes=$existing-")
            }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("空响应体")

            // 206 = 服务端接受了 Range；否则它给的是完整文件，必须从头写
            val append = response.code == 206 && existing > 0L
            val base = if (append) existing else 0L
            val total = body.contentLength().takeIf { it > 0L }?.let { base + it }
                ?: task.totalBytes.takeIf { it > 0L }
                ?: -1L

            body.byteStream().use { input ->
                FileOutputStream(file, append).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = base
                    var lastFlush = 0L
                    while (true) {
                        if (self?.isActive != true) return // 被暂停：保留已下载部分，等下次续传
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFlush >= PROGRESS_FLUSH_MS) {
                            lastFlush = now
                            val progress = if (total > 0L) {
                                ((written * 100L) / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            repo.updateProgress(task.id, progress, written, total)
                        }
                    }
                    out.flush()
                }
            }
        }

        if (!file.exists() || file.length() == 0L) error("下载内容为空")
        repo.markCompleted(task.id, file)

        // 双模式闭环：完成后自动入本地库（用户可在下载设置里关掉）
        if (settings.downloadAutoImport.first()) {
            runCatching { library.registerDownloadedFile(file, song) }
        }
    }

    // ── 文件名 ──

    /** `歌名 - 歌手_<短哈希>.<ext>`：哈希保证唯一（同名翻唱/版本不会互相覆盖） */
    private fun fileNameFor(task: DownloadTaskEntity, url: String): String {
        val base = "${task.name} - ${task.singer}"
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .take(60)
            .ifBlank { task.songId }
        return "${base}_${task.id.hashCode().toString(16)}.${extensionFor(task, url)}"
    }

    private fun extensionFor(task: DownloadTaskEntity, url: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
        if (fromUrl in AUDIO_EXTENSIONS) return fromUrl
        return when (task.quality) {
            Quality.FLAC.value, Quality.HIRES.value, Quality.MASTER.value -> "flac"
            else -> "mp3"
        }
    }

    private companion object {
        const val TICK_ACTIVE_MS = 700L
        const val TICK_IDLE_MS = 2_500L
        const val PROGRESS_FLUSH_MS = 400L
        const val BUFFER_BYTES = 64 * 1024

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
    }
}