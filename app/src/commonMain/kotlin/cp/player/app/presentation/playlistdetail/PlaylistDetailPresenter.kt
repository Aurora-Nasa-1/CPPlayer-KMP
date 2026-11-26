package cp.player.app.presentation.playlistdetail

import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.presentation.UiPresenter
import cp.player.core.BackendResult
import cp.player.core.music.PlaylistDetail
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.PlaylistTracksPage
import cp.player.core.music.TrackSummary
import cp.player.core.music.isCloudDrive
import cp.player.core.music.isRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaylistDetailUiState(
    val summary: PlaylistSummary? = null,
    val description: String? = null,
    val tracks: List<TrackSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val hasMore: Boolean = false,
    val fetchingMore: Boolean = false,
    val loadMoreError: String? = null,
    val nextOffset: Int = 0,
    val sortType: PlaylistSortType = PlaylistSortType.DEFAULT,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val likedIds: Set<String> = emptySet(),
    val isFavorite: Boolean = false,
)

/**
 * 歌单详情页的框架无关呈现契约。
 *
 * 页面只依赖本接口，Voyager 生命周期由 `PlaylistDetailScreenModel` 适配。
 */
interface PlaylistDetailContract : UiPresenter<PlaylistDetailUiState> {
    fun primeFromCache(summary: PlaylistSummary): Boolean = false
    fun load(summary: PlaylistSummary, strings: CpStrings, force: Boolean = false)
    fun refresh(strings: CpStrings)
    fun loadLocal(
        summary: PlaylistSummary,
        tracks: List<TrackSummary>,
        loading: Boolean = false,
        force: Boolean = false,
    )
    fun checkDailyConverted(todayStr: String)
    fun markDailyConverted()
    fun loadMore(strings: CpStrings)
    fun clearLoadMoreError()
    fun setSort(type: PlaylistSortType)
    fun displayTracks(): List<TrackSummary>
    fun enterSelection(trackId: String)
    fun toggleSelection(trackId: String)
    fun selectAll()
    fun deselectAll()
    fun toggleSelectAll()
    fun exitSelection()
    fun autoPlayAt(index: Int, animateCover: Boolean = false)
    fun playAt(index: Int, animateCover: Boolean = true)
    fun playAll()
    fun playShuffle()
    fun queueAll(strings: CpStrings)
    fun queueSelected(strings: CpStrings)
    fun isOwner(): Boolean
    fun deleteOrUnsubscribe(strings: CpStrings, onDone: () -> Unit)
    fun addTracks(newTracks: List<TrackSummary>, strings: CpStrings)
    fun removeTracks(trackIds: List<String>, strings: CpStrings)
    fun toggleLike(track: TrackSummary, strings: CpStrings)
    fun isLiked(trackId: String): Boolean
    fun togglePlaylistFavorite(strings: CpStrings)
}

/**
 * 歌单详情页的框架无关状态机。
 *
 * 职责：歌单详情 + 曲目分页加载（并行拉取、按 id 去重保序、防串扰丢弃）、
 * 排序 / 多选 / 播放 / 队列 / 删除或取消收藏 / 移除曲目 / 红心收藏。
 * 排序应用由 UI 层通过 [displayTracks]（或自行 remember）完成。
 *
 * @param notify 操作反馈出口；Compose 外壳传 `UiEvents::notify`，无头前端可接日志 / RPC。
 * @param playCoverFlight 封面飞行动画的副作用出口，保持业务逻辑不依赖 UI 动画。
 */
class PlaylistDetailPresenter(
    override val scope: CoroutineScope,
    private val notify: (String) -> Unit = {},
    private val playCoverFlight: (TrackSummary) -> Unit = {},
) : PlaylistDetailContract {

    companion object {
        /** 单页曲目数（默认 100）。 */
        private const val PAGE_SIZE = 300
    }

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    override val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    /** 当前正在加载的歌单 id（切换歌单时丢弃过期结果，防串扰）。 */
    private var fetchingPlaylistId: String? = null

    /**
     * 已加载完成的歌单 id。
     *
     * 详情页的加载挂在 `LaunchedEffect(playlist.id, ...)` 上，而**从播放页 pop 回来时
     * 页面会重新进入组合**，LaunchedEffect 随之重跑 —— 不去重的话每次退出大播放器
     * 都会把歌单重拉一遍：真实歌单是重发网络请求并闪一屏空白，虚拟歌单（每日推荐）
     * 则是整份 state 被重建，连带丢掉排序 / 多选 / 滚动位置。
     */
    private var loadedPlaylistId: String? = null

    /**
     * 已消费过自动播放的歌单 id。
     *
     * 与 [loadedPlaylistId] 同源的问题：自动播放同样挂在 LaunchedEffect 上，
     * 重新进入组合就会重放一次 —— 表现为「退出大播放器后播放被重置回第一首」。
     */
    private var autoPlayedPlaylistId: String? = null

    /**
     * 当前 [PlaylistDetailUiState.tracks] 里那些**裸 id** 属于哪个音源。
     *
     * `TrackSummary.id` 是音源侧的裸 id，只有配上 provider 才能拼成 mediaId。
     * 播放时若现取 `activeProviderId()`，切源后旧 id 会被新 Provider 当作自己的 id 解释
     * （id 空间撞车 ⇒ 播成无关歌曲，或整列解析失败）。所以在这里**随曲目一起记下**。
     */
    private var tracksProviderId: String? = null

    /**
     * 加载世代：每次发起加载自增，写回前校验。
     *
     * 切源时 `sourceGeneration` 会触发重拉，若旧的 in-flight 请求后到，仅靠
     * `fetchingPlaylistId` 拦不住（同一歌单 id，守卫照样通过）⇒ 旧音源内容覆盖新结果。
     */
    private var loadGen = 0

    /** 本地乐观修改中标记，避免自身增删曲目时并发触发的 playlistEvents 覆盖本地状态。 */
    private var isSelfModifying = false

    /**
     * 最近一次由页面传入的文案。
     *
     * 仅用于**页面没有直接调用**的内部重拉（音源切换后的自动重拉、外部歌单变动订阅）：
     * 这两条链路由 Model 自己触发，拿不到组合上下文；沿用「用户上一次操作时的语言」
     * 比固定中文更接近真实预期，语言切换后下一次显式操作就会刷新它。
     */
    private var lastStrings: CpStrings = CpStrings.zh

    init {
        // 音源切换后，本页曲目属于旧音源：作废加载标记并按当前音源重拉。
        // 与 HomeScreenModel / LibraryScreenModel 同一套处理。
        scope.launch {
            AppModel.sourceGeneration.drop(1).collect { reloadAfterIdentityChange(cloudOnly = false) }
        }
        // 云盘内容属于账号：同一音源下换账号也必须重拉，否则会留下上一个账号的歌。
        scope.launch {
            AppModel.accountGeneration.drop(1).collect { reloadAfterIdentityChange(cloudOnly = true) }
        }
        // 订阅歌单事件：当外部修改了当前歌单（如在播放器中加入当前歌单）时静默重拉
        scope.launch {
            AppModel.playlistEvents.collect { event ->
                val summary = _state.value.summary ?: return@collect
                if (!isSelfModifying && summary.isRemote && event.resourceId == summary.resourceId) {
                    refresh(lastStrings)
                }
            }
        }
    }

    private fun reloadAfterIdentityChange(cloudOnly: Boolean) {
        val summary = _state.value.summary ?: return
        loadedPlaylistId = null
        autoPlayedPlaylistId = null
        tracksProviderId = null
        // 虚拟歌单（每日推荐 / 相似歌曲 / 心动模式，id < 0）的曲目来自上一级，
        // 本模型拿不到新音源的等价数据，只作废标记让宿主重喂。
        if (summary.isCloudDrive || (!cloudOnly && summary.isRemote)) {
            load(summary, lastStrings, force = true)
        }
    }

    // ============ 加载 ============

    private data class CachedPlaylistSnapshot(
        val summary: PlaylistSummary,
        val description: String?,
        val tracks: List<TrackSummary>,
        val hasMore: Boolean,
        val nextOffset: Int,
        val allFresh: Boolean,
        val hasDetail: Boolean,
    )

    private fun peekCachedSnapshot(summary: PlaylistSummary, sortKey: String): CachedPlaylistSnapshot? {
        if (summary.isCloudDrive || !summary.isRemote) return null
        val repo = AppModel.musicRepository
        val detailPeek = repo.peekPlaylistDetail(summary.resourceId, sortKey)
        val tracksPeek = repo.peekPlaylistTracks(
            summary.resourceId, limit = PAGE_SIZE, offset = 0, sortKey = sortKey,
        )
        val detail = detailPeek?.value
        val tracksPage = tracksPeek?.value
        val rawTracks = tracksPage?.tracks ?: detail?.tracks?.takeIf { it.isNotEmpty() } ?: return null
        val distinctTracks = rawTracks.distinctBy { it.id }
        val hasMore = if (tracksPage != null) {
            tracksPage.hasMore || (!tracksPage.paginationSpecified && tracksPage.tracks.size >= PAGE_SIZE)
        } else {
            distinctTracks.size >= PAGE_SIZE
        }
        val nextOffset = tracksPage?.tracks?.size ?: distinctTracks.size
        val allFresh = (detailPeek?.isFresh == true) &&
            (tracksPeek?.isFresh == true || detail?.tracks?.isNotEmpty() == true)
        return CachedPlaylistSnapshot(
            summary = detail?.summary ?: summary,
            description = detail?.description,
            tracks = distinctTracks,
            hasMore = hasMore,
            nextOffset = nextOffset,
            allFresh = allFresh,
            hasDetail = detail != null,
        )
    }

    private fun applyCachedSnapshot(snap: CachedPlaylistSnapshot) {
        tracksProviderId = AppModel.activeProviderId()
        val cachedFav = AppModel.musicRepository.peekCurrentUserPlaylists()?.value
            ?.any { it.resourceId == snap.summary.resourceId }
        _state.update { current ->
            current.copy(
                summary = snap.summary,
                description = snap.description ?: current.description,
                tracks = snap.tracks,
                hasMore = snap.hasMore,
                nextOffset = snap.nextOffset,
                loading = false,
                error = null,
                loadMoreError = null,
                isFavorite = cachedFav ?: current.isFavorite,
            )
        }
    }

    /**
     * 首帧组合前同步窥视缓存：命中时立即填满曲目并将 `loading` 置为 false，
     * 让已缓存歌单在第一帧就完整渲染，彻底消除骨架屏闪烁。
     */
    override fun primeFromCache(summary: PlaylistSummary): Boolean {
        if (summary.isCloudDrive || !summary.isRemote) return false
        if (loadedPlaylistId == summary.resourceId && _state.value.tracks.isNotEmpty()) return true
        val snap = peekCachedSnapshot(summary, _state.value.sortType.name) ?: return false
        applyCachedSnapshot(snap)
        return true
    }

    /**
     * 加载歌单详情（description / trackCount 修正）与首页曲目（并行），并拉取收藏列表。
     *
     * @param force 为 true 时忽略 [loadedPlaylistId] 守卫（供「重试」与「刷新」使用）。
     */
    override fun load(summary: PlaylistSummary, strings: CpStrings, force: Boolean) {
        lastStrings = strings
        if (!force && loadedPlaylistId == summary.resourceId) return
        if (summary.isCloudDrive) {
            loadCloudDrive(summary, strings, force)
            return
        }
        val gen = ++loadGen
        // 记下发请求这一刻的音源：曲目就绪后 mediaIds 用它拼，避免切源后被新 Provider 解释。
        val requestProvider = AppModel.activeProviderId()
        val sortKey = _state.value.sortType.name
        loadedPlaylistId = summary.resourceId
        fetchingPlaylistId = summary.resourceId

        val cachedSnap = if (!force) peekCachedSnapshot(summary, sortKey) else null
        if (cachedSnap != null) {
            applyCachedSnapshot(cachedSnap)
            loadLiked()
            checkSubscribed(summary.resourceId)
            if (cachedSnap.allFresh) return
        }

        val silent = cachedSnap != null || (force && _state.value.tracks.isNotEmpty())
        if (!silent) {
            _state.value = PlaylistDetailUiState(summary = summary)
            loadLiked()
            checkSubscribed(summary.resourceId)
        }

        fun handleDetailUpdate(detailResult: BackendResult<PlaylistDetail>) {
            if (fetchingPlaylistId != summary.resourceId || gen != loadGen) return
            if (detailResult is BackendResult.Success) {
                _state.update { cur ->
                    if (cur.summary == detailResult.data.summary && cur.description == detailResult.data.description) cur
                    else cur.copy(summary = detailResult.data.summary, description = detailResult.data.description)
                }
            }
        }

        fun handleTracksUpdate(tracksResult: BackendResult<PlaylistTracksPage>) {
            if (fetchingPlaylistId != summary.resourceId || gen != loadGen) return
            if (tracksResult is BackendResult.Success) {
                val page = tracksResult.data
                val distinct = page.tracks.distinctBy { it.id }
                val hasMore = page.hasMore || (!page.paginationSpecified && page.tracks.size >= PAGE_SIZE)
                tracksProviderId = requestProvider
                _state.update { cur ->
                    if (cur.tracks == distinct && cur.hasMore == hasMore && cur.nextOffset == page.tracks.size) cur
                    else cur.copy(
                        tracks = distinct,
                        hasMore = hasMore,
                        nextOffset = page.tracks.size,
                        loading = false,
                        error = null,
                        loadMoreError = null,
                    )
                }
            }
        }

        scope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    coroutineScope {
                        val detail = async {
                            AppModel.musicRepository.getPlaylistDetail(
                                summary.resourceId,
                                sortKey,
                                onRefresh = { refreshed -> scope.launch { handleDetailUpdate(refreshed) } },
                            )
                        }
                        val tracks = async {
                            AppModel.musicRepository.getPlaylistTracks(
                                summary.resourceId,
                                limit = PAGE_SIZE,
                                offset = 0,
                                sortKey = sortKey,
                                onRefresh = { refreshed -> scope.launch { handleTracksUpdate(refreshed) } },
                            )
                        }
                        detail.await() to tracks.await()
                    }
                }.getOrNull()
            }
            // 歌单已切换，或有更新的一次加载（切源重拉）已在途 —— 丢弃过期结果
            if (fetchingPlaylistId != summary.resourceId || gen != loadGen) return@launch
            if (results == null) {
                if (silent && !force) return@launch
                if (silent) {
                    notify(strings.library.playlistDetailRefreshFailed)
                } else {
                    _state.update { it.copy(loading = false, error = strings.library.playlistDetailLoadFailed) }
                }
                return@launch
            }
            val (detailResult, tracksResult) = results
            var next = _state.value
            if (detailResult is BackendResult.Success) {
                next = next.copy(
                    summary = detailResult.data.summary,
                    description = detailResult.data.description,
                )
            }
            when (tracksResult) {
                is BackendResult.Success -> {
                    val page = tracksResult.data
                    val distinct = page.tracks.distinctBy { it.id }
                    val hasMore = page.hasMore || (!page.paginationSpecified && page.tracks.size >= PAGE_SIZE)
                    // 曲目与「它是哪个音源的」一起落库，二者永远配对。
                    tracksProviderId = requestProvider
                    if (next.tracks != distinct || next.hasMore != hasMore || next.nextOffset != page.tracks.size || next.loading) {
                        next = next.copy(
                            tracks = distinct,
                            hasMore = hasMore,
                            nextOffset = page.tracks.size,
                            loading = false,
                            error = null,
                            loadMoreError = null,
                        )
                    }
                    if (force && silent) {
                        notify(strings.library.playlistRefreshed())
                    }
                }
                is BackendResult.Error -> {
                    if (force && silent) notify(tracksResult.message)
                    else if (!silent) next = next.copy(loading = false, error = tracksResult.message)
                }
                is BackendResult.Unsupported -> {
                    if (force && silent) notify(tracksResult.message)
                    else if (!silent) next = next.copy(loading = false, error = tracksResult.message)
                }
            }
            if (next != _state.value) {
                _state.value = next
            }
        }
    }

    /**
     * 云盘复用歌单详情状态：分页取 `user/cloud`，没有 detail / 收藏 / 订阅语义。
     *
     * 云盘封面没有独立字段，取第一首歌的封面填进 summary，这样窄屏顶栏与 hero 都有图。
     */
    private fun loadCloudDrive(summary: PlaylistSummary, strings: CpStrings, force: Boolean) {
        val gen = ++loadGen
        val requestProvider = AppModel.activeProviderId()
        loadedPlaylistId = summary.resourceId
        fetchingPlaylistId = summary.resourceId
        tracksProviderId = requestProvider
        val silent = force && _state.value.tracks.isNotEmpty()
        if (!silent) {
            _state.value = PlaylistDetailUiState(
                summary = summary,
                description = strings.account.cloudUploadHint,
                loading = true,
            )
        }
        loadLiked()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppModel.musicRepository.getCloudDriveTracks(
                        limit = PAGE_SIZE,
                        offset = 0,
                        refresh = force,
                    )
                }.getOrNull()
            }
            if (fetchingPlaylistId != summary.resourceId || gen != loadGen) return@launch
            when (result) {
                is BackendResult.Success -> {
                    val page = result.data
                    val tracks = page.tracks.distinctBy { it.id }
                    _state.update { current ->
                        current.copy(
                            summary = (current.summary ?: summary).copy(
                                coverUrl = tracks.firstOrNull()?.coverUrl ?: summary.coverUrl,
                                trackCount = tracks.size,
                            ),
                            description = strings.account.cloudUploadHint,
                            tracks = tracks,
                            loading = false,
                            error = null,
                            hasMore = page.hasMore,
                            nextOffset = page.tracks.size,
                            loadMoreError = null,
                        )
                    }
                    if (silent) notify(strings.library.playlistRefreshed())
                }
                is BackendResult.Error -> {
                    if (silent) notify(result.message)
                    else _state.update { it.copy(loading = false, error = result.message) }
                }
                is BackendResult.Unsupported -> {
                    if (silent) notify(result.message)
                    else _state.update { it.copy(loading = false, error = result.message) }
                }
                null -> {
                    if (silent) notify(strings.library.playlistDetailRefreshFailed)
                    else _state.update { it.copy(loading = false, error = strings.account.cloudLoadFailed) }
                }
            }
        }
    }

    /** 手动刷新当前歌单详情与曲目（静默刷新，不闪空白屏）。 */
    override fun refresh(strings: CpStrings) {
        val summary = _state.value.summary ?: return
        if (summary.isRemote || summary.isCloudDrive) load(summary, strings, force = true)
    }

    /**
     * 用导航传入的曲目直接初始化详情页（首页生成的虚拟歌单：每日推荐 / 相似歌曲 / 心动模式）。
     *
     * 这些歌单 id 为负数（-101 每日推荐 / -103 相似歌曲 / -104 心动模式），服务端并不存在，
     * 走远端接口必然 404 并导致详情页空白，因此这里不发起任何远端请求，
     * 仅拉取收藏列表用于红心态。
     *
     * @param loading 调用方仍在拉取曲目时传 true，让 UI 显示加载态而不是"歌单暂无歌曲"。
     * @param force 为 true 时忽略 [loadedPlaylistId] 守卫。
     */
    override fun loadLocal(
        summary: PlaylistSummary,
        tracks: List<TrackSummary>,
        loading: Boolean,
        force: Boolean,
    ) {
        if (!force && loadedPlaylistId == summary.resourceId) return
        // 让在途的 load() 结果作废（本方法直接整份替换曲目）。
        loadGen++
        loadedPlaylistId = summary.resourceId
        fetchingPlaylistId = summary.resourceId
        // 这些曲目由宿主按当前音源喂入，记下对应音源供 mediaIds 使用。
        tracksProviderId = AppModel.activeProviderId()
        val distinct = tracks.distinctBy { it.id }
        _state.value = PlaylistDetailUiState(
            summary = summary.copy(trackCount = distinct.size),
            tracks = distinct,
            loading = loading,
            hasMore = false,
            nextOffset = distinct.size,
        )
        loadLiked()
        if (summary.id == -101L || summary.name == "每日推荐") {
            val now = cp.player.core.util.localDateTimeOf(cp.player.core.util.currentTimeMillis())
            val m = now.month.toString().padStart(2, '0')
            val d = now.day.toString().padStart(2, '0')
            checkDailyConverted("${now.year}-$m-$d")
        } else {
            checkSubscribed(summary.resourceId)
        }
    }

    /** 检查每日推荐是否已转存过歌单。 */
    override fun checkDailyConverted(todayStr: String) {
        scope.launch {
            val userPlaylists = runCatching { AppModel.musicRepository.getCurrentUserPlaylists() }.getOrNull()
            if (userPlaylists is BackendResult.Success) {
                val converted = userPlaylists.data.any {
                    it.name.contains("每日推荐") && it.name.contains(todayStr)
                }
                if (converted) {
                    _state.update { it.copy(isFavorite = true) }
                }
            }
        }
    }

    /** 每日推荐转存歌单成功后标记已转存。 */
    override fun markDailyConverted() {
        _state.update { it.copy(isFavorite = true) }
    }

    /** 加载下一页曲目（追加、按 id 去重保序）。 */
    override fun loadMore(strings: CpStrings) {
        lastStrings = strings
        // 守卫与 fetchingMore 置位合并为一次原子 update，只有抢到置位权的调用继续执行
        var acquired = false
        _state.update { s ->
            if (s.hasMore && !s.fetchingMore && !s.loading && s.loadMoreError == null && s.summary != null) {
                acquired = true
                s.copy(fetchingMore = true)
            } else s
        }
        if (!acquired) return
        val summary = _state.value.summary ?: return
        val playlistId = summary.resourceId
        val isCloudDrive = summary.isCloudDrive
        val sortKey = _state.value.sortType.name
        scope.launch {
            val offset = _state.value.nextOffset
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isCloudDrive) {
                        AppModel.musicRepository.getCloudDriveTracks(
                            limit = PAGE_SIZE, offset = offset,
                        )
                    } else {
                        AppModel.musicRepository.getPlaylistTracks(
                            playlistId, limit = PAGE_SIZE, offset = offset, sortKey = sortKey,
                        )
                    }
                }.getOrNull()
            }
            if (fetchingPlaylistId == playlistId) {
                when (result) {
                    is BackendResult.Success -> {
                        val page = result.data
                        val existing = _state.value.tracks
                        val seen = existing.mapTo(HashSet()) { it.id }
                        val appended = page.tracks.filter { seen.add(it.id) }
                        _state.update {
                            it.copy(
                                tracks = existing + appended,
                                // 追加去重后为空时以服务端 hasMore 为准，避免 size >= PAGE_SIZE 兜底放大导致反复拉取
                                hasMore = if (appended.isEmpty()) {
                                    // 云盘没有 hasMore，重复页再按满页兜底就会无限拉同一页。
                                    if (isCloudDrive) false else page.hasMore
                                }
                                else page.hasMore || (!page.paginationSpecified && page.tracks.size >= PAGE_SIZE),
                                nextOffset = offset + page.tracks.size,
                                loadMoreError = null,
                                summary = if (isCloudDrive) {
                                    it.summary?.copy(trackCount = (existing + appended).size)
                                } else {
                                    it.summary
                                },
                            )
                        }
                    }
                    is BackendResult.Error -> _state.update { it.copy(loadMoreError = result.message) }
                    is BackendResult.Unsupported -> _state.update { it.copy(loadMoreError = result.message) }
                    null -> _state.update { it.copy(loadMoreError = strings.library.loadMoreFailed) }
                }
            }
            _state.update { it.copy(fetchingMore = false) }
        }
    }

    /** 清除分页加载错误（点击"重试"项后重新触发 [loadMore]）。 */
    override fun clearLoadMoreError() {
        _state.update { it.copy(loadMoreError = null) }
    }

    // ============ 排序 ============

    override fun setSort(type: PlaylistSortType) {
        _state.update { it.copy(sortType = type) }
    }

    /** 按当前排序类型整理后的曲目（UI 层亦可自行 remember + sortedBy）。 */
    override fun displayTracks(): List<TrackSummary> {
        val s = _state.value
        return sortedTracks(s.tracks, s.sortType)
    }

    private fun sortedTracks(tracks: List<TrackSummary>, sort: PlaylistSortType): List<TrackSummary> = when (sort) {
        PlaylistSortType.DEFAULT -> tracks
        PlaylistSortType.NAME -> tracks.sortedBy { it.name }
        PlaylistSortType.ARTIST -> tracks.sortedBy { it.artist }
    }

    // ============ 多选 ============

    override fun enterSelection(trackId: String) {
        _state.update { it.copy(selectionMode = true, selectedIds = setOf(trackId)) }
    }

    /** 切换选中；取消最后一个选中项时自动退出多选。 */
    override fun toggleSelection(trackId: String) {
        _state.update { s ->
            val next = if (trackId in s.selectedIds) s.selectedIds - trackId else s.selectedIds + trackId
            s.copy(selectionMode = next.isNotEmpty(), selectedIds = next)
        }
    }

    /** 全选当前已加载曲目。若已全选，再次调用则取消全选（清空已选）。 */
    override fun selectAll() {
        toggleSelectAll()
    }

    /** 取消全选（清空已选，保留多选模式）。 */
    override fun deselectAll() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    /**
     * 切换全选 / 取消全选：
     * 若已全选当前所有已加载曲目，则清空已选（取消全选）；否则全选所有曲目。
     */
    override fun toggleSelectAll() {
        _state.update { s ->
            val allIds = s.tracks.mapTo(LinkedHashSet()) { it.id }
            val isAllSelected = allIds.isNotEmpty() && s.selectedIds.containsAll(allIds)
            val next = if (isAllSelected) emptySet() else allIds
            s.copy(selectionMode = true, selectedIds = next)
        }
    }

    override fun exitSelection() {
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    // ============ 播放 / 队列 ============

    private fun mediaIds(tracks: List<TrackSummary>): List<String> {
        // 用**曲目加载时**的音源，而不是当前活跃音源：否则切源后旧 id 会被新 Provider
        // 解释成别的歌。tracksProviderId 为空（尚未加载完）时才回落到当前活跃音源。
        val provider = tracksProviderId ?: AppModel.activeProviderId()
        return tracks.map { AppModel.toMediaId(it.id, provider) }
    }

    /**
     * 队列来源歌单 id（仅真实服务端歌单）。首页虚拟歌单（每日推荐 / 心动模式 /
     * 相似歌曲）的 id 是负数、服务端不存在，不能上报 —— 上游拿它当 `pid` 会拒绝。
     * 心动模式（playmode/intelligence/list）依赖这个来源做歌单上下文。
     */
    private fun sourceIdOrNull(): String? =
        _state.value.summary?.takeIf { it.isRemote }?.resourceId

    /** 按当前排序后的列表，从 [index] 处开始播放（替换队列）。[animateCover] 仅用户点击传 true。 */
    /**
     * 曲目就绪后从 [index] 起播 —— **每个歌单只生效一次**。
     *
     * ⚠️ 幂等是硬要求：这个调用挂在 `LaunchedEffect(autoPlayIndex, state.tracks)` 上，
     * 而从播放页 pop 回来时页面会**重新进入组合**，LaunchedEffect 重新执行。
     * 不去重的话，`playQueue(ids, startIndex = index)` 会重建队列并从起点重放 ——
     * 症状就是「退出大播放器之后播放被重置」，且只在携带 `autoPlayIndex` 的页面出现
     * （每日推荐 / 相似歌曲 / 心动模式这类首页虚拟歌单；普通歌单走详情入口时为 null）。
     */
    override fun autoPlayAt(index: Int, animateCover: Boolean) {
        val id = _state.value.summary?.resourceId ?: return
        if (autoPlayedPlaylistId == id) return
        if (_state.value.tracks.isEmpty()) return
        autoPlayedPlaylistId = id
        playAt(index.coerceIn(0, _state.value.tracks.lastIndex), animateCover)
    }

    override fun playAt(index: Int, animateCover: Boolean) {
        val tracks = sortedTracks(_state.value.tracks, _state.value.sortType)
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        if (animateCover) tracks.getOrNull(index)?.let { playCoverFlight(it) }
        val clicked = ids.getOrNull(index) ?: return
        scope.launch {
            // 一起听进行中点歌 = 「下一首播放」；只拦用户点击（animateCover=true），
            // autoPlayAt 的自动续播保持原语义。
            if (animateCover) {
                AppModel.playTrackClicked(clicked) {
                    AppModel.playback.playQueue(ids, startIndex = index, sourceId = sourceIdOrNull())
                }
            } else {
                AppModel.playback.playQueue(ids, startIndex = index, sourceId = sourceIdOrNull())
            }
        }
    }

    override fun playAll() {
        val tracks = sortedTracks(_state.value.tracks, _state.value.sortType)
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        tracks.firstOrNull()?.let { playCoverFlight(it) }
        scope.launch { AppModel.playback.playQueue(ids, startIndex = 0, sourceId = sourceIdOrNull()) }
    }

    override fun playShuffle() {
        // 先洗牌曲目再映射 mediaId，保证封面飞行起点与实际起播的曲目一致。
        val tracks = _state.value.tracks.shuffled()
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        tracks.firstOrNull()?.let { playCoverFlight(it) }
        scope.launch { AppModel.playback.playQueue(ids, startIndex = 0, sourceId = sourceIdOrNull()) }
    }

    override fun queueAll(strings: CpStrings) {
        val ids = mediaIds(sortedTracks(_state.value.tracks, _state.value.sortType))
        if (ids.isEmpty()) return
        scope.launch {
            ids.forEach { AppModel.playback.addToQueue(it) }
            notify(strings.library.queuedToPlay)
        }
    }

    override fun queueSelected(strings: CpStrings) {
        val selected = _state.value.selectedIds
        if (selected.isEmpty()) return
        val tracks = sortedTracks(_state.value.tracks, _state.value.sortType).filter { it.id in selected }
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        scope.launch {
            ids.forEach { AppModel.playback.addToQueue(it) }
            notify(strings.library.queuedToPlay)
        }
    }

    // ============ 歌单管理 ============

    /** 当前账号是否拥有该歌单（用于决定删除 vs 取消收藏）。 */
    override fun isOwner(): Boolean {
        if (_state.value.summary?.isCloudDrive == true) return false
        val nickname = AppModel.userProfileFlow.value?.nickname
        return nickname != null && _state.value.summary?.creatorName == nickname
    }

    /** owner → 删除歌单；否则取消收藏。成功后 [onDone]（由 Screen pop）。 */
    override fun deleteOrUnsubscribe(strings: CpStrings, onDone: () -> Unit) {
        val playlist = _state.value.summary ?: return
        val owner = isOwner()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (owner) AppModel.musicRepository.deletePlaylist(playlist.resourceId)
                    else AppModel.musicRepository.unsubscribePlaylist(playlist.resourceId)
                }.getOrDefault(false)
            }
            notify(
                if (ok) {
                    if (owner) strings.library.playlistDeleted(playlist.name)
                    else strings.library.playlistUnsubscribed(playlist.name)
                } else strings.library.operationFailed
            )
            if (ok) onDone()
        }
    }

    /** 向当前歌单添加歌曲（"从歌单导入 / 从播放队列添加"，仅 owner 有效）。
     *
     * 成功后本地追加（按 id 去重保序）并同步 trackCount，避免重新拉取整页。
     */
    override fun addTracks(newTracks: List<TrackSummary>, strings: CpStrings) {
        val playlist = _state.value.summary ?: return
        if (newTracks.isEmpty()) return
        scope.launch {
            if (!isOwner()) {
                val freshNick = withContext(Dispatchers.IO) {
                    runCatching { AppModel.refreshUserProfileAwait()?.nickname }.getOrNull()
                }
                if (freshNick == null || playlist.creatorName != freshNick) {
                    notify(strings.library.ownerOnlyAddTracks)
                    return@launch
                }
            }
            isSelfModifying = true
            try {
                // 过滤已在歌单中的歌曲，避免上游 502 报错整批失败
                val existingIds = _state.value.tracks.mapTo(HashSet()) { it.id }
                val toAdd = newTracks.filter { it.id.isNotBlank() && it.id !in existingIds }.distinctBy { it.id }
                if (toAdd.isEmpty()) {
                    notify(strings.library.tracksAlreadyInPlaylist)
                    return@launch
                }
                val ids = toAdd.map { it.id }
                val chunks = ids.chunked(100)
                var allOk = true
                for (chunk in chunks) {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { AppModel.musicRepository.addTracksToPlaylist(playlist.resourceId, chunk) }
                            .getOrDefault(false)
                    }
                    if (!ok) {
                        allOk = false
                        break
                    }
                }
                if (allOk) {
                    val addedCount = toAdd.size
                    _state.update { s ->
                        s.copy(
                            tracks = s.tracks + toAdd,
                            summary = s.summary?.copy(trackCount = s.summary.trackCount + addedCount),
                        )
                    }
                    notify(strings.library.tracksAdded(addedCount))
                } else {
                    notify(strings.library.addTracksFailed)
                }
            } finally {
                isSelfModifying = false
            }
        }
    }

    /** 从歌单移除曲目（仅 owner 有效）；成功后本地剔除并退出多选。 */
    override fun removeTracks(trackIds: List<String>, strings: CpStrings) {
        val playlist = _state.value.summary ?: return
        if (playlist.isCloudDrive) {
            deleteCloudTracks(trackIds, strings)
            return
        }
        if (trackIds.isEmpty() || !isOwner()) return
        scope.launch {
            isSelfModifying = true
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { AppModel.musicRepository.removeTracksFromPlaylist(playlist.resourceId, trackIds) }
                        .getOrDefault(false)
                }
                if (ok) {
                    // 以本地 tracks 实际命中剔除的数量计数（removeTracks 只删本地，不回退 nextOffset）
                    var removedCount = 0
                    _state.update { s ->
                        val idSet = trackIds.toSet()
                        removedCount = s.tracks.count { it.id in idSet }
                        s.copy(
                            tracks = s.tracks.filterNot { it.id in idSet },
                            selectionMode = false,
                            selectedIds = emptySet(),
                            summary = s.summary?.copy(trackCount = (s.summary.trackCount - removedCount).coerceAtLeast(0)),
                        )
                    }
                    notify(strings.library.tracksRemoved(removedCount))
                } else {
                    notify(strings.library.operationFailed)
                }
            } finally {
                isSelfModifying = false
            }
        }
    }

    /** 云盘删除：批量删上游记录，成功后就地剔除，避免整页重拉。 */
    private fun deleteCloudTracks(trackIds: List<String>, strings: CpStrings) {
        val ids = trackIds.distinct().filter(String::isNotBlank)
        if (ids.isEmpty()) return
        scope.launch {
            isSelfModifying = true
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { AppModel.musicRepository.deleteUserCloudBatch(ids) }
                        .getOrDefault(false)
                }
                if (ok) {
                    val idSet = ids.toSet()
                    val removedTracks = _state.value.tracks.filter { it.id in idSet }
                    var removedCount = 0
                    _state.update { s ->
                        removedCount = s.tracks.count { it.id in idSet }
                        s.copy(
                            tracks = s.tracks.filterNot { it.id in idSet },
                            selectionMode = false,
                            selectedIds = emptySet(),
                            summary = s.summary?.copy(
                                trackCount = (s.summary.trackCount - removedCount).coerceAtLeast(0),
                            ),
                        )
                    }
                    notify(
                        if (removedCount == 1) {
                            strings.account.cloudDeleted(removedTracks.firstOrNull()?.name ?: ids.first())
                        } else {
                            strings.account.cloudDeletedMany(removedCount)
                        }
                    )
                } else {
                    notify(strings.account.cloudDeleteFailed)
                }
            } finally {
                isSelfModifying = false
            }
        }
    }

    // ============ 收藏（红心） ============

    /** 拉取当前用户收藏列表（顶层 "ids" 数组）；任何异常静默置空集。 */
    private fun loadLiked() {
        scope.launch {
            val ids = withContext(Dispatchers.IO) {
                when (val result = runCatching { AppModel.musicRepository.getLikeList() }.getOrNull()) {
                    is BackendResult.Success -> result.data
                    else -> emptySet()
                }
            }
            _state.update { it.copy(likedIds = ids) }
        }
    }

    /** 切换收藏状态（乐观本地翻转在 API 成功后执行）。 */
    override fun toggleLike(track: TrackSummary, strings: CpStrings) {
        val liked = _state.value.likedIds.contains(track.id)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.likeSong(track.id, !liked) }.getOrDefault(false)
            }
            if (ok) {
                _state.update { s ->
                    s.copy(likedIds = if (liked) s.likedIds - track.id else s.likedIds + track.id)
                }
            } else {
                notify(strings.library.operationFailed)
            }
        }
    }

    override fun isLiked(trackId: String): Boolean = _state.value.likedIds.contains(trackId)

    /** 检查当前歌单是否已被当前用户收藏（订阅）。 */
    private fun checkSubscribed(playlistId: String) {
        if (_state.value.summary?.isRemote != true) return
        scope.launch {
            val userPlaylists = runCatching { AppModel.musicRepository.getCurrentUserPlaylists() }.getOrNull()
            if (userPlaylists is BackendResult.Success && fetchingPlaylistId == playlistId) {
                val favorited = userPlaylists.data.any { it.resourceId == playlistId }
                _state.update { it.copy(isFavorite = favorited) }
            }
        }
    }

    /** 切换歌单收藏（订阅）状态。 */
    override fun togglePlaylistFavorite(strings: CpStrings) {
        val current = _state.value.isFavorite
        val target = !current
        val playlistId = _state.value.summary?.resourceId ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.subscribePlaylist(playlistId, target) }.getOrDefault(false)
            }
            if (ok) {
                _state.update { it.copy(isFavorite = target) }
                notify(if (target) strings.library.playlistFavorited else strings.library.playlistUnfavorited)
            } else {
                notify(strings.library.operationFailed)
            }
        }
    }

}
