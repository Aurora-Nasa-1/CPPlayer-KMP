package cp.player.core.local

import cp.player.core.util.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义本地歌单。
 *
 * @param id 歌单唯一 ID，如 "local_pl_{timestamp}"
 * @param name 歌单名称
 * @param coverUri 自定义封面 URI，为 null 时默认回退到第一首有封面的本地歌曲
 * @param trackPaths 包含的本地文件绝对路径（有序列表）
 * @param createdAt 创建时间戳（毫秒）
 * @param updatedAt 最后修改时间戳（毫秒）
 */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val coverUri: String? = null,
    val trackPaths: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * 本地歌单存储管理（commonMain，持久化为 JSON 文件）。
 */
class LocalPlaylistsStore(private val file: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(load())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    fun snapshot(): List<LocalPlaylist> = _playlists.value

    fun getPlaylist(id: String): LocalPlaylist? = _playlists.value.find { it.id == id }

    /** 创建新本地歌单。 */
    fun createPlaylist(name: String): LocalPlaylist {
        val now = currentTimeMillis()
        val playlist = LocalPlaylist(
            id = "local_pl_$now",
            name = name.trim().ifBlank { "新建歌单" },
            createdAt = now,
            updatedAt = now,
        )
        val next = _playlists.value + playlist
        _playlists.value = next
        persist(next)
        return playlist
    }

    /** 删除本地歌单。 */
    fun deletePlaylist(id: String): Boolean {
        val current = _playlists.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        _playlists.value = next
        persist(next)
        return true
    }

    /** 重命名本地歌单。 */
    fun renamePlaylist(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val current = _playlists.value
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return false
        val item = current[index]
        val updated = item.copy(
            name = trimmed,
            updatedAt = currentTimeMillis(),
        )
        val next = current.toMutableList().also { it[index] = updated }
        _playlists.value = next
        persist(next)
        return true
    }

    /** 向歌单添加本地曲目（按路径去重）。返回成功添加的新条目数。 */
    fun addTracks(id: String, paths: List<String>): Int {
        val current = _playlists.value
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return 0
        val item = current[index]
        val existing = item.trackPaths.toSet()
        val toAdd = paths.filter { it !in existing }
        if (toAdd.isEmpty()) return 0
        val updated = item.copy(
            trackPaths = item.trackPaths + toAdd,
            updatedAt = currentTimeMillis(),
        )
        val next = current.toMutableList().also { it[index] = updated }
        _playlists.value = next
        persist(next)
        return toAdd.size
    }

    /** 从歌单移除指定路径的曲目。 */
    fun removeTracks(id: String, paths: List<String>): Boolean {
        val current = _playlists.value
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return false
        val item = current[index]
        val toRemove = paths.toSet()
        val nextPaths = item.trackPaths.filterNot { it in toRemove }
        if (nextPaths.size == item.trackPaths.size) return false
        val updated = item.copy(
            trackPaths = nextPaths,
            updatedAt = currentTimeMillis(),
        )
        val next = current.toMutableList().also { it[index] = updated }
        _playlists.value = next
        persist(next)
        return true
    }

    /** 批量清理路径（文件被移出曲库时调用）。 */
    fun removeAll(paths: Collection<String>) {
        val pathSet = paths.toSet()
        var changed = false
        val next = _playlists.value.map { pl ->
            val filtered = pl.trackPaths.filterNot { it in pathSet }
            if (filtered.size != pl.trackPaths.size) {
                changed = true
                pl.copy(trackPaths = filtered)
            } else pl
        }
        if (changed) {
            _playlists.value = next
            persist(next)
        }
    }

    private fun load(): List<LocalPlaylist> {
        val text = localMediaReadText(file) ?: return emptyList()
        return runCatching { json.decodeFromString<List<LocalPlaylist>>(text) }.getOrDefault(emptyList())
    }

    private fun persist(value: List<LocalPlaylist>) {
        runCatching { json.encodeToString(value) }.getOrNull()?.let { localMediaWriteText(file, it) }
    }
}

/** 空实现的本地歌单存储，用于测试或 Noop 媒体源。 */
val NoopLocalPlaylistsStore: LocalPlaylistsStore by lazy {
    LocalPlaylistsStore("")
}
