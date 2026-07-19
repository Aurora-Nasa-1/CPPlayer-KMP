package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.kmp.BackendResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistSummary
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
                    val account = (status as? JsonObject)?.get("account") as? JsonObject
                        ?: (status as? JsonObject)?.get("profile") as? JsonObject
                    val uid = account?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull }
                    val playlists = if (uid == null) emptyList() else {
                        val parsed = MusicSourceFromApi.parseUserPlaylists(AppModel.api.getUserPlaylists(uid))
                        (parsed as? BackendResult.Success)?.data.orEmpty()
                    }
                    LibraryUiState(playlists = playlists, loading = false)
                }.getOrElse {
                    LibraryUiState(loading = false, error = it.message ?: "媒体库加载失败")
                }
            }
        }
    }

    fun play(playlist: PlaylistSummary, addOnly: Boolean = false) {
        screenModelScope.launch {
            val result = MusicSourceFromApi.getPlaylistDetail(AppModel.api, playlist.id)
            if (result !is BackendResult.Success) return@launch
            val ids = result.data.tracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }
            if (addOnly) ids.forEach { AppModel.playback.addToQueue(it) }
            else AppModel.playback.playQueue(ids, startIndex = 0)
        }
    }
}
