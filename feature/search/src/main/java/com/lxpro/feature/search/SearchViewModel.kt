package com.lxpro.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxpro.core.common.TextNormalizer
import com.lxpro.core.model.Song
import com.lxpro.source.api.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    /** 归一化后为空的输入（如纯符号）必须回落初始态，不能匹配全部（06 §M1.2） */
    val keyword: String = "",
    val loading: Boolean = false,
    val results: List<Song> = emptyList(),
    val error: String? = null,
) {
    val isInitial: Boolean get() = keyword.isBlank() && results.isEmpty() && error == null
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val registry: SourceRegistry,
) : ViewModel() {

    private val keywordInput = MutableStateFlow("")

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            keywordInput
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { raw -> runSearch(raw) }
        }
    }

    fun onKeywordChange(value: String) {
        keywordInput.value = value
    }

    fun retry() {
        viewModelScope.launch { runSearch(keywordInput.value) }
    }

    private suspend fun runSearch(raw: String) {
        if (!TextNormalizer.isSearchable(raw)) {
            _uiState.value = SearchUiState(keyword = raw)
            return
        }
        val source = registry.enabled.value.firstOrNull()
        if (source == null) {
            _uiState.value = SearchUiState(keyword = raw, error = "没有可用的音源")
            return
        }
        _uiState.value = SearchUiState(keyword = raw, loading = true)
        runCatching { source.search(raw, page = 1, limit = 30) }
            .onSuccess { result ->
                _uiState.value = SearchUiState(
                    keyword = raw,
                    results = result.list,
                    error = if (result.list.isEmpty()) "没有找到结果" else null,
                )
            }
            .onFailure { throwable ->
                _uiState.value = SearchUiState(
                    keyword = raw,
                    error = throwable.message ?: "搜索失败",
                )
            }
    }
}