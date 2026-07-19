package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.ui.util.UiEvents
import cp.player.kmp.BackendResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class LibraryUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val cloudSongs: List<TrackSummary> = emptyList(),
    val cloudLoading: Boolean = false,
    val cloudError: String? = null,
    val cloudLoaded: Boolean = false,
)

class LibraryScreenModel : ScreenModel {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = withContext(Dispatchers.IO) {
                runCatching {
                    val status = AppModel.api.getLoginStatus()
                    val root = status as? JsonObject
                    val data = (root?.get("data") as? JsonObject) ?: root
                    val account = (data?.get("account") as? JsonObject)
                        ?: (data?.get("profile") as? JsonObject)
                    val uid = (account?.get("id") as? JsonPrimitive)?.longOrNull
                    val playlists = if (uid == null) emptyList() else {
                        val parsed = MusicSourceFromApi.parseUserPlaylists(AppModel.api.getUserPlaylists(uid))
                        (parsed as? BackendResult.Success)?.data.orEmpty()
                    }
                    _state.value.copy(playlists = playlists, loading = false)
                }.getOrElse {
                    _state.value.copy(loading = false, error = it.message ?: "媒体库加载失败")
                }
            }
        }
    }

    // ============ 云盘 ============

    fun loadCloud(force: Boolean = false) {
        if (_state.value.cloudLoading) return
        if (_state.value.cloudLoaded && !force) return
        screenModelScope.launch {
            _state.value = _state.value.copy(cloudLoading = true, cloudError = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { MusicSourceFromApi.getUserCloud(AppModel.api) }.getOrNull()
            }
            when (result) {
                is BackendResult.Success -> _state.value = _state.value.copy(
                    cloudSongs = result.data, cloudLoading = false, cloudLoaded = true,
                )
                is BackendResult.Error -> _state.value = _state.value.copy(
                    cloudLoading = false, cloudError = result.message,
                )
                is BackendResult.Unsupported -> _state.value = _state.value.copy(
                    cloudLoading = false, cloudError = result.message,
                )
                null -> _state.value = _state.value.copy(
                    cloudLoading = false, cloudError = "云盘加载失败",
                )
            }
        }
    }

    fun playCloud(index: Int) {
        val songs = _state.value.cloudSongs
        if (songs.isEmpty()) return
        val provider = AppModel.activeProviderId()
        screenModelScope.launch {
            AppModel.playback.playQueue(songs.map { "$provider://song/${it.id}" }, startIndex = index)
        }
    }

    // ============ 歌单管理 ============

    /** 当前账号是否拥有该歌单（用于决定删除 vs 取消收藏）。 */
    fun isOwner(playlist: PlaylistSummary): Boolean {
        val nickname = AppModel.userProfileFlow.value?.nickname
        return nickname != null && playlist.creatorName == nickname
    }

    fun deleteOrUnsubscribe(playlist: PlaylistSummary) {
        screenModelScope.launch {
            val ok = runCatching {
                if (isOwner(playlist)) AppModel.api.deletePlaylist(playlist.id)
                else AppModel.api.subscribePlaylist(playlist.id, t = 2)
                true
            }.getOrDefault(false)
            UiEvents.notify(
                if (ok) (if (isOwner(playlist)) "已删除「${playlist.name}」" else "已取消收藏「${playlist.name}」")
                else "操作失败"
            )
            if (ok) refresh()
        }
    }

    fun play(playlist: PlaylistSummary, addOnly: Boolean = false) {
        screenModelScope.launch {
            val result = MusicSourceFromApi.getPlaylistDetail(AppModel.api, playlist.id)
            if (result !is BackendResult.Success) return@launch
            val ids = result.data.tracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }
            if (ids.isEmpty()) return@launch
            if (addOnly) {
                ids.forEach { AppModel.playback.addToQueue(it) }
                UiEvents.notify("已加入播放队列")
            } else {
                AppModel.playback.playQueue(ids, startIndex = 0)
            }
        }
    }
}
