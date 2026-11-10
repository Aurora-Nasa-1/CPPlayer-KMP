package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.core.BackendResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LibraryTrackHit(
    val playlist: PlaylistSummary,
    val track: TrackSummary,
)

data class LibrarySearchUiState(
    val trackHits: List<LibraryTrackHit> = emptyList(),
    val loadingPlaylistTracks: Boolean = false,
)

/**
 * 歌单歌曲搜索状态。
 *
 * 仅通过仓库分页读取，按歌单顺序合并命中项。查询变化取消上一轮加载；
 * 不直接传递账号凭证或读取旧 JSON 缓存。
 */
class LibrarySearchScreenModel : ScreenModel {
    companion object {
        private const val PAGE_SIZE = 300
        private const val MAX_TRACKS_PER_PLAYLIST = 2000
    }

    private val _state = MutableStateFlow(LibrarySearchUiState())
    val state: StateFlow<LibrarySearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var generation = 0

    fun search(query: String, playlists: List<PlaylistSummary>) {
        searchJob?.cancel()
        val gen = ++generation
        val keyword = query.trim()
        _state.value = LibrarySearchUiState(
            loadingPlaylistTracks = keyword.isNotEmpty() && playlists.isNotEmpty(),
        )
        if (keyword.isEmpty() || playlists.isEmpty()) return

        searchJob = screenModelScope.launch {
            val loaded = LinkedHashMap<String, LinkedHashMap<String, TrackSummary>>()
            val loadedMutex = Mutex()
            withContext(Dispatchers.IO) {
                playlists.map { playlist ->
                    launch {
                        loadPlaylist(
                            playlist = playlist,
                            generation = gen,
                            onPage = { tracks ->
                                loadedMutex.withLock {
                                    if (gen != generation) return@withLock
                                    val tracksById = loaded.getOrPut(playlist.resourceId) { LinkedHashMap() }
                                    tracks.forEach { tracksById[it.id] = it }
                                    val hits = playlists.flatMap { orderedPlaylist ->
                                        loaded[orderedPlaylist.resourceId].orEmpty().values
                                            .filter { it.matches(keyword) }
                                            .map { LibraryTrackHit(orderedPlaylist, it) }
                                    }.take(MAX_TRACKS_PER_PLAYLIST)
                                    _state.value = LibrarySearchUiState(
                                        trackHits = hits,
                                        loadingPlaylistTracks = true,
                                    )
                                }
                            },
                        )
                    }
                }.forEach { it.join() }
            }
            if (gen == generation) {
                _state.value = _state.value.copy(loadingPlaylistTracks = false)
            }
        }
    }

    private suspend fun loadPlaylist(
        playlist: PlaylistSummary,
        generation: Int,
        onPage: suspend (List<TrackSummary>) -> Unit,
    ) {
        var offset = 0
        var loadedCount = 0
        while (generation == this.generation && loadedCount < MAX_TRACKS_PER_PLAYLIST) {
            val page = (AppModel.musicRepository.getPlaylistTracks(playlist.resourceId, PAGE_SIZE, offset)
                as? BackendResult.Success)?.data ?: break
            if (generation != this.generation) break
            loadedCount = (loadedCount + page.tracks.size).coerceAtMost(MAX_TRACKS_PER_PLAYLIST)
            onPage(page.tracks)
            val hasMore = page.hasMore || (!page.paginationSpecified && page.tracks.size >= PAGE_SIZE)
            if (!hasMore || page.tracks.isEmpty() || offset + PAGE_SIZE >= MAX_TRACKS_PER_PLAYLIST) break
            offset += PAGE_SIZE
        }
    }

    private fun TrackSummary.matches(keyword: String): Boolean =
        name.contains(keyword, ignoreCase = true) ||
            artist.contains(keyword, ignoreCase = true) ||
            album?.contains(keyword, ignoreCase = true) == true
}
