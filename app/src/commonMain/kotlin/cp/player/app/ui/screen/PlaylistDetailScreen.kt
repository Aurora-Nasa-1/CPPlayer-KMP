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
import cp.player.core.music.MusicSourceFromApi
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cp.player.core.util.localDateTimeOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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

/** INFO 弹窗解析结果（来自 getSongDetail 顶层 songs[0] 与 privileges[0]；字段缺失时为 null，弹窗不显示该行）。 */
private data class SongDetailInfo(
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val publishTimeMs: Long? = null,
    val commentCount: Long? = null,
    val mvId: Long? = null,
    val maxBitrate: Int? = null,
    val fee: Int? = null,
    val songId: String,
)

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
        else UiEvents.notify("仅歌单创建者可添加歌曲")
    }

    val togglePlaylistFavorite: () -> Unit = {
        val target = !playlistFavorite
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { AppModel.api.subscribePlaylist(playlist.id, if (target) 1 else 2) }.isSuccess
            }
            if (ok) {
                playlistFavorite = target
                UiEvents.notify(if (target) "已收藏歌单" else "已取消收藏")
            } else {
                UiEvents.notify("操作失败")
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
            title = if (isOwner) "删除歌单" else "取消收藏",
            message = if (isOwner) {
                "确定删除「${summary.name}」吗？删除后无法恢复。"
            } else {
                "确定取消收藏「${summary.name}」吗？之后仍可重新收藏。"
            },
            confirmLabel = if (isOwner) "删除" else "取消收藏",
            destructive = isOwner,
            onConfirm = { model.deleteOrUnsubscribe { navigator.popOrNotify() } },
        )
    }

    // 桌面端右键菜单：歌曲行动作集合与 SongOptionsSheet 完全对齐（一处动线两处入口）。
    val buildSongMenu: (TrackSummary, Int) -> List<CpContextMenuItem> = { track, index ->
        songContextMenuItems(
            SongMenuActions(
                onPlay = { model.playAt(index) },
                isFavorite = model.isLiked(track.id),
                onToggleFavorite = { model.toggleLike(track) },
                onAddToQueue = {
                    scope.launch {
                        AppModel.playback.addToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                        UiEvents.notify("已加入播放队列")
                    }
                },
                onPlayNext = {
                    scope.launch {
                        AppModel.playback.addNextToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                        UiEvents.notify("将在下一首播放")
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
        add(CpContextMenuItem("播放全部", Icons.Filled.PlayArrow, onClick = { model.playAll() }))
        add(CpContextMenuItem("加入队列", Icons.Filled.QueueMusic, onClick = { model.queueAll() }))
        add(CpContextMenuItem("全部下载", Icons.Filled.Download, onClick = { AppModel.downloadTracks(displayTracks) }))
        add(CpContextMenuSeparator)
        add(CpContextMenuItem("分享歌单", Icons.Filled.Share, onClick = {
            shareText(playlistShareText(playlist.id, summary.name))
        }))
        // 与 PlaylistOptionsSheet 的收藏 / 删除可见性规则保持一致：
        // 非 owner 才有收藏，owner 才有删除。
        if (!isOwner) {
            add(
                CpContextMenuItem(
                    if (playlistFavorite) "取消收藏" else "收藏歌单",
                    if (playlistFavorite) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd,
                    onClick = togglePlaylistFavorite,
                )
            )
        }
        if (isOwner) {
            add(
                CpContextMenuItem(
                    "删除歌单", Icons.Filled.Delete,
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
                title = "从歌单移除",
                message = "确定从「${summary.name}」移除选中的 ${ids.size} 首歌曲吗？",
                confirmLabel = "移除",
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
                    UiEvents.notify("已加入播放队列")
                }
            },
            onPlayNext = {
                scope.launch {
                    AppModel.playback.addNextToQueue("${AppModel.activeProviderId()}://song/${track.id}")
                    UiEvents.notify("将在下一首播放")
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
            title = "从歌单导入",
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
                    val page = MusicSourceFromApi.getPlaylistTracks(AppModel.api, source.id, limit = 300, offset = 0)
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
            sourceName = "正在播放",
            initialSongs = queueTracks,
            onDismiss = { showQueueSelection = false },
            onAddSelected = { tracks ->
                model.addTracks(tracks)
                showQueueSelection = false
            },
        )
    }

    // INFO 弹窗（getSongDetail 内联解析）
    showInfoTarget?.let { track ->
        var info by remember(track.id) { mutableStateOf<SongDetailInfo?>(null) }
        LaunchedEffect(track.id) {
            info = withContext(Dispatchers.IO) {
                runCatching {
                    val root = AppModel.api.getSongDetail(listOf(track.id))
                    val songs = (root as? JsonObject)?.get("songs") as? JsonArray
                    val first = songs?.firstOrNull() as? JsonObject ?: return@runCatching null
                    val name = (first["name"] as? JsonPrimitive)?.contentOrNull ?: track.name
                    val artist = (first["ar"] as? JsonArray)
                        ?.mapNotNull {
                            ((it as? JsonObject)?.get("name") as? JsonPrimitive)
                                ?.contentOrNull?.takeIf(String::isNotBlank)
                        }
                        ?.joinToString("/") ?: track.artist
                    val album = ((first["al"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: ""
                    val dt = (first["dt"] as? JsonPrimitive)?.longOrNull ?: track.durationMs
                    // 可选字段：缺失时为 null，弹窗不显示对应行
                    val publishTime = (first["publishTime"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                    val commentCount = (first["commentCount"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                    val mvId = (first["mv"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
                    val privilege = ((root as JsonObject)["privileges"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val maxbr = (privilege?.get("maxbr") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                    val fee = (privilege?.get("fee") as? JsonPrimitive)?.intOrNull
                    SongDetailInfo(
                        name = name,
                        artist = artist,
                        album = album,
                        durationMs = dt,
                        publishTimeMs = publishTime,
                        commentCount = commentCount,
                        mvId = mvId,
                        maxBitrate = maxbr,
                        fee = fee,
                        songId = track.id,
                    )
                }.getOrNull()
            }
            if (info == null) {
                UiEvents.notify("获取歌曲信息失败")
                showInfoTarget = null
            }
        }
        AlertDialog(
            onDismissRequest = { showInfoTarget = null },
            title = { Text("歌曲详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                            append("歌曲：").append(current.name)
                            append("\n歌手：").append(current.artist)
                            append("\n专辑：").append(current.album.ifBlank { "未知专辑" })
                            append("\n时长：").append(formatTimeMs(current.durationMs))
                            current.publishTimeMs?.let { append("\n发行时间：").append(formatPublishDate(it)) }
                            current.commentCount?.let { append("\n评论数：").append(it) }
                            current.mvId?.let { append("\nMV ID：").append(it) }
                            current.maxBitrate?.let { append("\n最高码率：").append(it / 1000).append("kbps") }
                            current.fee?.let {
                                append("\n付费类型：").append(
                                    when (it) {
                                        0 -> "免费"
                                        1 -> "VIP"
                                        4 -> "购买"
                                        8 -> "低音质免费"
                                        else -> "未知"
                                    }
                                )
                            }
                            append("\n歌曲 ID：").append(current.songId)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoTarget = null }) { Text("关闭") }
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
    buildSongMenu: (TrackSummary, Int) -> List<CpContextMenuItem>,
) {
    if (state.selectionMode) {
        AppScaffold(
            title = "已选 ${state.selectedIds.size} 首",
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            navigationIcon = {
                IconButton(onClick = { model.exitSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = "退出多选")
                }
            },
            topBarActions = buildList {
                add(TopBarAction(icon = { Icon(Icons.Filled.SelectAll, contentDescription = "全选") }, onClick = { model.selectAll() }))
                add(
                    TopBarAction(
                        icon = { Icon(Icons.Filled.QueueMusic, contentDescription = "加入队列") },
                        onClick = {
                            model.queueSelected()
                            model.exitSelection()
                        },
                    )
                )
                add(
                    TopBarAction(
                        icon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = "加入歌单") },
                        onClick = onAddSelectedToPlaylist,
                    )
                )
                if (isOwner) {
                    add(
                        TopBarAction(
                            icon = { Icon(Icons.Filled.Delete, contentDescription = "从歌单移除") },
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
                            text = "$trackCount 首歌曲 • $durationStr",
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
                    icon = { Icon(Icons.Filled.MoreVert, contentDescription = "更多选项") },
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
    buildSongMenu: (TrackSummary, Int) -> List<CpContextMenuItem>,
) {
    // 宽屏左栏的排序锚定菜单：排序方式只在这里切换，不再借道底部弹层；
    // 「更多」按钮承载歌单级动作（分享 / 收藏 / 删除），两者职责分离不再冲突。
    val sortMenuItems = listOf(
        CpContextMenuItem(
            "默认顺序", Icons.AutoMirrored.Filled.List,
            onClick = { model.setSort(PlaylistSortType.DEFAULT) },
            isSelected = state.sortType == PlaylistSortType.DEFAULT,
        ),
        CpContextMenuItem(
            "按名称", Icons.Filled.SortByAlpha,
            onClick = { model.setSort(PlaylistSortType.NAME) },
            isSelected = state.sortType == PlaylistSortType.NAME,
        ),
        CpContextMenuItem(
            "按歌手", Icons.Filled.Person,
            onClick = { model.setSort(PlaylistSortType.ARTIST) },
            isSelected = state.sortType == PlaylistSortType.ARTIST,
        ),
    )
    CpTwoPane(
        rail = { railModifier ->
            // 左侧：歌单信息面板（整块右键可弹歌单菜单，见 playlistMenu）
            CpContextMenu(items = playlistMenu, modifier = railModifier) {
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
                text = "$trackCount 首 • $durationStr",
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
                            contentDescription = "更多选项",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "更多",
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
                            text = "已选 ${state.selectedIds.size} 首",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { model.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                        }
                        IconButton(
                            onClick = {
                                model.queueSelected()
                                model.exitSelection()
                            },
                        ) {
                            Icon(Icons.Filled.QueueMusic, contentDescription = "加入队列")
                        }
                        IconButton(onClick = onAddSelectedToPlaylist) {
                            Icon(Icons.Filled.PlaylistAdd, contentDescription = "加入歌单")
                        }
                        if (isOwner) {
                            IconButton(onClick = { onRemoveSelected(state.selectedIds.toList()) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "从歌单移除")
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
    buildSongMenu: (TrackSummary, Int) -> List<CpContextMenuItem>,
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 0.dp,
) {
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
                        Text("重试")
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
                            "歌单暂无歌曲",
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
                                "加载失败，点击重试",
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
                        contentDescription = "播放",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "播放",
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
                        contentDescription = "随机",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "随机",
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
                        contentDescription = "添加",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "添加",
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
                            contentDescription = "排序",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "排序",
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
                    contentDescription = "全部下载",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "全部下载",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
