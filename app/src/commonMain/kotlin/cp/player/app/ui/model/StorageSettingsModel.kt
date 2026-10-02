package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.core.media.LocalMediaOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 存储管理页 UI 状态。
 *
 * @param downloadedCount 已下载的媒体条数（含音频 / 视频）
 * @param downloadedBytes 已下载文件总字节数（取媒体库登记值，不扫盘）
 * @param imageCacheBytes 图片磁盘缓存占用字节数；-1 表示不可用（读取失败或缓存未启用）
 */
data class StorageUiState(
    val downloadedCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val imageCacheBytes: Long = -1L,
)

/**
 * 存储管理页 ScreenModel。
 *
 * 统计口径说明：
 * - 下载占用取本地媒体库登记的 [LocalMediaItem.sizeBytes]（登记发生在下载完成与扫描时，
 *   与磁盘实际状态基本一致），**不做全盘扫描** —— 扫描大目录会卡住页面且没有必要。
 * - 图片缓存占用读 Coil 磁盘缓存的实时值（平台层 `imageCacheSizeBytes`）。
 */
class StorageSettingsModel : ScreenModel {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    init {
        // 本地媒体库条目是 StateFlow，这里常驻收集：在下载页删了文件，回到本页数字就是新的。
        screenModelScope.launch {
            AppModel.localMedia.items().collect { items ->
                val downloaded = items.filter { it.source == LocalMediaOrigin.DOWNLOADED }
                _state.value = _state.value.copy(
                    downloadedCount = downloaded.size,
                    downloadedBytes = downloaded.sumOf { it.sizeBytes },
                )
            }
        }
        refreshImageCacheSize()
    }

    /** 重新统计图片缓存占用。 */
    fun refreshImageCacheSize() {
        screenModelScope.launch(Dispatchers.IO) {
            val size = runCatching { cp.player.app.platform.imageCacheSizeBytes() }.getOrDefault(-1L)
            _state.value = _state.value.copy(imageCacheBytes = size)
        }
    }

    /**
     * 清理图片缓存并刷新统计。
     * 结果经全局 [cp.player.app.ui.util.UiEvents] 提示；能算出释放量时给出具体数字。
     */
    fun clearImageCache() {
        screenModelScope.launch(Dispatchers.IO) {
            val before = _state.value.imageCacheBytes
            val ok = runCatching { cp.player.app.platform.clearImageCache() }.getOrDefault(false)
            val after = runCatching { cp.player.app.platform.imageCacheSizeBytes() }.getOrDefault(-1L)
            _state.value = _state.value.copy(imageCacheBytes = after)
            val freed = if (before >= 0 && after >= 0) (before - after).coerceAtLeast(0L) else -1L
            val message = when {
                !ok -> "缓存清理失败"
                freed > 0L -> "图片缓存已清理，释放了 ${formatBytes(freed)}"
                else -> "图片缓存已清理"
            }
            withContext(Dispatchers.Main) { cp.player.app.ui.util.UiEvents.notify(message) }
        }
    }
}

/**
 * 字节数的人类可读格式（本文件专用；下载页有独立实现，行为保持一致）。
 * commonMain 无 String.format / java.util.Locale，用 `roundToInt()` 手工取整。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "未知"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.roundToInt()} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).roundToInt() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 100).roundToInt() / 100.0} GB"
}
