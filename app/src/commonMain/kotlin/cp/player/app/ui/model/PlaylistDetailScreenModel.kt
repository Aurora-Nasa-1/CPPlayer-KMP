package cp.player.app.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.playlistdetail.PlaylistDetailContract
import cp.player.app.presentation.playlistdetail.PlaylistDetailPresenter
import cp.player.app.presentation.playlistdetail.PlaylistSortType
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias PlaylistDetailUiState = cp.player.app.presentation.playlistdetail.PlaylistDetailUiState

/**
 * 歌单详情页的 Voyager 适配层（Thin Adapter）。
 *
 * 全部状态机与业务逻辑位于框架中立的
 * [cp.player.app.presentation.playlistdetail.PlaylistDetailPresenter]，
 * 本类只桥接 `viewModelScope`、操作提示与封面飞行动画。
 */
class PlaylistDetailScreenModel : ViewModel(), PlaylistDetailContract {

    private val presenter = PlaylistDetailPresenter(
        scope = viewModelScope,
        notify = UiEvents::notify,
        playCoverFlight = { track -> CoverFlight.play(track.id, track.coverUrl) },
    )

    override val state: StateFlow<PlaylistDetailUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun primeFromCache(summary: PlaylistSummary): Boolean =
        presenter.primeFromCache(summary)

    override fun load(summary: PlaylistSummary, strings: CpStrings, force: Boolean) =
        presenter.load(summary, strings, force)

    override fun refresh(strings: CpStrings) = presenter.refresh(strings)

    override fun loadLocal(
        summary: PlaylistSummary,
        tracks: List<TrackSummary>,
        loading: Boolean,
        force: Boolean,
    ) = presenter.loadLocal(summary, tracks, loading, force)

    override fun checkDailyConverted(todayStr: String) = presenter.checkDailyConverted(todayStr)

    override fun markDailyConverted() = presenter.markDailyConverted()

    override fun loadMore(strings: CpStrings) = presenter.loadMore(strings)

    override fun clearLoadMoreError() = presenter.clearLoadMoreError()

    override fun setSort(type: PlaylistSortType) = presenter.setSort(type)

    override fun displayTracks(): List<TrackSummary> = presenter.displayTracks()

    override fun enterSelection(trackId: String) = presenter.enterSelection(trackId)

    override fun toggleSelection(trackId: String) = presenter.toggleSelection(trackId)

    override fun selectAll() = presenter.selectAll()

    override fun deselectAll() = presenter.deselectAll()

    override fun toggleSelectAll() = presenter.toggleSelectAll()

    override fun exitSelection() = presenter.exitSelection()

    override fun autoPlayAt(index: Int, animateCover: Boolean) = presenter.autoPlayAt(index, animateCover)

    override fun playAt(index: Int, animateCover: Boolean) = presenter.playAt(index, animateCover)

    override fun playAll() = presenter.playAll()

    override fun playShuffle() = presenter.playShuffle()

    override fun queueAll(strings: CpStrings) = presenter.queueAll(strings)

    override fun queueSelected(strings: CpStrings) = presenter.queueSelected(strings)

    override fun isOwner(): Boolean = presenter.isOwner()

    override fun deleteOrUnsubscribe(strings: CpStrings, onDone: () -> Unit) =
        presenter.deleteOrUnsubscribe(strings, onDone)

    override fun addTracks(newTracks: List<TrackSummary>, strings: CpStrings) =
        presenter.addTracks(newTracks, strings)

    override fun removeTracks(trackIds: List<String>, strings: CpStrings) =
        presenter.removeTracks(trackIds, strings)

    override fun toggleLike(track: TrackSummary, strings: CpStrings) = presenter.toggleLike(track, strings)

    override fun isLiked(trackId: String): Boolean = presenter.isLiked(trackId)

    override fun togglePlaylistFavorite(strings: CpStrings) = presenter.togglePlaylistFavorite(strings)
}
