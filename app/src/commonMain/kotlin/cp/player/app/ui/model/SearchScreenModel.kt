package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.kmp.BackendResult
import cp.player.kmp.api.MusicApiMethod
import cp.player.kmp.music.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val SEARCH_HISTORY_KEY = "search_history"
private const val MAX_SEARCH_HISTORY = 10

data class HotSearch(
    val keyword: String,
    val description: String = "",
)

data class SearchUiState(
    val query: String = "",
    val searchType: Int = MusicApiMethod.SEARCH_TYPE_SONG,
    val result: SearchResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val hotSearches: List<HotSearch> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
)

class SearchScreenModel(private val initialQuery: String = "") : ScreenModel {
    private val _state = MutableStateFlow(SearchUiState(query = initialQuery))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var suggestionJob: Job? = null

    init {
        _state.value = _state.value.copy(searchHistory = readHistory())
        loadHotSearches()
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    private fun readHistory(): List<String> = AppModel.settings
        .getString(SEARCH_HISTORY_KEY)
        ?.split("\n")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        ?.take(MAX_SEARCH_HISTORY)
        ?: emptyList()

    private fun saveHistory(history: List<String>) {
        AppModel.settings.putString(SEARCH_HISTORY_KEY, history.joinToString("\n"))
    }

    private fun loadHotSearches() {
        screenModelScope.launch {
            val response = runCatching { AppModel.musicRepository.getHotSearches() }.getOrNull()
            val hot = response?.parseHotSearches().orEmpty()
            _state.value = _state.value.copy(
                hotSearches = hot.ifEmpty {
                    listOf(HotSearch("热门歌曲"), HotSearch("流行音乐"), HotSearch("古典乐"))
                },
            )
        }
    }

    private fun JsonElement.parseHotSearches(): List<HotSearch> {
        val root = this as? JsonObject ?: return emptyList()
        val array = (root["data"] as? JsonArray)
            ?: (root["result"] as? JsonArray)
            ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val keyword = (obj["searchWord"] ?: obj["keyword"] ?: obj["word"])
                ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (keyword.isEmpty()) null else HotSearch(
                keyword = keyword,
                description = (obj["content"] ?: obj["score"])
                    ?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.distinctBy { it.keyword }.take(20)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query, error = null, result = null)
        suggestionJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(suggestions = emptyList())
            return
        }
        suggestionJob = screenModelScope.launch {
            delay(250)
            val requestedQuery = query.trim()
            val response = runCatching { AppModel.musicRepository.getSearchSuggestions(requestedQuery) }.getOrNull()
            if (_state.value.query.trim() != requestedQuery) return@launch
            val suggestions = response?.parseSuggestions().orEmpty()
                .filterNot { it.equals(requestedQuery, ignoreCase = true) }
                .distinct()
                .take(8)
            _state.value = _state.value.copy(suggestions = suggestions)
        }
    }

    private fun JsonElement.parseSuggestions(): List<String> {
        val root = this as? JsonObject ?: return emptyList()
        val result = root["result"] as? JsonObject ?: root
        val array = (result["allMatch"] ?: result["suggestions"] ?: result["data"]) as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> (item["keyword"] ?: item["name"] ?: item["word"])
                    ?.jsonPrimitive?.contentOrNull
                else -> null
            }?.trim()?.takeIf(String::isNotEmpty)
        }
    }

    fun clear() {
        suggestionJob?.cancel()
        _state.value = _state.value.copy(query = "", result = null, error = null, suggestions = emptyList())
    }

    fun clearHistory() {
        saveHistory(emptyList())
        _state.value = _state.value.copy(searchHistory = emptyList())
    }

    fun selectSearchType(type: Int) {
        if (type == _state.value.searchType) return
        _state.value = _state.value.copy(searchType = type)
        if (_state.value.query.isNotBlank()) search()
    }

    fun search(keyword: String = _state.value.query, type: Int = _state.value.searchType) {
        val finalKeyword = keyword.trim()
        if (finalKeyword.isEmpty() || _state.value.loading) return
        suggestionJob?.cancel()
        val history = listOf(finalKeyword) + _state.value.searchHistory.filterNot { it.equals(finalKeyword, true) }
        val trimmedHistory = history.take(MAX_SEARCH_HISTORY)
        saveHistory(trimmedHistory)
        _state.value = _state.value.copy(
            query = finalKeyword,
            searchType = type,
            searchHistory = trimmedHistory,
            suggestions = emptyList(),
            result = null,
        )
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val response = runCatching { AppModel.musicRepository.search(finalKeyword, type) }
                .getOrElse { BackendResult.Error(it.message ?: "搜索失败") }
            _state.value = when (response) {
                is BackendResult.Success -> _state.value.copy(result = response.data, loading = false)
                is BackendResult.Error -> _state.value.copy(error = response.message, loading = false)
                is BackendResult.Unsupported -> _state.value.copy(error = response.message, loading = false)
            }
        }
    }
}
