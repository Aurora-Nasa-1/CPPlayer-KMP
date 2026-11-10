package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatBytes
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.playback.CacheRelocationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储管理页 UI 状态。
 *
 * @param downloadedCount 已下载的媒体条数（含音频 / 视频）
 * @param downloadedBytes 已下载文件总字节数（取媒体库登记值，不扫盘）
 * @param imageCacheBytes 图片磁盘缓存占用字节数；-1 表示不可用（读取失败或缓存未启用）
 * @param songCacheEntries 已落盘的歌曲缓存条数（无损流）
 * @param songCacheBytes 歌曲缓存总字节数
 * @param songCacheCapacityBytes 歌曲缓存容量上限；**0 表示该平台不落盘**（安卓），
 *   界面据此整块隐藏 —— 而不是显示一个永远是 0 的数字
 */
data class StorageUiState(
    val downloadedCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val imageCacheBytes: Long = -1L,
    val songCacheEntries: Int = 0,
    val songCacheBytes: Long = 0L,
    val songCacheCapacityBytes: Long = 0L,
    val songCacheDirPath: String? = null,
    val isRelocationSupported: Boolean = false,
) {
    /** 该平台是否真的在落盘（安卓为 false）。 */
    val songCacheSupported: Boolean get() = songCacheCapacityBytes > 0L
}

/**
 * 存储管理页 ScreenModel。
 *
 * 统计口径说明：
 * - 下载占用取本地媒体库登记的 `LocalMediaItem.sizeBytes`（登记发生在下载完成与扫描时，
 *   与磁盘实际状态基本一致），**不做全盘扫描** —— 扫描大目录会卡住页面且没有必要。
 * - 图片缓存占用读 Coil 磁盘缓存的实时值（平台层 `imageCacheSizeBytes`）。
 * - 歌曲缓存（无损流）走 [cp.player.core.MusicBackend.songCache] 的管理句柄：要读目录，放 IO 线程。
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
        refreshSongCache()
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
     * 结果经全局 [UiEvents] 提示；能算出释放量时给出具体数字。
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
            withContext(Dispatchers.Main) { UiEvents.notify(message) }
        }
    }

    // ============ 歌曲缓存（无损流落盘） ============

    /** 重新统计歌曲缓存占用。要读目录，放 IO。 */
    fun refreshSongCache() {
        screenModelScope.launch(Dispatchers.IO) {
            val stats = runCatching { AppModel.backend.songCache.stats() }.getOrNull() ?: return@launch
            val dirPath = runCatching { AppModel.backend.songCache.cacheDirPath() }.getOrNull()
            val canRelocate = runCatching { AppModel.backend.songCache.isRelocationSupported() }.getOrDefault(false)
            _state.value = _state.value.copy(
                songCacheEntries = stats.entries,
                songCacheBytes = stats.bytes,
                songCacheCapacityBytes = stats.capacityBytes,
                songCacheDirPath = dirPath,
                isRelocationSupported = canRelocate,
            )
        }
    }

    /** 清空全部歌曲缓存。 */
    fun clearSongCache() {
        screenModelScope.launch(Dispatchers.IO) {
            val before = _state.value.songCacheBytes
            val removed = runCatching { AppModel.backend.songCache.clear() }.getOrDefault(0)
            refreshSongCache()
            // 按「清之前」的占用报释放量：清完再读就是 0，那个数字对用户没有信息量。
            val message = if (removed > 0) "已清理 $removed 首缓存，释放了 ${formatBytes(before)}"
            else "歌曲缓存本来就是空的"
            withContext(Dispatchers.Main) { UiEvents.notify(message) }
        }
    }

    /**
     * 清理 [days] 天未访问过的歌曲缓存。
     *
     * 比「全清」温和得多：无损单曲几十上百 MB，全清之后下次播放要整曲重下。
     */
    fun clearSongCacheOlderThan(days: Int) {
        if (days <= 0) return
        screenModelScope.launch(Dispatchers.IO) {
            val cutoffMs = days.toLong() * 24L * 60L * 60L * 1000L
            val removed = runCatching { AppModel.backend.songCache.clearOlderThan(cutoffMs) }
                .getOrDefault(0)
            refreshSongCache()
            val message = if (removed > 0) "已清理 $removed 首 $days 天未播放的缓存"
            else "没有 $days 天未播放的缓存"
            withContext(Dispatchers.Main) { UiEvents.notify(message) }
        }
    }

    /**
     * 调整歌曲缓存容量上限（字节）。缩小上限会立刻按 LRU 淘汰到位。
     *
     * 反馈里报的是**实际释放量**而不是「已按最少使用清理」这类套话：
     * 多数情况下（调大上限、或本来就没超）一个字节都不会释放，说成清理过就是谎报。
     */
    fun setSongCacheCapacity(bytes: Long) {
        screenModelScope.launch(Dispatchers.IO) {
            val before = _state.value.songCacheBytes
            runCatching { AppModel.backend.songCache.setCapacityBytes(bytes) }
            refreshSongCache()
            val after = runCatching { AppModel.backend.songCache.stats().bytes }.getOrDefault(before)
            val freed = (before - after).coerceAtLeast(0L)
            val message = if (freed > 0L) {
                "缓存上限已设为 ${formatBytes(bytes)}，释放了 ${formatBytes(freed)}"
            } else {
                "缓存上限已设为 ${formatBytes(bytes)}"
            }
            withContext(Dispatchers.Main) { UiEvents.notify(message) }
        }
    }

    /**
     * 迁移歌曲缓存目录。
     */
    fun migrateCacheDir(targetPath: String) {
        screenModelScope.launch(Dispatchers.IO) {
            val result = runCatching { AppModel.backend.songCache.relocateCacheDir(targetPath) }
                .getOrElse { CacheRelocationResult.Failure(it.message ?: "未知异常") }
            refreshSongCache()
            withContext(Dispatchers.Main) {
                when (result) {
                    is CacheRelocationResult.Success -> {
                        val msg = if (result.movedFiles > 0) {
                            "已成功迁移至 $targetPath，搬移了 ${result.movedFiles} 个文件（${formatBytes(result.movedBytes)}）"
                        } else {
                            "已成功切换缓存目录至 $targetPath"
                        }
                        UiEvents.notify(msg)
                    }
                    is CacheRelocationResult.SameDirectory -> {
                        UiEvents.notify("所选目录与当前缓存目录相同")
                    }
                    is CacheRelocationResult.Failure -> {
                        UiEvents.notify("迁移失败: ${result.message}")
                    }
                    is CacheRelocationResult.Unsupported -> {
                        UiEvents.notify("当前平台不支持迁移缓存目录")
                    }
                }
            }
        }
    }
}

/** 兼容旧代码引用，统一委托至 [cp.player.app.ui.util.formatBytes] */
fun formatBytes(bytes: Long): String = cp.player.app.ui.util.formatBytes(bytes)


