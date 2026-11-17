package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.importplaylist.ImportPlaylistContract
import cp.player.app.presentation.importplaylist.ImportPlaylistPresenter
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias ImportTrackItem = cp.player.app.presentation.importplaylist.ImportTrackItem
typealias PlaylistImportOutcome = cp.player.app.presentation.importplaylist.PlaylistImportOutcome
typealias ImportPlaylistUiState = cp.player.app.presentation.importplaylist.ImportPlaylistUiState

/**
 * 外部歌单导入页的 Voyager 适配层（Thin Adapter）。
 */
class ImportPlaylistScreenModel(
    initialState: ImportPlaylistUiState = ImportPlaylistUiState(),
) : ScreenModel, ImportPlaylistContract {

    private val presenter = ImportPlaylistPresenter(
        scope = screenModelScope,
        initialState = initialState,
        notify = UiEvents::notify,
    )

    override val state: StateFlow<ImportPlaylistUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun setRaw(text: String) = presenter.setRaw(text)
    override fun setName(name: String) = presenter.setName(name)
    override fun toggleTrack(index: Int) = presenter.toggleTrack(index)
    override fun selectAll(selected: Boolean) = presenter.selectAll(selected)
    override fun analyze(s: CpStrings) = presenter.analyze(s)
    override fun importToNew(s: CpStrings) = presenter.importToNew(s)
    override fun importToExisting(target: PlaylistSummary, s: CpStrings) =
        presenter.importToExisting(target, s)
    override fun subscribeSource(s: CpStrings) = presenter.subscribeSource(s)
}
