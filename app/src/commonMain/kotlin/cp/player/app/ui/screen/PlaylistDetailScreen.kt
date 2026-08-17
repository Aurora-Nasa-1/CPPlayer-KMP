package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.AddSongsOptionsSheet
import cp.player.app.ui.component.AppScaffold
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.PlaylistPickerSheet
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.SourceSongsSelectionSheet
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.app.ui.model.PlaylistDetailUiState
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.kmp.BackendResult
import cp.player.kmp.music.CPMediaId
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

class PlaylistDetailScreen(val playlist: PlaylistSummary) : Screen {
    @Composable
    override fun Content() {
        PlaylistDetailContent(playlist, rememberScreenModel { PlaylistDetailScreenModel() })
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

/** 将发行时间毫秒时间戳格式化为 yyyy-MM-dd（纯 Kotlin 实现，兼容各目标平台）。 */
private fun formatPublishDate(ms: Long): String {
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%04d-%02d-%02d".format(dt.year, dt.monthNumber, dt.dayOfMonth)
}

@Composable
private fun PlaylistDetailContent(playlist: PlaylistSummary, model: PlaylistDetailScreenModel) {
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

    LaunchedEffect(playlist.id) { model.load(playlist) }

    // 多选模式下返回键退出多选
    BackHandler(enabled = state.selectionMode) { model.exitSelection() }

    val displayTracks = remember(state.tracks, state.sortType) { model.displayTracks() }
    val totalDurationMs = remember(state.tracks) { state.tracks.sumOf { it.durationMs } }
    val summary = state.summary ?: playlist
    val isOwner = model.isOwner()
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp
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
                onBack = { navigator.pop() },
                onSongOptions = { optionsTarget = it },
                onOpenPlaylistSheet = { showPlaylistSheet = true },
                onAddSelectedToPlaylist = { addToPlaylistIds = state.selectedIds.toList() },
                onAddTracks = openAddSongs,
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
                onBack = { navigator.pop() },
                onSongOptions = { optionsTarget = it },
                onOpenPlaylistSheet = { showPlaylistSheet = true },
                onAddSelectedToPlaylist = { addToPlaylistIds = state.selectedIds.toList() },
                onAddTracks = openAddSongs,
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
            onDelete = if (isOwner) {
                { model.deleteOrUnsubscribe { navigator.pop() } }
            } else null,
            onShare = {
                shareText("「${summary.name}」 https://music.163.com/#/playlist?id=${playlist.id}")
            },
            coverUrl = summary.coverUrl,
            isFavorite = playlistFavorite,
            onToggleFavorite = if (!isOwner) togglePlaylistFavorite else null,
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
                        CircularProgressIndicator(Modifier.size(28.dp))
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
                            onClick = { model.removeTracks(state.selectedIds.toList()) },
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
            )
        }
    } else {
        AppScaffold(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 2.dp,
                    ) {
                        if (!summary.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = summary.coverUrl.resized(200),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
    onBack: () -> Unit,
    onSongOptions: (TrackSummary) -> Unit,
    onOpenPlaylistSheet: () -> Unit,
    onAddSelectedToPlaylist: () -> Unit,
    onAddTracks: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // 左侧：歌单信息面板
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.size(200.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
            ) {
                if (!summary.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = summary.coverUrl.resized(600),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(20.dp))
            PlaylistHeader(
                onPlayAll = { model.playAll() },
                onShuffle = { model.playShuffle() },
                onAdd = onAddTracks,
                onSort = onOpenPlaylistSheet,
                onDownloadAll = { AppModel.downloadTracks(displayTracks) },
            )
        }

        // 右侧：歌曲列表
        Box(Modifier.weight(1f).fillMaxHeight()) {
            TrackList(
                model = model,
                state = state,
                displayTracks = displayTracks,
                currentTrackId = currentTrackId,
                withHeader = false,
                onSongOptions = onSongOptions,
                onSortClick = onOpenPlaylistSheet,
                onAddTracks = onAddTracks,
                modifier = Modifier.widthIn(max = 900.dp).align(Alignment.TopCenter),
                topContentPadding = 72.dp,
            )
            // 顶部导航行：返回 + 多选动作
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                if (state.selectionMode) {
                    Spacer(Modifier.width(12.dp))
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
                        IconButton(onClick = { model.removeTracks(state.selectedIds.toList()) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "从歌单移除")
                        }
                    }
                }
            }
        }
    }
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
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 0.dp,
) {
    when {
        state.loading && displayTracks.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
                    TextButton(onClick = { state.summary?.let { model.load(it) } }) {
                        Text("重试")
                    }
                }
            }
        }
        else -> LazyColumn(
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
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
            } else if (state.loadMoreError != null) {
                item(key = "__load_more_error__") {
                    Surface(
                        onClick = {
                            model.clearLoadMoreError()
                            model.loadMore()
                        },
                        shape = RoundedCornerShape(16.dp),
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
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // 第一行：播放 + 随机
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = onPlayAll,
                shape = RoundedCornerShape(18.dp),
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
                shape = RoundedCornerShape(18.dp),
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
                shape = RoundedCornerShape(16.dp),
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
            Surface(
                onClick = onSort,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1.2f).height(46.dp),
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

        Spacer(Modifier.height(10.dp))

        // 第三行：全部下载
        Surface(
            onClick = onDownloadAll,
            shape = RoundedCornerShape(16.dp),
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
