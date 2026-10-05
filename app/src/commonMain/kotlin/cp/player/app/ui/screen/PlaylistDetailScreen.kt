package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
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
import cp.player.app.ui.component.CpAnchoredMenu
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpContextMenuSeparator
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

    // 曲目就绪后从 autoPlayIndex 开始播放（沿用重构前"点击即播放"的行为；为 null 时只浏览不播放）
    //
    // ⚠️ 走 [PlaylistDetailScreenModel.autoPlayAt] 而不是直接 `playAt`：从播放页 pop 回来
    // 时本页会重新进入组合，这个 LaunchedEffect 会重新执行，直接 playAt 会重建队列并
    // 从起点重放 —— 也就是「退出大播放器之后播放被重置」。
    LaunchedEffect(autoPlayIndex, state.tracks) {
        val index = autoPlayIndex ?: return@LaunchedEffect
        // 自动播放不是封面点击，不触发 CoverFlight（避免打断可能仍在飞行的过渡）。
        model.autoPlayAt(index, animateCover = false)
    }

    // 多选模式下返回键退出多选
    BackHandler(enabled = state.selectionMode) { model.exitSelection() }

    val displayTracks = remember(state.tracks, state.sortType) { model.displayTracks() }
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

    // 「删除歌单 / 取消收藏」的统一确认入口。
    //
    // 两个入口（右键菜单、选项弹层）此前都是**一键即生效**：删掉自己建的歌单
    // 是不可恢复的服务端写操作，而菜单里它紧挨着「分享歌单」，一次误触就全没了。
    // 取消收藏可再次收藏，故按非破坏性呈现（确认键不用 error 色）。
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

    // 桌面端右键菜单：歌曲行动作集合与 SongOptionsSheet 完全对齐（一处动线两处入口）。
    val buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem> = { track, index ->
        songContextMenuItems(
            SongMenuActions(
                onPlay = { model.playAt(index) },
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

    // 桌面端歌单动作菜单：右键信息面板 + 宽屏左栏「更多」按钮共用同一份。
    // 本地虚拟歌单没有服务端实体，分享 / 收藏 / 删除链接与接口都无效，不出菜单。
    val playlistMenu: List<CpContextMenuItem>? = if (isLocalPlaylist) null else buildList {
        add(CpContextMenuItem(s.library.playAll, Icons.Filled.PlayArrow, onClick = { model.playAll() }))
        add(CpContextMenuItem(s.library.addToQueue, Icons.Filled.QueueMusic, onClick = { model.queueAll() }))
        add(CpContextMenuItem(s.library.downloadAll, Icons.Filled.Download, onClick = { AppModel.downloadTracks(displayTracks) }))
        add(CpContextMenuSeparator)
        add(CpContextMenuItem(s.library.sharePlaylist, Icons.Filled.Share, onClick = {
            shareText(playlistShareText(playlist.id, summary.name))
        }))
        // 与 PlaylistOptionsSheet 的收藏 / 删除可见性规则保持一致：
        // 非 owner 才有收藏，owner 才有删除。
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

    // 多选工具条的「从歌单移除」：同样是服务端写操作，移出去就得重新搜回来，先确认。
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

    // 桌面窗口标题栏的标题（内嵌成双栏详情栏时由宿主发布，见 LocalEmbeddedInPane）。
    if (!embedded) cp.player.app.ui.util.DesktopRouteTitle(summary.name)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 走 CpBreakpoints 而不是内联 840：断点只此一份，改一次处处生效。
        // ⚠️ 也不能读 LocalIsExpanded —— 本页是 push 出去的路由页，与提供它的
        // MainScreen 是兄弟节点，读到的永远是默认 false（宽窗口上会错判成窄屏）。
        val isWide = cp.player.app.ui.component.CpBreakpoints.isExpanded(maxWidth)
        // 桌面端（窗口 chrome 接管）不自绘返回键：返回入口统一在自绘标题栏上，
        // 否则同一屏会出现两个返回键。判据是 LocalWindowChromeActive 而不是平台，理由见它的 KDoc。
        val canShowInlineBack = isWide && !embedded &&
            !cp.player.app.ui.component.LocalWindowChromeActive.current
        if (isWide) {
            WideLayout(
                model = model,
                state = state,
                summary = summary,
                playlist = playlist,
                displayTracks = displayTracks,
                trackCount = trackCount,
                durationStr = durationStr,
                currentTrackId = currentTrackId,
                isOwner = isOwner,
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
                trackCount = trackCount,
                durationStr = durationStr,
                currentTrackId = currentTrackId,
                isOwner = isOwner,
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
            onDelete = if (isOwner) confirmDeletePlaylist else null,
            onShare = if (isLocalPlaylist) null else {
                { shareText("「${summary.name}」 https://music.163.com/#/playlist?id=${playlist.id}") }
            },
            coverUrl = summary.coverUrl,
            isFavorite = playlistFavorite,
            // 本地生成的虚拟歌单没有服务端实体，收藏接口对负数 id 无效，故不展示
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
            // 点击时对当前列表重新求值索引，避免弹层组合时固化过期 index
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
            coverUrl = track.coverUrl,
        )
    }

    // 多选 / 单曲：加入歌单
    addToPlaylistIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = { addToPlaylistIds = null })
    }

    // 添加歌曲：来源选项（移植旧项目"添加歌曲"弹层）
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

    // 从歌单导入：源歌单选择器（排除当前歌单）
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

    // 从播放队列添加：队列歌曲多选
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

    // INFO 弹窗（解析收敛在 MusicRepository.getSongDetailInfo，UI 只拿强类型结果）
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
                            // 整段进剪贴板 ⇒ 逐行都是文案。`extra` 收「有值才出现」的那些行
                            // （发行时间 / 评论数 / MV / 码率 / 付费类型），调用方只管判断有没有值。
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
                                        // MV ID 是标识符，不翻译。
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
                                                        // 「VIP」是音源侧的等级名，两端一致，不翻译。
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
                            // 「歌曲 ID」是标识符标签，两端都用 ID。
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

    // 二次确认框：与其余弹层同级，放在最后，避免被选项弹层盖住。
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
    trackCount: Int,
    durationStr: String,
    currentTrackId: String?,
    isOwner: Boolean,
    onBack: () -> Unit,
    onSongOptions: (TrackSummary) -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onAddSelectedToPlaylist: () -> Unit,
    onAddTracks: () -> Unit,
    /** 移除选中曲目；由宿主包一层二次确认后再落到 [model]。 */
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
                displayTracks = displayTracks,
                currentTrackId = currentTrackId,
                withHeader = false,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
                buildSongMenu = buildSongMenu,
            )
        }
    } else {
        AppScaffold(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(56.dp)
                            .coverFlightTarget(CoverFlight.TARGET_PLAYLIST_HEADER, 12.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 2.dp,
                    ) {
                        // 封面飞行落点：飞行未落位前先隐藏，落位时由飞行器淡出交还。
                        val hideCover = CoverFlight.isFlyingTo(CoverFlight.TARGET_PLAYLIST_HEADER)
                        if (!summary.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = summary.coverUrl.resized(200),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                                    .graphicsLayer { alpha = if (hideCover) 0f else 1f },
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize()
                                    .graphicsLayer { alpha = if (hideCover) 0f else 1f },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = summary.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = s.library.trackCountLabel(trackCount, durationStr),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
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
                displayTracks = displayTracks,
                currentTrackId = currentTrackId,
                withHeader = true,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
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
    trackCount: Int,
    durationStr: String,
    currentTrackId: String?,
    isOwner: Boolean,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onSongOptions: (TrackSummary) -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onAddSelectedToPlaylist: () -> Unit,
    onAddTracks: () -> Unit,
    /** 移除选中曲目；由宿主包一层二次确认后再落到 [model]。 */
    onRemoveSelected: (List<String>) -> Unit,
    /** 左栏信息面板的右键菜单（桌面端）；null 时不启用。 */
    playlistMenu: List<CpContextMenuItem>?,
    buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem>,
) {
    val s = cpStrings()
    // 宽屏左栏的排序锚定菜单：排序方式只在这里切换，不再借道底部弹层；
    // 「更多」按钮承载歌单级动作（分享 / 收藏 / 删除），两者职责分离不再冲突。
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
            // 左侧：歌单信息面板（整块右键可弹歌单菜单，见 playlistMenu）。
            //
            // 「返回上一级」只并进**右键**菜单，不进右边那个锚定的「更多」菜单 ——
            // 后者是歌单动作（分享 / 收藏 / 删除），混一个导航项进去会让人找不到重点。
            // 判据与动作与标题栏返回键同链，见 rememberBackContextMenuItem。
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
            Surface(
                modifier = Modifier.size(176.dp)
                    .coverFlightTarget(CoverFlight.TARGET_PLAYLIST_HEADER, 24.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
            ) {
                // 封面飞行落点：飞行未落位前先隐藏，落位时由飞行器淡出交还。
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
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { alpha = if (hideCover) 0f else 1f },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = summary.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.library.playlistCountLabel(trackCount, durationStr),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            PlaylistHeader(
                onPlayAll = { model.playAll() },
                onShuffle = { model.playShuffle() },
                onAdd = onAddTracks,
                onSort = onOpenPlaylistSheet,
                sortMenuItems = sortMenuItems,
                onDownloadAll = { AppModel.downloadTracks(displayTracks) },
            )
            // 宽屏没有顶栏「更多」按钮（那套 Scaffold 只在窄屏布局里）。
            // 桌面端点击弹出歌单动作锚定菜单（与右键同一份 items）；非桌面（平板宽屏）
            // 回落到底部弹层。排序按钮已经分流了排序职责，这里不再与它重复。
            var moreMenuExpanded by remember { mutableStateOf(false) }
            val windowChromeActive = cp.player.app.ui.component.LocalWindowChromeActive.current
            Spacer(Modifier.height(10.dp))
            CpAnchoredMenu(
                expanded = moreMenuExpanded,
                onDismiss = { moreMenuExpanded = false },
                items = playlistMenu,
            ) {
                Surface(
                    onClick = {
                        if (!playlistMenu.isNullOrEmpty() && windowChromeActive) {
                            moreMenuExpanded = true
                        } else {
                            onOpenPlaylistSheet()
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = s.library.moreOptions,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.player.more,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                    }
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
                displayTracks = displayTracks,
                currentTrackId = currentTrackId,
                withHeader = false,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
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

// ============ 歌曲列表 ============

@Composable
private fun TrackList(
    model: PlaylistDetailScreenModel,
    state: PlaylistDetailUiState,
    displayTracks: List<TrackSummary>,
    currentTrackId: String?,
    withHeader: Boolean,
    onSongOptions: (TrackSummary) -> Unit,
    onSortClick: () -> Unit,
    onAddTracks: () -> Unit,
    buildSongMenu: @Composable (TrackSummary, Int) -> List<CpContextMenuItem>,
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 0.dp,
) {
    val s = cpStrings()
    when {
        state.loading && displayTracks.isEmpty() -> {
              Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  cp.player.app.ui.component.CpLoadingIndicator(Modifier.size(40.dp))
              }
        }
        state.error != null && displayTracks.isEmpty() -> {
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
                    // force：加载失败时 loadedPlaylistId 已置位，重试必须越过守卫。
                    TextButton(onClick = { state.summary?.let { model.load(it, force = true) } }) {
                        Text(s.library.retry)
                    }
                }
            }
        }
        else -> LazyScrollColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = topContentPadding + 8.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (withHeader) {
                item(key = "__header__") {
                    PlaylistHeader(
                        onPlayAll = { model.playAll() },
                        onShuffle = { model.playShuffle() },
                        onAdd = onAddTracks,
                        onSort = onSortClick,
                        onDownloadAll = { AppModel.downloadTracks(displayTracks) },
                    )
                }
            }
            itemsIndexed(items = displayTracks, key = { _, track -> track.id }) { index, track ->
                // 分页预取：接近末尾时加载下一页（存在分页错误时不自动重触发，由底部重试项接管）
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
                    // 桌面端右键菜单；多选模式下 SongItem 内部会忽略（长按语义冲突）。
                    contextMenu = if (!state.selectionMode) buildSongMenu(track, index) else null,
                    // 进入/退出多选、增删歌曲都会改变行的位置，这里给位移动画。
                    modifier = Modifier.animateItem(),
                    onClick = {
                        if (state.selectionMode) model.toggleSelection(track.id)
                        else model.playAt(index)
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
                        Text(
                            s.library.playlistEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

// ============ 歌单头部按钮区 ============

@Composable
private fun PlaylistHeader(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAdd: () -> Unit,
    onSort: () -> Unit,
    onDownloadAll: () -> Unit,
    /**
     * 排序锚定菜单项（桌面端）。非空时排序按钮点击直接在按钮下弹出排序菜单；
     * 为 null（窄屏布局）时保持原行为：打开歌单选项弹层。
     */
    sortMenuItems: List<CpContextMenuItem>? = null,
) {
    val s = cpStrings()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // 第一行：播放 + 随机
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = onPlayAll,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = s.library.play,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.library.play,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Surface(
                onClick = onShuffle,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = s.library.shuffle,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.library.shuffle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 第二行：添加 + 排序
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = onAdd,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1.2f).height(46.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = s.library.add,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.library.add,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            var sortMenuExpanded by remember { mutableStateOf(false) }
            val windowChromeActive = cp.player.app.ui.component.LocalWindowChromeActive.current
            CpAnchoredMenu(
                expanded = sortMenuExpanded,
                onDismiss = { sortMenuExpanded = false },
                items = sortMenuItems,
                modifier = Modifier.weight(1.2f),
            ) {
                Surface(
                    onClick = {
                        if (!sortMenuItems.isNullOrEmpty() && windowChromeActive) {
                            sortMenuExpanded = true
                        } else {
                            onSort()
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = s.library.sort,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.library.sort,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 第三行：全部下载
        Surface(
            onClick = onDownloadAll,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = s.library.downloadAll,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    s.library.downloadAll,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
