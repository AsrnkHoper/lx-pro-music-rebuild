package com.lxpro.feature.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.TextNormalizer
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.library.toSong
import com.lxpro.core.model.LOCAL_SOURCE_NAME
import com.lxpro.core.model.SearchMode
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.core.model.SourceLabels
import com.lxpro.core.playlist.PlaylistRepository
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.core.playlist.songOverrideKey
import com.lxpro.core.playlist.withOverride
import com.lxpro.source.api.SearchResult
import com.lxpro.source.api.SourceApi
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * 搜索结果项。
 * ⚠️ 15 §5：**每个可播放项必须带来源**，UI 层不得出现「不知道来源」的歌曲行。
 */
data class SearchResultItem(
    val song: Song,
    val originLabel: String,
)

/** 路由参数名，与 `Routes.SEARCH` 的占位符一致 */
const val ARG_SEARCH_QUERY = "q"

/**
 * 结果呈现类型（权威稿 `#spTypes` 的 `data-ty`）：
 * 综合 / 单曲 / 歌手 / 专辑 / 歌单。
 *
 * ⚠️ 音源接口**只有关键字检索**（`SourceApi.search`，返回歌曲列表），没有分类检索。
 * 因此「歌手 / 专辑 / 歌单」三组是**从当前结果归纳**出来的（权威稿 `renderSp` 也是这么取的：
 * `new Set(res.map(x => x.a))`），不是假装音源支持分类搜索。
 */
enum class SearchType(val id: String, val label: String) {
    ALL("all", "综合"),
    SONG("song", "单曲"),
    ARTIST("artist", "歌手"),
    ALBUM("album", "专辑"),
    LIST("list", "歌单"),
    ;

    companion object {
        fun fromId(id: String?): SearchType = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/**
 * 结果行的两种排布：有图（封面）/ 无图（序号）。
 * 与歌单详情页的 `SongLayout` 同一套语义（权威稿 `#plDcover`：`aria-label="切换有无封面"`）。
 */
enum class ResultLayout { COVER, COMPACT }

/** 歌手分组：由结果里的歌手名归纳 */
data class ArtistGroup(val singer: String, val trackCount: Int)

/** 专辑分组：由结果里的专辑名归纳 */
data class AlbumGroup(val album: String, val singer: String, val coverUrl: String?, val trackCount: Int)

/** 歌单分组：本地歌单按名字命中（本产品没有云端歌单） */
data class PlaylistGroup(val id: String, val name: String, val itemCount: Int)

/** 搜索平台（音源）多选的一枚 chip；`全部` 是全开总闸（权威稿 `platRow`） */
data class PlatformOption(val id: String, val label: String, val selected: Boolean)

/** 搜索页的「偏好类」状态：历史 / 常搜 / 平台 / 计数（与结果状态分开，避免重搜时抖动） */
data class SearchPrefs(
    val history: List<String> = emptyList(),
    val hotQueries: List<String> = emptyList(),
    val platforms: List<PlatformOption> = emptyList(),
    val enabledCount: Int = 0,
    val sourceCount: Int = 0,
    val localCount: Int = 0,
)

/**
 * ⚠️ 这里**只有搜索结果**，**不含输入框文本**。
 *
 * 曾经的 bug（2026-10-02 真机反馈）：把输入框文本也放进 uiState，防抖搜索完成后整块
 * uiState 被重建（含 keyword），TextField 的 value 被外部覆盖 → **光标跳回最前**。
 * 输入文本归输入框自己管（`rememberSaveable`），本状态只描述"搜索结果长什么样"。
 */
data class SearchUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val results: List<SearchResultItem> = emptyList(),
    val artists: List<ArtistGroup> = emptyList(),
    val albums: List<AlbumGroup> = emptyList(),
    val playlists: List<PlaylistGroup> = emptyList(),
    val hasMore: Boolean = false,
    val page: Int = 1,
    val error: String? = null,
    /** 是否已经发起过一次搜索（用于区分「初始态」与「搜完但没结果」） */
    val searched: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val localMusic: LocalMusicRepository,
    private val songOverrides: SongOverrideRepository,
    private val playlists: PlaylistRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 从首页/播放页带过来的关键词（「信息容量大的地方不在同级展开」的落点） */
    val initialQuery: String = savedStateHandle.get<String>(ARG_SEARCH_QUERY).orEmpty()

    private val keywordInput = MutableStateFlow("")

    /** 搜索范围过滤（15 §4.1）：全局单例、跨页面保持，落盘在 DataStore；默认「全部」 */
    private val _mode = MutableStateFlow(SearchMode.ALL)
    val mode: StateFlow<SearchMode> = _mode.asStateFlow()

    /** 结果呈现类型（综合 / 单曲 / 歌手 / 专辑 / 歌单）；只切分组，不重搜 */
    private val _type = MutableStateFlow(SearchType.ALL)
    val type: StateFlow<SearchType> = _type.asStateFlow()

    /** 结果行排布：有图 / 无图（只改行样式，不重搜） */
    private val _layout = MutableStateFlow(ResultLayout.COVER)
    val layout: StateFlow<ResultLayout> = _layout.asStateFlow()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** 历史 / 常搜 / 平台 / 计数 */
    val prefs: StateFlow<SearchPrefs> = combine(
        settings.searchHistory,
        settings.hotQueries,
        registry.all,
        registry.enabled,
        localMusic.observeTracks(),
    ) { history, hot, all, enabled, tracks ->
        // ⚠️ 「搜索平台」按**平台**去重，不是按音源/脚本去重（2026-10-27 用户反馈
        // 「平台怎么有那么多重复的？音乐平台和音源脚本不是同一个东西」）：
        // 同一个平台可能同时有内置源和多个脚本（酷我 / QQ / 酷狗都这样），
        // 它们的 id 也可能不同（内置 QQ=tx，脚本=qq），所以先归一化再取首个代表。
        val platforms = all.distinctBy { SourceLabels.canonical(it.id.value) }
        val on = enabled.map { SourceLabels.canonical(it.id.value) }.toSet()
        SearchPrefs(
            history = history,
            hotQueries = hot,
            platforms = buildList {
                if (platforms.isNotEmpty()) {
                    add(
                        PlatformOption(
                            ALL_PLATFORMS,
                            "全部",
                            platforms.all { SourceLabels.canonical(it.id.value) in on },
                        ),
                    )
                    platforms.forEach {
                        val platform = SourceLabels.canonical(it.id.value)
                        add(PlatformOption(platform, it.name, platform in on))
                    }
                }
            },
            // 计数也按平台算：脚本数量不是「平台数量」
            enabledCount = platforms.count { SourceLabels.canonical(it.id.value) in on },
            sourceCount = platforms.size,
            localCount = tracks.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchPrefs())

    private var currentQuery: String = ""
    private var searchJob: Job? = null

    /** 最近一次交给播放器的分享链接 + 时间，用于粘贴后「防抖流」与「点搜索」两次触发去重 */
    private var lastShareLink: String? = null
    private var lastShareAt: Long = 0L

    /**
     * 把分享链接交给上层播放器。
     *
     * 同一条链接 3 秒内只发一次：粘贴会先走防抖流、点搜索又走 onSubmit，
     * 不去重就会重复搜索/重复播放。超过 3 秒用户再次点搜索 = 明确的「再播一次」。
     */
    private fun emitShareLink(raw: String, link: String) {
        currentQuery = raw
        _uiState.value = SearchUiState(searched = true)
        val now = System.currentTimeMillis()
        if (link == lastShareLink && now - lastShareAt < SHARE_DEDUPE_MS) return
        lastShareLink = link
        lastShareAt = now
        com.lxpro.core.common.ShareLinkBus.emit(link)
    }

    /** 本地结果区与在线结果区分开持有：「全部」档 = 本地在前 + 在线接后，翻页只续在线部分 */
    private var localPart: List<SearchResultItem> = emptyList()
    private var onlinePart: List<SearchResultItem> = emptyList()

    init {
        viewModelScope.launch {
            // 恢复上次勾选的平台（空集 = 全开，不动）。
            // ⚠️ 必须**按平台**遍历：存的是平台代表 id（如 tx），若逐源遍历，
            // 别名脚本（qq）不在缓存集合里就会把 tx 又关掉。
            val saved = settings.enabledSourceIds.first()
            if (saved.isNotEmpty()) {
                val savedPlatforms = saved.map { SourceLabels.canonical(it) }.toSet()
                registry.all.value
                    .distinctBy { SourceLabels.canonical(it.id.value) }
                    .forEach { registry.setEnabled(it.id, SourceLabels.canonical(it.id.value) in savedPlatforms) }
            }
            _mode.value = settings.searchMode.first()
            localMusic.refreshCache()
            // 带词进来就立刻搜一次（首页/播放页跳过来时）；空词留给输入框自己触发
            if (initialQuery.isNotBlank()) keywordInput.value = initialQuery
        }
        viewModelScope.launch {
            keywordInput
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { raw ->
                    // 分享文案 / 软链接不在输入过程中自动触发：既不当关键词搜，也不自动起播。
                    // 只在用户**显式点搜索**（onSubmit）时才交给播放器——避免「先搜一遍再自动跳页」。
                    if (com.lxpro.core.model.SongLink.extractLinkFromText(raw) != null) {
                        return@collectLatest
                    }
                    // ⚠️ 显式点搜索已经立即搜过这个词时，防抖流不再重复搜（2026-10-27 用户反馈
                    // 「点搜索后卡顿」的根因）：onSubmit 走 launchSearch 立即开搜，而最后一次
                    // 按键的防抖计时器还没到点，300ms 后会对同一关键词**再发一轮全音源请求**。
                    // 两轮并发各自写 results，界面就会「先出结果 → 清空转圈 → 再出一次」，
                    // 看起来就是点搜索卡了一下。currentQuery 在 search() 入口即写入，正好做去重。
                    if (raw.trim() == currentQuery.trim()) return@collectLatest
                    search(raw, page = 1)
                }
        }
    }

    /** 输入框每次变化都调它；真正的请求由防抖后的流触发 */
    fun onKeywordChange(value: String) {
        keywordInput.value = value
    }

    /**
     * 显式提交（输入法「搜索」/ 点历史·常搜 chip）。
     *
     * ⚠️ 只有显式提交才写搜索历史 —— 输入过程中的每一段前缀都写会让历史变成一坨垃圾。
     */
    fun onSubmit(raw: String) {
        val query = raw.trim()
        // 分享文案 / 软链接触发点还原：粘贴后防抖流可能已经拦过一次，这里也拦，
        // 但不能写进搜索历史（整句邀请语进历史毫无意义），并做 3 秒内去重避免重复播放。
        val link = com.lxpro.core.model.SongLink.extractLinkFromText(query)
        if (link != null) {
            emitShareLink(query, link)
            return
        }
        if (!TextNormalizer.isSearchable(query)) return
        viewModelScope.launch { settings.recordSearch(query) }
        // 文本没变（点 chip）→ 立即搜；变了 → 交给防抖流，避免同一关键词搜两次
        if (query == keywordInput.value) launchSearch(query, page = 1) else keywordInput.value = query
    }

    /** 清空输入框：回落初始态（历史 / 常搜） */
    fun onClearInput() {
        keywordInput.value = ""
    }

    fun onTypeChange(next: SearchType) {
        _type.value = next
    }

    /** 有图 ⇄ 无图（权威稿 `#plDcover` 的切换按钮） */
    fun onLayoutToggle() {
        _layout.value = if (_layout.value == ResultLayout.COVER) ResultLayout.COMPACT else ResultLayout.COVER
    }

    /**
     * 切换模式。
     * ⚠️ 硬性行为 #1：**保留搜索关键词并立即重搜**（用户常「在线搜不到 → 切本地看看」），
     * 因此这里**不走防抖**、直接搜。
     */
    fun onModeChange(mode: SearchMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        viewModelScope.launch {
            settings.setSearchMode(mode)
            search(keywordInput.value, page = 1)
        }
    }

    /** 勾选 / 取消勾选某个平台（`全部` 是全开总闸）；集合变了立即重搜 */
    fun onPlatformToggle(chipId: String) {
        if (chipId == ALL_PLATFORMS) {
            // 按平台判断「是不是已经全开」：enabled 里只有平台代表源，
            // 用逐个源 id 比较会把别名脚本算成「没开」，于是点「全部」反而变成全开
            val platforms = registry.all.value.distinctBy { SourceLabels.canonical(it.id.value) }
            val on = registry.enabled.value.map { SourceLabels.canonical(it.id.value) }.toSet()
            val allOn = platforms.all { SourceLabels.canonical(it.id.value) in on }
            registry.all.value.forEach { registry.setEnabled(it.id, !allOn) }
        } else {
            val platform = SourceLabels.canonical(chipId)
            val on = registry.enabled.value.any { SourceLabels.canonical(it.id.value) == platform }
            registry.setEnabled(SourceId(platform), !on)
        }
        viewModelScope.launch {
            settings.setEnabledSourceIds(registry.enabled.value.map { it.id.value }.toSet())
            if (currentQuery.isNotBlank()) search(currentQuery, page = 1)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { settings.clearSearchHistory() }
    }

    /** 删单条历史（chip 上的 ×） */
    fun removeHistory(word: String) {
        viewModelScope.launch { settings.removeSearchHistory(word) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        searchJob = viewModelScope.launch { search(currentQuery, page = state.page + 1) }
    }

    fun retry() {
        launchSearch(currentQuery, page = 1)
    }

    private fun launchSearch(raw: String, page: Int) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(raw, page) }
    }

    private suspend fun search(raw: String, page: Int) {
        // 分享文案 / 软链接粘进搜索框：抠出 lxpro:// 链接交给播放器，而不是把整句当关键词搜
        if (page == 1) {
            val link = com.lxpro.core.model.SongLink.extractLinkFromText(raw)
            if (link != null) {
                emitShareLink(raw.trim(), link)
                return
            }
        }
        // 归一化后为空（纯符号）必须回落初始态，不能匹配全部（06 §M1.2）
        if (!TextNormalizer.isSearchable(raw)) {
            currentQuery = ""
            _uiState.value = SearchUiState()
            return
        }

        currentQuery = raw
        val isFirstPage = page == 1
        // ⚠️ `searched` 必须在**发起时**就置 true（2026-10-27 真机反馈「点搜索后像卡住」）：
        // 之前只有结果回来才置 true，而 SearchScreen 把 `!searched` 分支排在 `loading` 前面，
        // 于是「点了搜索」的这几秒里界面仍停在搜索历史 / 常搜，一格进度都不给，看起来就是死机。
        _uiState.value = _uiState.value.copy(
            searched = true,
            loading = isFirstPage,
            loadingMore = !isFirstPage,
            error = null,
            results = if (isFirstPage) emptyList() else _uiState.value.results,
        )

        // 检索与结果归并放到后台线程：16 个音源各自的请求准备（签名 / 解密 / 脚本调用）
        // 在第一个挂起点之前是**同步**执行的，留在主线程会直接卡住渲染（2026-10-27 卡顿排查）
        withContext(Dispatchers.Default) {
            when (_mode.value) {
                SearchMode.LOCAL -> searchLocal(raw)
                SearchMode.ONLINE -> searchOnline(raw, page)
                SearchMode.ALL -> searchAll(raw, page)
            }
        }
    }

    private suspend fun searchLocal(raw: String) {
        localPart = searchLocalItems(raw)
        onlinePart = emptyList()
        _uiState.value = SearchUiState(
            results = localPart,
            hasMore = false,
            page = 1,
            searched = true,
            error = if (localPart.isEmpty()) "本地曲库里没有匹配「$raw」" else null,
        )
    }

    /** 本地检索（LocalMusicRepository 内存索引 + TextNormalizer 标点容错），「全部」档复用同一份实现 */
    private suspend fun searchLocalItems(raw: String): List<SearchResultItem> {
        val overrides = songOverrides.snapshot()
        return withContext(Dispatchers.Default) {
            localMusic.searchInMemory(raw).map {
                val song = it.toSong()
                SearchResultItem(
                    song = song.withOverride(overrides[songOverrideKey(song.id, song.source.value)]),
                    originLabel = LOCAL_SOURCE_NAME,
                )
            }
        }
    }

    /**
     * 统一搜索（默认「全部」档）：本地结果区在前 + 在线结果区接后，同一份列表。
     *
     * - 本地部分只在第一页搜一次（本地无分页），触底续页只翻在线部分；
     * - 在线失败**不拖垮本地结果**：本地部分照常展示，错误文案注明是「在线搜索」失败；
     * - 未勾选音源 = 在线部分无结果，本地部分不受影响。
     */
    private suspend fun searchAll(raw: String, page: Int) {
        val isFirstPage = page == 1
        if (isFirstPage) {
            localPart = searchLocalItems(raw)
            // 本地命中先上屏：在线音源里只要有一个慢，用户也得先看见东西
            _uiState.value = _uiState.value.copy(
                results = localPart,
                playlists = localPlaylistMatches(raw),
                loading = true,
                loadingMore = false,
                error = null,
                searched = true,
            )
        }

        val sources = registry.enabled.value
        if (sources.isEmpty()) {
            if (isFirstPage) onlinePart = emptyList()
            val merged = localPart + onlinePart
            _uiState.value = SearchUiState(
                results = merged,
                playlists = if (isFirstPage) localPlaylistMatches(raw) else _uiState.value.playlists,
                hasMore = false,
                page = page,
                searched = true,
                error = if (merged.isEmpty()) "未勾选音源 · 本地曲库也没有匹配「$raw」" else null,
            )
            return
        }

        val overrides = songOverrides.snapshot()
        val pageItems = mutableListOf<SearchResultItem>()
        var hasMore = false
        var failure: String? = null

        searchEachSource(sources, raw, page) { source, result ->
            result
                .onSuccess { searchResult ->
                    hasMore = hasMore || searchResult.hasMore
                    searchResult.list.forEach { song ->
                        pageItems += SearchResultItem(
                            song = song.withOverride(
                                overrides[songOverrideKey(song.id, song.source.value)],
                            ),
                            originLabel = source.name,
                        )
                    }
                }
                .onFailure { throwable -> if (failure == null) failure = throwable.message }

            // 每到一个音源就刷新一次：先到的结果立刻上屏，不等最慢的那个
            onlinePart = (if (isFirstPage) pageItems else onlinePart + pageItems)
                // 分页去重：LazyColumn 的 key 不能重复，否则运行时崩溃
                .distinctBy { "${it.song.source.value}:${it.song.id}" }
            val partial = localPart + onlinePart
            _uiState.value = _uiState.value.copy(
                results = partial,
                artists = onlinePart.toArtistGroups(),
                albums = onlinePart.toAlbumGroups(),
                loading = true,
                loadingMore = false,
                hasMore = false,
                page = page,
                error = null,
                searched = true,
            )
        }

        val merged = localPart + onlinePart
        _uiState.value = SearchUiState(
            results = merged,
            // 歌手 / 专辑归纳只看在线部分：没有在线结果就不出这两组
            artists = onlinePart.toArtistGroups(),
            albums = onlinePart.toAlbumGroups(),
            playlists = if (isFirstPage) localPlaylistMatches(raw) else _uiState.value.playlists,
            hasMore = hasMore,
            page = page,
            searched = true,
            error = when {
                merged.isNotEmpty() -> null
                failure != null -> "在线搜索失败：$failure"
                else -> "在线音源和本地曲库都没有匹配「$raw」"
            },
        )
    }

    /**
     * 并发搜全部音源，**哪个先回来先回调哪个**（而不是 `awaitAll` 等最慢的）。
     *
     * 2026-10-27 真机反馈：点搜索后要愣好几秒。原因之一是 `awaitAll` 把整页结果
     * 绑在最慢音源上——内置源带重试（QQ 最多 6 次）、脚本源单次上限 20s，
     * 只要有一个不响应，界面上就一个字都不出。这里改成按到货顺序逐个回调，
     * 并给每个音源加独立超时，避免单个音源拖住整次搜索。
     */
    private suspend fun searchEachSource(
        sources: List<SourceApi>,
        raw: String,
        page: Int,
        onArrive: suspend (SourceApi, Result<SearchResult>) -> Unit,
    ) = coroutineScope {
        val arrivals = Channel<Pair<SourceApi, Result<SearchResult>>>(Channel.UNLIMITED)
        sources.forEach { source ->
            launch {
                val startedAt = System.currentTimeMillis()
                val result = runCatching {
                    withTimeout(SOURCE_TIMEOUT_MS) {
                        source.search(raw, page = page, limit = PAGE_SIZE)
                    }
                }
                Log.i(
                    SEARCH_LOG_TAG,
                    "${source.id.value} ${System.currentTimeMillis() - startedAt}ms " +
                        "ok=${result.isSuccess}",
                )
                arrivals.send(source to result)
            }
        }
        repeat(sources.size) {
            val (source, result) = arrivals.receive()
            onArrive(source, result)
        }
    }

    /**
     * 在线搜索。
     *
     * ⚠️ 搜索的是**当前勾选的全部音源**（`registry.enabled`），不是一个 —— 否则「搜索平台」
     * 多选就是个摆设。每个音源各取一页并发搜，结果按到货顺序拼接并去重。
     *
     * ⚠️ 一个音源都没勾 = **一定没有结果**（权威稿 `searchTracks`：`!sel.length → return false`），
     * 不是「不过滤」。
     */
    private suspend fun searchOnline(raw: String, page: Int) {
        localPart = emptyList()
        val sources = registry.enabled.value
        if (sources.isEmpty()) {
            onlinePart = emptyList()
            _uiState.value = SearchUiState(error = "未勾选音源 · 不会有结果", searched = true)
            return
        }

        val overrides = songOverrides.snapshot()
        val pageItems = mutableListOf<SearchResultItem>()
        var hasMore = false
        var failure: String? = null

        searchEachSource(sources, raw, page) { source, result ->
            result
                .onSuccess { searchResult ->
                    hasMore = hasMore || searchResult.hasMore
                    searchResult.list.forEach { song ->
                        pageItems += SearchResultItem(
                            song = song.withOverride(
                                overrides[songOverrideKey(song.id, song.source.value)],
                            ),
                            originLabel = source.name,
                        )
                    }
                }
                .onFailure { throwable -> if (failure == null) failure = throwable.message }

            onlinePart = (if (page == 1) pageItems else onlinePart + pageItems)
                // 分页去重：LazyColumn 的 key 不能重复，否则运行时崩溃
                .distinctBy { "${it.song.source.value}:${it.song.id}" }
            val partial = onlinePart
            _uiState.value = _uiState.value.copy(
                results = partial,
                artists = partial.toArtistGroups(),
                albums = partial.toAlbumGroups(),
                loading = true,
                loadingMore = false,
                hasMore = false,
                page = page,
                error = null,
                searched = true,
            )
        }

        val merged = onlinePart
        _uiState.value = SearchUiState(
            results = merged,
            artists = merged.toArtistGroups(),
            albums = merged.toAlbumGroups(),
            playlists = if (page == 1) localPlaylistMatches(raw) else _uiState.value.playlists,
            hasMore = hasMore,
            page = page,
            searched = true,
            error = when {
                merged.isNotEmpty() -> null
                failure != null -> failure
                else -> "在线音源里没有匹配「$raw」"
            },
        )
    }

    /** 歌手组：由结果里的歌手名归纳（权威稿 `new Set(res.map(x => x.a))`） */
    private fun List<SearchResultItem>.toArtistGroups(): List<ArtistGroup> = asSequence()
        .map { it.song.singer }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(GROUP_LIMIT)
        .map { ArtistGroup(it.key, it.value) }
        .toList()

    /** 专辑组：同样由结果归纳；封面取该专辑下第一首有图的 */
    private fun List<SearchResultItem>.toAlbumGroups(): List<AlbumGroup> = asSequence()
        .mapNotNull { item ->
            item.song.albumName?.takeIf { it.isNotBlank() }?.let { album -> album to item.song }
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedByDescending { it.value.size }
        .take(GROUP_LIMIT)
        .map { (album, songs) ->
            AlbumGroup(
                album = album,
                singer = songs.first().singer,
                coverUrl = songs.firstOrNull { !it.picUrl.isNullOrBlank() }?.picUrl,
                trackCount = songs.size,
            )
        }
        .toList()

    /** 歌单组：本地歌单按名字命中（本产品没有云端歌单，不假装有） */
    private suspend fun localPlaylistMatches(raw: String): List<PlaylistGroup> = runCatching {
        val rows = playlists.observePlaylists().first()
        val counts = playlists.observeSummaries().first()
        rows.filter { TextNormalizer.matches(it.name, raw) }
            .take(GROUP_LIMIT)
            .map { PlaylistGroup(it.id, it.name, counts[it.id]?.itemCount ?: 0) }
    }.getOrDefault(emptyList())

    companion object {
        const val DEBOUNCE_MS = 300L
        const val PAGE_SIZE = 30

        /** 同一条分享链接的触发去重窗口（防抖流 + 点搜索两条路径） */
        private const val SHARE_DEDUPE_MS = 3_000L

        /**
         * 单个音源的搜索超时。
         * 内置源带重试（QQ 最多 6 次 × 30s 读超时）、脚本源单次上限 20s ——
         * 不单独设闸的话，一个不响应的音源就能让整页搜索悬着。
         */
        private const val SOURCE_TIMEOUT_MS = 8_000L

        /** 搜索耗时日志标签（adb logcat -s SearchPerf） */
        const val SEARCH_LOG_TAG = "SearchPerf"

        /** 归纳类分组最多展示几条（权威稿 `.slice(0, 6)` / `.slice(0, 4)`） */
        const val GROUP_LIMIT = 6

        private const val ALL_PLATFORMS = "all"
    }
}