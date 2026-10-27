package com.lxpro.music.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.database.entity.SongTagEntity
import com.lxpro.core.library.SongTagFacts
import com.lxpro.core.library.SongTagRepository
import com.lxpro.core.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SongTagsUiState(
    val song: Song? = null,
    /** 软件自贴标签的原始数据（UI 负责措辞） */
    val facts: SongTagFacts = SongTagFacts(),
    /** 用户文字标签（按贴上的先后） */
    val tags: List<SongTagEntity> = emptyList(),
)

/**
 * 歌曲标签页（2026-10-26 用户要求第 8 项）。
 *
 * 由 [com.lxpro.core.model.SongTagBus] 触发：app 层的两个歌曲操作弹层入口
 * （跨页面共用 / 歌单详情）只发「看这首歌的标签」，本 VM 负责取数据与增删。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SongTagsViewModel @Inject constructor(
    private val repository: SongTagRepository,
) : ViewModel() {

    private val song = MutableStateFlow<Song?>(null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<SongTagsUiState> = song
        .flatMapLatest { current ->
            if (current == null) {
                flowOf(SongTagsUiState())
            } else {
                combine(
                    repository.observeUserTags(current),
                    repository.observeFacts(current),
                ) { tags, facts -> SongTagsUiState(song = current, facts = facts, tags = tags) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongTagsUiState())

    fun show(song: Song) {
        _message.value = null
        this.song.value = song
    }

    fun dismiss() {
        song.value = null
        _message.value = null
    }

    fun addTag(raw: String) {
        val current = song.value ?: return
        viewModelScope.launch {
            _message.value = repository.addTag(current, raw)
        }
    }

    fun removeTag(tag: SongTagEntity) {
        viewModelScope.launch { repository.removeTag(tag) }
    }

    fun clearMessage() {
        _message.value = null
    }
}
