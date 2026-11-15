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
import androidx.compose.foundation.layout.height
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
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpDetailHeroSkeleton
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSectionHeaderSkeleton
import cp.player.app.ui.component.CpSkeletonCaption
import cp.player.app.ui.component.CpSongRowSkeleton
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.platform.shareText
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.AlbumSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.flow.StateFlow
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
        val navigator = LocalAppNavigator.current
        AlbumDetailContent(
            albumId = fallback?.resourceId ?: albumId.toString(),
            fallback = fallback,
            // ⚠️ `rememberScreenModel` 是**定义在 `Screen` 上的扩展函数**
            // （`javap` 核实：`rememberScreenModel(Screen, String, Function0, ...)`），
            // 只有在 Screen 子类的成员里才有接收者。放到顶层的私有 @Composable 里会
            // 直接报 `Unresolved reference`。所以模型一律在这里建好往下传 ——
            // 与 [PlaylistDetailScreen] 同一套写法。
            model = rememberScreenModel { AlbumDetailModel() },
            onBack = { navigator.pop() },
        )
    }
}

private typealias AlbumUiState = cp.player.app.presentation.albumdetail.AlbumUiState

/** 专辑详情页的 Voyager 适配层（Thin Adapter）。 */
private class AlbumDetailModel : ScreenModel {
    private val presenter = cp.player.app.presentation.albumdetail.AlbumDetailPresenter(
        scope = screenModelScope,
        notify = UiEvents::notify,
    )

    val state: StateFlow<AlbumUiState> get() = presenter.state

    fun load(albumId: String, fallback: AlbumSummary?) = presenter.load(albumId, fallback)
    fun toggleFavorite() = presenter.toggleFavorite()
}

@Composable
private fun AlbumDetailContent(
    albumId: String,
    fallback: AlbumSummary?,
    model: AlbumDetailModel,
    onBack: () -> Unit,
) {
    val s = cpStrings()
    val navigator = LocalAppNavigator.current
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
                state.loading && detail == null -> ScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = CpSpacing.pageHorizontal,
                        end = CpSpacing.pageHorizontal,
                        bottom = CpSpacing.formBottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                ) {
                    // 骨架而不是居中加载器：这一页的结构（封面 + 元信息 + 曲目）在
                    // 请求发出前就已经确定，先摆出来比「空一屏再整屏跳出」稳定得多。
                    CpSkeletonCaption(s.album.loadingNote)
                    CpDetailHeroSkeleton(wide = isWide)
                    Spacer(Modifier.height(8.dp))
                    CpSectionHeaderSkeleton(titleWidth = 64.dp, subtitleWidth = 150.dp, trailing = true)
                    CpSongRowSkeleton(rows = 8)
                }
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
                                detail.let { state.error ?: s.album.noPlayableTracks }
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
                                            { navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId", title = "${track.name} - MV")) }
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
                { navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId", title = "${track.name} - MV")) }
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
