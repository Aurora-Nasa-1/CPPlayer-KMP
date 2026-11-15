package cp.player.app.presentation.clouddrive

import cp.player.app.AppModel
import cp.player.app.presentation.UiPresenter
import cp.player.core.BackendResult
import cp.player.core.music.TrackSummary
import cp.player.core.provider.ProviderCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudDriveUiState(
    val songs: List<TrackSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** 服务端还有更多（上一页返回的数量达到单页上限时为真）。 */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

/**
 * 云盘取数的框架无关状态机。
 *
 * 刷新信号只认 `sourceGeneration` / `accountGeneration`（见 AppModel 的 KDoc）：
 * 云盘内容属于**账号**，换音源或换账号后必须重拉，而这两个 Flow 不会在启动时
 * 白发射一次（不像 `activeProviderFlow` / `userProfileFlow`）。
 */
class CloudDrivePresenter(
    override val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
    private val playCoverFlight: (TrackSummary) -> Unit = {},
) : UiPresenter<CloudDriveUiState> {

    private val _state = MutableStateFlow(CloudDriveUiState())
    override val state: StateFlow<CloudDriveUiState> = _state.asStateFlow()

    /** 刷新在途标记（驱动下拉刷新指示器，与全屏 loading 分开）。 */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 在途协程与世代号：切源 / 换账号 / 手动刷新 / 加载更多可能并发，后到者不该覆盖新结果。 */
    private var job: Job? = null
    private var moreJob: Job? = null
    private var gen = 0

    init {
        // ⚠️ 初始加载**不带任何守卫**：初态本来就是 loading=true，
        // 旧版在这里用 `if (loading && !force) return` 把唯一一次加载拦掉了。
        load()
        scope.launch {
            AppModel.sourceGeneration.drop(1).collect { load(force = true) }
        }
        scope.launch {
            AppModel.accountGeneration.drop(1).collect { load(force = true) }
        }
    }

    /** 从第一页重拉（首次 / 下拉 / 切源 / 换账号）。IO 线程只取数，状态回写在 Main。 */
    fun load(force: Boolean = false) {
        if (!AppModel.activeProviderSupports(ProviderCapability.CLOUD_DRIVE)) {
            _state.value = CloudDriveUiState(loading = false, error = "当前音源未提供云盘功能")
            return
        }
        if (_state.value.loading && job?.isActive == true && !force) return
        job?.cancel()
        moreJob?.cancel()
        val myGen = ++gen
        job = scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _refreshing.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        AppModel.musicRepository.getCloudDriveTracks(
                            limit = PAGE_SIZE,
                            offset = 0,
                            refresh = force,
                        )
                    }.getOrNull()
                }
                if (myGen != gen) return@launch
                _state.value = when (result) {
                    is BackendResult.Success -> CloudDriveUiState(
                        songs = result.data.tracks,
                        loading = false,
                        hasMore = result.data.hasMore,
                    )
                    is BackendResult.Error -> CloudDriveUiState(loading = false, error = result.message)
                    is BackendResult.Unsupported -> CloudDriveUiState(loading = false, error = result.message)
                    null -> CloudDriveUiState(loading = false, error = "云盘加载失败")
                }
            } finally {
                if (myGen == gen) _refreshing.value = false
            }
        }
    }

    /** 追加下一页：offset 取当前已加载数量；追加时按 id 去重（同一首歌上传两次会拿到同 id）。 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.error != null || !s.hasMore || s.loadingMore) return
        val myGen = gen
        moreJob?.cancel()
        moreJob = scope.launch {
            _state.update { it.copy(loadingMore = true, loadMoreError = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppModel.musicRepository.getCloudDriveTracks(limit = PAGE_SIZE, offset = s.songs.size)
                }.getOrNull()
            }
            if (myGen != gen) return@launch
            when (result) {
                is BackendResult.Success -> _state.update { cur ->
                    val known = cur.songs.mapTo(mutableSetOf()) { it.id }
                    val appended = result.data.tracks.filterNot { it.id in known }
                    cur.copy(
                        songs = cur.songs + appended,
                        loadingMore = false,
                        // 追加去重后为空时以返回数量为准，避免反复拉同一页（与歌单详情页同一约定）。
                        hasMore = if (appended.isEmpty()) false else result.data.hasMore,
                    )
                }
                is BackendResult.Error -> _state.update { it.copy(loadingMore = false, loadMoreError = result.message) }
                is BackendResult.Unsupported -> _state.update { it.copy(loadingMore = false, loadMoreError = result.message) }
                null -> _state.update { it.copy(loadingMore = false, loadMoreError = "加载更多失败") }
            }
        }
    }

    /** 清除分页错误（点击重试后重新触发 [loadMore]）。 */
    fun clearLoadMoreError() {
        moreJob?.cancel()
        _state.update { it.copy(loadMoreError = null) }
    }

    fun play(index: Int) {
        val songs = _state.value.songs
        if (songs.isEmpty()) return
        songs.getOrNull(index)?.let(playCoverFlight)
        val provider = AppModel.activeProviderId()
        val clicked = songs.getOrNull(index)?.let { "$provider://song/${it.id}" } ?: return
        // 云盘歌曲的 simpleSong.id 是标准歌曲 id，song/url/v1 实测可用，
        // 直接按普通歌曲入列（带上列表上下文，跟随播放列表的功能才有落点）。
        AppModel.playTrackClicked(clicked) {
            AppModel.playback.playQueue(songs.map { "$provider://song/${it.id}" }, startIndex = index)
        }
    }

    fun addToQueue(track: TrackSummary) {
        scope.launch {
            AppModel.playback.addToQueue("${AppModel.activeProviderId()}://song/${track.id}")
            notify("已加入播放队列")
        }
    }

    fun playNext(track: TrackSummary) {
        scope.launch {
            AppModel.playback.addNextToQueue("${AppModel.activeProviderId()}://song/${track.id}")
            notify("将在下一首播放")
        }
    }

    /** 从云盘删除（上游 `user/cloud/del`）。成功后就地移除，不整页重拉。 */
    fun delete(track: TrackSummary) {
        scope.launch {
            val ok = runCatching { AppModel.musicRepository.deleteUserCloud(track.id) }
                .getOrDefault(false)
            if (ok) {
                _state.update { it.copy(songs = it.songs.filterNot { s -> s.id == track.id }) }
                notify("已从云盘删除「${track.name}」")
            } else {
                notify("删除失败")
            }
        }
    }

    private companion object {
        /** `user/cloud` 单页上限（服务端实测 200 封顶，传更大值也只回 200）。 */
        const val PAGE_SIZE = 200
    }
}
