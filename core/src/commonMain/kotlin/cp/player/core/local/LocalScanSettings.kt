package cp.player.core.local

import cp.player.core.media.LocalMediaItem
import cp.player.core.media.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 本地扫描的过滤规则（**影响索引本身**，不是浏览期的显示开关）。
 *
 * 与库内筛选（只看重复 / 只看无损等，见 `LocalLibraryFilter`）的区别：
 * 这里的规则在 `scan()` 阶段就把条目挡在索引之外，重扫才会重新生效；
 * 那边的开关只影响当前视图，随时可切回。
 *
 * @param minDurationSeconds 最短时长（秒）；0 = 不按时长过滤
 * @param filterVideoFiles 扫描时跳过视频文件
 * @param excludeFolders 排除目录（前缀匹配）；常用于排除录音、播客、有声书目录
 * @param includeOnlyFolders 仅扫描这些目录；为空表示不限制
 */
@Serializable
data class LocalScanSettings(
    val minDurationSeconds: Int = 0,
    val filterVideoFiles: Boolean = false,
    val excludeFolders: List<String> = emptyList(),
    val includeOnlyFolders: List<String> = emptyList(),
) {
    /** 是否全是默认值（UI 用来决定要不要在按钮上打标记）。 */
    val isDefault: Boolean
        get() = minDurationSeconds <= 0 && !filterVideoFiles &&
            excludeFolders.isEmpty() && includeOnlyFolders.isEmpty()

    /** 生效中的规则条数（UI 角标）。 */
    val activeRuleCount: Int
        get() = (if (minDurationSeconds > 0) 1 else 0) +
            (if (filterVideoFiles) 1 else 0) +
            (if (excludeFolders.isNotEmpty()) 1 else 0) +
            (if (includeOnlyFolders.isNotEmpty()) 1 else 0)

    /**
     * 对扫描发现的条目应用规则。
     *
     * 默认设置下原样返回（不产生一次多余的列表遍历 —— 大曲库扫描全程都在这个循环里）。
     */
    fun apply(items: List<LocalMediaItem>): List<LocalMediaItem> {
        if (isDefault) return items
        return items.filter { item ->
            if (filterVideoFiles && item.mediaType != MediaType.AUDIO) return@filter false
            // 时长未知（0）时**保留**：解析失败不等于「短音频」，
            // 按 < 阈值 滤掉会把整类无法解析的文件误删出曲库。
            if (minDurationSeconds > 0 && item.durationMs > 0 &&
                item.durationMs < minDurationSeconds * 1000L
            ) {
                return@filter false
            }
            if (matchesAny(item.path, excludeFolders)) return@filter false
            if (includeOnlyFolders.isNotEmpty() && !matchesAny(item.path, includeOnlyFolders)) {
                return@filter false
            }
            true
        }
    }

    private fun matchesAny(path: String, folders: List<String>): Boolean {
        // content:// 树 URI 与本地路径的「包含于某目录」不是同一套语义，
        // 不做匹配（否则 SAF 导入的条目会被整批误伤）。
        if (LocalMediaIndex.isContentUri(path)) return false
        return folders.any { folder ->
            folder.isNotBlank() && LocalMediaIndex.isUnderRoot(path, folder.trim())
        }
    }
}

/**
 * 扫描设置存储（commonMain，JSON 持久化到平台数据目录）。
 *
 * 与 [LocalFavorites] 同一套模式：构造时读盘，改动即写盘，通过 [StateFlow] 让 UI 跟随。
 */
class LocalScanSettingsStore(private val file: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LocalScanSettings> = _settings.asStateFlow()

    /** 当前设置快照。 */
    fun snapshot(): LocalScanSettings = _settings.value

    /** 整体替换设置并持久化。 */
    fun update(next: LocalScanSettings) {
        _settings.value = next
        persist(next)
    }

    /** 恢复默认（清空全部规则）。 */
    fun reset() = update(LocalScanSettings())

    private fun load(): LocalScanSettings {
        val text = localMediaReadText(file) ?: return LocalScanSettings()
        return runCatching { json.decodeFromString<LocalScanSettings>(text) }
            .getOrDefault(LocalScanSettings())
    }

    private fun persist(value: LocalScanSettings) {
        runCatching { json.encodeToString(value) }.getOrNull()?.let { localMediaWriteText(file, it) }
    }
}
