package com.lxpro.source.api

import com.lxpro.core.model.LOCAL_SOURCE_ID
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.core.model.SourceLabels
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 跨源「找同一首歌」（对齐 LX 的 `searchMusic` / `findMusic`，见 04 · 换源）：
 *
 * - [searchAcrossSources]：原始跨源搜索（**不排除任何源**，对齐 LX `MusicToggleModal.loadData` 的
 *   source 传空串调用）——按源分组返回原始结果，供手动面板直接展示。用户可以在**本平台内**
 *   挑不同版本。[exclude] 参数保留给内部调用，外部不应传。
 * - [findMatched]：播放取流失败的自动换源兜底用——**排除当前源**（避免死循环），按 LX 的匹配规则
 *   （时长差 <5s 硬门槛 + 歌名/歌手归一化 + 三遍匹配 + 优先级排序）产出有序候选，
 *   调用方按序逐候选取流，首个成功即止。
 *
 * 两方法语义必须严格分开：手动面板让用户全量候选（包括当前源自己）里挑，自动兜底必须避开当前源。
 */
@Singleton
class CrossSourceFinder @Inject constructor(
    private val registry: SourceRegistry,
) {

    /** 参与换源检索的在线源：跳过本地伪源；默认不排除任何源（对齐 LX 手动面板 `source: ''`）。
     *  [exclude] 仅由 [findMatched] 传入用于自动兜底时跳过当前源避免死循环。 */
    private fun onlineSources(exclude: SourceId? = null): List<SourceApi> {
        return registry.all.value
            .filter { it.id != LOCAL_SOURCE_ID && (exclude == null || it.id != exclude) }
    }

    /**
     * 手动面板的原始搜索结果：sourceId → 候选列表。
     *
     * 逐源独立超时 + 先到先报（对齐搜索页 #132 与 LX 的逐源 Promise 形态）：
     * 每个源 8s 闸门，单源卡住/失败只丢这一源（记为空清单，面板仍出 Tab）；
     * 每回一源立刻经 [onSourceResult] 上屏，不等最慢源。
     */
    suspend fun searchAcrossSources(
        name: String,
        singer: String,
        exclude: SourceId? = null,
        onSourceResult: (SourceId, List<Song>) -> Unit = { _, _ -> },
    ): Map<SourceId, List<Song>> {
        val keyword = listOf(name.trim(), singer.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (keyword.isEmpty()) return emptyMap()
        val sources = onlineSources(exclude)
        val results = java.util.concurrent.ConcurrentHashMap<SourceId, List<Song>>()
        coroutineScope {
            sources
                .map { api ->
                    async {
                        val start = android.os.SystemClock.elapsedRealtime()
                        val outcome = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                            runCatching { api.search(keyword, page = 1, limit = 25).list }
                        }
                        val cost = android.os.SystemClock.elapsedRealtime() - start
                        val list = when {
                            outcome == null -> {
                                emptyList()
                            }
                            outcome.isFailure -> {
                                emptyList()
                            }
                            else -> {
                                outcome.getOrThrow().also {
                                }
                            }
                        }
                        results[api.id] = list
                        onSourceResult(api.id, list)
                    }
                }
                .joinAll()
        }
        return results
    }

    /** 自动换源兜底：匹配并排序后的候选（已排除 [song] 自己的源） */
    suspend fun findMatched(song: Song): List<Song> {
        val lists = searchAcrossSources(song.name, song.singer, exclude = song.source)
        if (lists.isEmpty()) return emptyList()
        return matchAndSort(song, lists)
    }

    // ── 以下整体 port 自 LX `musicSdk/index.js` 的 findMusic ──

    private fun matchAndSort(song: Song, lists: Map<SourceId, List<Song>>): List<Song> {
        val fName = filterStr(song.name)
        val fSinger = filterStr(sortSingers(song.singer))
        val fAlbum = filterStr(song.albumName.orEmpty())
        val fInterval = song.interval ?: 0L

        fun equalInterval(intv: Long): Boolean {
            val a = if (fInterval != 0L) fInterval else intv
            val b = if (intv != 0L) intv else fInterval
            return abs(a - b) < 5
        }

        fun includesName(n: String): Boolean = fName.contains(n) || n.contains(fName)
        fun includesSinger(s: String): Boolean =
            if (fSinger.isEmpty()) true else fSinger.contains(s) || s.contains(fSinger)

        fun equalsAlbum(a: String): Boolean = fAlbum.isEmpty() || fAlbum == a

        // 每个源三遍匹配取第一条；时长差 >=5s 的候选直接出局（LX 里 name=null 的语义）
        val matched = lists.values.mapNotNull { list ->
            val candidates = list.map { Candidate(it) }.filter { equalInterval(it.fInterval) }
            candidates.firstOrNull { it.fName == fName && includesSinger(it.fSinger) }
                ?: candidates.firstOrNull { it.fSinger == fSinger && includesName(it.fName) }
                ?: candidates.firstOrNull {
                    equalsAlbum(it.fAlbum) && includesSinger(it.fSinger) && includesName(it.fName)
                }
        }

        // 全局 9 级优先（sortedBy 稳定，同级保持搜索返回的先后顺序，对齐 LX sortMusic）
        fun tier(c: Candidate): Int = when {
            c.fSinger == fSinger && c.fName == fName && c.fInterval == fInterval -> 0
            c.fName == fName && c.fSinger == fSinger && c.fAlbum == fAlbum -> 1
            c.fSinger == fSinger && c.fName == fName -> 2
            c.fName == fName && c.fInterval == fInterval -> 3
            c.fSinger == fSinger && c.fInterval == fInterval -> 4
            c.fInterval == fInterval -> 5
            c.fName == fName -> 6
            c.fSinger == fSinger -> 7
            c.fAlbum == fAlbum -> 8
            else -> 9
        }
        return matched.sortedBy { tier(it) }.map { it.song }
    }

    private class Candidate(val song: Song) {
        val fSinger: String = filterStr(sortSingers(song.singer))
        val fName: String = filterStr(song.name)
        val fAlbum: String = filterStr(song.albumName.orEmpty())
        val fInterval: Long = song.interval ?: 0L
    }

    companion object {
        /** 每源独立超时（对齐搜索页 #132 的 8s 闸门）：单源卡住只丢这一源 */
        private const val PER_SOURCE_TIMEOUT_MS = 8_000L

        /** 逐源耗时/结果数日志：`adb logcat -s SwapPerf`（#142 复现取证） */
        private const val SWAP_LOG = "SwapPerf"

        /** 音质降级榜（对齐 LX QUALITY_RANK）：从偏好档起向下找候选实际支持的档 */
        private val QUALITY_RANK = listOf(
            Quality.MASTER, Quality.ATMOS_PLUS, Quality.ATMOS,
            Quality.HIRES, Quality.FLAC, Quality.K320, Quality.K128,
        )

        /** 换源目标实际可用的音质：偏好档不支持时逐级降级，保底 128k */
        fun pickQuality(preferred: Quality, available: List<Quality>): Quality {
            val start = QUALITY_RANK.indexOf(preferred).let { if (it < 0) 0 else it }
            for (i in start until QUALITY_RANK.size) {
                if (QUALITY_RANK[i] in available) return QUALITY_RANK[i]
            }
            return Quality.K128
        }

        /** LX `filterStr`：去空白与标点再小写化 */
        private val FILTER_RXP = Regex("""[\s'.,，&"、()（）`~\-<>|/\[\]!！]""")

        private fun filterStr(str: String): String = str.replace(FILTER_RXP, "").lowercase()

        /** LX `sortSingle`：多歌手拆开排序再拼回，消除「A、B」vs「B、A」的顺序差异 */
        private val SINGERS_RXP = Regex("、|&|;|；|/|,|，|\\|")

        private fun sortSingers(singer: String): String =
            if (SINGERS_RXP.containsMatchIn(singer)) {
                singer.split(SINGERS_RXP).sorted().joinToString("、")
            } else {
                singer
            }
    }
}
