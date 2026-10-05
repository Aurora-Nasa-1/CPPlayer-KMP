package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.requestMediaScanPermission
import cp.player.app.platform.setOnMediaPermissionGranted
import cp.player.app.ui.util.UiEvents
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
 */
data class DownloadsUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val localItems: List<LocalMediaItem> = emptyList(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val importing: Boolean = false,
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
        UiEvents.notify(strings.downloads.removedFromLibrary)
    }

    /** 播放本地音频（mediaId 规则 `local://{audio|video}/{path}`）；视频暂不支持。 */
    fun play(item: LocalMediaItem, strings: CpStrings) {
        if (item.mediaType != MediaType.AUDIO) {
            UiEvents.notify(strings.downloads.unsupportedMedia)
            return
        }
        val mediaId = "local://audio/${item.path}"
        screenModelScope.launch {
            AppModel.playback.playQueue(listOf(mediaId), startIndex = 0)
        }
    }
}
