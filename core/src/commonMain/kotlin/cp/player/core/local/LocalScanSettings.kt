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
 * @param minDurationSeconds 最短时长（秒）；0 = 不按时长过滤（常用预设见 [SHORT_AUDIO_SECONDS]）
 * @param filterVideoFiles 扫描时跳过视频文件
 * @param excludeFolders 排除目录（前缀匹配）；常用于排除录音、播客、有声书目录
 * @param includeOnlyFolders 仅扫描这些目录；为空表示不限制
 * @param excludeUnreadableAudio 排除读不出时长的音频（文件截断 / 编码损坏这类「不规则」文件）
 * @param excludeDuplicates 排除重复音频（同一首只保留音质最好的一份，跨批次判定见 [deduplicate]）
 */
@Serializable
data class LocalScanSettings(
    val minDurationSeconds: Int = 0,
    val filterVideoFiles: Boolean = false,
    val excludeFolders: List<String> = emptyList(),
    val includeOnlyFolders: List<String> = emptyList(),
    val excludeUnreadableAudio: Boolean = false,
    val excludeDuplicates: Boolean = false,
) {
    companion object {
        /** 「排除短音频」的一键预设：小于 5 秒的提示音 / 音效 / 空白录音。 */
        const val SHORT_AUDIO_SECONDS: Int = 5

        /**
         * 判定同一首的两个副本允许的时长差（毫秒）。
         *
         * 同一首歌的不同码率 / 不同封装在尾部常差几百毫秒（编码器补静音、标签写入差异），
         * 卡死相等会把本该合并的副本判成两首。但放到 5 秒以上就会把
         * 「原版 / Live / 加长版」也合并掉，所以取 2 秒。
         */
        const val DUPLICATE_DURATION_TOLERANCE_MS: Long = 2_000L
    }

    /** 是否全是默认值（UI 用来决定要不要在按钮上打标记）。 */
    val isDefault: Boolean
        get() = minDurationSeconds <= 0 && !filterVideoFiles &&
            excludeFolders.isEmpty() && includeOnlyFolders.isEmpty() &&
            !excludeUnreadableAudio && !excludeDuplicates

    /** 生效中的规则条数（UI 角标）。 */
    val activeRuleCount: Int
        get() = (if (minDurationSeconds > 0) 1 else 0) +
            (if (filterVideoFiles) 1 else 0) +
            (if (excludeFolders.isNotEmpty()) 1 else 0) +
            (if (includeOnlyFolders.isNotEmpty()) 1 else 0) +
            (if (excludeUnreadableAudio) 1 else 0) +
            (if (excludeDuplicates) 1 else 0)

    /**
     * 对扫描发现的条目应用**逐条**规则（时长 / 视频 / 目录 / 无法解析）。
     *
     * 默认设置下原样返回（不产生一次多余的列表遍历 —— 大曲库扫描全程都在这个循环里）。
     * 重复判定需要看到全部条目，不在这个逐批调用的函数里做，见 [applyAll]。
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
            // 「不规则」音频：读不出时长基本就是文件被截断 / 编码损坏。
            // SAF 条目从不解析时长（walkTree 里恒为 0），不能据此判成损坏，跳过。
            if (excludeUnreadableAudio && item.mediaType == MediaType.AUDIO &&
                item.durationMs <= 0 && !LocalMediaIndex.isContentUri(item.path)
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

    /**
     * 对一次扫描的**完整结果**应用全部规则：逐条规则 + 跨批次的重复合并。
     *
     * 扫描按 50 条一批解析，重复判定必须等整轮结束（否则同一首歌的两个副本
     * 落在相邻两批里就互相看不见）。
     */
    fun applyAll(items: List<LocalMediaItem>): List<LocalMediaItem> = deduplicate(apply(items))

    /**
     * 合并重复音频：同「标题 + 艺人」且时长差在
     * [DUPLICATE_DURATION_TOLERANCE_MS] 内的条目视为同一首，只保留
     * 音质最好的一份（无损 > 码率高 > 体积大），其余从结果里剔除。
     *
     * 三条边界：
     * - **视频不参与**（同一部片子的不同清晰度不该被互删）
     * - **SAF 条目不参与**（时长恒为 0，无法和本地条目对齐，互删风险大于收益）
     * - **双方时长都未知**时只在文件大小完全相同时才合并（同一文件被复制两份）
     *
     * 结果剔除顺序稳定（同分数按 path 排序），重扫不会来回换保留哪一份。
     */
    fun deduplicate(items: List<LocalMediaItem>): List<LocalMediaItem> {
        if (!excludeDuplicates || items.size < 2) return items
        val groups = LinkedHashMap<String, MutableList<LocalMediaItem>>()
        for (item in items) {
            if (item.mediaType != MediaType.AUDIO) continue
            if (LocalMediaIndex.isContentUri(item.path)) continue
            groups.getOrPut(duplicateKey(item)) { ArrayList() } += item
        }
        val dropped = HashSet<String>()
        for (group in groups.values) {
            if (group.size < 2) continue
            // 按时长升序后线性聚类：同一首的不同码率副本总挨在一起
            val sorted = group.sortedBy { it.durationMs }
            var index = 0
            while (index < sorted.size) {
                val seed = sorted[index]
                val cluster = ArrayList<LocalMediaItem>(2)
                cluster += seed
                var next = index + 1
                while (next < sorted.size && sameRecording(seed, sorted[next])) {
                    cluster += sorted[next]
                    next++
                }
                if (cluster.size > 1) {
                    val keep = cluster.minWith(bestQualityFirst)
                    cluster.forEach { if (it.path != keep.path) dropped += it.path }
                }
                index = next
            }
        }
        return if (dropped.isEmpty()) items else items.filterNot { it.path in dropped }
    }

    private fun duplicateKey(item: LocalMediaItem): String {
        val title = item.title.trim().lowercase()
        val artist = item.artist?.trim()?.lowercase().orEmpty()
        return "$title|$artist"
    }

    private fun sameRecording(a: LocalMediaItem, b: LocalMediaItem): Boolean {
        if (a.durationMs > 0 && b.durationMs > 0) {
            return kotlin.math.abs(a.durationMs - b.durationMs) <= DUPLICATE_DURATION_TOLERANCE_MS
        }
        // 时长未知：只能靠「字节数完全一致」判断是不是同一个文件被复制了一份
        return a.durationMs <= 0 && b.durationMs <= 0 &&
            a.sizeBytes > 0 && a.sizeBytes == b.sizeBytes
    }

    private val bestQualityFirst: Comparator<LocalMediaItem> =
        compareByDescending<LocalMediaItem> { if (it.isLossless) 1 else 0 }
            .thenByDescending { it.metadata?.bitrateKbps ?: 0 }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.path }

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
