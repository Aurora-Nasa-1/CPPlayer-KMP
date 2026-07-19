package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
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

data class HomeUiState(
    val dailySongs: List<TrackSummary> = emptyList(),
    val recommendedPlaylists: List<PlaylistSummary> = emptyList(),
    val userPlaylists: List<PlaylistSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeScreenModel : ScreenModel {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { refresh() }

    /** 播放私人 FM：拉取一批 FM 曲目并整体入队。 */
    fun playPersonalFm() {
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { MusicSourceFromApi.getPersonalFm(AppModel.api) }.getOrNull()
            }
            val songs = (result as? BackendResult.Success)?.data.orEmpty()
            if (songs.isEmpty()) {
                cp.player.app.ui.util.UiEvents.notify("私人FM暂不可用，请稍后再试")
                return@launch
            }
            val provider = AppModel.activeProviderId()
            AppModel.playback.playQueue(songs.map { "$provider://song/${it.id}" }, startIndex = 0)
        }
    }

    fun refresh() {
        if (_state.value.loading && _state.value.dailySongs.isNotEmpty()) return
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _state.value = withContext(Dispatchers.IO) { loadHome() }.copy(loading = false)
        }
    }

    private suspend fun loadHome(): HomeUiState {
        var error: String? = null
        val songs = runCatching {
            MusicSourceFromApi.parseRecommendedSongs(AppModel.api.getRecommendedSongs())
        }.fold(
            onSuccess = {
                when (it) {
                    is BackendResult.Success -> it.data
                    is BackendResult.Error -> { error = it.message; emptyList() }
                    is BackendResult.Unsupported -> { error = it.message; emptyList() }
                }
            },
            onFailure = { error = it.message ?: "日推加载失败"; emptyList() },
        )
        val recommended = runCatching {
            MusicSourceFromApi.parseRecommendedPlaylists(AppModel.api.getRecommendedPlaylists())
        }.getOrNull().let { (it as? BackendResult.Success)?.data.orEmpty() }
        val user = runCatching {
            val status = AppModel.api.getLoginStatus()
            val account = (status as? JsonObject)?.get("account") as? JsonObject
                ?: (status as? JsonObject)?.get("profile") as? JsonObject
            val uid = account?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull }
            if (uid == null) emptyList() else {
                val result = MusicSourceFromApi.parseUserPlaylists(AppModel.api.getUserPlaylists(uid))
                (result as? BackendResult.Success)?.data.orEmpty()
            }
        }.getOrDefault(emptyList())
        return HomeUiState(songs, recommended, user, loading = false, error = error)
    }
}
