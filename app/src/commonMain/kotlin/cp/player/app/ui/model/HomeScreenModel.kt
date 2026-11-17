package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.home.HomeContract
import cp.player.app.presentation.home.HomePresenter
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias HomeUiState = cp.player.app.presentation.home.HomeUiState
typealias PlaylistSource = cp.player.app.presentation.home.PlaylistSource
typealias NewSongRegion = cp.player.app.presentation.home.NewSongRegion

/**
 * 首页的 Voyager 适配层（Thin Adapter）。
 *
 * 全部状态机与业务逻辑位于框架中立的 [HomePresenter]，
 * 本类只桥接 `screenModelScope` 与操作提示。
 */
class HomeScreenModel(
    loadDiscovery: Boolean = true,
) : ScreenModel, HomeContract {

    private val presenter = HomePresenter(
        scope = screenModelScope,
        loadDiscovery = loadDiscovery,
        notify = UiEvents::notify,
    )

    override val state: StateFlow<HomeUiState> get() = presenter.state
    override val refreshing: StateFlow<Boolean> get() = presenter.refreshing
    override val scope: CoroutineScope get() = presenter.scope

    override fun playPersonalFm(strings: CpStrings) = presenter.playPersonalFm(strings)

    override fun isServerPlaylist(playlist: PlaylistSummary): Boolean = presenter.isServerPlaylist(playlist)

    override fun isPlaylistOwner(playlist: PlaylistSummary): Boolean = presenter.isPlaylistOwner(playlist)

    override fun playPlaylist(playlist: PlaylistSummary, strings: CpStrings) =
        presenter.playPlaylist(playlist, strings)

    override fun queuePlaylist(playlist: PlaylistSummary, strings: CpStrings) =
        presenter.queuePlaylist(playlist, strings)

    override fun downloadPlaylist(playlist: PlaylistSummary, strings: CpStrings) =
        presenter.downloadPlaylist(playlist, strings)

    override fun deleteOrUnsubscribePlaylist(playlist: PlaylistSummary, strings: CpStrings) =
        presenter.deleteOrUnsubscribePlaylist(playlist, strings)

    override fun refresh(force: Boolean) = presenter.refresh(force)

    override fun selectPlaylistSource(source: PlaylistSource) = presenter.selectPlaylistSource(source)

    override fun selectNewSongRegion(region: NewSongRegion) = presenter.selectNewSongRegion(region)
}
