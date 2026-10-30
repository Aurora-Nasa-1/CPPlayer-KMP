package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.platform.shareText
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import cp.player.core.music.AlbumDetail
import cp.player.core.music.AlbumSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 专辑详情页面：展示专辑元数据、曲目列表与播放控制。
 */
class AlbumDetailScreen(
    private val albumId: Long,
    private val fallback: AlbumSummary? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        AlbumDetailContent(
            albumId = albumId,
            fallback = fallback,
            // ⚠️ `rememberScreenModel` 是**定义在 `Screen` 上的扩展函数**
            // （`javap` 核实：`rememberScreenModel(Screen, String, Function0, ...)`），
            // 只有在 Screen 子类的成员里才有接收者。放到顶层的私有 @Composable 里会
            // 直接报 `Unresolved reference`。所以模型一律在这里建好往下传 ——
            // 与 [PlaylistDetailScreen] 同一套写法。
            model = rememberScreenModel { AlbumDetailModel() },
            onBack = { navigator.popOrNotify() },
        )
    }
}

private data class AlbumUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: AlbumDetail? = null,
    /** 当前专辑是否已收藏（由 `album/sublist` 全集判定）。 */
    val isFavorite: Boolean = false,
    /** 收藏请求在途：按钮置灰，避免连点叠出互相取消的请求。 */
    val favoritePending: Boolean = false,
)

private class AlbumDetailModel : ScreenModel {
    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state

    private var loadedId: Long? = null

    /**
     * 收藏集合是否已拉到。
     *
     * 上游没有「这张专辑是否已收藏」的单条查询 —— 只能拉 `album/sublist` 全集本地比对。
     * 详情页可能被反复进入 / 退出大播放器后重进组合，所以拉到的结果缓存在模型里，
     * 只有 [AppModel.albumFavoriteGeneration] 真的变了才重新拉。
     */
    private var favoriteIds: Set<Long>? = null

    init {
        // 任何地方收藏 / 取消收藏专辑后，本页的收藏态都要跟着变（典型场景：
        // 详情页在双栏右栏、用户在左栏列表里收藏了同一张专辑）。
        // `drop(1)` 跳过订阅时的当前值：首屏的收藏态由 [load] 自己拉，
        // 不跳过的话这里会先白拉一次（且与 load 的那次并发，结果可能乱序覆盖）。
        screenModelScope.launch {
            AppModel.albumFavoriteGeneration.drop(1).collect {
                // 自己发的那一次不重拉：本地已经乐观更新过，再拉一遍只会
                // 因网络慢而闪回旧状态（见 AppModel.isLastChangedBySelf）。
                if (AppModel.isLastChangedBySelf(loadedId)) return@collect
                favoriteIds = null
                syncFavoriteState()
            }
        }
        // 换账号 ⇒ 收藏集合整份换掉（「已收藏」是账号数据，登录 / 登出 / 切号后
        // 上一账号的判定没有任何意义）。订阅 accountGeneration 而不是 userProfileFlow：
        // 后者启动时恢复资料也会发一次（见 AppModel.accountGeneration 的 KDoc）。
        screenModelScope.launch {
            AppModel.accountGeneration.drop(1).collect {
                favoriteIds = null
                syncFavoriteState()
            }
        }
        // 切音源 ⇒ 收藏集合属于旧音源，同样作废。
        screenModelScope.launch {
            AppModel.sourceGeneration.drop(1).collect {
                favoriteIds = null
                syncFavoriteState()
            }
        }
    }

    fun load(albumId: Long, fallback: AlbumSummary?) {
        if (loadedId == albumId && _state.value.detail != null) return
        loadedId = albumId
        screenModelScope.launch {
            _state.value = AlbumUiState(loading = true)
            val result = runCatching { AppModel.musicRepository.getAlbumDetail(albumId) }
                .getOrElse { BackendResult.Error(it.message.orEmpty()) }
            _state.value = when (result) {
                is BackendResult.Success -> AlbumUiState(loading = false, detail = result.data)
                is BackendResult.Error -> AlbumUiState(
                    loading = false,
                    error = result.message,
                    // 拿不到详情时**保留列表页传来的封面**：让用户至少知道自己点开了哪一张。
                    detail = fallback?.let {
                        AlbumDetail(
                            id = it.id,
                            name = it.name,
                            coverUrl = it.coverUrl,
                            artistName = it.artistName,
                            artistId = it.artistId,
                            publishTimeMs = it.publishTimeMs,
                            company = null,
                            description = null,
                            tracks = emptyList(),
                        )
                    },
                )
                is BackendResult.Unsupported -> AlbumUiState(
                    loading = false,
                    error = result.message,
                )
            }
            // 详情落定后再问收藏态：两个请求互不依赖，但收藏态错了顶多是按钮显示不对，
            // 而详情拿不到整页就是错误态 —— 先保证主要信息。
            syncFavoriteState()
        }
    }

    /**
     * 拉收藏全集并把结果套到当前专辑上。**已拉过就直接用缓存**（[favoriteIds] 非空）。
     *
     * 要点：`album/sublist` 本身在缓存层是读透的（见 `CachedMusicApiService.isCacheable`），
     * 所以「拉全集」不是每次都打网络；但**空结果同样要缓存**（`emptySet()` 不为 null），
     * 否则未登录时每次进详情页都会重打一次 —— 用 `?:` 而不是判 `isNotEmpty` 正是为此。
     *
     * **未登录 / 请求失败一律当作「未收藏」**而不是报错：详情页本身是可看的，
     * 为了一个装饰性开关把整页变成错误态得不偿失（点下去会走 [toggleFavorite]
     * 的登录提示分支，用户仍然知道为什么点了没反应）。
     */
    private suspend fun syncFavoriteState() {
        val albumId = loadedId ?: return
        val ids = favoriteIds ?: runCatching {
            val result = AppModel.musicRepository.getUserAlbums(limit = 300)
            when (result) {
                is BackendResult.Success -> result.data.map { it.id }.toSet()
                else -> emptySet()
            }
        }.getOrElse { emptySet() }.also { favoriteIds = it }
        _state.update { it.copy(isFavorite = albumId in ids) }
    }

    /**
     * 切换当前专辑的收藏态。
     *
     * 顺序是**乐观更新 → 请求 → 失败回滚**（与 `PlaybackControllerImpl.toggleFavoriteFor`
     * 同一套）：收藏是「带结果的动作」，等网络回来才变色会让点击像没生效。
     *
     * 失败时回滚并出声 —— 静默回滚的结果是「按钮弹回去但没有任何解释」。
     */
    fun toggleFavorite() {
        val albumId = loadedId ?: return
        if (_state.value.favoritePending) return
        if (AppModel.userProfileFlow.value == null) {
            UiEvents.notify(AppModel.strings().album.favoriteAlbumNeedLogin)
            return
        }
        val target = !_state.value.isFavorite
        // 乐观更新 + 在途标记：连点由 [AlbumUiState.favoritePending] 挡住，
        // 不挡的话两次点击会各自按「点击那一刻的本地状态」算出同一个目标，
        // 上一次的响应回来又把按钮弹回去。
        _state.update { it.copy(isFavorite = target, favoritePending = true) }
        screenModelScope.launch {
            val ok = runCatching { AppModel.musicRepository.subscribeAlbum(albumId, target) }
                .getOrDefault(false)
            val strings = AppModel.strings()
            if (ok) {
                // 收藏全集已经变了：本模型的缓存跟着改，**不必**立刻重拉
                // （subscribeAlbum 已发代际信号，本页自己不会收到重拉）。
                favoriteIds = if (target) favoriteIds?.plus(albumId) else favoriteIds?.minus(albumId)
                _state.update { it.copy(favoritePending = false) }
                UiEvents.notify(strings.album.favoriteAlbumDone(target))
            } else {
                _state.update { it.copy(isFavorite = !target, favoritePending = false) }
                UiEvents.notify(strings.album.favoriteAlbumFailed)
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    albumId: Long,
    fallback: AlbumSummary?,
    model: AlbumDetailModel,
    onBack: () -> Unit,
) {
    val s = cpStrings()
    val navigator = LocalNavigator.current
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val provider = AppModel.activeProviderId()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    var optionsTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    // 「歌曲信息」的目标：与其余页面同款，走共享的 SongDetailInfoDialog。
    var showInfoTarget by remember { mutableStateOf<TrackSummary?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // 取消收藏的二次确认。**可恢复**操作 ⇒ destructive = false（确认键不走 error 色，
    // 见 AGENTS.md §6）。收藏（加法）不需要确认，只有取消才问。
    val confirm = cp.player.app.ui.component.rememberConfirmState()
    val requestToggleFavorite: () -> Unit = {
        val target = !state.isFavorite
        if (!target) {
            confirm.request(
                title = s.album.unfavoriteAlbumAction,
                message = s.album.unfavoriteAlbumMessage(state.detail?.name ?: fallback?.name ?: ""),
                confirmLabel = s.album.unfavoriteAlbumAction,
                destructive = false,
                onConfirm = { model.toggleFavorite() },
            )
        } else {
            model.toggleFavorite()
        }
    }

    LaunchedEffect(albumId) { model.load(albumId, fallback) }

    val detail = state.detail
    val allTracks = detail?.tracks.orEmpty()
    val filteredTracks = remember(allTracks, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) allTracks
        else allTracks.filter { track ->
            track.name.contains(q, ignoreCase = true) ||
            track.artist.contains(q, ignoreCase = true) ||
            (track.album?.contains(q, ignoreCase = true) == true)
        }
    }
    val mediaIds = filteredTracks.map { "$provider://song/${it.id}" }

    val playAt: (Int) -> Unit = { index ->
        filteredTracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val clicked = mediaIds.getOrNull(index)
        if (clicked != null) {
            AppModel.playTrackClicked(clicked) {
                AppModel.playback.playQueue(mediaIds, index.coerceAtLeast(0))
            }
        }
    }
    val shufflePlay: () -> Unit = {
        scope.launch {
            AppModel.playback.playQueue(mediaIds.shuffled(), 0)
            UiEvents.notify(s.album.shufflePlay)
        }
    }
    val queueAll: () -> Unit = {
        scope.launch {
            mediaIds.forEach { AppModel.playback.addToQueue(it) }
            UiEvents.notify(s.library.queuedToPlay)
        }
    }

    CpRouteScaffold(title = detail?.name ?: fallback?.name ?: s.album.fallbackTitle, onBack = onBack) { pageModifier ->
        BoxWithConstraints(pageModifier.fillMaxSize()) {
            // 宽屏把「封面 + 说明」放到左侧固定栏，曲目占右侧 —— 与歌单详情同一套断点。
            val isWide = cp.player.app.ui.component.CpBreakpoints.isExpanded(maxWidth)
            when {
                state.loading && detail == null -> ContentState(
                    title = s.album.loading,
                    message = s.album.loadingNote,
                    loading = true,
                )
                detail == null -> ContentState(
                    title = s.album.notOpened,
                    // 后端给的 message 可能是空串（见 load() 里的 orEmpty）⇒ 用本地文案兜底。
                    message = state.error?.takeIf { it.isNotBlank() } ?: s.album.loadFailed,
                    error = true,
                    actionLabel = s.album.retry,
                    onAction = { model.load(albumId, fallback) },
                )
                else -> LazyScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = CpSpacing.pageHorizontal,
                        end = CpSpacing.pageHorizontal,
                        bottom = CpSpacing.formBottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                ) {
                    item {
                        AlbumHeader(
                            detail = detail,
                            trackCount = allTracks.size,
                            totalDurationMs = allTracks.sumOf { it.durationMs },
                            wide = isWide,
                            isFavorite = state.isFavorite,
                            favoritePending = state.favoritePending,
                            onToggleFavorite = { requestToggleFavorite() },
                            onPlayAll = { playAt(0) },
                            onShuffle = shufflePlay,
                            onAddToQueue = queueAll,
                            onDownloadAll = { AppModel.downloadTracks(allTracks) },
                        )
                    }
                    item {
                        SectionHeader(
                            title = s.album.tracks,
                            supportingText = if (allTracks.isEmpty()) {
                                detail?.let { state.error ?: s.album.noPlayableTracks }.orEmpty()
                            } else if (searchQuery.isNotBlank()) {
                                "${filteredTracks.size} / ${allTracks.size}"
                            } else {
                                s.album.trackCount(allTracks.size)
                            },
                            action = {
                                if (allTracks.isNotEmpty()) {
                                    androidx.compose.material3.IconButton(
                                        onClick = {
                                            isSearchActive = !isSearchActive
                                            if (!isSearchActive) searchQuery = ""
                                        },
                                    ) {
                                        androidx.compose.material3.Icon(
                                            imageVector = if (isSearchActive) androidx.compose.material.icons.Icons.Filled.Close else androidx.compose.material.icons.Icons.Filled.Search,
                                            contentDescription = s.album.searchAlbum,
                                            tint = if (isSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (isSearchActive) {
                        item {
                            androidx.compose.material3.Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier.weight(1f),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                        singleLine = true,
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                        decorationBox = { innerTextField ->
                                            if (searchQuery.isEmpty()) {
                                                CpText(
                                                    text = s.album.searchAlbum,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                )
                                            }
                                            innerTextField()
                                        },
                                    )
                                    if (searchQuery.isNotEmpty()) {
                                        androidx.compose.material3.IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Filled.Close,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (filteredTracks.isEmpty()) {
                        item {
                            ContentState(
                                title = if (searchQuery.isNotBlank()) s.album.emptyTracks else s.album.emptyTracks,
                                message = if (searchQuery.isNotBlank()) "没有匹配 \"$searchQuery\" 的曲目" else (state.error ?: s.album.emptyTracksNote),
                            )
                        }
                    } else {
                        items(filteredTracks.size, key = { filteredTracks[it].id }) { index ->
                            val track = filteredTracks[index]
                            SongItem(
                                track = track,
                                index = index,
                                total = filteredTracks.size,
                                isCurrentlyPlaying = track.id == currentTrackId,
                                modifier = Modifier.animateItem(),
                                onClick = { playAt(index) },
                                onOptionsClick = { optionsTarget = track },
                                // 桌面端右键菜单：动作集合与 SongOptionsSheet 对齐
                                contextMenu = songContextMenuItems(
                                    SongMenuActions(
                                        onPlay = { playAt(index) },
                                        onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                                            { navigator?.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV")) }
                                        },
                                        isFavorite = track.id in likedIds,
                                        onToggleFavorite = {
                                            val target = track.id !in likedIds
                                            scope.launch {
                                                AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                                                UiEvents.notify(if (target) s.album.liked else s.album.unliked)
                                            }
                                        },
                                        onAddToQueue = {
                                            scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                                            UiEvents.notify(s.library.queuedToPlay)
                                        },
                                        onPlayNext = {
                                            scope.launch { AppModel.playback.addNextToQueue("$provider://song/${track.id}") }
                                            UiEvents.notify(s.library.playNext)
                                        },
                                        isDownloaded = AppModel.isDownloaded(track.id),
                                        onDownload = { AppModel.downloadTrack(track) },
                                        onAddToPlaylist = { addToPlaylistTrack = track },
                                        onShare = { shareText(songShareText(track)) },
                                    )
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    optionsTarget?.let { track ->
        SongOptionsSheet(
            songName = track.name,
            artistName = track.artist,
            coverUrl = track.coverUrl,
            isFavorite = track.id in likedIds,
            isDownloaded = AppModel.isDownloaded(track.id),
            onDismiss = { optionsTarget = null },
            onPlay = {
                playAt(allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                optionsTarget = null
            },
            onToggleFavorite = {
                val target = track.id !in likedIds
                scope.launch {
                    AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                    UiEvents.notify(if (target) s.album.liked else s.album.unliked)
                }
            },
            onAddToQueue = {
                scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                UiEvents.notify(s.library.queuedToPlay)
            },
            onPlayNext = {
                scope.launch { AppModel.playback.addNextToQueue("$provider://song/${track.id}") }
                UiEvents.notify(s.library.playNext)
            },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onDownload = { AppModel.downloadTrack(track) },
            onShowInfo = { showInfoTarget = track },
            onShare = { shareText(songShareText(track)) },
            onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                { navigator?.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV")) }
            },
        )
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistSheet(trackId = track.id, onDismiss = { addToPlaylistTrack = null })
    }

    showInfoTarget?.let { track ->
        cp.player.app.ui.component.SongDetailInfoDialog(
            track = track,
            onDismiss = { showInfoTarget = null },
        )
    }

    // 确认框宿主放在页面组合**末尾**（与其余弹层同级）——见 CpConfirmHost 的约定。
    cp.player.app.ui.component.CpConfirmHost(confirm)
}

// 头部（封面 / 标题 / 元信息 / 说明 / 收藏 / 动作组）在 `AlbumHeaderParts.kt`：
// 拆出去的唯一理由是**可出图** —— 整页要 AppModel + 网络才能起来，而头部是纯数据驱动的。
