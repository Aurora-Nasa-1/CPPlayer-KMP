package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberDirectoryPicker
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AddToLocalPlaylistDialog
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConfirmDialog
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.local.LocalAlbum
import cp.player.core.local.LocalArtist
import cp.player.core.local.LocalFolderNode
import cp.player.core.local.LocalFolderTree
import cp.player.core.local.LocalLibraryAggregator
import cp.player.core.local.LocalLibrarySort
import cp.player.core.local.LocalPlaylist
import cp.player.core.local.LocalScanSettings
import cp.player.core.media.LocalMediaItem
import cp.player.core.media.LocalMediaOrigin
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

/**
 * 本地音乐库独立页面。
 *
 * 作为一等公民页面，支持手机端路由压栈（push）以及桌面端双栏面板嵌入（embedded = true）。
 */
class LocalLibraryScreen(
    private val embedded: Boolean = false,
    private val initialTab: Int = 0,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = rememberScreenModel { DownloadsScreenModel() }
        val state by model.state.collectAsState()
        val s = cpStrings()

        androidx.compose.runtime.LaunchedEffect(initialTab) {
            if (initialTab != 0) model.setLibraryTab(initialTab)
        }

        CpRouteScaffold(
            title = s.downloads.tabLocalLibrary,
            onBack = { navigator?.popOrNotify() },
            embedded = embedded,
        ) { pageModifier ->
            Box(pageModifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LocalLibraryTab(state = state, model = model, strings = s)
                }
            }
        }
    }
}

/**
 * 本地曲库浏览面板（下载页 Tab 3 / 本地音乐独立页核心）。
 *
 * 子页结构：
 * 0: 全部歌曲 (`songs`)
 * 1: 专辑 (`albums`)
 * 2: 艺术家 (`artists`)
 * 3: 本地喜欢 (`pureLocalFavorites`，仅展示纯本地收藏，与云端喜欢隔离)
 * 4: 本地歌单 (`localPlaylists`，自定义本地歌单体系)
 * 5: 文件夹 (`folders`)
 */
@Composable
internal fun LocalLibraryTab(
    state: DownloadsUiState,
    model: DownloadsScreenModel,
    strings: CpStrings,
) {
    val s = strings.downloads
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmState()
    val pickFolder = rememberDirectoryPicker { path ->
        if (path != null) model.importFolder(path, strings)
    }

    var openedAlbum by remember { mutableStateOf<LocalAlbum?>(null) }
    var openedArtist by remember { mutableStateOf<LocalArtist?>(null) }
    var openedFolder by remember { mutableStateOf<LocalFolderNode?>(null) }
    var openedLocalPlaylist by remember { mutableStateOf<LocalPlaylist?>(null) }

    // 交互弹窗状态
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var renamePlaylistTarget by remember { mutableStateOf<LocalPlaylist?>(null) }
    var addToCloudPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var addToLocalPlaylistPath by remember { mutableStateOf<String?>(null) }
    var deleteTargetSong by remember { mutableStateOf<LocalMediaItem?>(null) }
    var showInfoTrack by remember { mutableStateOf<TrackSummary?>(null) }

    val likedIds by AppModel.playback.likedIds.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentPlayingTrackId = playbackState.currentTrack?.id

    // 聚合过滤与搜索
    val query = state.libraryQuery
    val filter = state.libraryFilter
    val base = remember(state.localItems, filter) {
        LocalLibraryAggregator.applyFilter(state.localAudioItems, filter)
    }
    val songs = remember(base, query, state.songSort, state.songSortDescending) {
        LocalLibraryAggregator.sortSongs(
            LocalLibraryAggregator.search(base, query),
            state.songSort,
            state.songSortDescending,
        )
    }
    val albums = remember(base, query, state.gridSort) {
        LocalLibraryAggregator.albums(base, state.gridSort, query)
    }
    val artists = remember(base, query, state.gridSort) {
        LocalLibraryAggregator.artists(base, state.gridSort, query)
    }
    // 纯本地喜欢：仅保留纯本地导入且在 favoritePaths 中的曲目
    val pureLocalFavorites = remember(base, state.favoritePaths, state.songSort, state.songSortDescending) {
        val pure = base.filter {
            it.path in state.favoritePaths &&
                (it.source == LocalMediaOrigin.IMPORTED || it.originalMediaId.isNullOrBlank())
        }
        LocalLibraryAggregator.sortSongs(
            pure,
            state.songSort,
            state.songSortDescending,
        )
    }
    val folders = remember(base) { LocalFolderTree.build(base) }

    // 页面内二级视图：专辑 / 艺术家 / 文件夹 / 本地歌单详情
    val album = openedAlbum
    if (album != null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            LocalAlbumDetail(
                album = album,
                items = state.localAudioItems,
                model = model,
                strings = strings,
                onBack = { openedAlbum = null },
                currentPlayingId = currentPlayingTrackId,
                likedIds = likedIds,
                favoritePaths = state.favoritePaths,
                onToggleCloudFav = { cloudId ->
                    scope.launch {
                        val cloudFavId = runCatching { cp.player.core.music.CPMediaId.parse(cloudId).resourceId }.getOrDefault(cloudId)
                        val target = cloudFavId !in likedIds
                        AppModel.playback.toggleFavoriteFor(cloudId)
                        UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                    }
                },
                onToggleLocalFav = { path ->
                    val now = AppModel.localMedia.favorites.toggle(path)
                    UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                },
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onDeleteSong = { deleteTargetSong = it },
                onShowInfo = { showInfoTrack = it },
            )
        }
        return
    }

    val artist = openedArtist
    if (artist != null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            LocalArtistDetail(
                artist = artist,
                items = state.localAudioItems,
                model = model,
                strings = strings,
                onBack = { openedArtist = null },
                currentPlayingId = currentPlayingTrackId,
                likedIds = likedIds,
                favoritePaths = state.favoritePaths,
                onToggleCloudFav = { cloudId ->
                    scope.launch {
                        val cloudFavId = runCatching { cp.player.core.music.CPMediaId.parse(cloudId).resourceId }.getOrDefault(cloudId)
                        val target = cloudFavId !in likedIds
                        AppModel.playback.toggleFavoriteFor(cloudId)
                        UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                    }
                },
                onToggleLocalFav = { path ->
                    val now = AppModel.localMedia.favorites.toggle(path)
                    UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                },
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onDeleteSong = { deleteTargetSong = it },
                onShowInfo = { showInfoTrack = it },
            )
        }
        return
    }

    val openedFolderNode = openedFolder
    if (openedFolderNode != null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            LocalFolderDetail(
                node = openedFolderNode,
                items = base,
                model = model,
                strings = strings,
                onBack = { openedFolder = null },
                currentPlayingId = currentPlayingTrackId,
                likedIds = likedIds,
                favoritePaths = state.favoritePaths,
                onToggleCloudFav = { cloudId ->
                    scope.launch {
                        val cloudFavId = runCatching { cp.player.core.music.CPMediaId.parse(cloudId).resourceId }.getOrDefault(cloudId)
                        val target = cloudFavId !in likedIds
                        AppModel.playback.toggleFavoriteFor(cloudId)
                        UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                    }
                },
                onToggleLocalFav = { path ->
                    val now = AppModel.localMedia.favorites.toggle(path)
                    UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                },
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onDeleteSong = { deleteTargetSong = it },
                onShowInfo = { showInfoTrack = it },
            )
        }
        return
    }

    val currentPlaylist = openedLocalPlaylist
    if (currentPlaylist != null) {
        val latestPlaylist = state.localPlaylists.find { it.id == currentPlaylist.id } ?: currentPlaylist
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            LocalPlaylistDetail(
                playlist = latestPlaylist,
                items = state.localAudioItems,
                model = model,
                strings = strings,
                onBack = { openedLocalPlaylist = null },
                onRename = { renamePlaylistTarget = latestPlaylist },
                onDelete = {
                    confirm.request(
                        title = "删除本地歌单",
                        message = "确定要删除本地歌单《${latestPlaylist.name}》吗？歌单内的本地音频文件不会被删除。",
                        confirmLabel = "删除",
                        destructive = true,
                        onConfirm = {
                            model.deleteLocalPlaylist(latestPlaylist.id)
                            openedLocalPlaylist = null
                        },
                    )
                },
                onRemoveSong = { path ->
                    model.removeTrackFromLocalPlaylist(latestPlaylist.id, path)
                },
                currentPlayingId = currentPlayingTrackId,
                likedIds = likedIds,
                favoritePaths = state.favoritePaths,
                onToggleCloudFav = { cloudId ->
                    scope.launch {
                        val cloudFavId = runCatching { cp.player.core.music.CPMediaId.parse(cloudId).resourceId }.getOrDefault(cloudId)
                        val target = cloudFavId !in likedIds
                        AppModel.playback.toggleFavoriteFor(cloudId)
                        UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                    }
                },
                onToggleLocalFav = { path ->
                    val now = AppModel.localMedia.favorites.toggle(path)
                    UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                },
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onDeleteSong = { deleteTargetSong = it },
                onShowInfo = { showInfoTrack = it },
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LocalLibraryToolbar(
            state = state,
            model = model,
            strings = strings,
            onScan = { model.startScan(strings) },
            onImport = { pickFolder() },
        )

        if (state.localAudioItems.isNotEmpty() || query.isNotBlank()) {
            LocalLibrarySubTabs(
                selected = state.libraryTab,
                songCount = songs.size,
                albumCount = albums.size,
                artistCount = artists.size,
                favoriteCount = pureLocalFavorites.size,
                playlistCount = state.localPlaylists.size,
                folderCount = folders.size,
                strings = strings,
                onSelect = { model.setLibraryTab(it) },
            )
        }

        when {
            state.localItems.isEmpty() -> {
                StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(
                        title = s.emptyLocalTitle,
                        message = s.emptyLocalNote,
                        actionLabel = s.scanDevice,
                        onAction = { model.startScan(strings) },
                    )
                }
            }

            state.libraryTab == 3 && pureLocalFavorites.isEmpty() -> {
                StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(
                        title = "暂无本地喜欢歌曲",
                        message = "纯本地歌曲可在菜单中选择「喜欢」，将其收录至此列表",
                    )
                }
            }

            state.libraryTab == 4 && state.localPlaylists.isEmpty() -> {
                StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(
                        title = "暂无本地歌单",
                        message = "可创建自定义本地歌单，将不同本地歌曲分类归纳",
                        actionLabel = "新建本地歌单",
                        onAction = { showCreatePlaylistDialog = true },
                    )
                }
            }

            query.isNotBlank() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty() -> {
                StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(title = s.emptySearchTitle, message = "")
                }
            }

            else -> when (state.libraryTab) {
                1 -> AlbumGrid(
                    albums = albums,
                    model = model,
                    strings = strings,
                    onClick = { openedAlbum = it },
                )
                2 -> ArtistGrid(
                    artists = artists,
                    model = model,
                    strings = strings,
                    onClick = { openedArtist = it },
                )
                3 -> SongList(
                    title = "本地喜欢 · ${pureLocalFavorites.size}",
                    songs = pureLocalFavorites,
                    model = model,
                    strings = strings,
                    showSort = true,
                    state = state,
                    currentPlayingId = currentPlayingTrackId,
                    likedIds = likedIds,
                    favoritePaths = state.favoritePaths,
                    onToggleCloudFav = { cloudId ->
                        scope.launch {
                            val cloudFavId = runCatching { cp.player.core.music.CPMediaId.parse(cloudId).resourceId }.getOrDefault(cloudId)
                            val target = cloudFavId !in likedIds
                            AppModel.playback.toggleFavoriteFor(cloudId)
                            UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                        }
                    },
                    onToggleLocalFav = { path ->
                        val now = AppModel.localMedia.favorites.toggle(path)
                        UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                    },
                    onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                    onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                    onDeleteSong = { deleteTargetSong = it },
                    onShowInfo = { showInfoTrack = it },
                )
                4 -> LocalPlaylistGrid(
                    playlists = state.localPlaylists,
                    items = state.localAudioItems,
                    model = model,
                    strings = strings,
                    onCreatePlaylist = { showCreatePlaylistDialog = true },
                    onClick = { openedLocalPlaylist = it },
                    onRename = { renamePlaylistTarget = it },
                    onDelete = { pl ->
                        confirm.request(
                            title = "删除本地歌单",
                            message = "确定要删除本地歌单《${pl.name}》吗？",
                            confirmLabel = "删除",
                            destructive = true,
                            onConfirm = { model.deleteLocalPlaylist(pl.id) },
                        )
                    },
                )
                5 -> FolderTreeView(
                    nodes = folders,
                    items = base,
                    model = model,
                    strings = strings,
                    onOpenFolder = { openedFolder = it },
                )
                else -> SongList(
                    title = null,
                    songs = songs,
                    model = model,
                    strings = strings,
                    showSort = true,
                    state = state,
                    currentPlayingId = currentPlayingTrackId,
                    likedIds = likedIds,
                    favoritePaths = state.favoritePaths,
                    onToggleCloudFav = { cloudId ->
                        scope.launch {
                            val cloudFavId = runCatching { cp.player.core.music.CPMediaId.parse(cloudId).resourceId }.getOrDefault(cloudId)
                            val target = cloudFavId !in likedIds
                            AppModel.playback.toggleFavoriteFor(cloudId)
                            UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                        }
                    },
                    onToggleLocalFav = { path ->
                        val now = AppModel.localMedia.favorites.toggle(path)
                        UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                    },
                    onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                    onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                    onDeleteSong = { deleteTargetSong = it },
                    onShowInfo = { showInfoTrack = it },
                )
            }
        }
    }

    CpConfirmHost(confirm)

    if (showCreatePlaylistDialog) {
        CreateLocalPlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name ->
                showCreatePlaylistDialog = false
                model.createLocalPlaylist(name)
            },
        )
    }

    renamePlaylistTarget?.let { pl ->
        RenameLocalPlaylistDialog(
            currentName = pl.name,
            onDismiss = { renamePlaylistTarget = null },
            onConfirm = { newName ->
                renamePlaylistTarget = null
                model.renameLocalPlaylist(pl.id, newName)
            },
        )
    }

    addToCloudPlaylistTrack?.let { track ->
        val cloudId = track.originalMediaId ?: track.id
        AddToPlaylistSheet(
            trackIds = listOf(cloudId),
            onDismiss = { addToCloudPlaylistTrack = null },
        )
    }

    addToLocalPlaylistPath?.let { path ->
        AddToLocalPlaylistDialog(
            trackPaths = listOf(path),
            onDismiss = { addToLocalPlaylistPath = null },
        )
    }

    deleteTargetSong?.let { song ->
        CpConfirmDialog(
            title = "彻底删除本地文件",
            message = "确定要将《${song.title}》从磁盘彻底删除吗？此操作无法撤销。",
            confirmLabel = "彻底删除",
            destructive = true,
            onConfirm = {
                model.deleteLocalFile(song, strings)
                deleteTargetSong = null
            },
            onDismiss = { deleteTargetSong = null },
        )
    }

    showInfoTrack?.let { track ->
        SongDetailInfoDialog(
            track = track,
            onDismiss = { showInfoTrack = null },
        )
    }
}

// ============ 顶部工具行 ============

@Composable
private fun LocalLibraryToolbar(
    state: DownloadsUiState,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onScan: () -> Unit,
    onImport: () -> Unit,
) {
    val s = strings.downloads
    Column(
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = onScan,
                enabled = !state.scanning,
                modifier = Modifier.weight(1f),
            ) {
                if (state.scanning) {
                    CpLoadingIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    CpText(
                        state.scanProgress?.let { s.scanningFound(it.scanned) } ?: s.scanning,
                        maxLines = 1,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    CpText(s.scanDevice, maxLines = 1)
                }
            }
            FilledTonalButton(
                onClick = onImport,
                enabled = !state.importing,
                modifier = Modifier.weight(1f),
            ) {
                if (state.importing) {
                    CpLoadingIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    CpText(s.importing, maxLines = 1)
                } else {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    CpText(s.importFolder, maxLines = 1)
                }
            }
        }
        if (state.localItems.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.libraryQuery,
                    onValueChange = { model.setLibraryQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { CpText(s.librarySearchHint) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.libraryQuery.isNotEmpty()) {
                            IconButton(onClick = { model.setLibraryQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除")
                            }
                        }
                    },
                    singleLine = true,
                )
                LibraryFilterMenu(state = state, model = model, strings = strings)
            }
        }
    }
}

@Composable
private fun LibraryFilterMenu(
    state: DownloadsUiState,
    model: DownloadsScreenModel,
    strings: CpStrings,
) {
    var expanded by remember { mutableStateOf(false) }
    var showScanSettings by remember { mutableStateOf(false) }
    val filter = state.libraryFilter
    val badge = filter.activeCount + state.scanSettings.activeRuleCount

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.FilterList, contentDescription = "筛选与扫描设置")
            if (badge > 0) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .align(Alignment.TopEnd),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterToggle("只看无损音频", filter.losslessOnly) {
                model.toggleLibraryFilter { it.copy(losslessOnly = !it.losslessOnly) }
            }
            FilterToggle("只看重复曲目", filter.duplicatesOnly) {
                model.toggleLibraryFilter { it.copy(duplicatesOnly = !it.duplicatesOnly) }
            }
            FilterToggle("只看缺少封面", filter.missingCoverOnly) {
                model.toggleLibraryFilter { it.copy(missingCoverOnly = !it.missingCoverOnly) }
            }
            FilterToggle("只看标签残缺", filter.missingTagsOnly) {
                model.toggleLibraryFilter { it.copy(missingTagsOnly = !it.missingTagsOnly) }
            }
            if (!filter.isDefault) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { CpText("清除筛选") },
                    onClick = {
                        expanded = false
                        model.clearLibraryFilter()
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    CpText(
                        buildString {
                            append("扫描设置")
                            if (state.scanSettings.activeRuleCount > 0) {
                                append(" · ${state.scanSettings.activeRuleCount} 条规则")
                            }
                        },
                    )
                },
                onClick = {
                    expanded = false
                    showScanSettings = true
                },
            )
        }
    }

    if (showScanSettings) {
        ScanSettingsDialog(
            current = state.scanSettings,
            onDismiss = { showScanSettings = false },
            onConfirm = {
                model.updateScanSettings(it)
                showScanSettings = false
            },
        )
    }
}

@Composable
private fun FilterToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { CpText(label) },
        trailingIcon = {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        },
        onClick = onToggle,
    )
}

@Composable
private fun ScanSettingsDialog(
    current: LocalScanSettings,
    onDismiss: () -> Unit,
    onConfirm: (LocalScanSettings) -> Unit,
) {
    var minDuration by remember { mutableStateOf(current.minDurationSeconds.toString()) }
    var filterVideo by remember { mutableStateOf(current.filterVideoFiles) }
    var excludeText by remember { mutableStateOf(current.excludeFolders.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText("扫描设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CpText(
                    "改动会在下一次扫描时生效，并同步清理已从规则中排除的条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = minDuration,
                    onValueChange = { minDuration = it.filter(Char::isDigit).take(4) },
                    label = { CpText("最短时长（秒），0 表示不过滤") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filterVideo, onCheckedChange = { filterVideo = it })
                    Spacer(Modifier.width(4.dp))
                    CpText("扫描时跳过视频文件")
                }
                OutlinedTextField(
                    value = excludeText,
                    onValueChange = { excludeText = it },
                    label = { CpText("排除目录（每行一个）") },
                    placeholder = { CpText("/storage/emulated/0/Music/Recordings") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        LocalScanSettings(
                            minDurationSeconds = minDuration.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            filterVideoFiles = filterVideo,
                            excludeFolders = excludeText.split('\n')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                            includeOnlyFolders = current.includeOnlyFolders,
                        ),
                    )
                },
            ) { CpText("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText("取消") }
        },
    )
}

// ============ 子页 Tab ============

@Composable
private fun LocalLibrarySubTabs(
    selected: Int,
    songCount: Int,
    albumCount: Int,
    artistCount: Int,
    favoriteCount: Int,
    playlistCount: Int,
    folderCount: Int,
    strings: CpStrings,
    onSelect: (Int) -> Unit,
) {
    val s = strings.downloads
    val labels = listOf(
        s.tabSongs to songCount,
        s.tabAlbums to albumCount,
        s.tabArtists to artistCount,
        "本地喜欢" to favoriteCount,
        "本地歌单" to playlistCount,
        s.tabFolders to folderCount,
    )
    Row(
        modifier = Modifier
            .widthIn(max = CpSpacing.pageMaxWidth)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, (label, count) ->
            val text = if (count > 0) "$label $count" else label
            CpToggleChip(
                checked = selected == index,
                onCheckedChange = { if (it) onSelect(index) },
                label = text,
            )
        }
    }
}

// ============ 歌曲列表 ============

@Composable
private fun ColumnScope.SongList(
    title: String?,
    songs: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    showSort: Boolean,
    state: DownloadsUiState,
    currentPlayingId: String?,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onDeleteSong: (LocalMediaItem) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
) {
    val s = strings.downloads
    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        if (title != null || showSort) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CpText(
                    title ?: "${s.tabSongs} · ${songs.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (songs.isNotEmpty()) {
                    IconButton(onClick = { model.playAll(songs) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = s.playAll)
                    }
                    IconButton(onClick = { model.shuffleAll(songs) }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "随机播放")
                    }
                }
                if (showSort) {
                    SortMenu(
                        current = state.songSort,
                        descending = state.songSortDescending,
                        onSelect = { model.toggleSongSort(it) },
                    )
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            items(songs, key = { it.path }) { song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(songs, songs.indexOf(song)) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                )
            }
        }
    }
}

/**
 * 统一标准样式的本地歌曲条目。
 *
 * 接入标准 [SongItem] 及右键菜单 [songContextMenuItems]，
 * 区分已下载云端曲目（联动云端收藏与云端歌单）与纯本地曲目（本地喜欢与本地歌单），
 * 同时完整支持本地能力（在文件管理器中打开、移出曲库、彻底删除）。
 */
@Composable
private fun LocalSongRow(
    song: LocalMediaItem,
    isCurrentlyPlaying: Boolean,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onPlay: () -> Unit,
    onRemoveFromLocalPlaylist: (() -> Unit)? = null,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onDeleteSong: (LocalMediaItem) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val s = strings.downloads
    val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
    val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"

    val isDownloaded = song.source == LocalMediaOrigin.DOWNLOADED || !song.originalMediaId.isNullOrBlank()
    val cloudMediaId = song.originalMediaId
    val cloudFavId = cloudMediaId?.let { runCatching { cp.player.core.music.CPMediaId.parse(it).resourceId }.getOrDefault(it) }
    val isCloudFav = cloudFavId != null && cloudFavId in likedIds
    val isLocalFav = song.path in favoritePaths
    val isFav = if (isDownloaded && cloudMediaId != null) isCloudFav else isLocalFav

    val handlePlay: () -> Unit = {
        if (isVideo) {
            navigator?.push(VideoPlayerScreen(mediaId, title = song.title))
        } else {
            onPlay()
        }
    }

    val trackSummary = remember(song) {
        TrackSummary(
            id = mediaId,
            name = song.title,
            artist = song.artist ?: s.unknownArtist,
            album = song.album,
            coverUrl = song.coverUri,
            durationMs = song.durationMs,
            originalMediaId = song.originalMediaId,
        )
    }

    val subtitle = remember(song) {
        buildList {
            add(song.artist ?: s.unknownArtist)
            song.qualityLabel?.let { add(it) }
            if (song.durationMs > 0) add(formatDuration(song.durationMs))
        }.joinToString(" · ")
    }

    val menuActions = SongMenuActions(
        onPlay = handlePlay,
        isFavorite = isFav,
        onToggleFavorite = if (isDownloaded && cloudMediaId != null) {
            { onToggleCloudFav(cloudMediaId) }
        } else {
            { onToggleLocalFav(song.path) }
        },
        favoriteLabel = if (isDownloaded && cloudMediaId != null) {
            if (isCloudFav) "取消云端收藏" else "云端收藏"
        } else {
            if (isLocalFav) "取消喜欢" else "喜欢"
        },
        onAddToQueue = if (!isVideo) {
            {
                scope.launch {
                    AppModel.playback.addToQueue(mediaId)
                    UiEvents.notify("已加入播放队列")
                }
            }
        } else null,
        onPlayNext = if (!isVideo) {
            {
                scope.launch {
                    AppModel.playback.addNextToQueue(mediaId)
                    UiEvents.notify("将在下一首播放")
                }
            }
        } else null,
        isDownloaded = true,
        onAddToPlaylist = if (isDownloaded && cloudMediaId != null) {
            { onAddToCloudPlaylist(trackSummary) }
        } else null,
        onAddToLocalPlaylist = {
            onAddToLocalPlaylist(song.path)
        },
        onRemoveFromPlaylist = onRemoveFromLocalPlaylist,
        onOpenInFileManager = {
            cp.player.app.platform.openInFileManager(song.path)
        },
        onRemoveFromLibrary = {
            model.removeLocalItem(song, strings)
        },
        onDelete = {
            onDeleteSong(song)
        },
        deleteLabel = "彻底删除本地文件",
        onShowInfo = {
            onShowInfo(trackSummary)
        },
    )

    val menuItems = songContextMenuItems(menuActions)

    SongItem(
        track = trackSummary,
        onClick = handlePlay,
        isCurrentlyPlaying = isCurrentlyPlaying,
        contextMenu = menuItems,
        supportingText = subtitle,
    )
}

// ============ 本地歌单网格与详情 ============

@Composable
private fun ColumnScope.LocalPlaylistGrid(
    playlists: List<LocalPlaylist>,
    items: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onCreatePlaylist: () -> Unit,
    onClick: (LocalPlaylist) -> Unit,
    onRename: (LocalPlaylist) -> Unit,
    onDelete: (LocalPlaylist) -> Unit,
) {
    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CpText(
                "本地歌单 · ${playlists.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            FilledTonalButton(onClick = onCreatePlaylist) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                CpText("新建歌单")
            }
        }
        val itemsByPath = remember(items) { items.associateBy { it.path } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
                top = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(playlists, key = { it.id }) { pl ->
                val coverUri = remember(pl, itemsByPath) {
                    pl.trackPaths.firstNotNullOfOrNull { itemsByPath[it]?.coverUri }
                }
                val totalDurationMs = remember(pl, itemsByPath) {
                    pl.trackPaths.sumOf { itemsByPath[it]?.durationMs ?: 0L }
                }
                LocalPlaylistCell(
                    playlist = pl,
                    coverUri = coverUri,
                    totalDurationMs = totalDurationMs,
                    onClick = { onClick(pl) },
                    onPlay = {
                        val tracks = pl.trackPaths.mapNotNull { itemsByPath[it] }
                        if (tracks.isNotEmpty()) model.playAll(tracks)
                    },
                    onRename = { onRename(pl) },
                    onDelete = { onDelete(pl) },
                )
            }
        }
    }
}

@Composable
private fun LocalPlaylistCell(
    playlist: LocalPlaylist,
    coverUri: String?,
    totalDurationMs: Long,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            LocalCover(
                coverUri = coverUri,
                fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                CpText(
                    playlist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                CpText(
                    buildString {
                        append("${playlist.trackPaths.size} 首歌曲")
                        if (totalDurationMs > 0) append(" · ").append(formatDuration(totalDurationMs))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "歌单选项",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { CpText("播放歌单") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                        onClick = {
                            menuExpanded = false
                            onPlay()
                        },
                    )
                    DropdownMenuItem(
                        text = { CpText("重命名") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { CpText("删除歌单", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.LocalPlaylistDetail(
    playlist: LocalPlaylist,
    items: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onRemoveSong: (String) -> Unit,
    currentPlayingId: String?,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onDeleteSong: (LocalMediaItem) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
) {
    val itemsByPath = remember(items) { items.associateBy { it.path } }
    val tracks = remember(playlist.trackPaths, itemsByPath) {
        playlist.trackPaths.mapNotNull { itemsByPath[it] }
    }
    val totalDurationMs = remember(tracks) { tracks.sumOf { it.durationMs } }

    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        DetailHeader(
            title = playlist.name,
            subtitle = buildString {
                append("${tracks.size} 首歌曲")
                if (totalDurationMs > 0) append(" · ").append(formatDuration(totalDurationMs))
            },
            onBack = onBack,
            trailingActions = {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "重命名歌单")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除歌单", tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        if (tracks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(onClick = { model.playAll(tracks) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    CpText(strings.downloads.playAll)
                }
                FilledTonalButton(onClick = { model.shuffleAll(tracks) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    CpText("随机播放")
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                items(tracks, key = { it.path }) { song ->
                    val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                    val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                    LocalSongRow(
                        song = song,
                        isCurrentlyPlaying = currentPlayingId == mediaId,
                        likedIds = likedIds,
                        favoritePaths = favoritePaths,
                        model = model,
                        strings = strings,
                        onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
                        onRemoveFromLocalPlaylist = { onRemoveSong(song.path) },
                        onToggleCloudFav = onToggleCloudFav,
                        onToggleLocalFav = onToggleLocalFav,
                        onAddToCloudPlaylist = onAddToCloudPlaylist,
                        onAddToLocalPlaylist = onAddToLocalPlaylist,
                        onDeleteSong = onDeleteSong,
                        onShowInfo = onShowInfo,
                    )
                }
            }
        } else {
            StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                ContentState(
                    title = "此歌单暂无歌曲",
                    message = "可在本地音乐或其他歌曲菜单中选择「添加到本地歌单」",
                )
            }
        }
    }
}

// ============ 对话框：新建与重命名本地歌单 ============

@Composable
private fun CreateLocalPlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText("新建本地歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { CpText("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
                enabled = name.isNotBlank(),
            ) { CpText("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText("取消") }
        },
    )
}

@Composable
private fun RenameLocalPlaylistDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText("重命名歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { CpText("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                },
                enabled = name.isNotBlank() && name != currentName,
            ) { CpText("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText("取消") }
        },
    )
}

// ============ 专辑网格 ============

@Composable
private fun ColumnScope.AlbumGrid(
    albums: List<LocalAlbum>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onClick: (LocalAlbum) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        state = gridState,
        modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize().weight(1f),
        contentPadding = PaddingValues(
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
            bottom = 96.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCell(album = album, strings = strings, onClick = { onClick(album) })
        }
    }
}

@Composable
private fun AlbumCell(album: LocalAlbum, strings: CpStrings, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            LocalCover(
                coverUri = album.coverUri,
                fallbackIcon = Icons.Filled.Album,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(6.dp))
        CpText(
            album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        CpText(
            buildString {
                append(album.artist ?: strings.downloads.unknownArtist)
                append(" · ")
                append(strings.downloads.itemCount(album.songCount))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ============ 艺术家网格 ============

@Composable
private fun ColumnScope.ArtistGrid(
    artists: List<LocalArtist>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onClick: (LocalArtist) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize().weight(1f),
        contentPadding = PaddingValues(
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
            bottom = 96.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(artists, key = { it.name }) { artist ->
            ArtistCell(artist = artist, strings = strings, onClick = { onClick(artist) })
        }
    }
}

@Composable
private fun ArtistCell(artist: LocalArtist, strings: CpStrings, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            LocalCover(
                coverUri = artist.coverUri,
                fallbackIcon = Icons.Filled.Person,
                modifier = Modifier.fillMaxSize(),
                circular = true,
            )
        }
        Spacer(Modifier.height(6.dp))
        CpText(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        CpText(
            buildString {
                append(strings.downloads.itemCount(artist.songCount))
                if (artist.albumCount > 1) {
                    append(" · ")
                    append(strings.downloads.albumCount(artist.albumCount))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ============ 文件夹树 ============

@Composable
private fun ColumnScope.FolderTreeView(
    nodes: List<LocalFolderNode>,
    items: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onOpenFolder: (LocalFolderNode) -> Unit,
) {
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val visible = remember(nodes, expanded) { LocalFolderTree.visibleNodes(nodes, expanded) }

    LazyColumn(
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize().weight(1f),
        contentPadding = PaddingValues(
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
    ) {
        items(visible, key = { it.path }) { node ->
            FolderRow(
                node = node,
                isExpanded = node.path in expanded,
                strings = strings,
                onToggleExpand = {
                    expanded = if (node.path in expanded) expanded - node.path else expanded + node.path
                },
                onOpen = { model.playAll(LocalFolderTree.allSongs(items, node)) },
                onShowDetail = { onOpenFolder(node) },
            )
        }
    }
}

@Composable
private fun FolderRow(
    node: LocalFolderNode,
    isExpanded: Boolean,
    strings: CpStrings,
    onToggleExpand: () -> Unit,
    onOpen: () -> Unit,
    onShowDetail: () -> Unit,
) {
    val s = strings.downloads
    Surface(
        onClick = onShowDetail,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = (12 + (node.depth - 1) * 16).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.hasChildren) {
                IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(28.dp))
            }
            LocalCover(
                coverUri = node.coverUri,
                fallbackIcon = Icons.Filled.FolderOpen,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                CpText(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                CpText(
                    buildString {
                        append(s.itemCount(node.totalSongCount))
                        if (node.hasChildren && node.directSongCount > 0) {
                            append(" · 本级 ").append(node.directSongCount)
                        }
                        if (node.totalDurationMs > 0) {
                            append(" · ").append(formatDuration(node.totalDurationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Filled.PlayArrow, contentDescription = s.playAll)
            }
        }
    }
}

@Composable
private fun ColumnScope.LocalFolderDetail(
    node: LocalFolderNode,
    items: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onBack: () -> Unit,
    currentPlayingId: String?,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onDeleteSong: (LocalMediaItem) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
) {
    val s = strings.downloads
    val tracks = remember(items, node.path) { LocalFolderTree.allSongs(items, node) }
    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        DetailHeader(
            title = node.name,
            subtitle = buildString {
                append(s.itemCount(tracks.size))
                if (node.totalDurationMs > 0) append(" · ").append(formatDuration(node.totalDurationMs))
            },
            onBack = onBack,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(onClick = { model.playAll(tracks) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.playAll)
            }
            FilledTonalButton(onClick = { model.shuffleAll(tracks) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText("随机播放")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            items(tracks, key = { it.path }) { song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                )
            }
        }
    }
}

// ============ 页内二级视图：专辑 / 艺术家详情 ============

@Composable
private fun ColumnScope.LocalAlbumDetail(
    album: LocalAlbum,
    items: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onBack: () -> Unit,
    currentPlayingId: String?,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onDeleteSong: (LocalMediaItem) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
) {
    val s = strings.downloads
    val pathSet = album.paths.toSet()
    val tracks = remember(items, album.id) {
        items.filter { it.path in pathSet }
            .sortedWith(
                compareBy<LocalMediaItem> { it.metadata?.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.metadata?.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
    }
    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        DetailHeader(
            title = album.name,
            subtitle = buildString {
                append(album.artist ?: s.unknownArtist)
                album.year?.let { append(" · ").append(it) }
                append(" · ").append(s.itemCount(tracks.size))
            },
            onBack = onBack,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(onClick = { model.playAll(tracks) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.playAll)
            }
            FilledTonalButton(onClick = { model.shuffleAll(tracks) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText("随机播放")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            items(tracks, key = { it.path }) { song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.LocalArtistDetail(
    artist: LocalArtist,
    items: List<LocalMediaItem>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onBack: () -> Unit,
    currentPlayingId: String?,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onDeleteSong: (LocalMediaItem) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
) {
    val s = strings.downloads
    val tracks = remember(items, artist.name) {
        items.filter { (it.artist?.takeIf { v -> v.isNotBlank() } ?: s.unknownArtist) == artist.name }
            .sortedWith(
                compareBy<LocalMediaItem, String>(String.CASE_INSENSITIVE_ORDER) {
                    it.album ?: s.unknownAlbum
                }
                    .thenBy { it.metadata?.discNumber ?: Int.MAX_VALUE }
                    .thenBy { it.metadata?.trackNumber ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
            )
    }
    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        DetailHeader(
            title = artist.name,
            subtitle = buildString {
                append(s.itemCount(tracks.size))
                if (artist.albumCount > 1) append(" · ").append(s.albumCount(artist.albumCount))
            },
            onBack = onBack,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(onClick = { model.playAll(tracks) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText(s.playAll)
            }
            FilledTonalButton(onClick = { model.shuffleAll(tracks) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText("随机播放")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            items(tracks, key = { it.path }) { song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            CpText(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            CpText(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (trailingActions != null) {
            trailingActions()
        }
    }
}

// ============ 通用零件 ============

@Composable
private fun LocalCover(
    coverUri: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
) {
    val shape = if (circular) CircleShape else MaterialTheme.shapes.small
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (!coverUri.isNullOrBlank()) {
            AsyncImage(
                model = coverUri.resized(240),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (fallbackIcon == Icons.Filled.MusicNote) {
            CpCoverPlaceholder(
                modifier = Modifier.fillMaxSize(),
                corner = if (circular) 36.dp else 8.dp,
                animated = false,
            )
        } else {
            Icon(
                fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SortMenu(
    current: LocalLibrarySort,
    descending: Boolean,
    onSelect: (LocalLibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "排序方式")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LocalLibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        CpText(
                            if (option == current) {
                                "${option.label} ${if (descending) "↓" else "↑"}"
                            } else {
                                option.label
                            },
                            fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** 时长格式化：`3:45` / `1:02:03`。 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = if (hours > 0 && minutes < 10) "0$minutes" else "$minutes"
    val ss = if (seconds < 10) "0$seconds" else "$seconds"
    return if (hours > 0) "$hours:$mm:$ss" else "$minutes:$ss"
}
