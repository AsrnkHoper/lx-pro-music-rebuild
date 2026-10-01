package com.lxpro.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lxpro.core.designsystem.ambient.LXAmbientBackground
import com.lxpro.core.designsystem.theme.LXTheme
import com.lxpro.feature.home.HomeScreen
import com.lxpro.music.ui.AboutScreen
import dagger.hilt.android.AndroidEntryPoint

object Routes {
    const val HOME = "home"
    const val ABOUT = "about"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: AppViewModel = hiltViewModel()
            val palette by viewModel.palette.collectAsStateWithLifecycle()
            val ambientEnabled by viewModel.ambientEnabled.collectAsStateWithLifecycle()

            LXTheme(palette = palette) {
                LXAmbientBackground(enabled = ambientEnabled) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                palette = palette,
                                onPaletteSelected = viewModel::selectPalette,
                                ambientEnabled = ambientEnabled,
                                onAmbientChange = viewModel::setAmbientEnabled,
                                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                            )
                        }
                        composable(Routes.ABOUT) {
                            AboutScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}