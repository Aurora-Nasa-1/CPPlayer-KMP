package cp.player.app.ui.model

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cp.player.app.AppModel
import cp.player.app.ui.component.PlaylistSortType
import cp.player.app.ui.util.UiEvents
import cp.player.kmp.BackendResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // ============ 加载 ============

    /** 加载歌单详情（description / trackCount 修正）与首页曲目（并行），并拉取收藏列表。 */
    fun load(summary: PlaylistSummary) {
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
            // 歌单已切换，丢弃过期结果
            if (fetchingPlaylistId != summary.id) return@launch
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
        val provider = AppModel.activeProviderId()
        return tracks.map { "$provider://song/${it.id}" }
    }

    /** 按当前排序后的列表，从 [index] 处开始播放（替换队列）。 */
    fun playAt(index: Int) {
        val ids = mediaIds(sortedTracks(_state.value.tracks, _state.value.sortType))
        if (ids.isEmpty()) return
        screenModelScope.launch { AppModel.playback.playQueue(ids, startIndex = index) }
    }

    fun playAll() {
        val ids = mediaIds(sortedTracks(_state.value.tracks, _state.value.sortType))
        if (ids.isEmpty()) return
        screenModelScope.launch { AppModel.playback.playQueue(ids, startIndex = 0) }
    }

    fun playShuffle() {
        val ids = mediaIds(_state.value.tracks).shuffled()
        if (ids.isEmpty()) return
        screenModelScope.launch { AppModel.playback.playQueue(ids, startIndex = 0) }
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
