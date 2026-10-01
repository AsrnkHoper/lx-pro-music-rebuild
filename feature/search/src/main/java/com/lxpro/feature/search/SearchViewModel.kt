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
    val results: List<Song> = emptyList(),
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
) : ViewModel() {

    private val keywordInput = MutableStateFlow("")

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var currentQuery: String = ""

    init {
        viewModelScope.launch {
            keywordInput
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { raw -> search(raw, page = 1) }
        }
    }

    /** 输入框每次变化都调它；真正的请求由防抖后的流触发 */
    fun onKeywordChange(value: String) {
        keywordInput.value = value
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        viewModelScope.launch { search(currentQuery, page = state.page + 1) }
    }

    fun retry() {
        viewModelScope.launch { search(currentQuery, page = 1) }
    }

    private suspend fun search(raw: String, page: Int) {
        // 归一化后为空（纯符号）必须回落初始态，不能匹配全部（06 §M1.2）
        if (!TextNormalizer.isSearchable(raw)) {
            currentQuery = ""
            _uiState.value = SearchUiState()
            return
        }
        val source = registry.enabled.value.firstOrNull()
        if (source == null) {
            _uiState.value = SearchUiState(error = "没有可用的音源", searched = true)
            return
        }

        currentQuery = raw
        val isFirstPage = page == 1
        _uiState.value = _uiState.value.copy(
            loading = isFirstPage,
            loadingMore = !isFirstPage,
            error = null,
            results = if (isFirstPage) emptyList() else _uiState.value.results,
        )

        runCatching { source.search(raw, page = page, limit = PAGE_SIZE) }
            .onSuccess { result ->
                val merged = (if (isFirstPage) result.list else _uiState.value.results + result.list)
                    // 分页去重：LazyColumn 的 key 不能重复，否则运行时崩溃
                    .distinctBy { "${it.source.value}:${it.id}" }
                _uiState.value = SearchUiState(
                    results = merged,
                    hasMore = result.hasMore,
                    page = page,
                    searched = true,
                    error = if (merged.isEmpty()) "没有找到结果" else null,
                )
            }
            .onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    loadingMore = false,
                    searched = true,
                    error = throwable.message ?: "搜索失败",
                )
            }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val PAGE_SIZE = 30
    }
}