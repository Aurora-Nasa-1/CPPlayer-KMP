package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.ui.util.UiEvents
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
 * @param songCacheEntries 已落盘的歌曲缓存条数（无损流）
 * @param songCacheBytes 歌曲缓存总字节数
 * @param songCacheCapacityBytes 歌曲缓存容量上限；**0 表示该平台不落盘**（安卓），
 *   界面据此整块隐藏 —— 而不是显示一个永远是 0 的数字
 * @param apiCacheEntries 接口缓存条目数
 * @param apiCacheHits 接口缓存命中数（累计）
 * @param apiCacheMisses 接口缓存未命中数（累计）
 */
data class StorageUiState(
    val downloadedCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val imageCacheBytes: Long = -1L,
    val songCacheEntries: Int = 0,
    val songCacheBytes: Long = 0L,
    val songCacheCapacityBytes: Long = 0L,
    val apiCacheEntries: Int = 0,
    val apiCacheHits: Long = 0L,
    val apiCacheMisses: Long = 0L,
) {
    /** 该平台是否真的在落盘（安卓为 false）。 */
    val songCacheSupported: Boolean get() = songCacheCapacityBytes > 0L

    /** 命中 + 未命中总数；0 表示本次会话还没查过任何东西。 */
    val apiCacheSamples: Long get() = apiCacheHits + apiCacheMisses

    /**
     * 接口缓存命中率 0f..1f；**没有样本时返回 null**。
     *
     * 返回 null 而不是 0f：0% 的命中率是「一查一个准地没命中」，和「还没查过」
     * 是完全相反的两件事 —— 前者说明缓存白名单或键配错了，值得去查。
     */
    val apiCacheHitRate: Float?
        get() = if (apiCacheSamples <= 0L) null
        else apiCacheHits.toFloat() / apiCacheSamples.toFloat()
}

/**
 * 存储管理页 ScreenModel。
 *
 * 统计口径说明：
 * - 下载占用取本地媒体库登记的 `LocalMediaItem.sizeBytes`（登记发生在下载完成与扫描时，
 *   与磁盘实际状态基本一致），**不做全盘扫描** —— 扫描大目录会卡住页面且没有必要。
 * - 图片缓存占用读 Coil 磁盘缓存的实时值（平台层 `imageCacheSizeBytes`）。
 * - 歌曲缓存（无损流）与接口缓存走 [cp.player.core.MusicBackend] 的管理句柄：
 *   前者要读目录放 IO 线程，后者是内存计数直接取。
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
        // 接口缓存计数常驻收集：命中率是个「一直在动」的数字，进来取一次就永远停在旧值。
        screenModelScope.launch {
            AppModel.backend.apiCacheStats.collect { stats ->
                _state.value = _state.value.copy(
                    apiCacheHits = stats.hits,
                    apiCacheMisses = stats.misses,
                    // 条目数不在 stats 里（它只记累计事件），单独取一次快照。
                    apiCacheEntries = AppModel.backend.apiCacheSize(),
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
            _state.value = _state.value.copy(
                songCacheEntries = stats.entries,
                songCacheBytes = stats.bytes,
                songCacheCapacityBytes = stats.capacityBytes,
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

    // ============ 接口缓存（进程内元数据） ============

    /** 清空接口缓存。它只是元数据副本，清掉不影响任何已下载或已缓存的音频。 */
    fun clearApiCache() {
        screenModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { AppModel.backend.clearApiCache() }.getOrDefault(0)
            // 命中 / 未命中是累计值，不会被 clear 重置 —— 这里只为顺带刷新条目数。
            val stats = AppModel.backend.apiCacheStats.value
            _state.value = _state.value.copy(
                apiCacheEntries = AppModel.backend.apiCacheSize(),
                apiCacheHits = stats.hits,
                apiCacheMisses = stats.misses,
            )
            withContext(Dispatchers.Main) {
                UiEvents.notify(if (removed > 0) "接口缓存已清理，删除 $removed 条" else "接口缓存本来就是空的")
            }
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
