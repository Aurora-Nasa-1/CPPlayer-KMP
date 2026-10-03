package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import cp.player.core.music.MusicSourceFromApi
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
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
    val cloudSongs: List<TrackSummary> = emptyList(),
    val cloudLoading: Boolean = false,
    val cloudError: String? = null,
    val cloudLoaded: Boolean = false,
    val selectedPlaylistId: Long? = null,
    val selectedTab: Int = 0,
)

class LibraryScreenModel : ScreenModel {
    private val _state = MutableStateFlow(LibraryUiState())
    fun selectPlaylist(playlistId: Long?) { _state.value = _state.value.copy(selectedPlaylistId = playlistId, selectedTab = 0) }
    fun selectTab(index: Int) { _state.value = _state.value.copy(selectedTab = index) }
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** 刷新请求在途标记（驱动下拉刷新指示器；与 [LibraryUiState.loading] 分开）。 */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 在途刷新协程与世代号：登录态变化 / 切源 / 下拉刷新可能并发，后到者不该覆盖新结果。 */
    private var refreshJob: Job? = null
    private var refreshGen = 0

    init {
        refresh()
        // 订阅登录态变化（登录成功/登出后 userProfile 更新），自动刷新媒体库；
        // drop(1) 跳过首次收集的当前值，避免与 init 中的 refresh() 重复加载。
        // StateFlow 只在值真正变化时发射，无需额外去重。
        screenModelScope.launch {
            AppModel.userProfileFlow.drop(1).collect { refresh() }
        }
        // 音源切换后歌单列表（以及云盘）都属于旧音源：作废云盘缓存并重新拉取。
        // 订阅 sourceGeneration 而不是 activeProviderFlow：后者在启动恢复 Provider 时
        // 也会发射，会造成启动时白拉一遍（见 AppModel.sourceGeneration 的 KDoc）。
        screenModelScope.launch {
            AppModel.sourceGeneration.drop(1).collect {
                _state.value = _state.value.copy(cloudSongs = emptyList(), cloudLoaded = false)
                refresh()
            }
        }
    }

    /**
     * 拉取用户歌单列表。
     *
     * 已有歌单时是**静默**刷新：不置 [LibraryUiState.loading]（那会把歌单栅格整个
     * 顶成一行加载态），旧列表留在原地，拉完一次性替换；进度反馈由 [refreshing] 驱动。
     */
    fun refresh() {
        refreshJob?.cancel()
        val gen = ++refreshGen
        refreshJob = screenModelScope.launch {
            val silent = _state.value.playlists.isNotEmpty()
            if (!silent) _state.value = _state.value.copy(loading = true, error = null)
            _refreshing.value = true
            try {
                // ⚠️ IO 线程**只负责取数据**，不要在 IO 线程上读-改-写 `_state`：
                // 那会和 Main 上的 selectTab / selectPlaylist 并发，把用户刚选的态覆盖回退。
                val result = withContext(Dispatchers.IO) {
                    runCatching { AppModel.musicRepository.getCurrentUserPlaylists() }.getOrNull()
                }
                if (gen != refreshGen) return@launch
                _state.update { s ->
                    when (result) {
                        is BackendResult.Success ->
                            if (silent && result.data == s.playlists) {
                                // 内容没变就保留旧列表实例：观察者（如歌单详情页的
                                // LaunchedEffect）以列表实例为 key，换了实例会白触发一轮。
                                s.copy(loading = false)
                            } else {
                                s.copy(playlists = result.data, loading = false)
                            }
                        is BackendResult.Error -> s.copy(loading = false, error = result.message)
                        is BackendResult.Unsupported -> s.copy(loading = false, error = result.message)
                        null -> s.copy(loading = false, error = "媒体库加载失败")
                    }
                }
            } finally {
                if (gen == refreshGen) _refreshing.value = false
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
                runCatching { AppModel.musicRepository.getUserCloud() }.getOrNull()
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
        songs.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
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
                if (isOwner(playlist)) AppModel.musicRepository.deletePlaylist(playlist.id)
                else AppModel.musicRepository.unsubscribePlaylist(playlist.id)
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
            val result = AppModel.musicRepository.getPlaylistDetail(playlist.id)
            if (result !is BackendResult.Success) return@launch
            val ids = result.data.tracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }
            if (ids.isEmpty()) return@launch
            if (addOnly) {
                ids.forEach { AppModel.playback.addToQueue(it) }
                UiEvents.notify("已加入播放队列")
            } else {
                // 带上来源歌单：心动模式等「跟随播放列表」的功能要拿它当歌单上下文。
                AppModel.playback.playQueue(
                    ids,
                    startIndex = 0,
                    sourceId = playlist.id.takeIf { it > 0 }?.toString(),
                )
            }
        }
    }
}
