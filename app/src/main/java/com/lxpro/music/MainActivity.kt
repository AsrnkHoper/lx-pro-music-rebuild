package com.lxpro.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lxpro.core.designsystem.ambient.LXAmbientBackground
import com.lxpro.core.designsystem.component.LXBottomBar
import com.lxpro.core.designsystem.component.LXMiniPlayerBar
import com.lxpro.core.designsystem.component.LXTabItem
import com.lxpro.core.designsystem.component.LxIcons
import com.lxpro.core.designsystem.theme.LXMotion
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.feature.home.HomeScreen
import com.lxpro.feature.library.LocalLibraryScreen
import com.lxpro.feature.player.LyricsViewModel
import com.lxpro.feature.player.PlayerScreen
import com.lxpro.feature.playlist.ARG_PLAYLIST_ID
import com.lxpro.feature.playlist.PlaylistDetailScreen
import com.lxpro.feature.playlist.PlaylistDetailViewModel
import com.lxpro.feature.playlist.PlaylistsScreen
import com.lxpro.feature.playlist.PlaylistsViewModel
import com.lxpro.feature.search.SearchScreen
import com.lxpro.music.ui.AboutScreen
import com.lxpro.music.ui.ComingSoonScreen
import dagger.hilt.android.AndroidEntryPoint

object Routes {
    // ── 5 个 Tab 根（15 §2：首页 · 歌单 · 统计 · 白噪音 · 设置）──
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val STATS = "stats"
    const val NOISE = "noise"
    const val SETTINGS = "settings"

    // ── 二级 / 独立全屏 ──
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val PLAYER = "player"
    const val ABOUT = "about"
    const val PLAYLIST_DETAIL = "playlists/{$ARG_PLAYLIST_ID}"

    fun playlistDetail(playlistId: String) = "playlists/$playlistId"
}

/** Tab 顺序按 12 §2 #1 裁决：白噪音为第 4 项，设置保持最后一个 */
private val TAB_ITEMS = listOf(
    LXTabItem(Routes.HOME, "首页", LxIcons.Home),
    LXTabItem(Routes.PLAYLISTS, "歌单", LxIcons.Playlist),
    LXTabItem(Routes.STATS, "统计", LxIcons.Stats),
    LXTabItem(Routes.NOISE, "白噪音", LxIcons.Noise),
    LXTabItem(Routes.SETTINGS, "设置", LxIcons.Settings),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val playerViewModel: PlayerViewModel = hiltViewModel()

            val palette by appViewModel.palette.collectAsStateWithLifecycle()
            val ambientEnabled by appViewModel.ambientEnabled.collectAsStateWithLifecycle()
            val playerState by playerViewModel.state.collectAsStateWithLifecycle()

            LXTheme(palette = palette) {
                LXAmbientBackground(enabled = ambientEnabled) {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    // Tab 栏只在 5 个根页出现（14 §4.1：详情页/播放页全屏沉浸）
                    val isTabRoot = TAB_ITEMS.any { it.route == currentRoute }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            NavHost(
                                navController = navController,
                                startDestination = Routes.HOME,
                                modifier = Modifier.fillMaxSize(),
                                // 默认 = Tab 切换：交叉淡入淡出（不做横向滑动，避免与返回手势冲突）
                                enterTransition = { fadeIn(tween(LXMotion.tabCrossFade)) },
                                exitTransition = { fadeOut(tween(LXMotion.tabCrossFade)) },
                                // 返回：当前页右滑出，被返回的那页轻微左移（视差 28%）
                                popEnterTransition = {
                                    slideInHorizontally(tween(LXMotion.exitDetail)) { full ->
                                        -(full * LXMotion.parallaxReturn).toInt()
                                    }
                                },
                                popExitTransition = {
                                    slideOutHorizontally(tween(LXMotion.exitDetail)) { full -> full }
                                },
                            ) {
                                composable(Routes.HOME) {
                                    HomeScreen(
                                        palette = palette,
                                        onPaletteSelected = appViewModel::selectPalette,
                                        ambientEnabled = ambientEnabled,
                                        onAmbientChange = appViewModel::setAmbientEnabled,
                                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                                        onOpenLocalLibrary = { navController.navigate(Routes.LIBRARY) },
                                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                                    )
                                }

                                composable(Routes.PLAYLISTS) {
                                    val playlistsViewModel: PlaylistsViewModel = hiltViewModel()
                                    val state by playlistsViewModel.uiState.collectAsStateWithLifecycle()
                                    PlaylistsScreen(
                                        state = state,
                                        onQueryChange = playlistsViewModel::onQueryChange,
                                        onCreatePlaylist = playlistsViewModel::createPlaylist,
                                        onOpenPlaylist = { id ->
                                            navController.navigate(Routes.playlistDetail(id))
                                        },
                                    )
                                }

                                composable(
                                    route = Routes.PLAYLIST_DETAIL,
                                    // 进详情：从右侧滑入（300ms）
                                    enterTransition = {
                                        slideInHorizontally(tween(LXMotion.enterDetail)) { it }
                                    },
                                    exitTransition = { fadeOut(tween(LXMotion.quick)) },
                                ) {
                                    val detailViewModel: PlaylistDetailViewModel = hiltViewModel()
                                    val state by detailViewModel.uiState.collectAsStateWithLifecycle()
                                    PlaylistDetailScreen(
                                        state = state,
                                        onBack = { navController.popBackStack() },
                                        onPlayAll = { songs ->
                                            songs.firstOrNull()?.let { first ->
                                                playerViewModel.play(first, songs)
                                            }
                                        },
                                        onSongClick = { song, queue ->
                                            playerViewModel.play(song, queue)
                                        },
                                        onRename = detailViewModel::rename,
                                        onDelete = { detailViewModel.delete { navController.popBackStack() } },
                                        onRemoveSong = detailViewModel::removeSong,
                                    )
                                }

                                composable(Routes.STATS) {
                                    ComingSoonScreen(
                                        title = "统计",
                                        milestone = "M3 任务 7",
                                        description = "六档时间维度 + 固定 10 个区块 + 歌手归并。\n依据 mockup_stats.html。",
                                    )
                                }
                                composable(Routes.NOISE) {
                                    ComingSoonScreen(
                                        title = "白噪音",
                                        milestone = "M4 声音层",
                                        description = "场景 = 图层叠加；28 音源 + 8 预设；睡眠定时。\n依据 mockup_noise.html 与 17。",
                                    )
                                }
                                composable(Routes.SETTINGS) {
                                    ComingSoonScreen(
                                        title = "设置",
                                        milestone = "M3 任务 9",
                                        description = "分类 hub（9 分类）+ 详情页 + 跨分类搜索。\n" +
                                            "现在「外观 / 本地音乐 / 关于」暂时还挂在首页上。",
                                    )
                                }

                                composable(Routes.SEARCH) {
                                    SearchScreen(
                                        onSongClick = { song, queue ->
                                            playerViewModel.play(song, queue)
                                        },
                                    )
                                }
                                composable(Routes.LIBRARY) {
                                    LocalLibraryScreen(
                                        onSongClick = { song, queue ->
                                            playerViewModel.play(song, queue)
                                        },
                                    )
                                }
                                composable(
                                    route = Routes.PLAYER,
                                    // 打开播放页：从底部滑入全屏（对齐「从迷你条上拉」的直觉）
                                    enterTransition = {
                                        slideInVertically(tween(LXMotion.playerEnter)) { it }
                                    },
                                    // 关闭播放页：向下滑出
                                    popExitTransition = {
                                        slideOutVertically(tween(LXMotion.playerExit)) { it }
                                    },
                                ) {
                                    // 歌词 VM 挂在播放页的路由作用域上：只在打开播放页时取词
                                    val lyricsViewModel: LyricsViewModel = hiltViewModel()
                                    val lyricsState by lyricsViewModel.uiState
                                        .collectAsStateWithLifecycle()
                                    PlayerScreen(
                                        state = playerState,
                                        lyrics = lyricsState,
                                        onBack = { navController.popBackStack() },
                                        onTogglePlay = playerViewModel::togglePlayPause,
                                        onPrevious = playerViewModel::previous,
                                        onNext = playerViewModel::next,
                                        onSeek = playerViewModel::seekTo,
                                    )
                                }
                                composable(Routes.ABOUT) {
                                    AboutScreen(onBack = { navController.popBackStack() })
                                }
                            }
                        }

                        // 迷你条：有歌才出现；播放页自己不带（避免重复）
                        playerState.current?.let { song ->
                            if (currentRoute != Routes.PLAYER) {
                                LXMiniPlayerBar(
                                    title = song.name,
                                    subtitle = song.singer,
                                    coverUrl = song.picUrl,
                                    isPlaying = playerState.isPlaying,
                                    isBuffering = playerState.isBuffering,
                                    onTogglePlay = playerViewModel::togglePlayPause,
                                    onPrevious = playerViewModel::previous,
                                    onNext = playerViewModel::next,
                                    onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                                )
                            }
                        }

                        if (isTabRoot) {
                            LXBottomBar(
                                items = TAB_ITEMS,
                                selectedRoute = currentRoute,
                                onSelect = { item ->
                                    // 每个 Tab 记住自己的返回栈（15 §3）：从歌单详情返回应回到歌单 Tab
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}