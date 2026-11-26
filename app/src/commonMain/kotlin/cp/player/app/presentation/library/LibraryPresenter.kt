package cp.player.app.presentation.library

import cp.player.app.AppModel
import cp.player.app.presentation.UiPresenter
import cp.player.core.BackendResult
import cp.player.core.music.PlaylistSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val selectedPlaylistId: String? = null,
    val selectedTab: Int = 0,
)

/**
 * 框架无关的媒体库业务状态呈现器。
 *
 * 负责歌单加载、自动响应登录/音源/歌单变更、歌单播放与下载等业务逻辑，
 * 脱离 Voyager / Compose 依赖，可直接用于任何前端框架。
 */
class LibraryPresenter(
    private val initialPlaylistId: Long? = null,
    override val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
) : UiPresenter<LibraryUiState> {

    private val _state = MutableStateFlow(
        LibraryUiState(selectedPlaylistId = initialPlaylistId?.toString())
    )
    override val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var refreshJob: Job? = null
    private var refreshGen = 0

    init {
        val cached = runCatching { AppModel.musicRepository.peekCurrentUserPlaylists() }.getOrNull()
        if (cached != null) {
            _state.value = _state.value.copy(playlists = cached.value, loading = false, error = null)
        }
        if (cached == null || !cached.isFresh) {
            refresh()
        }
        scope.launch {
            AppModel.userProfileFlow.drop(1).collect { refresh() }
        }
        scope.launch {
            AppModel.sourceGeneration.drop(1).collect { refresh() }
        }
        scope.launch {
            AppModel.playlistGeneration.drop(1).collect { refresh() }
        }
    }

    fun selectPlaylist(playlistId: Long?) {
        _state.value = _state.value.copy(
            selectedPlaylistId = playlistId?.toString(),
            selectedTab = 0,
        )
    }

    fun selectTab(index: Int) {
        _state.value = _state.value.copy(selectedTab = index)
    }

    fun refresh() {
        refreshJob?.cancel()
        val gen = ++refreshGen
        refreshJob = scope.launch {
            val silent = _state.value.playlists.isNotEmpty()
            if (!silent) _state.value = _state.value.copy(loading = true, error = null)
            _refreshing.value = true
            try {
                if (AppModel.activeProvider() == null) {
                    if (gen == refreshGen) {
                        _state.update { s -> s.copy(playlists = emptyList(), loading = false, error = null) }
                    }
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    runCatching { AppModel.musicRepository.getCurrentUserPlaylists() }.getOrNull()
                }
                if (gen != refreshGen) return@launch
                _state.update { s ->
                    when (result) {
                        is BackendResult.Success ->
                            if (silent && result.data == s.playlists) {
                                s.copy(loading = false)
                            } else {
                                s.copy(playlists = result.data, loading = false)
                            }
                        is BackendResult.Error ->
                            if (s.playlists.isNotEmpty()) s.copy(loading = false)
                            else s.copy(loading = false, error = result.message)
                        is BackendResult.Unsupported -> s.copy(loading = false, error = result.message)
                        null ->
                            if (s.playlists.isNotEmpty()) s.copy(loading = false)
                            else s.copy(loading = false, error = "媒体库加载失败")
                    }
                }
            } finally {
                if (gen == refreshGen) _refreshing.value = false
            }
        }
    }

    fun isOwner(playlist: PlaylistSummary): Boolean {
        val nickname = AppModel.userProfileFlow.value?.nickname
        return nickname != null && playlist.creatorName == nickname
    }

    fun deleteOrUnsubscribe(playlist: PlaylistSummary) {
        scope.launch {
            val ok = runCatching {
                if (isOwner(playlist)) AppModel.musicRepository.deletePlaylist(playlist.resourceId)
                else AppModel.musicRepository.unsubscribePlaylist(playlist.resourceId)
            }.getOrDefault(false)
            notify(
                if (ok) (if (isOwner(playlist)) "已删除「${playlist.name}」" else "已取消收藏「${playlist.name}」")
                else "操作失败"
            )
            if (ok) refresh()
        }
    }

    fun play(playlist: PlaylistSummary, addOnly: Boolean = false) {
        scope.launch {
            val result = AppModel.musicRepository.getPlaylistDetail(playlist.resourceId)
            if (result !is BackendResult.Success) return@launch
            val ids = result.data.tracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }
            if (ids.isEmpty()) return@launch
            if (addOnly) {
                ids.forEach { AppModel.playback.addToQueue(it) }
                notify("已加入播放队列")
            } else {
                AppModel.playback.playQueue(
                    ids,
                    startIndex = 0,
                    sourceId = playlist.resourceId,
                )
            }
        }
    }

    fun download(playlist: PlaylistSummary) {
        scope.launch {
            val result = AppModel.musicRepository.getPlaylistDetail(playlist.resourceId)
            if (result !is BackendResult.Success) return@launch
            AppModel.downloadTracks(result.data.tracks)
        }
    }
}
