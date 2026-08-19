package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.kmp.BackendResult
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val dailySongs: List<TrackSummary> = emptyList(),
    val newSongs: List<TrackSummary> = emptyList(),
    val recommendedPlaylists: List<PlaylistSummary> = emptyList(),
    val hotPlaylists: List<PlaylistSummary> = emptyList(),
    val userPlaylists: List<PlaylistSummary> = emptyList(),
    val likedPlaylist: PlaylistSummary? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class HomeScreenModel : ScreenModel {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        println("[HomeScreenModel] init - calling refresh()")
        refresh()
    }

    fun playIntelligence(seed: TrackSummary?) {
        if (seed == null) { cp.player.app.ui.util.UiEvents.notify("请先等待每日推荐加载"); return }
        screenModelScope.launch {
            val songs = runCatching { AppModel.musicRepository.getIntelligenceSongs(seed.id) }.getOrNull()
            val tracks = (songs as? BackendResult.Success)?.data.orEmpty()
            if (tracks.isEmpty()) cp.player.app.ui.util.UiEvents.notify("心动模式暂不可用")
            else AppModel.playback.playQueue(tracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }, 0)
        }
    }

    fun playSimilar(seed: TrackSummary?) {
        if (seed == null) { cp.player.app.ui.util.UiEvents.notify("请先等待每日推荐加载"); return }
        screenModelScope.launch {
            val result = runCatching { AppModel.musicRepository.getSimilarSongs(seed.id) }.getOrNull()
            val tracks = (result as? BackendResult.Success)?.data.orEmpty()
            if (tracks.isEmpty()) cp.player.app.ui.util.UiEvents.notify("相似歌曲暂不可用")
            else AppModel.playback.playQueue(tracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }, 0)
        }
    }

    /** 播放私人 FM：按批次连续拉取，补足一组可听队列。 */
    fun playPersonalFm() {
        screenModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.getPersonalFmBatch(targetSize = 18) }.getOrNull()
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
        println("[HomeScreenModel] refresh() called, loading=${_state.value.loading}, dailySongs=${_state.value.dailySongs.size}")
        if (_state.value.loading && _state.value.dailySongs.isNotEmpty()) return
        screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            println("[HomeScreenModel] refresh() launching loadHome on IO")
            _state.value = withContext(Dispatchers.IO) { loadHome() }.copy(loading = false)
            println("[HomeScreenModel] refresh() loadHome completed, error=${_state.value.error}")
        }
    }

    private suspend fun loadHome(): HomeUiState {
        var error: String? = null
        println("[HomeScreenModel] loadHome 开始")
        val songs = runCatching {
            println("[HomeScreenModel] 调用 getRecommendedSongs")
            AppModel.musicRepository.getRecommendedSongs()
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
            AppModel.musicRepository.getRecommendedPlaylists()
        }.getOrNull().let { (it as? BackendResult.Success)?.data.orEmpty() }
        // Public discovery APIs provide useful fallback content when personalized data is unavailable.
        val publicRecommended = runCatching {
            AppModel.musicRepository.getPersonalizedPlaylists(30)
        }.getOrNull().let { (it as? BackendResult.Success)?.data.orEmpty() }
        val hotPlaylists = runCatching {
            AppModel.musicRepository.getTopPlaylists(limit = 30)
        }.getOrNull().let { (it as? BackendResult.Success)?.data.orEmpty() }
        val newSongs = runCatching {
            AppModel.musicRepository.getPersonalizedNewSongs(12)
        }.getOrNull().let { (it as? BackendResult.Success)?.data.orEmpty() }
        val user = runCatching {
            when (val result = AppModel.musicRepository.getCurrentUserPlaylists()) {
                is BackendResult.Success -> result.data
                is BackendResult.Error -> {
                    if (error == null) error = result.message
                    emptyList()
                }
                is BackendResult.Unsupported -> {
                    if (error == null) error = result.message
                    emptyList()
                }
            }
        }.getOrDefault(emptyList())
        val likedPlaylist = user.firstOrNull { it.name.contains("喜欢", ignoreCase = true) || it.name.contains("Like", ignoreCase = true) }
        val mergedRecommended = (recommended + publicRecommended).distinctBy { it.id }
        return HomeUiState(
            dailySongs = songs,
            newSongs = newSongs,
            recommendedPlaylists = mergedRecommended,
            hotPlaylists = hotPlaylists,
            userPlaylists = user,
            likedPlaylist = likedPlaylist,
            loading = false,
            error = error,
        )
    }
}
