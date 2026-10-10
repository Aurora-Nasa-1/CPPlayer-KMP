package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.requestMediaScanPermission
import cp.player.app.platform.setOnMediaPermissionGranted
import cp.player.app.ui.util.UiEvents
import cp.player.core.local.LocalGridSort
import cp.player.core.local.LocalLibraryFilter
import cp.player.core.local.LocalLibrarySort
import cp.player.core.local.LocalScanSettings
import cp.player.core.local.ScanProgress
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType
import cp.player.core.model.DownloadStatus
import cp.player.core.model.DownloadTask
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
    val scanSettings: LocalScanSettings = LocalScanSettings(),
    val libraryFilter: LocalLibraryFilter = LocalLibraryFilter(),
) {
    /** 进行中任务：等待 / 下载中 / 暂停 / 失败 / 已取消。 */
    val activeTasks: List<DownloadTask>
        get() = tasks.filter {
            it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PAUSED ||
                it.status == DownloadStatus.FAILED ||
                it.status == DownloadStatus.CANCELLED
        }

    /** 已完成任务。 */
    val completedTasks: List<DownloadTask>
        get() = tasks.filter { it.status == DownloadStatus.COMPLETED }

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
}

/**
 * 下载管理页 ScreenModel（范式仿 [LibraryScreenModel]）。
 *
 * 直接转发 [AppModel.downloads] / [AppModel.localMedia]，
 * UI 状态经 screenModelScope 收集后端 StateFlow 得到。
 */
class DownloadsScreenModel : ScreenModel {

    private val _state = MutableStateFlow(DownloadsUiState())
    val state: StateFlow<DownloadsUiState> = _state.asStateFlow()

    init {
        // 下载任务清单（实时状态 + 进度）
        screenModelScope.launch {
            AppModel.downloads.tasksFlow.collect { tasks ->
                _state.value = _state.value.copy(tasks = tasks)
            }
        }
        // 本地媒体库条目
        screenModelScope.launch {
            AppModel.localMedia.items().collect { items ->
                _state.value = _state.value.copy(localItems = items)
            }
        }
        // 扫描状态
        screenModelScope.launch {
            AppModel.localMedia.isScanningFlow.collect { scanning ->
                _state.value = _state.value.copy(scanning = scanning)
            }
        }
        // 本地收藏（按文件路径）
        screenModelScope.launch {
            AppModel.localMedia.favorites.paths.collect { paths ->
                _state.value = _state.value.copy(favoritePaths = paths)
            }
        }
        // 扫描过滤规则（最短时长 / 排除目录 / 是否含视频）
        screenModelScope.launch {
            AppModel.localMedia.scanSettings.settings.collect { settings ->
                _state.value = _state.value.copy(scanSettings = settings)
            }
        }
    }

    // ============ 本地库浏览状态 ============

    fun setLibraryTab(index: Int) {
        _state.value = _state.value.copy(libraryTab = index)
    }

    fun setLibraryQuery(query: String) {
        _state.value = _state.value.copy(libraryQuery = query)
    }

    fun setGridSort(sort: LocalGridSort) {
        _state.value = _state.value.copy(gridSort = sort)
    }

    // ============ 扫描规则与浏览筛选 ============

    /** 整体替换扫描规则并持久化；需重扫一次才会体现到曲库。 */
    fun updateScanSettings(next: LocalScanSettings) {
        runCatching { AppModel.localMedia.scanSettings.update(next) }
    }

    fun resetScanSettings() {
        runCatching { AppModel.localMedia.scanSettings.reset() }
    }

    /** 切换浏览期筛选开关。 */
    fun toggleLibraryFilter(transform: (LocalLibraryFilter) -> LocalLibraryFilter) {
        _state.value = _state.value.copy(libraryFilter = transform(_state.value.libraryFilter))
    }

    fun clearLibraryFilter() {
        _state.value = _state.value.copy(libraryFilter = LocalLibraryFilter())
    }

    /**
     * 设置歌曲排序；重复点同一项时切换升降序。
     *
     * 复用同一排序键切方向，比在菜单里再放一个「升序/降序」开关省一次点击，
     * 也是桌面音乐播放器的通行做法。
     */
    fun toggleSongSort(sort: LocalLibrarySort) {
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
    fun toggleFavorite(path: String, strings: CpStrings) {
        val nowFavorite = runCatching { AppModel.localMedia.favorites.toggle(path) }.getOrDefault(false)
        UiEvents.notify(
            if (nowFavorite) strings.downloads.addedToFavorites else strings.downloads.removedFromFavorites
        )
    }

    // ============ 下载任务操作 ============

    fun pause(task: DownloadTask) = runCatching { AppModel.downloads.pause(task.id) }

    fun resume(task: DownloadTask) = runCatching { AppModel.downloads.resume(task.id) }

    /**
     * @param strings 由调用方（组合上下文）传入而不是在协程里取：通知发在 IO 协程中，
     *   那儿读不到 CompositionLocal（照 `SongCacheModel.remove(entry, strings)` 的写法）。
     */
    fun cancel(task: DownloadTask, strings: CpStrings) {
        AppModel.cancelDownload(task.id)
        UiEvents.notify(strings.downloads.downloadCancelled)
    }

    fun retry(task: DownloadTask) = runCatching { AppModel.downloads.retry(task.id) }

    /** 移除任务记录；[deleteFile] 为 true 时同时删除已下载文件。 */
    fun remove(task: DownloadTask, deleteFile: Boolean, strings: CpStrings) {
        runCatching { AppModel.downloads.remove(task.id, deleteFile) }
        UiEvents.notify(
            if (deleteFile) strings.downloads.fileAndRecordDeleted else strings.downloads.recordRemoved
        )
    }

    // ============ 本地媒体库操作 ============

    /** 是否已挂起一次「权限授予后自动重试扫描」。 */
    private var permissionRetryPending = false

    override fun onDispose() {
        if (permissionRetryPending) {
            permissionRetryPending = false
            setOnMediaPermissionGranted(null)
        }
        super.onDispose()
    }

    /**
     * 触发一次设备扫描，进度经 [DownloadsUiState.scanProgress] 反馈。
     *
     * @param strings 由调用方（组合上下文）传入；理由见 [cancel]。
     */
    fun startScan(strings: CpStrings) {
        if (_state.value.scanning) return
        screenModelScope.launch {
            _state.value = _state.value.copy(scanning = true, scanProgress = null)
            var permissionDenied = false
            val result = runCatching {
                AppModel.localMedia.scan().collect { progress ->
                    if (progress.permissionDenied) permissionDenied = true
                    // 不翻译：`errorMessage` 是 core 扫描层给的原始串（平台层文案，见 I18N.md 批次 7）。
                    progress.errorMessage?.let { UiEvents.notify(it) }
                    _state.value = _state.value.copy(scanProgress = progress)
                }
            }
            _state.value = _state.value.copy(scanning = false)
            when {
                permissionDenied -> {
                    // 触发平台权限申请（Android 弹系统授权框；Desktop 空实现）
                    requestMediaScanPermission()
                    UiEvents.notify(strings.downloads.permissionRequested)
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
                    UiEvents.notify(strings.downloads.scanFailed(result.exceptionOrNull()?.message))
                else -> {
                    val total = _state.value.scanProgress?.total ?: 0
                    UiEvents.notify(
                        if (total > 0) strings.downloads.scanCompleted(total)
                        else strings.downloads.scanCompletedEmpty
                    )
                }
            }
        }
    }

    /** 导入文件夹 / SAF 树，完成后提示新增条数。 */
    fun importFolder(uri: String, strings: CpStrings) {
        if (_state.value.importing) return
        screenModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val added = withContext(Dispatchers.IO) {
                runCatching { AppModel.localMedia.importFolder(uri) }.getOrDefault(-1)
            }
            _state.value = _state.value.copy(importing = false)
            UiEvents.notify(
                when {
                    added < 0 -> strings.downloads.importFailed
                    added == 0 -> strings.downloads.importNoNewFiles
                    else -> strings.downloads.imported(added)
                }
            )
        }
    }

    /** 从库中移除条目（不删除磁盘文件）。 */
    fun removeLocalItem(item: LocalMediaItem, strings: CpStrings) {
        runCatching { AppModel.localMedia.removeItem(item) }
        // 同步清掉收藏，避免留下指向已移出曲库的「幽灵收藏」
        runCatching { AppModel.localMedia.favorites.removeAll(listOf(item.path)) }
        UiEvents.notify(strings.downloads.removedFromLibrary)
    }

    /** 播放本地音频（mediaId 规则 `local://{audio|video}/{path}`）；视频暂不支持。 */
    fun play(item: LocalMediaItem, strings: CpStrings) {
        if (item.mediaType != MediaType.AUDIO) {
            UiEvents.notify(strings.downloads.unsupportedMedia)
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
    fun playAll(items: List<LocalMediaItem>, startIndex: Int = 0) {
        val playable = items.filter { it.mediaType == MediaType.AUDIO }
        if (playable.isEmpty()) return
        playQueue(playable, startIndex.coerceIn(0, playable.lastIndex))
    }

    private fun playQueue(items: List<LocalMediaItem>, startIndex: Int) {
        val mediaIds = items.map { "local://audio/${it.path}" }
        screenModelScope.launch {
            AppModel.playback.playQueue(mediaIds, startIndex = startIndex)
        }
    }
}
