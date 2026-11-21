package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.presentation.search.SearchPresenter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

// 兼容现有引用的类型别名
typealias SearchUiState = cp.player.app.presentation.search.SearchUiState

/**
 * 搜索页的 Voyager 适配层（Thin Adapter）。
 *
 * 核心状态机与业务逻辑全部交由 [SearchPresenter]（UI 框架无关层），
 * 本类只负责在 Voyager 的 [screenModelScope] 中桥接生命周期。
 */
class SearchScreenModel(initialQuery: String = "") : ScreenModel {

    private val presenter = SearchPresenter(
        initialQuery = initialQuery,
        scope = screenModelScope,
    )

    init {
        screenModelScope.launch {
            AppModel.sourceGeneration.drop(1).collect {
                presenter.loadHotSearches()
            }
        }
    }

    val state: StateFlow<SearchUiState> get() = presenter.state

    fun setQuery(query: String) {
        presenter.setQuery(query)
    }

    fun clear() {
        presenter.clear()
    }

    fun clearHistory() {
        presenter.clearHistory()
    }

    fun selectSearchType(type: Int) {
        presenter.selectSearchType(type)
    }

    fun search(keyword: String = state.value.query, type: Int = state.value.searchType) {
        presenter.search(keyword, type)
    }
}
