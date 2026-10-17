package cp.player.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import cp.player.app.ui.component.cpBounceClickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.platform.BackHandler
import cp.player.app.platform.shareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightTarget
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.AddSongsOptionsSheet
import cp.player.app.ui.component.AppScaffold
import cp.player.app.ui.component.CpActionButtonGroup
import cp.player.app.ui.component.CpActionGroupItem
import cp.player.app.ui.component.CpAnchoredMenu
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpContextMenuSeparator
import cp.player.app.ui.component.LocalWindowChromeActive
import cp.player.app.ui.component.CpTwoPane
import cp.player.app.ui.component.PlaylistSortType
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.PlaylistPickerSheet
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.SourceSongsSelectionSheet
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.cpBounceClickable
import cp.player.app.ui.component.playlistShareText
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.app.ui.model.PlaylistDetailUiState
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.BackendResult
import cp.player.core.music.CPMediaId
import cp.player.core.music.PlaylistTracksPage
import cp.player.core.music.SongDetailInfo
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cp.player.core.util.localDateTimeOf

class PlaylistDetailScreen(
    val playlist: PlaylistSummary,
    private val embedded: Boolean = false,
    private val onEmbeddedBack: (() -> Unit)? = null,
) : Screen {
    @Composable
    override fun Content() {
        PlaylistDetailContent(
            playlist = playlist,
            model = rememberScreenModel { PlaylistDetailScreenModel() },
            embedded = embedded,
            onEmbeddedBack = onEmbeddedBack,
        )
    }
}

/**
 * 将发行时间毫秒时间戳格式化为 yyyy-MM-dd。
 *
 * 走 `cp.player.core.util.localDateTimeOf`（expect/actual，jvm 侧用 java.time），
 * **不要用 kotlinx-datetime**：运行时类路径上是 0.7.x，`kotlinx.datetime.Instant`
 * 已变成指向 `kotlin.time.Instant` 的 typealias（无类文件），一碰就是
 * `NoClassDefFoundError`（2026-10-02 真实崩溃）。
 */
private fun formatPublishDate(ms: Long): String {
    val dt = localDateTimeOf(ms)
    return "%04d-%02d-%02d".format(dt.year, dt.month, dt.day)
}

@Composable
fun PlaylistDetailContent(
    playlist: PlaylistSummary,
    model: PlaylistDetailScreenModel,
    embedded: Boolean = false,
    onEmbeddedBack: (() -> Unit)? = null,
    initialOverrideTracks: List<TrackSummary>? = null,
    autoPlayIndex: Int? = null,
    /** 该歌单是首页生成的本地虚拟歌单（id 为负数），没有服务端实体。 */
    isLocalPlaylist: Boolean = false,
    /** 调用方仍在拉取曲目：以加载态起步，避免先闪一屏"歌单暂无歌曲"。 */
    loadingOverride: Boolean = false,
) {
    val s = cpStrings()
    val navigator = LocalNavigator.currentOrThrow
    val state by model.state.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val scope = rememberCoroutineScope()

    var showPlaylistSheet by remember { mutableStateOf(false) }
    var optionsTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var showInfoTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistIds by remember { mutableStateOf<List<String>?>(null) }
    // 添加歌曲来源流程（移植旧项目）：来源选项 → 歌单选择器/队列 → 歌曲多选
    var showAddSongsOptions by remember { mutableStateOf(false) }
    var showImportPicker by remember { mutableStateOf(false) }
    var importSource by remember { mutableStateOf<PlaylistSummary?>(null) }
    var showQueueSelection by remember { mutableStateOf(false) }
    // 非 owner 歌单的收藏态（Screen 内简化维护）
    var playlistFavorite by remember { mutableStateOf(false) }

    // 歌单内即时搜索过滤状态（对标 PixelPlayer）
    var inPlaylistSearchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // 破坏性操作（删除歌单 / 取消收藏 / 移除曲目）的二次确认。
    // 本页面有三个入口能触发删除（右键菜单、选项弹层、多选工具条），
    // 用一个挂起态保证它们共用同一份文案、且不会同时弹两个框。
    val confirm = cp.player.app.ui.component.rememberConfirmState()

    // 首页生成的虚拟歌单（每日推荐 / 相似歌曲 / 心动模式）id 为负数，服务端并不存在，
    // 曲目已随导航传入，必须跳过远端加载，否则接口 404 会让详情页只剩空白。
    LaunchedEffect(playlist.id, isLocalPlaylist) {
        if (isLocalPlaylist) model.loadLocal(playlist, initialOverrideTracks.orEmpty(), loadingOverride)
        else model.load(playlist)
    }

    // 曲目就绪后从 autoPlayIndex 开始播放（为 null 时只浏览不播放）
    LaunchedEffect(autoPlayIndex, state.tracks) {
        val index = autoPlayIndex ?: return@LaunchedEffect
        model.autoPlayAt(index, animateCover = false)
    }

    // 多选模式下返回键退出多选
    BackHandler(enabled = state.selectionMode) { model.exitSelection() }

    val displayTracks = remember(state.tracks, state.sortType) { model.displayTracks() }
    val filteredDisplayTracks = remember(displayTracks, inPlaylistSearchQuery) {
        val q = inPlaylistSearchQuery.trim()
        if (q.isEmpty()) displayTracks
        else displayTracks.filter { track ->
            track.name.contains(q, ignoreCase = true) ||
            track.artist.contains(q, ignoreCase = true) ||
            (track.album?.contains(q, ignoreCase = true) == true)
        }
    }

    val totalDurationMs = remember(state.tracks) { state.tracks.sumOf { it.durationMs } }
    val summary = state.summary ?: playlist
    // 虚拟歌单没有"创建者"概念，一律按非本人处理，避免出现删除 / 添加等必然失败的操作
    val isOwner = !isLocalPlaylist && model.isOwner()
    val trackCount = if (state.tracks.isNotEmpty()) state.tracks.size
        else (state.summary?.trackCount ?: playlist.trackCount)
    val durationStr = if (state.tracks.isEmpty()) "…" else formatTimeMs(totalDurationMs)

    // "添加"按钮：仅创建者可向歌单导入歌曲
    val openAddSongs: () -> Unit = {
        if (isOwner) showAddSongsOptions = true
        else UiEvents.notify(s.library.creatorOnlyHint)
    }

    val togglePlaylistFavorite: () -> Unit = {
        val target = !playlistFavorite
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.musicRepository.subscribePlaylist(playlist.id, target) }.getOrDefault(false)
            }
            if (ok) {
                playlistFavorite = target
                UiEvents.notify(if (target) s.library.favorited else s.library.unfavorited)
            } else {
                UiEvents.notify(s.library.operationFailed)
            }
        }
    }

    // 「删除歌单 / 取消收藏」的统一确认入口
    val confirmDeletePlaylist: () -> Unit = {
        confirm.request(
            title = if (isOwner) s.library.deletePlaylist else s.library.unfavoritePlaylist,
            message = if (isOwner) {
                s.library.deletePlaylistMessage(summary.name)
            } else {
                s.library.unfavoritePlaylistMessage(summary.name)
            },
            confirmLabel = if (isOwner) s.common.confirm else s.library.unfavoritePlaylist,
            destructive = isOwner,
            onConfirm = { model.deleteOrUnsubscribe { navigator.popOrNotify() } },
        )
    }

    // 桌面端右键菜单：歌曲行动作集合与 SongOptionsSheet 完全对齐
    val buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem> = { track, index ->
        songContextMenuItems(
            SongMenuActions(
                onPlay = { model.playAt(index) },
                onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                    { navigator.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV")) }
                },
                isFavorite = model.isLiked(track.id),
                onToggleFavorite = { model.toggleLike(track) },
                onAddToQueue = {
                    scope.launch {
                        AppModel.playback.addToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                        UiEvents.notify(s.library.queuedToPlay)
                    }
                },
                onPlayNext = {
                    scope.launch {
                        AppModel.playback.addNextToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                        UiEvents.notify(s.library.playNext)
                    }
                },
                isDownloaded = AppModel.isDownloaded(track.id),
                onDownload = { AppModel.downloadTrack(track) },
                onAddToPlaylist = { addToPlaylistIds = listOf(track.id) },
                onShare = { shareText(songShareText(track)) },
                onShowInfo = { showInfoTarget = track },
            )
        )
    }

    // 桌面端歌单动作菜单
    val playlistMenu: List<CpContextMenuItem>? = if (isLocalPlaylist) null else buildList {
        add(CpContextMenuItem(s.common.refresh, Icons.Filled.Refresh, onClick = { model.refresh() }))
        add(CpContextMenuItem(s.library.playAll, Icons.Filled.PlayArrow, onClick = { model.playAll() }))
        add(CpContextMenuItem(s.library.addToQueue, Icons.Filled.QueueMusic, onClick = { model.queueAll() }))
        add(CpContextMenuItem(s.library.downloadAll, Icons.Filled.Download, onClick = { AppModel.downloadTracks(displayTracks) }))
        add(CpContextMenuSeparator)
        add(CpContextMenuItem(s.library.sharePlaylist, Icons.Filled.Share, onClick = {
            shareText(playlistShareText(playlist.id, summary.name))
        }))
        if (!isOwner) {
            add(
                CpContextMenuItem(
                    if (playlistFavorite) s.library.unfavoritePlaylist else s.library.favoritePlaylist,
                    if (playlistFavorite) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd,
                    onClick = togglePlaylistFavorite,
                )
            )
        }
        if (isOwner) {
            add(
                CpContextMenuItem(
                    s.library.deletePlaylist, Icons.Filled.Delete,
                    onClick = confirmDeletePlaylist,
                    danger = true,
                )
            )
        }
    }

    // 多选工具条的「从歌单移除」
    val confirmRemoveTracks: (List<String>) -> Unit = { ids ->
        if (ids.isNotEmpty()) {
            confirm.request(
                title = s.library.removeFromPlaylist,
                message = "${s.library.removeSelectedMessage(summary.name, ids.size)}",
                confirmLabel = s.library.removeSelected,
                onConfirm = { model.removeTracks(ids) },
            )
        }
    }

    val onSharePlaylist: (() -> Unit)? = if (isLocalPlaylist) null else {
        { shareText(playlistShareText(playlist.id, summary.name)) }
    }

    if (!embedded) cp.player.app.ui.util.DesktopRouteTitle(summary.name)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = cp.player.app.ui.component.CpBreakpoints.isExpanded(maxWidth)
        val canShowInlineBack = isWide && !embedded &&
            !cp.player.app.ui.component.LocalWindowChromeActive.current
        if (isWide) {
            WideLayout(
                model = model,
                state = state,
                summary = summary,
                playlist = playlist,
                displayTracks = displayTracks,
                filteredTracks = filteredDisplayTracks,
                trackCount = trackCount,
                durationStr = durationStr,
                currentTrackId = currentTrackId,
                isOwner = isOwner,
                isFavorite = playlistFavorite,
                onToggleFavorite = if (!isOwner && !isLocalPlaylist) togglePlaylistFavorite else null,
                onShare = onSharePlaylist,
                onDelete = if (isOwner) confirmDeletePlaylist else null,
                searchQuery = inPlaylistSearchQuery,
                onSearchQueryChange = { inPlaylistSearchQuery = it },
                isSearchActive = isSearchActive,
                onToggleSearch = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) inPlaylistSearchQuery = ""
                },
                showBackButton = canShowInlineBack,
                onBack = { if (embedded) onEmbeddedBack?.invoke() else navigator.popOrNotify() },
                onSongOptions = { optionsTarget = it },
                onOpenPlaylistSheet = { showPlaylistSheet = true },
                onAddSelectedToPlaylist = { addToPlaylistIds = state.selectedIds.toList() },
                onAddTracks = openAddSongs,
                onRemoveSelected = confirmRemoveTracks,
                playlistMenu = playlistMenu,
                buildSongMenu = buildSongMenu,
            )
        } else {
            NarrowLayout(
                model = model,
                state = state,
                summary = summary,
                displayTracks = displayTracks,
                filteredTracks = filteredDisplayTracks,
                trackCount = trackCount,
                durationStr = durationStr,
                currentTrackId = currentTrackId,
                isOwner = isOwner,
                isFavorite = playlistFavorite,
                onToggleFavorite = if (!isOwner && !isLocalPlaylist) togglePlaylistFavorite else null,
                onShare = onSharePlaylist,
                onDelete = if (isOwner) confirmDeletePlaylist else null,
                searchQuery = inPlaylistSearchQuery,
                onSearchQueryChange = { inPlaylistSearchQuery = it },
                isSearchActive = isSearchActive,
                onToggleSearch = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) inPlaylistSearchQuery = ""
                },
                onBack = { if (embedded) onEmbeddedBack?.invoke() else navigator.popOrNotify() },
                onSongOptions = { optionsTarget = it },
                onOpenPlaylistSheet = { showPlaylistSheet = true },
                onAddSelectedToPlaylist = { addToPlaylistIds = state.selectedIds.toList() },
                onAddTracks = openAddSongs,
                onRemoveSelected = confirmRemoveTracks,
                buildSongMenu = buildSongMenu,
            )
        }
    }

    // 歌单选项弹层
    if (showPlaylistSheet) {
        PlaylistOptionsSheet(
            playlistName = summary.name,
            isOwner = isOwner,
            onDismiss = { showPlaylistSheet = false },
            onPlay = { model.playAll() },
            onAddToQueue = { model.queueAll() },
            onRefresh = if (isLocalPlaylist) null else { { model.refresh() } },
            onDelete = if (isOwner) confirmDeletePlaylist else null,
            onShare = onSharePlaylist,
            coverUrl = summary.coverUrl,
            isFavorite = playlistFavorite,
            onToggleFavorite = if (!isOwner && !isLocalPlaylist) togglePlaylistFavorite else null,
            currentSort = state.sortType,
            onSortChange = { model.setSort(it) },
        )
    }

    // 歌曲选项弹层
    optionsTarget?.let { track ->
        SongOptionsSheet(
            songName = track.name,
            artistName = track.artist,
            isFavorite = model.isLiked(track.id),
            isDownloaded = AppModel.isDownloaded(track.id),
            onDismiss = { optionsTarget = null },
            onPlay = {
                val index = displayTracks.indexOf(track)
                if (index >= 0) model.playAt(index)
            },
            onToggleFavorite = { model.toggleLike(track) },
            onAddToQueue = {
                scope.launch {
                    AppModel.playback.addToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                    UiEvents.notify(s.library.queuedToPlay)
                }
            },
            onPlayNext = {
                scope.launch {
                    AppModel.playback.addNextToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                    UiEvents.notify(s.library.playNext)
                }
            },
            onAddToPlaylist = { addToPlaylistIds = listOf(track.id) },
            onDownload = { AppModel.downloadTrack(track) },
            onShowInfo = { showInfoTarget = track },
            onShare = {
                shareText("「${track.name}」 https://music.163.com/#/song?id=${track.id}")
            },
            onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                { navigator.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV")) }
            },
            coverUrl = track.coverUrl,
        )
    }

    // 多选 / 单曲：加入歌单
    addToPlaylistIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = { addToPlaylistIds = null })
    }

    // 添加歌曲：来源选项
    if (showAddSongsOptions) {
        AddSongsOptionsSheet(
            onDismiss = { showAddSongsOptions = false },
            onImportFromPlaylist = {
                showAddSongsOptions = false
                showImportPicker = true
            },
            onAddFromQueue = {
                showAddSongsOptions = false
                showQueueSelection = true
            },
        )
    }

    // 从歌单导入：源歌单选择器
    if (showImportPicker) {
        PlaylistPickerSheet(
            title = s.library.importFromPlaylist,
            excludePlaylistId = playlist.id,
            onDismiss = { showImportPicker = false },
            onSelected = { source ->
                showImportPicker = false
                importSource = source
            },
        )
    }

    // 从歌单导入：源歌曲多选
    importSource?.let { source ->
        SourceSongsSelectionSheet(
            sourceName = source.name,
            fetchSongs = {
                withContext(Dispatchers.IO) {
                    val page = AppModel.musicRepository.getPlaylistTracks(source.id, limit = 300, offset = 0)
                    (page as? BackendResult.Success)?.data?.tracks.orEmpty()
                }
            },
            onDismiss = { importSource = null },
            onAddSelected = { tracks ->
                model.addTracks(tracks)
                importSource = null
            },
        )
    }

    // 从播放队列添加
    if (showQueueSelection) {
        val queueTracks = playbackState.queue.map { item ->
            TrackSummary(
                id = runCatching { CPMediaId.parse(item.mediaId).resourceId }.getOrDefault(item.mediaId),
                name = item.title,
                artist = item.artist,
                album = item.album,
                coverUrl = item.coverUrl,
                durationMs = item.durationMs,
            )
        }
        SourceSongsSelectionSheet(
            sourceName = s.library.sourceNowPlaying,
            initialSongs = queueTracks,
            onDismiss = { showQueueSelection = false },
            onAddSelected = { tracks ->
                model.addTracks(tracks)
                showQueueSelection = false
            },
        )
    }

    // INFO 弹窗
    showInfoTarget?.let { track ->
        var info by remember(track.id) { mutableStateOf<SongDetailInfo?>(null) }
        LaunchedEffect(track.id) {
            info = withContext(Dispatchers.IO) {
                val fallback = SongDetailInfo(
                    songId = track.id,
                    name = track.name,
                    artist = track.artist,
                    album = track.album ?: "",
                    durationMs = track.durationMs,
                )
                (runCatching { AppModel.musicRepository.getSongDetailInfo(track.id, fallback) }.getOrNull()
                        as? BackendResult.Success)?.data
            }
            if (info == null) {
                UiEvents.notify(s.library.loadTrackInfoFailed)
                showInfoTarget = null
            }
        }
        AlertDialog(
            onDismissRequest = { showInfoTarget = null },
            title = { Text(s.library.songDetails, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                val current = info
                if (current == null) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(32.dp))
                    }
                } else {
                    Text(
                        buildString {
                            append(
                                s.library.shareTrack(
                                    name = current.name,
                                    artist = current.artist,
                                    album = current.album.ifBlank { s.player.unknownAlbum },
                                    duration = formatTimeMs(current.durationMs),
                                    extra = buildString {
                                        current.publishTimeMs?.let {
                                            append(
                                                s.library.songShareLine(
                                                    s.library.publishDate,
                                                    formatPublishDate(it),
                                                ),
                                            )
                                        }
                                        current.commentCount?.let {
                                            append(s.library.songShareLine(s.library.commentCount, "$it"))
                                        }
                                        current.mvId?.let { append(s.library.songShareLine("MV ID", "$it")) }
                                        current.maxBitrate?.let {
                                            append(
                                                s.library.songShareLine(
                                                    s.library.maxBitrate,
                                                    "${it / 1000}kbps",
                                                ),
                                            )
                                        }
                                        current.fee?.let { fee ->
                                            append(
                                                s.library.songShareLine(
                                                    s.library.paidType,
                                                    when (fee) {
                                                        0 -> s.library.paidFree
                                                        1 -> "VIP"
                                                        4 -> s.library.paidPurchased
                                                        8 -> s.library.paidLowQualityFree
                                                        else -> s.library.paidUnknown
                                                    },
                                                ),
                                            )
                                        }
                                    },
                                ),
                            )
                            append(s.library.songShareLine("ID", current.songId))
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoTarget = null }) { Text(s.common.dismiss) }
            },
        )
    }

    // 二次确认框
    cp.player.app.ui.component.CpConfirmHost(confirm)
}

// ============ 窄屏布局 ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarrowLayout(
    model: PlaylistDetailScreenModel,
    state: PlaylistDetailUiState,
    summary: PlaylistSummary,
    displayTracks: List<TrackSummary>,
    filteredTracks: List<TrackSummary>,
    trackCount: Int,
    durationStr: String,
    currentTrackId: String?,
    isOwner: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
    onBack: () -> Unit,
    onSongOptions: (TrackSummary) -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onAddSelectedToPlaylist: () -> Unit,
    onAddTracks: () -> Unit,
    onRemoveSelected: (List<String>) -> Unit,
    buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem>,
) {
    val s = cpStrings()
    if (state.selectionMode) {
        AppScaffold(
            title = s.library.selectedCount(state.selectedIds.size),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationIcon = {
                IconButton(onClick = { model.exitSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = s.library.exitSelection)
                }
            },
            topBarActions = buildList {
                add(TopBarAction(icon = { Icon(Icons.Filled.SelectAll, contentDescription = s.library.selectAll) }, onClick = { model.selectAll() }))
                add(
                    TopBarAction(
                        icon = { Icon(Icons.Filled.QueueMusic, contentDescription = s.library.addToQueue) },
                        onClick = {
                            model.queueSelected()
                            model.exitSelection()
                        },
                    )
                )
                add(
                    TopBarAction(
                        icon = { Icon(Icons.Filled.Download, contentDescription = s.player.download) },
                        onClick = {
                            val selectedTracks = displayTracks.filter { it.id in state.selectedIds }
                            AppModel.downloadTracks(selectedTracks)
                            model.exitSelection()
                        },
                    )
                )
                add(
                    TopBarAction(
                        icon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = s.player.addToPlaylist) },
                        onClick = onAddSelectedToPlaylist,
                    )
                )
                if (isOwner) {
                    add(
                        TopBarAction(
                            icon = { Icon(Icons.Filled.Delete, contentDescription = s.library.removeFromPlaylist) },
                            onClick = { onRemoveSelected(state.selectedIds.toList()) },
                        )
                    )
                }
            },
        ) { _ ->
            TrackList(
                model = model,
                state = state,
                allTracks = displayTracks,
                displayTracks = filteredTracks,
                currentTrackId = currentTrackId,
                withHero = false,
                summary = summary,
                isOwner = isOwner,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                onDelete = onDelete,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                isSearchActive = isSearchActive,
                onToggleSearch = onToggleSearch,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
                onDownloadAll = { AppModel.downloadTracks(displayTracks) },
                onEnterSelection = { },
                onMore = onOpenPlaylistSheet,
                buildSongMenu = buildSongMenu,
            )
        }
    } else {
        AppScaffold(
            title = summary.name,
            onBackPressed = onBack,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            topBarActions = listOf(
                TopBarAction(
                    icon = { Icon(Icons.Filled.MoreVert, contentDescription = s.library.moreOptions) },
                    onClick = onOpenPlaylistSheet,
                )
            ),
        ) { _ ->
            TrackList(
                model = model,
                state = state,
                allTracks = displayTracks,
                displayTracks = filteredTracks,
                currentTrackId = currentTrackId,
                withHero = true,
                summary = summary,
                isOwner = isOwner,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                onDelete = onDelete,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                isSearchActive = isSearchActive,
                onToggleSearch = onToggleSearch,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
                onDownloadAll = { AppModel.downloadTracks(displayTracks) },
                onEnterSelection = {
                    displayTracks.firstOrNull()?.let { model.enterSelection(it.id) }
                },
                onMore = onOpenPlaylistSheet,
                buildSongMenu = buildSongMenu,
            )
        }
    }
}

// ============ 宽屏布局 ============

@Composable
private fun WideLayout(
    model: PlaylistDetailScreenModel,
    state: PlaylistDetailUiState,
    summary: PlaylistSummary,
    playlist: PlaylistSummary,
    displayTracks: List<TrackSummary>,
    filteredTracks: List<TrackSummary>,
    trackCount: Int,
    durationStr: String,
    currentTrackId: String?,
    isOwner: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSongOptions: (TrackSummary) -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onAddSelectedToPlaylist: () -> Unit,
    onAddTracks: () -> Unit,
    onRemoveSelected: (List<String>) -> Unit,
    playlistMenu: List<CpContextMenuItem>?,
    buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem>,
) {
    val s = cpStrings()
    val windowChromeActive = LocalWindowChromeActive.current
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    val sortMenuItems = listOf(
        CpContextMenuItem(
            s.library.sortDefault, Icons.AutoMirrored.Filled.List,
            onClick = { model.setSort(PlaylistSortType.DEFAULT) },
            isSelected = state.sortType == PlaylistSortType.DEFAULT,
        ),
        CpContextMenuItem(
            s.library.sortByName, Icons.Filled.SortByAlpha,
            onClick = { model.setSort(PlaylistSortType.NAME) },
            isSelected = state.sortType == PlaylistSortType.NAME,
        ),
        CpContextMenuItem(
            s.library.sortByArtist, Icons.Filled.Person,
            onClick = { model.setSort(PlaylistSortType.ARTIST) },
            isSelected = state.sortType == PlaylistSortType.ARTIST,
        ),
    )

    CpTwoPane(
        rail = { railModifier ->
            val backItem = cp.player.app.ui.component.rememberBackContextMenuItem()
            val railMenu = playlistMenu?.let { items ->
                if (backItem == null) items else listOf(backItem) + items
            }
            CpContextMenu(items = railMenu, modifier = railModifier) {
                ScrollColumn(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(16.dp))
                    // 1. M3E 宽屏超大封面（188dp，extraLarge 28dp 圆角，8dp 阴影）
                    Surface(
                        modifier = Modifier.size(188.dp)
                            .coverFlightTarget(CoverFlight.TARGET_PLAYLIST_HEADER, 28.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 8.dp,
                    ) {
                        val hideCover = CoverFlight.isFlyingTo(CoverFlight.TARGET_PLAYLIST_HEADER)
                        if (!summary.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = summary.coverUrl.resized(600),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                                    .graphicsLayer { alpha = if (hideCover) 0f else 1f },
                            )
                        } else {
                            CpCoverPlaceholder(
                                modifier = Modifier.fillMaxSize()
                                    .graphicsLayer { alpha = if (hideCover) 0f else 1f },
                                corner = 28.dp,
                                animated = true,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = summary.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(8.dp))

                    // 2. 元数据药丸行
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!summary.creatorName.isNullOrBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Person, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = summary.creatorName.orEmpty(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.MusicNote, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = s.library.playlistCountLabel(trackCount, durationStr),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // 3. PixelPlayer 标志性非对称双胶囊主播放操作组（撑满左栏）
                    M3EPlayShufflePillGroup(
                        onPlayAll = { model.playAll() },
                        onShuffle = { model.playShuffle() },
                        hasTracks = displayTracks.isNotEmpty(),
                    )

                    Spacer(Modifier.height(14.dp))

                    // 4. 次级快捷胶囊动作轨（包含分享、删除、取消收藏等）
                    CpAnchoredMenu(
                        expanded = sortMenuExpanded,
                        onDismiss = { sortMenuExpanded = false },
                        items = sortMenuItems,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CpAnchoredMenu(
                            expanded = moreMenuExpanded,
                            onDismiss = { moreMenuExpanded = false },
                            items = playlistMenu,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            M3ESecondaryActionsRail(
                                isOwner = isOwner,
                                onAdd = onAddTracks,
                                isFavorite = isFavorite,
                                onToggleFavorite = onToggleFavorite,
                                onShare = onShare,
                                onDelete = onDelete,
                                searchActive = isSearchActive,
                                onToggleSearch = onToggleSearch,
                                currentSort = state.sortType,
                                onSortClick = {
                                    if (windowChromeActive) sortMenuExpanded = true
                                    else onOpenPlaylistSheet()
                                },
                                onDownloadAll = { AppModel.downloadTracks(displayTracks) },
                                selectionMode = state.selectionMode,
                                onEnterSelection = {
                                    displayTracks.firstOrNull()?.let { model.enterSelection(it.id) }
                                },
                                onOpenOptions = {
                                    if (windowChromeActive && !playlistMenu.isNullOrEmpty()) moreMenuExpanded = true
                                    else onOpenPlaylistSheet()
                                },
                                useFlowLayout = true,
                            )
                        }
                    }
                }
            }
        },
        detail = {
            val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            TrackList(
                model = model,
                state = state,
                allTracks = displayTracks,
                displayTracks = filteredTracks,
                currentTrackId = currentTrackId,
                withHero = false,
                summary = summary,
                isOwner = isOwner,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onShare = onShare,
                onDelete = onDelete,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                isSearchActive = isSearchActive,
                onToggleSearch = onToggleSearch,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
                onDownloadAll = { AppModel.downloadTracks(displayTracks) },
                onEnterSelection = { },
                onMore = onOpenPlaylistSheet,
                buildSongMenu = buildSongMenu,
                modifier = Modifier.widthIn(max = 980.dp).align(Alignment.TopCenter),
                topContentPadding = if (showBackButton || state.selectionMode) topInset + 56.dp else 0.dp,
            )
            if (showBackButton || state.selectionMode) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = topInset + 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBackButton) {
                        CpBackButton(onClick = onBack)
                    }
                    if (state.selectionMode) {
                        if (showBackButton) Spacer(Modifier.width(12.dp))
                        Text(
                            text = s.library.selectedCount(state.selectedIds.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { model.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = s.library.selectAll)
                        }
                        IconButton(
                            onClick = {
                                model.queueSelected()
                                model.exitSelection()
                            },
                        ) {
                            Icon(Icons.Filled.QueueMusic, contentDescription = s.library.addToQueue)
                        }
                        IconButton(
                            onClick = {
                                val selectedTracks = displayTracks.filter { it.id in state.selectedIds }
                                AppModel.downloadTracks(selectedTracks)
                                model.exitSelection()
                            },
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = s.player.download)
                        }
                        IconButton(onClick = onAddSelectedToPlaylist) {
                            Icon(Icons.Filled.PlaylistAdd, contentDescription = s.player.addToPlaylist)
                        }
                        if (isOwner) {
                            IconButton(onClick = { onRemoveSelected(state.selectedIds.toList()) }) {
                                Icon(Icons.Filled.Delete, contentDescription = s.library.removeFromPlaylist)
                            }
                        }
                    }
                }
            }
        }
    )
}

// ============ 歌曲列表与 M3E Header 组件 ============

@Composable
private fun TrackList(
    model: PlaylistDetailScreenModel,
    state: PlaylistDetailUiState,
    allTracks: List<TrackSummary>,
    displayTracks: List<TrackSummary>,
    currentTrackId: String?,
    withHero: Boolean,
    summary: PlaylistSummary,
    isOwner: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
    onSongOptions: (TrackSummary) -> Unit,
    onSortClick: () -> Unit,
    onAddTracks: () -> Unit,
    onDownloadAll: () -> Unit,
    onEnterSelection: () -> Unit,
    onMore: () -> Unit,
    buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem>,
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 0.dp,
) {
    val s = cpStrings()
    when {
        state.loading && allTracks.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(40.dp))
            }
        }
        state.error != null && allTracks.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { state.summary?.let { model.load(it, force = true) } }) {
                        Text(s.library.retry)
                    }
                }
            }
        }
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(isSearchActive) {
                if (isSearchActive) {
                    listState.animateScrollToItem(0)
                }
            }
            LazyScrollColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = topContentPadding + 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 窄屏 Hero 头部
                if (withHero) {
                    item(key = "__hero_header__") {
                        NarrowPlaylistHero(
                            summary = summary,
                            displayTracks = allTracks,
                            isOwner = isOwner,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            onShare = onShare,
                            onDelete = onDelete,
                            searchQuery = searchQuery,
                            onSearchQueryChange = onSearchQueryChange,
                            isSearchActive = isSearchActive,
                            onToggleSearch = onToggleSearch,
                            currentSort = state.sortType,
                            onSortClick = onSortClick,
                            onPlayAll = { model.playAll() },
                            onShuffle = { model.playShuffle() },
                            onAdd = onAddTracks,
                            onDownloadAll = onDownloadAll,
                            onEnterSelection = onEnterSelection,
                            onMore = onMore,
                        )
                    }
                } else if (isSearchActive) {
                    // 宽屏模式下若搜索展开，顶部渲染搜索栏
                    item(key = "__inline_search__") {
                        InPlaylistSearchBar(
                            query = searchQuery,
                            onQueryChange = onSearchQueryChange,
                            onClose = onToggleSearch,
                            resultCount = displayTracks.size,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }

                itemsIndexed(items = displayTracks, key = { _, track -> track.id }) { index, track ->
                    if (index >= displayTracks.size - 5 && state.hasMore && !state.fetchingMore && state.loadMoreError == null) {
                        LaunchedEffect(index) { model.loadMore() }
                    }
                    SongItem(
                        track = track,
                        index = index,
                        total = displayTracks.size,
                        isCurrentlyPlaying = track.id == currentTrackId,
                        selectionMode = state.selectionMode,
                        isSelected = track.id in state.selectedIds,
                        contextMenu = if (!state.selectionMode) buildSongMenu(track, index) else null,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            if (state.selectionMode) model.toggleSelection(track.id)
                            else {
                                val originalIndex = allTracks.indexOf(track).takeIf { it >= 0 } ?: index
                                model.playAt(originalIndex)
                            }
                        },
                        onOptionsClick = if (!state.selectionMode) {
                            { onSongOptions(track) }
                        } else null,
                        onLongClick = if (!state.selectionMode) {
                            { model.enterSelection(track.id) }
                        } else null,
                    )
                }
                if (displayTracks.isEmpty()) {
                    item(key = "__empty__") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    if (searchQuery.isNotBlank()) "未在歌单中找到与“$searchQuery”匹配的歌曲"
                                    else s.library.playlistEmpty,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (searchQuery.isNotBlank()) {
                                    TextButton(onClick = { onSearchQueryChange("") }) {
                                        Text("清空搜索")
                                    }
                                }
                            }
                        }
                    }
                }
            if (state.fetchingMore) {
                item(key = "__loading_more__") {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(32.dp))
                    }
                }
            } else if (state.loadMoreError != null) {
                item(key = "__load_more_error__") {
                    Surface(
                        onClick = {
                            model.clearLoadMoreError()
                            model.loadMore()
                        },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                s.library.loadFailedRetry,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                state.loadMoreError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
}



/**
 * 窄屏模式下的 M3E Hero 头部区。
 * 包含沉浸式封面、歌单元数据药丸、双胶囊播放组、横向快捷动作轨以及即时搜索栏。
 */
@Composable
private fun NarrowPlaylistHero(
    summary: PlaylistSummary,
    displayTracks: List<TrackSummary>,
    isOwner: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchActive: Boolean,
    onToggleSearch: () -> Unit,
    currentSort: PlaylistSortType,
    onSortClick: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAdd: () -> Unit,
    onDownloadAll: () -> Unit,
    onEnterSelection: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. 沉浸式大封面
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.size(152.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (summary.coverUrl.isNullOrBlank()) {
                    CpCoverPlaceholder(modifier = Modifier.fillMaxSize(), corner = 24.dp)
                } else {
                    AsyncImage(
                        model = summary.coverUrl,
                        contentDescription = summary.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 2. 歌单标题与元数据
        Text(
            text = summary.name,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = if (isOwner) "我创建的" else if (isFavorite) "已收藏" else "公开歌单",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Text(
                text = "${displayTracks.size} 首歌曲",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))

        // 3. PixelPlayer 标志性非对称双胶囊主播放组
        M3EPlayShufflePillGroup(
            onPlayAll = onPlayAll,
            onShuffle = onShuffle,
            hasTracks = displayTracks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        // 4. 次级快捷动作轨（全圆角药丸胶囊）
        M3ESecondaryActionsRail(
            isOwner = isOwner,
            onAdd = onAdd,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare,
            onDelete = onDelete,
            searchActive = isSearchActive,
            onToggleSearch = onToggleSearch,
            currentSort = currentSort,
            onSortClick = onSortClick,
            onDownloadAll = onDownloadAll,
            selectionMode = false,
            onEnterSelection = onEnterSelection,
            onOpenOptions = onMore,
            useFlowLayout = false,
            modifier = Modifier.fillMaxWidth(),
        )

        // 5. 若搜索激活，呈现即时搜索过滤胶囊栏
        if (isSearchActive) {
            Spacer(Modifier.height(12.dp))
            InPlaylistSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onClose = onToggleSearch,
                resultCount = displayTracks.size,
            )
        }
    }
}

/**
 * PixelPlayer 风格的非对称咬合双胶囊主播放控制组。
 * 左侧为饱满的 PrimaryContainer "播放全部" 胶囊，右侧为次级 SecondaryContainer "随机播放" 胶囊。
 */
@Composable
private fun M3EPlayShufflePillGroup(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    hasTracks: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 主播放胶囊
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .then(if (hasTracks) Modifier.cpBounceClickable(onClick = onPlayAll) else Modifier),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放全部",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "全部播放",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // 随机播放胶囊
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .width(68.dp)
                .fillMaxSize()
                .then(if (hasTracks) Modifier.cpBounceClickable(onClick = onShuffle) else Modifier),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "随机播放",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * M3E 次级横向快捷操作轨。
 * 包含添加、分享、删除（创建者红底）、取消收藏（非创建者红底）/收藏、搜索、排序、下载全部、多选、更多。
 * 支持在宽屏侧边栏折行平铺（[useFlowLayout] = true），彻底解决按钮被截断隐藏的问题。
 */
@Composable
private fun M3ESecondaryActionsRail(
    isOwner: Boolean,
    onAdd: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    searchActive: Boolean,
    onToggleSearch: () -> Unit,
    currentSort: PlaylistSortType,
    onSortClick: () -> Unit,
    onDownloadAll: () -> Unit,
    selectionMode: Boolean,
    onEnterSelection: () -> Unit,
    onOpenOptions: () -> Unit,
    useFlowLayout: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val chips = @Composable {
        // 1. 添加歌曲
        ActionChip(
            label = "添加",
            icon = Icons.Filled.PlaylistAdd,
            onClick = onAdd,
        )

        // 2. 分享歌单
        if (onShare != null) {
            ActionChip(
                label = "分享",
                icon = Icons.Filled.Share,
                onClick = onShare,
            )
        }

        // 3. 删除（创建者）/ 取消收藏（非创建者已收藏）/ 收藏
        if (isOwner && onDelete != null) {
            ActionChip(
                label = "删除",
                icon = Icons.Filled.Delete,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = onDelete,
            )
        } else if (onToggleFavorite != null) {
            if (isFavorite) {
                ActionChip(
                    label = "取消收藏",
                    icon = Icons.Filled.BookmarkRemove,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onToggleFavorite,
                )
            } else {
                ActionChip(
                    label = "收藏",
                    icon = Icons.Filled.BookmarkAdd,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = onToggleFavorite,
                )
            }
        }

        // 4. 搜索
        ActionChip(
            label = "搜索",
            icon = Icons.Filled.Search,
            containerColor = if (searchActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (searchActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            onClick = onToggleSearch,
        )

        // 5. 排序
        val sortLabel = when (currentSort) {
            PlaylistSortType.DEFAULT -> "默认"
            PlaylistSortType.NAME -> "按名称"
            PlaylistSortType.ARTIST -> "按歌手"
        }
        ActionChip(
            label = sortLabel,
            icon = Icons.AutoMirrored.Filled.Sort,
            onClick = onSortClick,
        )

        // 6. 下载全部
        ActionChip(
            label = "下载全部",
            icon = Icons.Filled.Download,
            onClick = onDownloadAll,
        )

        // 7. 多选
        ActionChip(
            label = "多选",
            icon = Icons.Filled.SelectAll,
            containerColor = if (selectionMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (selectionMode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            onClick = onEnterSelection,
        )

        // 8. 更多
        ActionChip(
            label = "更多",
            icon = Icons.Filled.MoreVert,
            onClick = onOpenOptions,
        )
    }

    if (useFlowLayout) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips()
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips()
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = modifier
            .height(38.dp)
            .cpBounceClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = contentColor,
            )
        }
    }
}

/**
 * 歌单内即时搜索过滤胶囊栏。
 */
@Composable
private fun InPlaylistSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    resultCount: Int,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "在歌单中搜索歌曲、歌手、专辑...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = "$resultCount 首",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(26.dp).clickable { onQueryChange("") },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

