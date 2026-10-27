package com.lxpro.feature.search

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.FlowRandom
import com.lxpro.core.designsystem.component.AsyncArtwork
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXDimens
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.core.designsystem.theme.LXType
import com.lxpro.core.designsystem.theme.lxSafeDrawingPadding
import com.lxpro.core.model.Song
import com.lxpro.core.model.SongFingerprint
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

const val ARG_AA_KIND = "kind"
const val ARG_AA_KEY = "key"
const val ROUTE_ARTIST_ALBUM =
    "artistalbum?$ARG_AA_KIND={$ARG_AA_KIND}&$ARG_AA_KEY={$ARG_AA_KEY}"

/** 歌手 / 专辑详情（结构同源，见 06 M3 任务 10） */
fun artistAlbumRoute(kind: String, key: String) =
    "artistalbum?$ARG_AA_KIND=$kind&$ARG_AA_KEY=${Uri.encode(key)}"

enum class ListKind(val id: String, val label: String) {
    ARTIST("artist", "歌手"),
    ALBUM("album", "专辑"),
    ;

    companion object {
        fun fromId(value: String?): ListKind = entries.firstOrNull { it.id == value } ?: ARTIST
    }
}

data class ArtistAlbumState(
    val kind: ListKind = ListKind.ARTIST,
    val key: String = "",
    val loading: Boolean = true,
    val songs: List<Song> = emptyList(),
)

/**
 * 歌手 / 专辑详情的数据。
 *
 * ⚠️ 音源接口**只有关键字检索**（`SourceApi.search`），没有「按歌手/专辑取全部作品」的接口，
 * 所以这里如实做：用该名字在**已启用音源**里检索，再按歌手名 / 专辑名归类过滤、按指纹去重。
 * 结果以音源实际返回为准，不编造「全部作品」。
 *
 * 只在**在线模式**可达（本地模式搜索页不归纳歌手/专辑分组，见 SearchScreen）。
 */
@HiltViewModel
class ArtistAlbumViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val registry: SourceRegistry,
) : ViewModel() {

    val kind: ListKind = ListKind.fromId(savedState.get<String>(ARG_AA_KIND))
    private val key: String = savedState.get<String>(ARG_AA_KEY).orEmpty()

    private val _state = MutableStateFlow(ArtistAlbumState(kind = kind, key = key))
    val state: StateFlow<ArtistAlbumState> = _state.asStateFlow()

    init {
        load()
    }

    fun reload() = load()

    private fun load() {
        val keyword = key.trim()
        if (keyword.isEmpty()) {
            _state.value = ArtistAlbumState(kind = kind, key = keyword, loading = false)
            return
        }
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val found = registry.enabled.value
                .mapNotNull { api ->
                    runCatching { api.search(keyword, page = 1, limit = SEARCH_LIMIT).list }.getOrNull()
                }
                .flatten()
            val songs = found
                .filter { matches(it, keyword) }
                .distinctBy { SongFingerprint.of(it) }
            _state.value = ArtistAlbumState(
                kind = kind,
                key = keyword,
                loading = false,
                songs = songs,
            )
        }
    }

    private fun matches(song: Song, keyword: String): Boolean = when (kind) {
        ListKind.ARTIST ->
            song.singer.trim() == keyword ||
                song.singer.split("、", "&", "/", ",", "，").any { it.trim() == keyword }

        ListKind.ALBUM -> song.albumName?.trim() == keyword
    }

    private companion object {
        /** 详情页不做翻页：一次取足一页结果再归类（超出部分按指纹去重后截断） */
        const val SEARCH_LIMIT = 50
    }
}

/**
 * 歌手 / 专辑详情页（权威稿 `mockup_app.html` 的 `#pg-artist` / `#pg-album`）。
 * 结构：顶栏（返回 + 面包屑）→ Hero（头像/封面 + 名字 + 汇总）→ 播放全部 / 随机 → 歌曲列表。
 *
 * ⚠️ 原型里的「相似歌手」与「专辑简介」没有真实数据来源，**不编造**，故不实现。
 */
@Composable
fun ArtistAlbumScreen(
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMoreSong: (Song) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistAlbumViewModel = hiltViewModel(),
) {
    val colors = LXTheme.colors
    val scalars = LXTheme.scalars
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queue = state.songs

    Column(
        modifier = modifier
            .fillMaxSize()
            .lxSafeDrawingPadding(),
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = scalars.sidePadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(LxIcons.Back, contentDescription = "返回", tint = colors.ink1)
            }
            Text(
                text = state.kind.label,
                style = LXType.titleSmall,
                color = colors.ink1,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = scalars.sidePadding,
                end = scalars.sidePadding,
                bottom = scalars.sectionGap,
            ),
        ) {
            item(key = "hero") {
                Hero(state = state)
            }

            item(key = "actions") {
                ActionRow(
                    enabled = state.songs.isNotEmpty(),
                    onPlayAll = { state.songs.firstOrNull()?.let { onPlaySong(it, queue) } },
                    onShuffle = {
                        val shuffled = FlowRandom.shuffled(state.songs)
                        shuffled.firstOrNull()?.let { onPlaySong(it, shuffled) }
                    },
                )
            }

            item(key = "head") {
                Text(
                    text = if (state.kind == ListKind.ALBUM) "专辑曲目" else "全部歌曲",
                    style = LXType.titleSmall,
                    color = colors.ink1,
                    modifier = Modifier.padding(top = scalars.sectionGap, bottom = 11.dp),
                )
            }

            when {
                state.loading -> item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }

                state.songs.isEmpty() -> item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "没有找到${state.kind.label}「${state.key}」的曲目",
                            style = LXType.bodyMedium,
                            color = colors.ink2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "音源只提供关键字检索，结果以实际返回为准",
                            style = LXType.labelSmall,
                            color = colors.ink3,
                        )
                    }
                }

                else -> items(state.songs, key = { SongFingerprint.of(it) }) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song, queue) },
                        onMore = { onMoreSong(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(state: ArtistAlbumState) {
    val colors = LXTheme.colors
    val cover = state.songs.firstOrNull { !it.picUrl.isNullOrBlank() }?.picUrl
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.kind == ListKind.ARTIST) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(LXTheme.pageInset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.key.take(1),
                    style = LXType.displaySmall,
                    color = colors.ink2,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(LXDimens.radiusMedium))
                    .background(LXTheme.pageInset),
            ) {
                AsyncArtwork(
                    model = cover,
                    contentDescription = state.key,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.key,
                style = LXType.headlineSmall,
                color = colors.ink1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.loading) "检索中…" else "${state.songs.size} 首 · 由音源检索归纳",
                style = LXType.labelSmall,
                color = colors.ink3,
            )
        }
    }
}

@Composable
private fun ActionRow(enabled: Boolean, onPlayAll: () -> Unit, onShuffle: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(if (enabled) colors.accent else colors.inset)
                .clickable(enabled = enabled, onClick = onPlayAll)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = LxIcons.Play,
                contentDescription = null,
                tint = if (enabled) colors.bg else colors.ink3,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "播放全部",
                style = LXType.labelLarge,
                color = if (enabled) colors.bg else colors.ink3,
            )
        }
        IconButton(onClick = onShuffle, enabled = enabled) {
            Icon(
                imageVector = LxIcons.Shuffle,
                contentDescription = "随机播放",
                tint = if (enabled) colors.ink2 else colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit, onMore: () -> Unit) {
    val colors = LXTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(LXDimens.coverList)
                .clip(RoundedCornerShape(10.dp))
                .background(LXTheme.pageInset),
        ) {
            AsyncArtwork(
                model = song.picUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = LXType.labelLarge,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = song.singer,
                style = LXType.labelSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        song.interval?.let { seconds ->
            Text(text = formatDuration(seconds), style = LXType.labelSmall, color = colors.ink3)
        }
        IconButton(onClick = onMore) {
            Icon(
                imageVector = LxIcons.More,
                contentDescription = "更多",
                tint = colors.ink3,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}