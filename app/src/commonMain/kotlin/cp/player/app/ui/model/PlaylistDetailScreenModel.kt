package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.PlaylistSortType
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import cp.player.core.music.MusicSourceFromApi
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
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
)

/**
 * 歌单详情页状态层。
 *
 * 职责：歌单详情 + 曲目分页加载（并行拉取、按 id 去重保序、防串扰丢弃）、
 * 排序 / 多选 / 播放 / 队列 / 删除或取消收藏 / 移除曲目 / 红心收藏。
 * 排序应用由 UI 层通过 [displayTracks]（或自行 remember）完成。
 */
class PlaylistDetailScreenModel : ScreenModel {

    companion object {
        /** 单页曲目数（与 MusicSourceFromApi.getPlaylistTracks 默认值一致）。 */
        private const val PAGE_SIZE = 300
    }

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    /** 当前正在加载的歌单 id（切换歌单时丢弃过期结果，防串扰）。 */
    private var fetchingPlaylistId: Long? = null

    /**
     * 已加载完成的歌单 id。
     *
     * 详情页的加载挂在 `LaunchedEffect(playlist.id, ...)` 上，而**从播放页 pop 回来时
     * 页面会重新进入组合**，LaunchedEffect 随之重跑 —— 不去重的话每次退出大播放器
     * 都会把歌单重拉一遍：真实歌单是重发网络请求并闪一屏空白，虚拟歌单（每日推荐）
     * 则是整份 state 被重建，连带丢掉排序 / 多选 / 滚动位置。
     */
    private var loadedPlaylistId: Long? = null

    /**
     * 已消费过自动播放的歌单 id。
     *
     * 与 [loadedPlaylistId] 同源的问题：自动播放同样挂在 LaunchedEffect 上，
     * 重新进入组合就会重放一次 —— 表现为「退出大播放器后播放被重置回第一首」。
     */
    private var autoPlayedPlaylistId: Long? = null

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

    init {
        // 音源切换后，本页曲目属于旧音源：作废加载标记并（真实歌单）按当前音源重拉。
        // 与 HomeScreenModel / LibraryScreenModel 同一套处理。
        screenModelScope.launch {
            AppModel.sourceGeneration.drop(1).collect {
                val summary = _state.value.summary ?: return@collect
                loadedPlaylistId = null
                autoPlayedPlaylistId = null
                tracksProviderId = null
                // 虚拟歌单（每日推荐 / 相似歌曲 / 心动模式，id < 0）的曲目来自上一级，
                // 本模型拿不到新音源的等价数据，只作废标记让宿主重喂。
                if (summary.id > 0) load(summary, force = true)
            }
        }
    }

    // ============ 加载 ============

    /**
     * 加载歌单详情（description / trackCount 修正）与首页曲目（并行），并拉取收藏列表。
     *
     * @param force 为 true 时忽略 [loadedPlaylistId] 守卫（供「重试」按钮使用）。
     */
    fun load(summary: PlaylistSummary, force: Boolean = false) {
        if (!force && loadedPlaylistId == summary.id) return
        val gen = ++loadGen
        // 记下发请求这一刻的音源：曲目就绪后 mediaIds 用它拼，避免切源后被新 Provider 解释。
        val requestProvider = AppModel.activeProviderId()
        loadedPlaylistId = summary.id
        fetchingPlaylistId = summary.id
        _state.value = PlaylistDetailUiState(summary = summary)
        loadLiked()
        screenModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    coroutineScope {
                        val detail = async { AppModel.musicRepository.getPlaylistDetail(summary.id) }
                        val tracks = async {
                            AppModel.musicRepository.getPlaylistTracks(summary.id, limit = PAGE_SIZE, offset = 0)
                        }
                        detail.await() to tracks.await()
                    }
                }.getOrNull()
            }
            // 歌单已切换，或有更新的一次加载（切源重拉）已在途 —— 丢弃过期结果
            if (fetchingPlaylistId != summary.id || gen != loadGen) return@launch
            if (results == null) {
                _state.update { it.copy(loading = false, error = "歌单详情加载失败") }
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
                    // 曲目与「它是哪个音源的」一起落库，二者永远配对。
                    tracksProviderId = requestProvider
                    next = next.copy(
                        tracks = page.tracks.distinctBy { it.id },
                        hasMore = page.hasMore || page.tracks.size >= PAGE_SIZE,
                        nextOffset = page.tracks.size,
                        loading = false,
                        error = null,
                        loadMoreError = null,
                    )
                }
                is BackendResult.Error -> next = next.copy(loading = false, error = tracksResult.message)
                is BackendResult.Unsupported -> next = next.copy(loading = false, error = tracksResult.message)
            }
            _state.value = next
        }
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
    fun loadLocal(
        summary: PlaylistSummary,
        tracks: List<TrackSummary>,
        loading: Boolean = false,
        force: Boolean = false,
    ) {
        if (!force && loadedPlaylistId == summary.id) return
        // 让在途的 load() 结果作废（本方法直接整份替换曲目）。
        loadGen++
        loadedPlaylistId = summary.id
        fetchingPlaylistId = summary.id
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
    }

    /** 加载下一页曲目（追加、按 id 去重保序）。 */
    fun loadMore() {
        // 守卫与 fetchingMore 置位合并为一次原子 update，只有抢到置位权的调用继续执行
        var acquired = false
        _state.update { s ->
            if (s.hasMore && !s.fetchingMore && !s.loading && s.loadMoreError == null && s.summary != null) {
                acquired = true
                s.copy(fetchingMore = true)
            } else s
        }
        if (!acquired) return
        val playlistId = _state.value.summary?.id ?: return
        screenModelScope.launch {
            val offset = _state.value.nextOffset
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppModel.musicRepository.getPlaylistTracks(playlistId, limit = PAGE_SIZE, offset = offset)
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
                                hasMore = if (appended.isEmpty()) page.hasMore
                                else page.hasMore || page.tracks.size >= PAGE_SIZE,
                                nextOffset = offset + page.tracks.size,
                                loadMoreError = null,
                            )
                        }
                    }
                    is BackendResult.Error -> _state.update { it.copy(loadMoreError = result.message) }
                    is BackendResult.Unsupported -> _state.update { it.copy(loadMoreError = result.message) }
                    null -> _state.update { it.copy(loadMoreError = "加载更多失败") }
                }
            }
            _state.update { it.copy(fetchingMore = false) }
        }
    }

    /** 清除分页加载错误（点击"重试"项后重新触发 [loadMore]）。 */
    fun clearLoadMoreError() {
        _state.update { it.copy(loadMoreError = null) }
    }

    // ============ 排序 ============

    fun setSort(type: PlaylistSortType) {
        _state.update { it.copy(sortType = type) }
    }

    /** 按当前排序类型整理后的曲目（UI 层亦可自行 remember + sortedBy）。 */
    fun displayTracks(): List<TrackSummary> {
        val s = _state.value
        return sortedTracks(s.tracks, s.sortType)
    }

    private fun sortedTracks(tracks: List<TrackSummary>, sort: PlaylistSortType): List<TrackSummary> = when (sort) {
        PlaylistSortType.DEFAULT -> tracks
        PlaylistSortType.NAME -> tracks.sortedBy { it.name }
        PlaylistSortType.ARTIST -> tracks.sortedBy { it.artist }
    }

    // ============ 多选 ============

    fun enterSelection(trackId: String) {
        _state.update { it.copy(selectionMode = true, selectedIds = setOf(trackId)) }
    }

    /** 切换选中；取消最后一个选中项时自动退出多选。 */
    fun toggleSelection(trackId: String) {
        _state.update { s ->
            val next = if (trackId in s.selectedIds) s.selectedIds - trackId else s.selectedIds + trackId
            s.copy(selectionMode = next.isNotEmpty(), selectedIds = next)
        }
    }

    /** 全选当前已加载曲目。 */
    fun selectAll() {
        _state.update { it.copy(selectionMode = true, selectedIds = it.tracks.mapTo(LinkedHashSet()) { t -> t.id }) }
    }

    fun exitSelection() {
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    // ============ 播放 / 队列 ============

    private fun mediaIds(tracks: List<TrackSummary>): List<String> {
        // 用**曲目加载时**的音源，而不是当前活跃音源：否则切源后旧 id 会被新 Provider
        // 解释成别的歌。tracksProviderId 为空（尚未加载完）时才回落到当前活跃音源。
        val provider = tracksProviderId ?: AppModel.activeProviderId()
        return tracks.map { "$provider://song/${it.id}" }
    }

    /**
     * 队列来源歌单 id（仅真实服务端歌单）。首页虚拟歌单（每日推荐 / 心动模式 /
     * 相似歌曲）的 id 是负数、服务端不存在，不能上报 —— 上游拿它当 `pid` 会拒绝。
     * 心动模式（playmode/intelligence/list）依赖这个来源做歌单上下文。
     */
    private fun sourceIdOrNull(): String? =
        _state.value.summary?.id?.takeIf { it > 0 }?.toString()

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
    fun autoPlayAt(index: Int, animateCover: Boolean = false) {
        val id = _state.value.summary?.id ?: return
        if (autoPlayedPlaylistId == id) return
        if (_state.value.tracks.isEmpty()) return
        autoPlayedPlaylistId = id
        playAt(index.coerceIn(0, _state.value.tracks.lastIndex), animateCover)
    }

    fun playAt(index: Int, animateCover: Boolean = true) {
        val tracks = sortedTracks(_state.value.tracks, _state.value.sortType)
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        if (animateCover) tracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val clicked = ids.getOrNull(index) ?: return
        screenModelScope.launch {
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

    fun playAll() {
        val tracks = sortedTracks(_state.value.tracks, _state.value.sortType)
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        tracks.firstOrNull()?.let { CoverFlight.play(it.id, it.coverUrl) }
        screenModelScope.launch { AppModel.playback.playQueue(ids, startIndex = 0, sourceId = sourceIdOrNull()) }
    }

    fun playShuffle() {
        // 先洗牌曲目再映射 mediaId，保证 CoverFlight 起点与实际起播的曲目一致。
        val tracks = _state.value.tracks.shuffled()
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        tracks.firstOrNull()?.let { CoverFlight.play(it.id, it.coverUrl) }
        screenModelScope.launch { AppModel.playback.playQueue(ids, startIndex = 0, sourceId = sourceIdOrNull()) }
    }

    fun queueAll() {
        val ids = mediaIds(sortedTracks(_state.value.tracks, _state.value.sortType))
        if (ids.isEmpty()) return
        screenModelScope.launch {
            ids.forEach { AppModel.playback.addToQueue(it) }
            UiEvents.notify("已加入播放队列")
        }
    }

    fun queueSelected() {
        val selected = _state.value.selectedIds
        if (selected.isEmpty()) return
        val tracks = sortedTracks(_state.value.tracks, _state.value.sortType).filter { it.id in selected }
        val ids = mediaIds(tracks)
        if (ids.isEmpty()) return
        screenModelScope.launch {
            ids.forEach { AppModel.playback.addToQueue(it) }
            UiEvents.notify("已加入播放队列")
        }
    }

    // ============ 歌单管理 ============

    /** 当前账号是否拥有该歌单（用于决定删除 vs 取消收藏）。 */
    fun isOwner(): Boolean {
        val nickname = AppModel.userProfileFlow.value?.nickname
        return nickname != null && _state.value.summary?.creatorName == nickname
    }

    /** owner → 删除歌单；否则取消收藏。成功后 [onDone]（由 Screen pop）。 */
    fun deleteOrUnsubscribe(onDone: () -> Unit) {
        val playlist = _state.value.summary ?: return
        val owner = isOwner()
        screenModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    if (owner) AppModel.musicRepository.deletePlaylist(playlist.id)
                    else AppModel.musicRepository.unsubscribePlaylist(playlist.id)
                }.getOrDefault(false)
            }
            UiEvents.notify(
                if (ok) (if (owner) "已删除「${playlist.name}」" else "已取消收藏「${playlist.name}」")
                else "操作失败"
            )
            if (ok) onDone()
        }
    }

    /** 向当前歌单添加歌曲（"从歌单导入 / 从播放队列添加"，仅 owner 有效）。
     *
     * 成功后本地追加（按 id 去重保序）并同步 trackCount，避免重新拉取整页。
     */
    fun addTracks(newTracks: List<TrackSummary>) {
        val playlist = _state.value.summary ?: return
        if (newTracks.isEmpty() || !isOwner()) return
        screenModelScope.launch {
            val ids = newTracks.map { it.id }
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.addTracksToPlaylist(playlist.id, ids) }
                    .getOrDefault(false)
            }
            if (ok) {
                var addedCount = 0
                _state.update { s ->
                    val seen = s.tracks.mapTo(HashSet()) { it.id }
                    val added = newTracks.filter { seen.add(it.id) }
                    addedCount = added.size
                    s.copy(
                        tracks = s.tracks + added,
                        summary = s.summary?.copy(trackCount = s.summary.trackCount + added.size),
                    )
                }
                UiEvents.notify(if (addedCount > 0) "已添加 $addedCount 首歌曲" else "所选歌曲均已在歌单中")
            } else {
                UiEvents.notify("加入歌单失败")
            }
        }
    }

    /** 从歌单移除曲目（仅 owner 有效）；成功后本地剔除并退出多选。 */
    fun removeTracks(trackIds: List<String>) {
        val playlist = _state.value.summary ?: return
        if (trackIds.isEmpty() || !isOwner()) return
        screenModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.removeTracksFromPlaylist(playlist.id, trackIds) }
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
                UiEvents.notify("已移除 $removedCount 首歌曲")
            } else {
                UiEvents.notify("操作失败")
            }
        }
    }

    // ============ 收藏（红心） ============

    /** 拉取当前用户收藏列表（顶层 "ids" 数组）；任何异常静默置空集。 */
    private fun loadLiked() {
        screenModelScope.launch {
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
    fun toggleLike(track: TrackSummary) {
        val liked = _state.value.likedIds.contains(track.id)
        screenModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.likeSong(track.id, !liked) }.getOrDefault(false)
            }
            if (ok) {
                _state.update { s ->
                    s.copy(likedIds = if (liked) s.likedIds - track.id else s.likedIds + track.id)
                }
            } else {
                UiEvents.notify("操作失败")
            }
        }
    }

    fun isLiked(trackId: String): Boolean = _state.value.likedIds.contains(trackId)

}
