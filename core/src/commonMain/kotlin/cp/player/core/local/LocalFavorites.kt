package cp.player.core.local

import cp.player.core.media.LocalMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 本地曲「喜欢」集合（commonMain，JSON 持久化到平台数据目录）。
 *
 * ## 为什么单独做一套而不是复用在线收藏
 *
 * 播放页的 `isFavorite` 走音源的 `likeSong`，本地曲（`local://…`）根本没有
 * 在线 id，恒为 false —— 用户对自己硬盘上的歌反而无法表达喜好。
 * 这里按**文件路径**收藏：路径变了（重命名 / 移动）就当新条目，语义简单且可预测。
 *
 * 只存路径集合而非整首曲目：曲目本体仍在 [LocalMediaItem] 索引里，
 * 避免两份数据不一致（改了标签后收藏里还是旧的标题）。
 */
class LocalFavorites(private val file: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _paths = MutableStateFlow(load())
    val paths: StateFlow<Set<String>> = _paths.asStateFlow()

    /** 是否收藏。 */
    operator fun contains(path: String): Boolean = path in _paths.value

    /** 取全部收藏路径（快照）。 */
    fun snapshot(): Set<String> = _paths.value

    /**
     * 切换收藏状态。
     * @return 切换**之后**是否为收藏状态
     */
    fun toggle(path: String): Boolean {
        val current = _paths.value
        val next = if (path in current) current - path else current + path
        _paths.value = next
        persist(next)
        return path in next
    }

    /** 批量移除（文件被移出曲库时同步清理，避免残留幽灵收藏）。 */
    fun removeAll(paths: Collection<String>) {
        val next = _paths.value - paths.toSet()
        if (next.size == _paths.value.size) return
        _paths.value = next
        persist(next)
    }

    /** 按收藏集合过滤出曲目，保持 [items] 原有顺序。 */
    fun filter(items: List<LocalMediaItem>): List<LocalMediaItem> {
        val favorites = _paths.value
        if (favorites.isEmpty()) return emptyList()
        return items.filter { it.path in favorites }
    }

    private fun load(): Set<String> {
        val text = localMediaReadText(file) ?: return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(text) }.getOrDefault(emptySet())
    }

    private fun persist(value: Set<String>) {
        runCatching { json.encodeToString(value) }.getOrNull()?.let { localMediaWriteText(file, it) }
    }
}
