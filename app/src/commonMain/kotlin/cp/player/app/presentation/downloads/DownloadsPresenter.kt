package cp.player.app.presentation.downloads

import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.requestMediaScanPermission
import cp.player.app.platform.setOnMediaPermissionGranted
import cp.player.app.presentation.UiPresenter
import cp.player.core.local.LocalGridSort
import cp.player.core.local.LocalLibraryFilter
import cp.player.core.local.LocalLibrarySort
import cp.player.core.local.LocalPlaylist
import cp.player.core.local.LocalScanSettings
import cp.player.core.local.ScanProgress
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载管理页 UI 状态。
 *
 * @param tasks 全部下载任务（含进行中与已完成）
 * @param localItems 本地媒体库条目（下载产物 + 扫描/导入）
 * @param scanning 是否正在扫描设备
 * @param scanProgress 最近一次扫描进度快照
 * @param importing 是否正在导入文件夹
 * @param libraryTab 本地库子页下标（0 歌曲 / 1 专辑 / 2 艺术家 / 3 收藏）
 * @param libraryQuery 本地库搜索关键词
 * @param songSort 歌曲排序方式
 * @param songSortDescending 歌曲排序是否降序
 * @param gridSort 专辑 / 艺术家网格排序方式
 * @param favoritePaths 本地收藏的文件路径集合
 * @param scanSettings 扫描过滤规则（影响索引，改动后需重扫）
 * @param libraryFilter 浏览期筛选开关（只影响当前视图）
 */
data class DownloadsUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val localItems: List<LocalMediaItem> = emptyList(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val importing: Boolean = false,
    val libraryTab: Int = 0,
    val libraryQuery: String = "",
    val songSort: LocalLibrarySort = LocalLibrarySort.TITLE,
    val songSortDescending: Boolean = false,
    val gridSort: LocalGridSort = LocalGridSort.NAME,
    val favoritePaths: Set<String> = emptySet(),
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val scanSettings: LocalScanSettings = LocalScanSettings(),
    val libraryFilter: LocalLibraryFilter = LocalLibraryFilter(),
) {
    /** 进行中任务：按下载顺序从上往下排布（正在下载 > 等待中 > 已暂停 > 失败/取消，组内按创建时间升序）。 */
    val activeTasks: List<DownloadTask>
        get() = tasks.filter {
            it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PAUSED ||
                it.status == DownloadStatus.FAILED ||
                it.status == DownloadStatus.CANCELLED
        }.sortedWith(
            compareBy(
                { task ->
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> 0
                        DownloadStatus.PENDING -> 1
                        DownloadStatus.PAUSED -> 2
                        DownloadStatus.FAILED -> 3
                        DownloadStatus.CANCELLED -> 4
                        DownloadStatus.COMPLETED -> 5
                    }
                },
                { it.createdAt }
            )
        )

    /** 已完成任务：按下载顺序从上往下（创建时间升序）。 */
    val completedTasks: List<DownloadTask>
        get() = tasks.filter { it.status == DownloadStatus.COMPLETED }
            .sortedBy { it.createdAt }

    /** 本地库：下载产物分组。 */
    val downloadedItems: List<LocalMediaItem>
        get() = localItems.filter {
            it.source == cp.player.core.media.LocalMediaOrigin.DOWNLOADED
        }

    /** 本地库：扫描/导入分组。 */
    val importedItems: List<LocalMediaItem>
        get() = localItems.filter {
            it.source == cp.player.core.media.LocalMediaOrigin.IMPORTED
        }

    /** 本地库音频条目（视频不参与音乐库聚合与播放队列）。 */
    val localAudioItems: List<LocalMediaItem>
        get() = localItems.filter { it.mediaType == MediaType.AUDIO }

    /** 本地音频中的无损音质条目总数。 */
    val losslessCount: Int
        get() = localAudioItems.count { it.isLossless }

    /** 最近导入或修改的本地音频（最多 5 首），用于曲库主页速览。 */
    val recentAudioItems: List<LocalMediaItem>
        get() = localAudioItems
            .sortedByDescending { it.lastModified }
            .take(5)
}

/**
 * 下载管理页的框架无关呈现契约。
 */
interface DownloadsContract : UiPresenter<DownloadsUiState> {
    fun setLibraryTab(index: Int)
    fun setLibraryQuery(query: String)
    fun setGridSort(sort: LocalGridSort)
    fun updateScanSettings(next: LocalScanSettings)
    fun resetScanSettings()
    fun toggleLibraryFilter(transform: (LocalLibraryFilter) -> LocalLibraryFilter)
    fun clearLibraryFilter()
    fun toggleSongSort(sort: LocalLibrarySort)
    fun toggleFavorite(path: String, strings: CpStrings)
    fun createLocalPlaylist(name: String): LocalPlaylist
    fun deleteLocalPlaylist(id: String)
    fun renameLocalPlaylist(id: String, newName: String)
    fun addTracksToLocalPlaylist(playlistId: String, paths: List<String>)
    fun removeTrackFromLocalPlaylist(playlistId: String, path: String)
    fun pause(task: DownloadTask): Result<Unit>
    fun pauseAll(): Result<Unit>
    fun resume(task: DownloadTask): Result<Unit>
    fun resumeAll(): Result<Unit>
    fun cancel(task: DownloadTask, strings: CpStrings)
    fun cancelAll(strings: CpStrings)
    fun retry(task: DownloadTask): Result<Unit>
    fun remove(task: DownloadTask, deleteFile: Boolean, strings: CpStrings)
    fun pauseTasks(tasks: List<DownloadTask>)
    fun resumeTasks(tasks: List<DownloadTask>)
    fun removeTasks(tasks: List<DownloadTask>, deleteFiles: Boolean, strings: CpStrings)
    fun removeAllActiveTasks(strings: CpStrings)
    fun removeAllCompletedTasks(strings: CpStrings)
    fun startScan(strings: CpStrings)
    fun importFolder(uri: String, strings: CpStrings)
    fun removeLocalItem(item: LocalMediaItem, strings: CpStrings)
    fun deleteLocalFile(item: LocalMediaItem, strings: CpStrings)
    fun play(item: LocalMediaItem, strings: CpStrings)
    fun playAll(items: List<LocalMediaItem>, startIndex: Int = 0)
    fun shuffleAll(items: List<LocalMediaItem>)
}

/**
 * 下载管理页状态机（框架无关）。
 *
 * 直接转发 [AppModel.downloads] / [AppModel.localMedia]，
 * UI 状态经 [scope] 收集后端 StateFlow 得到。
 */
class DownloadsPresenter(
    override val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
) : DownloadsContract {

    private val _state = MutableStateFlow(DownloadsUiState())
    override val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    init {
        // 下载任务清单（实时状态 + 进度）
        scope.launch {
            AppModel.downloads.tasksFlow.collect { tasks ->
                _state.value = _state.value.copy(tasks = tasks)
            }
        }
        // 本地媒体库条目
        scope.launch {
            AppModel.localMedia.items().collect { items ->
                _state.value = _state.value.copy(localItems = items)
            }
        }
        // 扫描状态
        scope.launch {
            AppModel.localMedia.isScanningFlow.collect { scanning ->
                _state.value = _state.value.copy(scanning = scanning)
            }
        }
        // 本地收藏（按文件路径）
        scope.launch {
            AppModel.localMedia.favorites.paths.collect { paths ->
                _state.value = _state.value.copy(favoritePaths = paths)
            }
        }
        // 自定义本地歌单
        scope.launch {
            AppModel.localMedia.playlists.playlists.collect { playlists ->
                _state.value = _state.value.copy(localPlaylists = playlists)
            }
        }
        // 扫描过滤规则（最短时长 / 排除目录 / 是否含视频）
        scope.launch {
            AppModel.localMedia.scanSettings.settings.collect { settings ->
                _state.value = _state.value.copy(scanSettings = settings)
            }
        }
    }

    // ============ 本地库浏览状态 ============

    override fun setLibraryTab(index: Int) {
        _state.value = _state.value.copy(libraryTab = index)
    }

    override fun setLibraryQuery(query: String) {
        _state.value = _state.value.copy(libraryQuery = query)
    }

    override fun setGridSort(sort: LocalGridSort) {
        _state.value = _state.value.copy(gridSort = sort)
    }

    // ============ 扫描规则与浏览筛选 ============

    /** 整体替换扫描规则并持久化；需重扫一次才会体现到曲库。 */
    override fun updateScanSettings(next: LocalScanSettings) {
        runCatching { AppModel.localMedia.scanSettings.update(next) }
    }

    override fun resetScanSettings() {
        runCatching { AppModel.localMedia.scanSettings.reset() }
    }

    /** 切换浏览期筛选开关。 */
    override fun toggleLibraryFilter(transform: (LocalLibraryFilter) -> LocalLibraryFilter) {
        _state.value = _state.value.copy(libraryFilter = transform(_state.value.libraryFilter))
    }

    override fun clearLibraryFilter() {
        _state.value = _state.value.copy(libraryFilter = LocalLibraryFilter())
    }

    /**
     * 设置歌曲排序；重复点同一项时切换升降序。
     *
     * 复用同一排序键切方向，比在菜单里再放一个「升序/降序」开关省一次点击，
     * 也是桌面音乐播放器的通行做法。
     */
    override fun toggleSongSort(sort: LocalLibrarySort) {
        val current = _state.value
        _state.value = if (current.songSort == sort) {
            current.copy(songSortDescending = !current.songSortDescending)
        } else {
            current.copy(songSort = sort, songSortDescending = false)
        }
    }

    /**
     * 切换某条本地曲的收藏状态。
     *
     * 本地曲没有在线 id，走音源的 `likeSong` 恒为 false，因此这里用独立的
     * 本地收藏（按文件路径持久化）。
     */
    override fun toggleFavorite(path: String, strings: CpStrings) {
        val nowFavorite = runCatching { AppModel.localMedia.favorites.toggle(path) }.getOrDefault(false)
        notify(
            if (nowFavorite) strings.downloads.addedToFavorites else strings.downloads.removedFromFavorites
        )
    }

    // ============ 自定义本地歌单操作 ============

    override fun createLocalPlaylist(name: String): LocalPlaylist {
        val pl = AppModel.localMedia.playlists.createPlaylist(name)
        notify("已创建歌单「${pl.name}」")
        return pl
    }

    override fun deleteLocalPlaylist(id: String) {
        val pl = AppModel.localMedia.playlists.getPlaylist(id)
        if (AppModel.localMedia.playlists.deletePlaylist(id)) {
            notify("已删除本地歌单「${pl?.name ?: id}」")
        }
    }

    override fun renameLocalPlaylist(id: String, newName: String) {
        if (AppModel.localMedia.playlists.renamePlaylist(id, newName)) {
            notify("歌单已重命名为「$newName」")
        }
    }

    override fun addTracksToLocalPlaylist(playlistId: String, paths: List<String>) {
        val count = AppModel.localMedia.playlists.addTracks(playlistId, paths)
        val pl = AppModel.localMedia.playlists.getPlaylist(playlistId)
        notify(
            if (count > 0) "已将 $count 首歌曲添加到「${pl?.name ?: "本地歌单"}」"
            else "所选歌曲已在歌单中"
        )
    }

    override fun removeTrackFromLocalPlaylist(playlistId: String, path: String) {
        if (AppModel.localMedia.playlists.removeTracks(playlistId, listOf(path))) {
            notify("已从歌单移出")
        }
    }

    // ============ 下载任务操作 ============

    override fun pause(task: DownloadTask) = runCatching { AppModel.downloads.pause(task.id) }

    override fun pauseAll() = runCatching { AppModel.downloads.pauseAll() }

    override fun resume(task: DownloadTask) = runCatching { AppModel.downloads.resume(task.id) }

    override fun resumeAll() = runCatching { AppModel.downloads.resumeAll() }

    /**
     * @param strings 由调用方（组合上下文）传入而不是在协程里取：通知发在 IO 协程中，
     *   那儿读不到 CompositionLocal（照 `SongCacheModel.remove(entry, strings)` 的写法）。
     */
    override fun cancel(task: DownloadTask, strings: CpStrings) {
        AppModel.cancelDownload(task.id)
        notify(strings.downloads.downloadCancelled)
    }

    override fun cancelAll(strings: CpStrings) {
        runCatching { AppModel.downloads.cancelAll() }
        notify(strings.downloads.downloadCancelled)
    }

    override fun retry(task: DownloadTask) = runCatching { AppModel.downloads.retry(task.id) }

    /** 移除任务记录；[deleteFile] 为 true 时同时删除已下载文件。 */
    override fun remove(task: DownloadTask, deleteFile: Boolean, strings: CpStrings) {
        runCatching { AppModel.downloads.remove(task.id, deleteFile) }
        notify(
            if (deleteFile) strings.downloads.fileAndRecordDeleted else strings.downloads.recordRemoved
        )
    }

    /** 批量暂停任务（仅对下载中/等待中生效）。 */
    override fun pauseTasks(tasks: List<DownloadTask>) {
        tasks.forEach { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) {
                pause(task)
            }
        }
    }

    /** 批量恢复任务（仅对已暂停生效）。 */
    override fun resumeTasks(tasks: List<DownloadTask>) {
        tasks.forEach { task ->
            if (task.status == DownloadStatus.PAUSED) {
                resume(task)
            }
        }
    }

    /** 批量移除任务记录；[deleteFiles] 为 true 时连同已下载文件与 .part 一并删除。 */
    override fun removeTasks(tasks: List<DownloadTask>, deleteFiles: Boolean, strings: CpStrings) {
        if (tasks.isEmpty()) return
        val ids = tasks.map { it.id }
        runCatching { AppModel.downloads.removeTasks(ids, deleteFiles) }
        notify(
            if (deleteFiles) strings.downloads.fileAndRecordDeleted else strings.downloads.recordRemoved
        )
    }

    /** 清空全部进行中任务（连同未完成的临时文件）。 */
    override fun removeAllActiveTasks(strings: CpStrings) {
        val active = _state.value.activeTasks
        if (active.isEmpty()) return
        removeTasks(active, deleteFiles = true, strings = strings)
    }

    /** 清空全部已完成任务（删除本地文件与记录）。 */
    override fun removeAllCompletedTasks(strings: CpStrings) {
        val completed = _state.value.completedTasks
        if (completed.isEmpty()) return
        removeTasks(completed, deleteFiles = true, strings = strings)
    }

    // ============ 本地媒体库操作 ============

    /** 是否已挂起一次「权限授予后自动重试扫描」。 */
    private var permissionRetryPending = false

    override fun dispose() {
        if (permissionRetryPending) {
            permissionRetryPending = false
            setOnMediaPermissionGranted(null)
        }
        super.dispose()
    }

    /**
     * 触发一次设备扫描，进度经 [DownloadsUiState.scanProgress] 反馈。
     *
     * @param strings 由调用方（组合上下文）传入；理由见 [cancel]。
     */
    override fun startScan(strings: CpStrings) {
        if (_state.value.scanning) return
        scope.launch {
            _state.value = _state.value.copy(scanning = true, scanProgress = null)
            var permissionDenied = false
            val result = runCatching {
                AppModel.localMedia.scan().collect { progress ->
                    if (progress.permissionDenied) permissionDenied = true
                    // 不翻译：`errorMessage` 是 core 扫描层给的原始串（平台层文案，见 I18N.md 批次 7）。
                    progress.errorMessage?.let { notify(it) }
                    _state.value = _state.value.copy(scanProgress = progress)
                }
            }
            _state.value = _state.value.copy(scanning = false)
            when {
                permissionDenied -> {
                    // 触发平台权限申请（Android 弹系统授权框；Desktop 空实现）
                    requestMediaScanPermission()
                    notify(strings.downloads.permissionRequested)
                    // 授权完成后自动重试一次扫描
                    if (!permissionRetryPending) {
                        permissionRetryPending = true
                        setOnMediaPermissionGranted {
                            setOnMediaPermissionGranted(null)
                            permissionRetryPending = false
                            startScan(strings)
                        }
                    }
                }
                result.isFailure ->
                    notify(strings.downloads.scanFailed(result.exceptionOrNull()?.message))
                else -> {
                    val total = _state.value.scanProgress?.total ?: 0
                    notify(
                        if (total > 0) strings.downloads.scanCompleted(total)
                        else strings.downloads.scanCompletedEmpty
                    )
                }
            }
        }
    }

    /** 导入文件夹 / SAF 树，完成后提示新增条数。 */
    override fun importFolder(uri: String, strings: CpStrings) {
        if (_state.value.importing) return
        scope.launch {
            _state.value = _state.value.copy(importing = true)
            val added = withContext(Dispatchers.IO) {
                runCatching { AppModel.localMedia.importFolder(uri) }.getOrDefault(-1)
            }
            _state.value = _state.value.copy(importing = false)
            notify(
                when {
                    added < 0 -> strings.downloads.importFailed
                    added == 0 -> strings.downloads.importNoNewFiles
                    else -> strings.downloads.imported(added)
                }
            )
        }
    }

    /** 从库中移除条目（不删除磁盘文件）。 */
    override fun removeLocalItem(item: LocalMediaItem, strings: CpStrings) {
        runCatching { AppModel.localMedia.removeItem(item) }
        // 同步清掉收藏，避免留下指向已移出曲库的「幽灵收藏」
        runCatching { AppModel.localMedia.favorites.removeAll(listOf(item.path)) }
        notify(strings.downloads.removedFromLibrary)
    }

    /** 彻底删除本地文件并移出曲库。 */
    override fun deleteLocalFile(item: LocalMediaItem, strings: CpStrings) {
        removeLocalItem(item, strings)
        runCatching { cp.player.core.util.PlatformSupport.deleteRecursively(item.path) }
        notify("已删除本地文件")
    }

    /** 播放本地音频（mediaId 规则 `local://{audio|video}/{path}`）；视频暂不支持。 */
    override fun play(item: LocalMediaItem, strings: CpStrings) {
        if (item.mediaType != MediaType.AUDIO) {
            notify(strings.downloads.unsupportedMedia)
            return
        }
        playQueue(listOf(item), 0)
    }

    /**
     * 播放整个列表（专辑 / 艺术家 / 搜索结果的「播放全部」）。
     *
     * 队列一次性建好，并且**只推送音频条目** —— 视频混进队列会让播放器在
     * 切歌时撞上不支持的媒体。
     */
    override fun playAll(items: List<LocalMediaItem>, startIndex: Int) {
        val playable = items.filter { it.mediaType == MediaType.AUDIO }
        if (playable.isEmpty()) return
        playQueue(playable, startIndex.coerceIn(0, playable.lastIndex))
    }

    /** 随机播放当前列表中的全部音频。 */
    override fun shuffleAll(items: List<LocalMediaItem>) {
        val playable = items.filter { it.mediaType == MediaType.AUDIO }
        if (playable.isEmpty()) return
        playQueue(playable.shuffled(), 0)
    }

    private fun playQueue(items: List<LocalMediaItem>, startIndex: Int) {
        val mediaIds = items.map { "local://audio/${it.path}" }
        scope.launch {
            AppModel.playback.playQueue(mediaIds, startIndex = startIndex)
        }
    }
}
