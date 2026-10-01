package com.lxpro.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lxpro.core.designsystem.ambient.LXAmbientBackground
import com.lxpro.core.designsystem.component.LXMiniPlayerBar
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.feature.home.HomeScreen
import com.lxpro.feature.player.PlayerScreen
import com.lxpro.feature.search.SearchScreen
import com.lxpro.music.ui.AboutScreen
import dagger.hilt.android.AndroidEntryPoint

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val PLAYER = "player"
    const val ABOUT = "about"
}

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

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            NavHost(
                                navController = navController,
                                startDestination = Routes.HOME,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                composable(Routes.HOME) {
                                    HomeScreen(
                                        palette = palette,
                                        onPaletteSelected = appViewModel::selectPalette,
                                        ambientEnabled = ambientEnabled,
                                        onAmbientChange = appViewModel::setAmbientEnabled,
                                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                                    )
                                }
                                composable(Routes.SEARCH) {
                                    SearchScreen(
                                        onSongClick = { song, queue ->
                                            playerViewModel.play(song, queue)
                                        },
                                    )
                                }
                                composable(Routes.PLAYER) {
                                    PlayerScreen(
                                        state = playerState,
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
                    }
                }
            }
        }
    }
}