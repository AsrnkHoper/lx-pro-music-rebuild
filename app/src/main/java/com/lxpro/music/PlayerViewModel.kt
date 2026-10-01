package com.lxpro.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.media.PlayerController
import com.lxpro.core.media.PlayerState
import com.lxpro.core.model.Quality
import com.lxpro.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放器 UI 状态。播放器本身是应用级单例（[PlayerController]），
 * 所以这里的 ViewModel 只是把它暴露给 Compose。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlayerController,
) : ViewModel() {

    val state: StateFlow<PlayerState> = controller.state
    val queue: StateFlow<List<Song>> = controller.queue

    fun play(song: Song, queue: List<Song>) {
        viewModelScope.launch { controller.play(song, Quality.K320, queue) }
    }

    fun togglePlayPause() = controller.togglePlayPause()

    fun next() {
        viewModelScope.launch { controller.next() }
    }

    fun previous() {
        viewModelScope.launch { controller.previous() }
    }

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
}