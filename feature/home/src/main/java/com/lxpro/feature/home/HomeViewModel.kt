package com.lxpro.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.database.dao.PlaylistSummaryRow
import com.lxpro.core.database.entity.ArtistIconOverrideEntity
import com.lxpro.core.database.entity.PlaylistEntity
import com.lxpro.core.datastore.SettingsRepository
import com.lxpro.core.library.LocalMusicRepository
import com.lxpro.core.media.PlayerController
import com.lxpro.core.model.SearchMode
import com.lxpro.core.model.Song
import com.lxpro.core.model.SourceId
import com.lxpro.core.playlist.PlaylistRepository
import com.lxpro.core.playlist.SongOverride
import com.lxpro.core.playlist.SongOverrideRepository
import com.lxpro.core.playlist.songOverrideKey
import com.lxpro.core.playlist.withOverride
import com.lxpro.core.stats.PlayHistoryRepository
import com.lxpro.core.stats.ArtistIconOverrideRepository
import com.lxpro.core.stats.StatsCalculator
import com.lxpro.core.stats.TopArtist
import com.lxpro.core.model.SourceLabels
import com.lxpro.source.api.SourceApi
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** 首页货架上的一首歌（最近播放） */
data class RecentCard(
    val song: Song,
    /** 音源展示名，做封面左上角的来源徽章（15 §5：不得出现不知道来源的歌曲行） */
    val sourceLabel: String,
)

/** 首页货架上的一个歌单 */
data class HomePlaylistCard(
    val id: String,
    val name: String,
    val itemCount: Int,
    val coverUrl: String?,
)

/** 搜索框左侧放大镜下拉里可选的一个「音乐平台」（展示名来自已装音源） */
data class PlatformChip(val id: String, val label: String, val selected: Boolean)

/**
 * 常听歌手货架项：统计行 + 用户自定义图标。
 *
 * @property artistKey 归并键（`StatsCalculator.normalizeArtist`），自定义图标的存取键
 * @property iconKind 覆盖类型（`song_cover` / `local_image`）；null = 默认样式
 * @property iconValue 覆盖值（网络封面 url 或本地 content uri），Coil 都能渲染
 */
data class HomeArtistCard(
    val artist: TopArtist,
    val artistKey: String,
    val iconKind: String?,
    val iconValue: String?,
)

data class HomeUiState(
    val greeting: String = "",
    val dateline: String = "",
    val mode: SearchMode = SearchMode.ALL,
    val localTrackCount: Int = 0,
    val recent: List<RecentCard> = emptyList(),
    val playlists: List<HomePlaylistCard> = emptyList(),
    val topArtists: List<HomeArtistCard> = emptyList(),
    /** 搜索框左侧放大镜下拉里的「音乐平台」多选（批四：取代旧内联面板的 chips 块） */
    val platforms: List<PlatformChip> = emptyList(),
    val playingKey: String? = null,
    val isPlaying: Boolean = false,
)

/**
 * 首页（权威依据 `mockup_app.html` 的 `data-view="home"`）。
 *
 * ⚠️ 首页**不做搜索**：批四（2026-10-10）起搜索栏是「输入 + 搜索按钮」，
 * 提交即带着关键词跳**搜索页**；历史 / 常搜只在搜索页出现，首页不展开任何面板。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val history: PlayHistoryRepository,
    private val playlists: PlaylistRepository,
    private val songOverrides: SongOverrideRepository,
    private val artistIcons: ArtistIconOverrideRepository,
    private val localMusic: LocalMusicRepository,
    private val settings: SettingsRepository,
    private val registry: SourceRegistry,
    private val player: PlayerController,
) : ViewModel() {

    private val mode = MutableStateFlow(SearchMode.ALL)

    init {
        viewModelScope.launch {
            localMusic.refreshCache()
            // 恢复上次勾选的平台（空集 = 全开，不动）。按平台遍历，避免别名脚本
            // 不在缓存集合里时把同平台的内置源又关掉（与 SearchViewModel 同一套逻辑）
            val saved = settings.enabledSourceIds.first()
            if (saved.isNotEmpty()) {
                val savedPlatforms = saved.map { SourceLabels.canonical(it) }.toSet()
                registry.all.value
                    .distinctBy { SourceLabels.canonical(it.id.value) }
                    .forEach { registry.setEnabled(it.id, SourceLabels.canonical(it.id.value) in savedPlatforms) }
            }
            // 模式是全局状态：DataStore 是唯一事实源，这里只是镜像一份给本页用
            settings.searchMode.collect { mode.value = it }
        }
    }

    private data class HistoryBundle(
        val rows: List<Song>,
        val overrides: Map<String, SongOverride>,
        val localCount: Int,
    )

    private data class ShelfBundle(
        val playlists: List<PlaylistEntity>,
        val summaries: Map<String, PlaylistSummaryRow>,
        val artists: List<TopArtist>,
        val artistIcons: Map<String, ArtistIconOverrideEntity>,
    )

    private data class PrefBundle(
        val allSources: List<SourceApi>,
        val enabledSources: List<SourceApi>,
    )

    private data class PlayerBundle(val key: String?, val isPlaying: Boolean)

    private val historyBundle = combine(
        history.observeRecent(),
        songOverrides.observe(),
        localMusic.observeTracks(),
    ) { recent, overrides, tracks -> HistoryBundle(recent, overrides, tracks.size) }

    private val shelfBundle = combine(
        playlists.observePlaylists(),
        playlists.observeSummaries(),
        history.observeTopArtists(),
        artistIcons.observeAll(),
    ) { rows, summaries, artists, icons -> ShelfBundle(rows, summaries, artists, icons) }

    private val prefBundle = combine(
        registry.all,
        registry.enabled,
    ) { all, enabled -> PrefBundle(all, enabled) }

    private val playerBundle = player.state.map { state ->
        PlayerBundle(
            key = state.current?.let { "${it.source.value}:${it.id}" },
            isPlaying = state.isPlaying,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        historyBundle,
        shelfBundle,
        prefBundle,
        combine(mode, playerBundle) { currentMode, playing -> currentMode to playing },
    ) { historyData, shelfData, prefs, modeAndPlaying ->
        val (currentMode, playing) = modeAndPlaying
        val sourceNameById = prefs.allSources.associate { it.id.value to it.name }

        HomeUiState(
            greeting = greetingOf(LocalTime.now().hour),
            dateline = datelineOf(
                date = LocalDate.now(),
                localCount = historyData.localCount,
                sourceCount = prefs.allSources.size,
            ),
            mode = currentMode,
            localTrackCount = historyData.localCount,
            recent = historyData.rows.map { song ->
                RecentCard(
                    song = song.withOverride(historyData.overrides[songOverrideKey(song.id, song.source.value)]),
                    // ⚠️ 不能拿「本地」当兜底：导入的 lxmc 播放历史来自 kw/wy/tx 等**没装脚本**
                    // 的音源，注册表查不到名字就误标成「本地」（2026-10-11 用户反馈）。
                    // SourceLabels 内置了 LX-Music 各家音源名，没装的也能正确显示。
                    sourceLabel = SourceLabels.of(song.source.value, sourceNameById),
                )
            },
            playlists = shelfData.playlists.map {
                HomePlaylistCard(
                    id = it.id,
                    name = it.name,
                    itemCount = shelfData.summaries[it.id]?.itemCount ?: 0,
                    coverUrl = shelfData.summaries[it.id]?.coverUrl,
                )
            },
            topArtists = shelfData.artists.map { artist ->
                val key = StatsCalculator.normalizeArtist(artist.singer)
                val icon = shelfData.artistIcons[key]
                HomeArtistCard(
                    artist = artist,
                    artistKey = key,
                    iconKind = icon?.kind,
                    iconValue = icon?.value,
                )
            },
            platforms = buildPlatforms(prefs),
            playingKey = playing.key,
            isPlaying = playing.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** 平台多选：`全部` 是全开总闸（语义与旧面板 chips 一致） */
    private fun buildPlatforms(prefs: PrefBundle): List<PlatformChip> {
        // 按**音乐平台**去重：同一平台可能同时有内置源和多个脚本（id 也可能是别名，
        // 如内置 QQ=tx、脚本=qq），不能一个脚本算一个「平台」（2026-10-27 用户反馈）
        val platforms = prefs.allSources.distinctBy { SourceLabels.canonical(it.id.value) }
        if (platforms.isEmpty()) return emptyList()
        val on = prefs.enabledSources.map { SourceLabels.canonical(it.id.value) }.toSet()
        return buildList {
            add(PlatformChip(ALL_PLATFORMS, "全部", platforms.all { SourceLabels.canonical(it.id.value) in on }))
            platforms.forEach {
                val platform = SourceLabels.canonical(it.id.value)
                add(PlatformChip(platform, it.name, platform in on))
            }
        }
    }

    fun onModeChange(next: SearchMode) {
        if (mode.value == next) return
        mode.value = next
        viewModelScope.launch { settings.setSearchMode(next) }
    }

    fun onPlatformToggle(chipId: String) {
        if (chipId == ALL_PLATFORMS) {
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
        }
    }

    /** 交出关键词前把它写回搜索历史（原型：一旦真的搜了就算一次） */
    fun recordSearch(query: String) {
        viewModelScope.launch { settings.recordSearch(query) }
    }

    /** 点货架卡片：直接起播 */
    fun play(song: Song, queue: List<Song>) {
        viewModelScope.launch { player.play(song, com.lxpro.core.model.Quality.K320, queue.ifEmpty { listOf(song) }) }
    }

    /** 「用播放记录里的封面」的候选：该歌手在播放历史里出现过的封面（去重、最近在前），进弹层时才拉 */
    fun loadArtistCoverCandidates(artistKey: String, onLoaded: (List<String>) -> Unit) {
        viewModelScope.launch { onLoaded(history.artistCoverCandidates(artistKey)) }
    }

    /** 自定义歌手图标：用播放记录里的某张封面 */
    fun setArtistIconFromCover(artistKey: String, coverUrl: String) {
        viewModelScope.launch { artistIcons.setSongCover(artistKey, coverUrl) }
    }

    /** 自定义歌手图标：本地图片（调用方须已 takePersistableUriPermission） */
    fun setArtistIconFromImage(artistKey: String, imageUri: String) {
        viewModelScope.launch { artistIcons.setLocalImage(artistKey, imageUri) }
    }

    /** 恢复默认（token 底色 + 首字） */
    fun clearArtistIcon(artistKey: String) {
        viewModelScope.launch { artistIcons.clear(artistKey) }
    }

    private companion object {
        const val ALL_PLATFORMS = "all"
    }
}

/** 问候语（`mockup_app.html` 的 `refreshGreet`） */
internal fun greetingOf(hour: Int): String = when {
    hour < 5 -> "夜深了"
    hour < 11 -> "早上好"
    hour < 14 -> "中午好"
    hour < 18 -> "下午好"
    hour < 23 -> "晚上好"
    else -> "夜深了"
}

/** 日期行（原型 `refreshGreet` 的 dateline）：`8月12日 星期三 · 本地 3208 首 · 3 个音源已装` */
internal fun datelineOf(date: LocalDate, localCount: Int, sourceCount: Int): String {
    val week = "日一二三四五六"[date.dayOfWeek.value % 7]
    return "${date.monthValue}月${date.dayOfMonth}日 星期$week · 本地 $localCount 首 · $sourceCount 个音源已装"
}