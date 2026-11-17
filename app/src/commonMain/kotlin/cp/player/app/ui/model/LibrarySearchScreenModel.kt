package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.presentation.librarysearch.LibrarySearchContract
import cp.player.app.presentation.librarysearch.LibrarySearchPresenter
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias LibraryTrackHit = cp.player.app.presentation.librarysearch.LibraryTrackHit
typealias LibrarySearchUiState = cp.player.app.presentation.librarysearch.LibrarySearchUiState

/** 歌单歌曲搜索的 Voyager 适配层（Thin Adapter）。 */
class LibrarySearchScreenModel : ScreenModel, LibrarySearchContract {

    private val presenter = LibrarySearchPresenter(scope = screenModelScope)

    override val state: StateFlow<LibrarySearchUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun search(query: String, playlists: List<PlaylistSummary>) =
        presenter.search(query, playlists)
}
