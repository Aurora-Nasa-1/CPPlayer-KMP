package cp.player.app.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cp.player.app.presentation.library.LibraryPresenter
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.flow.StateFlow

// 兼容现有引用的类型别名
typealias LibraryUiState = cp.player.app.presentation.library.LibraryUiState

/**
 * 媒体库页面的 Voyager 适配层（Thin Adapter）。
 *
 * 核心状态机交由框架中立的 [LibraryPresenter] 处理。
 */
class LibraryScreenModel(initialPlaylistId: Long? = null) : ViewModel() {

    private val presenter = LibraryPresenter(
        initialPlaylistId = initialPlaylistId,
        scope = viewModelScope,
        notify = UiEvents::notify,
    )

    val state: StateFlow<LibraryUiState> get() = presenter.state
    val refreshing: StateFlow<Boolean> get() = presenter.refreshing

    fun selectPlaylist(playlistId: Long?) {
        presenter.selectPlaylist(playlistId)
    }

    fun selectTab(index: Int) {
        presenter.selectTab(index)
    }

    fun refresh() {
        presenter.refresh()
    }

    fun isOwner(playlist: PlaylistSummary): Boolean {
        return presenter.isOwner(playlist)
    }

    fun deleteOrUnsubscribe(playlist: PlaylistSummary) {
        presenter.deleteOrUnsubscribe(playlist)
    }

    fun play(playlist: PlaylistSummary, addOnly: Boolean = false) {
        presenter.play(playlist, addOnly)
    }

    fun download(playlist: PlaylistSummary) {
        presenter.download(playlist)
    }
}
