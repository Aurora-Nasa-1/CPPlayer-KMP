package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.downloads.DownloadsContract
import cp.player.app.presentation.downloads.DownloadsPresenter
import cp.player.app.ui.util.UiEvents
import cp.player.core.local.LocalGridSort
import cp.player.core.local.LocalLibraryFilter
import cp.player.core.local.LocalLibrarySort
import cp.player.core.local.LocalPlaylist
import cp.player.core.local.LocalScanSettings
import cp.player.core.media.LocalMediaItem
import cp.player.core.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// 兼容现有 UI 引用的类型别名
typealias DownloadsUiState = cp.player.app.presentation.downloads.DownloadsUiState

/**
 * 下载管理页的 Voyager 适配层（Thin Adapter）。
 */
class DownloadsScreenModel : ScreenModel, DownloadsContract {

    private val presenter = DownloadsPresenter(
        scope = screenModelScope,
        notify = UiEvents::notify,
    )

    override val state: StateFlow<DownloadsUiState> get() = presenter.state
    override val scope: CoroutineScope get() = presenter.scope

    override fun onDispose() {
        presenter.dispose()
        super.onDispose()
    }

    override fun setLibraryTab(index: Int) = presenter.setLibraryTab(index)
    override fun setLibraryQuery(query: String) = presenter.setLibraryQuery(query)
    override fun setGridSort(sort: LocalGridSort) = presenter.setGridSort(sort)
    override fun updateScanSettings(next: LocalScanSettings) = presenter.updateScanSettings(next)
    override fun resetScanSettings() = presenter.resetScanSettings()
    override fun toggleLibraryFilter(transform: (LocalLibraryFilter) -> LocalLibraryFilter) =
        presenter.toggleLibraryFilter(transform)
    override fun clearLibraryFilter() = presenter.clearLibraryFilter()
    override fun toggleSongSort(sort: LocalLibrarySort) = presenter.toggleSongSort(sort)
    override fun toggleFavorite(path: String, strings: CpStrings) = presenter.toggleFavorite(path, strings)
    override fun createLocalPlaylist(name: String): LocalPlaylist = presenter.createLocalPlaylist(name)
    override fun deleteLocalPlaylist(id: String) = presenter.deleteLocalPlaylist(id)
    override fun renameLocalPlaylist(id: String, newName: String) = presenter.renameLocalPlaylist(id, newName)
    override fun addTracksToLocalPlaylist(playlistId: String, paths: List<String>) =
        presenter.addTracksToLocalPlaylist(playlistId, paths)
    override fun removeTrackFromLocalPlaylist(playlistId: String, path: String) =
        presenter.removeTrackFromLocalPlaylist(playlistId, path)
    override fun pause(task: DownloadTask) = presenter.pause(task)
    override fun pauseAll() = presenter.pauseAll()
    override fun resume(task: DownloadTask) = presenter.resume(task)
    override fun resumeAll() = presenter.resumeAll()
    override fun cancel(task: DownloadTask, strings: CpStrings) = presenter.cancel(task, strings)
    override fun cancelAll(strings: CpStrings) = presenter.cancelAll(strings)
    override fun retry(task: DownloadTask) = presenter.retry(task)
    override fun remove(task: DownloadTask, deleteFile: Boolean, strings: CpStrings) =
        presenter.remove(task, deleteFile, strings)
    override fun pauseTasks(tasks: List<DownloadTask>) = presenter.pauseTasks(tasks)
    override fun resumeTasks(tasks: List<DownloadTask>) = presenter.resumeTasks(tasks)
    override fun removeTasks(tasks: List<DownloadTask>, deleteFiles: Boolean, strings: CpStrings) =
        presenter.removeTasks(tasks, deleteFiles, strings)
    override fun removeAllActiveTasks(strings: CpStrings) = presenter.removeAllActiveTasks(strings)
    override fun removeAllCompletedTasks(strings: CpStrings) = presenter.removeAllCompletedTasks(strings)
    override fun startScan(strings: CpStrings) = presenter.startScan(strings)
    override fun importFolder(uri: String, strings: CpStrings) = presenter.importFolder(uri, strings)
    override fun removeLocalItem(item: LocalMediaItem, strings: CpStrings) =
        presenter.removeLocalItem(item, strings)
    override fun deleteLocalFile(item: LocalMediaItem, strings: CpStrings) =
        presenter.deleteLocalFile(item, strings)
    override fun play(item: LocalMediaItem, strings: CpStrings) = presenter.play(item, strings)
    override fun playAll(items: List<LocalMediaItem>, startIndex: Int) = presenter.playAll(items, startIndex)
    override fun shuffleAll(items: List<LocalMediaItem>) = presenter.shuffleAll(items)
}
