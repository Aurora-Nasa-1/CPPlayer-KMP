package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.kmp.BackendResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val result: SearchResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val hotSearches: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
)

class SearchScreenModel : ScreenModel {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        loadHotSearches()
    }

    private fun loadHotSearches() {
        screenModelScope.launch {
            val response = runCatching { AppModel.api.getHotSearches() }.getOrNull()
            // 简单处理，实际应解析 JSON
            _state.value = _state.value.copy(hotSearches = listOf("热门歌曲", "流行音乐", "古典乐"))
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query, error = null)
        if (query.isNotEmpty()) {
            updateSuggestions(query)
        } else {
            _state.value = _state.value.copy(suggestions = emptyList())
        }
    }

    private fun updateSuggestions(query: String) {
        screenModelScope.launch {
            val response = runCatching { AppModel.api.getSearchSuggestions(query) }.getOrNull()
            // 简单模拟
            _state.value = _state.value.copy(suggestions = listOf("$query 1", "$query 2"))
        }
    }

    fun clear() { _state.value = SearchUiState(hotSearches = _state.value.hotSearches) }

    fun search(keyword: String = _state.value.query) {
        val finalKeyword = keyword.trim()
        if (finalKeyword.isEmpty() || _state.value.loading) return
        _state.value = _state.value.copy(query = finalKeyword)
        val keyword = _state.value.query.trim()
        if (keyword.isEmpty() || _state.value.loading) return
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val response = runCatching { MusicSourceFromApi.search(AppModel.api, keyword) }
                .getOrElse { BackendResult.Error(it.message ?: "搜索失败") }
            _state.value = when (response) {
                is BackendResult.Success -> _state.value.copy(result = response.data, loading = false)
                is BackendResult.Error -> _state.value.copy(error = response.message, loading = false)
                is BackendResult.Unsupported -> _state.value.copy(error = response.message, loading = false)
            }
        }
    }
}
