package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.core.BackendResult
import cp.player.core.api.MusicApiMethod
import cp.player.core.cache.CacheResult
import cp.player.core.music.MusicSourceFromApi
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
 * 每个歌单都先消费 API 缓存，再等待网络结果；缓存命中时先把已有歌曲放进结果，
 * 网络补齐后按歌单顺序追加新命中项。查询变化会取消上一轮加载，避免旧查询串进来。
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
            val loaded = LinkedHashMap<Long, LinkedHashMap<String, TrackSummary>>()
            val loadedMutex = Mutex()
            withContext(Dispatchers.IO) {
                playlists.map { playlist ->
                    launch {
                        loadPlaylist(
                            playlist = playlist,
                            keyword = keyword,
                            generation = gen,
                            onPage = { tracks ->
                                loadedMutex.withLock {
                                    val tracksById = loaded.getOrPut(playlist.id) { LinkedHashMap() }
                                    tracks.forEach { tracksById[it.id] = it }
                                    val hits = playlists.flatMap { orderedPlaylist ->
                                        loaded[orderedPlaylist.id].orEmpty().values
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
        keyword: String,
        generation: Int,
        onPage: suspend (List<TrackSummary>) -> Unit,
    ) {
        var offset = 0
        var loadedCount = 0
        while (generation == this.generation && loadedCount < MAX_TRACKS_PER_PLAYLIST) {
            var pageHasMore = false
            var emittedPage = false
            val params = mapOf(
                "id" to playlist.id.toString(),
                "limit" to PAGE_SIZE.toString(),
                "offset" to offset.toString(),
            )
            AppModel.backend.cachedApi.callApiCached(
                method = MusicApiMethod.PLAYLIST_TRACK_ALL,
                params = params,
                cookie = AppModel.cookieStorage.getCookie(AppModel.activeProviderId()),
            ).collect { cachedResult ->
                val data = when (cachedResult) {
                    is CacheResult.Cached -> cachedResult.data
                    is CacheResult.Fresh -> cachedResult.data
                    is CacheResult.Error -> cachedResult.fallback
                    CacheResult.NoChange -> null
                } ?: return@collect
                val page = (MusicSourceFromApi.parsePlaylistTracks(data) as? BackendResult.Success)?.data
                    ?: return@collect
                pageHasMore = page.hasMore || page.tracks.size >= PAGE_SIZE
                if (!emittedPage || cachedResult is CacheResult.Fresh) {
                    emittedPage = true
                    loadedCount = (loadedCount + page.tracks.size).coerceAtMost(MAX_TRACKS_PER_PLAYLIST)
                    onPage(page.tracks)
                } else {
                    // A fresh response may replace a cached page without changing its size.
                    onPage(page.tracks)
                }
            }
            if (!emittedPage || !pageHasMore || offset + PAGE_SIZE >= MAX_TRACKS_PER_PLAYLIST) break
            offset += PAGE_SIZE
        }
    }

    private fun TrackSummary.matches(keyword: String): Boolean =
        name.contains(keyword, ignoreCase = true) ||
            artist.contains(keyword, ignoreCase = true) ||
            album?.contains(keyword, ignoreCase = true) == true
}
