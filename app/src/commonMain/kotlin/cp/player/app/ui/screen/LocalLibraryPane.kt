package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.rememberDirectoryPicker
import cp.player.app.ui.component.AddToLocalPlaylistDialog
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpConfirmDialog
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSearchField
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.cpBounceClickable
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowContainer
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
import cp.player.core.local.LocalGridSort
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
    var removeTargetSong by remember { mutableStateOf<LocalMediaItem?>(null) }
    var showInfoTrack by remember { mutableStateOf<TrackSummary?>(null) }

    val likedIds by AppModel.playback.likedIds.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentPlayingTrackId = playbackState.currentTrack?.id

    /**
     * 四个二级视图（专辑 / 艺术家 / 文件夹 / 本地歌单详情）共用的**行内动作**。
     *
     * 此前这四组闭包在调用点上**逐字复制了四份**（每份六行）—— 改一处措辞就得改四处，
     * 漏一处就是「同一个动作在两个详情页提示不一样」。提到这里只此一份。
     * 它们不依赖任何二级视图的状态，所以可以直接在组合里建一次、四处复用。
     *
     * `remember(likedIds)`：闭包里判的是「收藏前后的目标态」，拿到的必须是**最新**的
     * 收藏集合；不换 key 会读到过期的 likedIds —— 连点两次会两次都判成「收藏」。
     */
    val toggleCloudFav: (String) -> Unit = remember(likedIds) {
        { cloudId: String ->
            scope.launch {
                val cloudFavId = runCatching {
                    cp.player.core.music.CPMediaId.parse(cloudId).resourceId
                }.getOrDefault(cloudId)
                val target = cloudFavId !in likedIds
                AppModel.playback.toggleFavoriteFor(cloudId)
                UiEvents.notify(strings.downloads.favoriteCloudDone(target))
            }
        }
    }
    val toggleLocalFav: (String) -> Unit = {
        val now = AppModel.localMedia.favorites.toggle(it)
        UiEvents.notify(if (now) s.addedToFavorites else s.removedFromFavorites)
    }

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
                onToggleCloudFav = toggleCloudFav,
                onToggleLocalFav = toggleLocalFav,
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onRemoveSong = { removeTargetSong = it },
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
                onToggleCloudFav = toggleCloudFav,
                onToggleLocalFav = toggleLocalFav,
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onRemoveSong = { removeTargetSong = it },
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
                onToggleCloudFav = toggleCloudFav,
                onToggleLocalFav = toggleLocalFav,
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onRemoveSong = { removeTargetSong = it },
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
                        title = strings.library.deletePlaylist,
                        message = s.deleteLocalPlaylistMessage(latestPlaylist.name),
                        confirmLabel = s.delete,
                        destructive = true,
                        onConfirm = {
                            model.deleteLocalPlaylist(latestPlaylist.id)
                            openedLocalPlaylist = null
                        },
                    )
                },
                onRemoveFromPlaylist = { path ->
                    model.removeTrackFromLocalPlaylist(latestPlaylist.id, path)
                },
                currentPlayingId = currentPlayingTrackId,
                likedIds = likedIds,
                favoritePaths = state.favoritePaths,
                onToggleCloudFav = toggleCloudFav,
                onToggleLocalFav = toggleLocalFav,
                onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                onRemoveSong = { removeTargetSong = it },
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
            // 四块空态共用同一份**度量**，只是文案不同 —— 此前四个分支各写一遍
            // `StateSurface(Modifier.padding(CpSpacing.pageHorizontal))`，页尾那个
            // 「此歌单暂无歌曲」也是同一种写法：五处必须一起改才不出偏差，所以收成一个槽。
            state.localItems.isEmpty() -> LibraryEmptyState {
                ContentState(
                    title = s.emptyLocalTitle,
                    message = s.emptyLocalNote,
                    actionLabel = s.scanDevice,
                    onAction = { model.startScan(strings) },
                )
            }

            state.libraryTab == 3 && pureLocalFavorites.isEmpty() -> LibraryEmptyState {
                ContentState(
                    title = s.emptyLocalFavoritesTitle,
                    message = s.emptyLocalFavoritesNote,
                )
            }

            state.libraryTab == 4 && state.localPlaylists.isEmpty() -> LibraryEmptyState {
                ContentState(
                    title = s.emptyLocalPlaylistsTitle,
                    message = s.emptyLocalPlaylistsNote,
                    actionLabel = s.newLocalPlaylist,
                    onAction = { showCreatePlaylistDialog = true },
                )
            }

            query.isNotBlank() && songs.isEmpty() && albums.isEmpty() && artists.isEmpty() -> LibraryEmptyState {
                ContentState(title = s.emptySearchTitle, message = "")
            }

            else -> when (state.libraryTab) {
                1 -> AlbumGrid(
                    albums = albums,
                    gridSort = state.gridSort,
                    onSort = model::setGridSort,
                    strings = strings,
                    onClick = { openedAlbum = it },
                )
                2 -> ArtistGrid(
                    artists = artists,
                    gridSort = state.gridSort,
                    onSort = model::setGridSort,
                    strings = strings,
                    onClick = { openedArtist = it },
                )
                3 -> SongList(
                    title = "${s.tabLocalFavorites} · ${pureLocalFavorites.size}",
                    songs = pureLocalFavorites,
                    model = model,
                    strings = strings,
                    showSort = true,
                    state = state,
                    currentPlayingId = currentPlayingTrackId,
                    likedIds = likedIds,
                    favoritePaths = state.favoritePaths,
                    onToggleCloudFav = toggleCloudFav,
                    onToggleLocalFav = toggleLocalFav,
                    onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                    onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                    onRemoveSong = { removeTargetSong = it },
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
                            title = strings.library.deletePlaylist,
                            message = s.deleteLocalPlaylistMessage(pl.name),
                            confirmLabel = s.delete,
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
                    onToggleCloudFav = toggleCloudFav,
                    onToggleLocalFav = toggleLocalFav,
                    onAddToCloudPlaylist = { addToCloudPlaylistTrack = it },
                    onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
                    onRemoveSong = { removeTargetSong = it },
                    onDeleteSong = { deleteTargetSong = it },
                    onShowInfo = { showInfoTrack = it },
                )
            }
        }
    }

    CpConfirmHost(confirm)

    if (showCreatePlaylistDialog) {
        CreateLocalPlaylistDialog(
            strings = strings,
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
            strings = strings,
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

    removeTargetSong?.let { song ->
        CpConfirmDialog(
            title = s.removeConfirmTitle,
            message = s.removeConfirmMessage(song.title),
            confirmLabel = s.removeFromLibrary,
            destructive = false,
            onConfirm = {
                model.removeLocalItem(song, strings)
                removeTargetSong = null
            },
            onDismiss = { removeTargetSong = null },
        )
    }

    deleteTargetSong?.let { song ->
        CpConfirmDialog(
            title = s.deleteLocalFile,
            message = s.deleteLocalFileMessage(song.title),
            confirmLabel = s.delete,
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

/**
 * 顶部工具行：扫描 / 导入 + 搜索 + 筛选。
 *
 * 三条与全应用对齐的约定：
 * 1. **水平内边距取 [CpSpacing.pageHorizontal]**（栅格页标准 20dp），不是裸 16dp ——
 *    本页正文（歌曲列表 / 专辑栅格 / 文件夹树）都是 20dp，工具行用 16dp 会让
 *    「扫描设备」按钮比列表左缘缩进一格。
 * 2. **搜索框用 [CpSearchField]**（全应用唯一入口），而不是就地拼 `OutlinedTextField`。
 * 3. 间距与计数一律取自 `s`（`CpStrings`），不在页面里写死中文 —— 见 `I18N.md`。
 */
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
        // 水平内边距取 `CpSpacing.pageHorizontal`（栅格页标准 20dp），不是裸 16dp：
        // 本页正文（列表 / 栅格）都是 20dp，工具行用 16dp 会让扫描按钮比列表左缘缩进一格。
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
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
                // 搜索框走全应用唯一的 [CpSearchField]（标题栏那条 40dp 与页面里 52dp 的
                // 都是它）。就地拼 `OutlinedTextField` 的话：高度 56dp 比别处高一档、
                // 聚焦态容器色不过渡、清除按钮的落点也与其它页面不同。
                // 筛选按钮留在框**外面**：它是独立动作，塞进 trailingIcon 会与「清除」抢槽位。
                CpSearchField(
                    query = state.libraryQuery,
                    onQueryChange = { model.setLibraryQuery(it) },
                    // 本地库是**即时筛选**（不是远端搜索）：回车无需再做别的，
                    // 保留 IME 的「搜索」动作键只是让键盘上那一下有落点。
                    onSubmit = {},
                    modifier = Modifier.weight(1f),
                    placeholder = s.librarySearchHint,
                    textStyle = MaterialTheme.typography.bodyMedium,
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
            Icon(Icons.Filled.FilterList, contentDescription = strings.downloads.filterAndScanSettings)
            if (badge > 0) {
                // 角标画在**右下**而不是右上：右侧紧邻搜索框，右上角那 8dp
                // 会被输入框的圆角吃掉一半，看起来像落错了位置。
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 10.dp)
                        .size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterToggle(strings.downloads.losslessOnly, filter.losslessOnly) {
                model.toggleLibraryFilter { it.copy(losslessOnly = !it.losslessOnly) }
            }
            FilterToggle(strings.downloads.duplicatesOnly, filter.duplicatesOnly) {
                model.toggleLibraryFilter { it.copy(duplicatesOnly = !it.duplicatesOnly) }
            }
            FilterToggle(strings.downloads.missingCoverOnly, filter.missingCoverOnly) {
                model.toggleLibraryFilter { it.copy(missingCoverOnly = !it.missingCoverOnly) }
            }
            FilterToggle(strings.downloads.missingTagsOnly, filter.missingTagsOnly) {
                model.toggleLibraryFilter { it.copy(missingTagsOnly = !it.missingTagsOnly) }
            }
            if (!filter.isDefault) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { CpText(strings.downloads.clearFilters) },
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
                            append(strings.downloads.scanSettings)
                            if (state.scanSettings.activeRuleCount > 0) {
                                append(" · ")
                                append(strings.downloads.scanRuleCount(state.scanSettings.activeRuleCount))
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
            strings = strings,
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
    strings: CpStrings,
    onDismiss: () -> Unit,
    onConfirm: (LocalScanSettings) -> Unit,
) {
    var minDuration by remember { mutableStateOf(current.minDurationSeconds.toString()) }
    var filterVideo by remember { mutableStateOf(current.filterVideoFiles) }
    var excludeText by remember { mutableStateOf(current.excludeFolders.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(strings.downloads.scanSettings) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CpText(
                    strings.downloads.scanSettingsNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = minDuration,
                    onValueChange = { minDuration = it.filter(Char::isDigit).take(4) },
                    label = { CpText(strings.downloads.minimumDuration) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filterVideo, onCheckedChange = { filterVideo = it })
                    Spacer(Modifier.width(4.dp))
                    CpText(strings.downloads.skipVideoFiles)
                }
                OutlinedTextField(
                    value = excludeText,
                    onValueChange = { excludeText = it },
                    label = { CpText(strings.downloads.excludedFolders) },
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
            ) { CpText(strings.downloads.save) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText(strings.common.dismiss) }
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
        s.tabLocalFavorites to favoriteCount,
        s.tabLocalPlaylists to playlistCount,
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
            // 计数走 `badge` 槽位而不是拼进标签：拼进去之后计数与标题同字号、同颜色，
            // 「本地喜欢 12」读起来像名字的一部分；徽标退一档，扫读时才分得清。
            // 计数为 0 的页签不显徽标 —— 「本地歌单 0」比「本地歌单」更像出错。
            CpToggleChip(
                checked = selected == index,
                onCheckedChange = { if (it) onSelect(index) },
                label = label,
                badge = if (count > 0) "$count" else null,
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
    onRemoveSong: (LocalMediaItem) -> Unit,
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
                        Icon(Icons.Filled.Shuffle, contentDescription = s.shuffleAll)
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
            itemsIndexed(songs, key = { _, song -> song.path }) { index, song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(songs, index) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onRemoveSong = onRemoveSong,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                    index = index,
                    total = songs.size,
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
internal fun LocalSongRow(
    song: LocalMediaItem,
    isCurrentlyPlaying: Boolean,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onPlay: () -> Unit,
    onRemoveFromLocalPlaylist: (() -> Unit)? = null,
    onToggleCloudFav: ((String) -> Unit)? = null,
    onToggleLocalFav: ((String) -> Unit)? = null,
    onAddToCloudPlaylist: ((TrackSummary) -> Unit)? = null,
    onAddToLocalPlaylist: ((String) -> Unit)? = null,
    onRemoveSong: ((LocalMediaItem) -> Unit)? = null,
    onDeleteSong: ((LocalMediaItem) -> Unit)? = null,
    onShowInfo: ((TrackSummary) -> Unit)? = null,
    index: Int = 0,
    total: Int = 1,
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
            onToggleCloudFav?.let { fn -> { fn(cloudMediaId) } }
        } else {
            onToggleLocalFav?.let { fn -> { fn(song.path) } }
        },
        favoriteLabel = if (isDownloaded && cloudMediaId != null) {
            if (isCloudFav) s.unfavoriteCloud else s.favoriteCloud
        } else {
            if (isLocalFav) s.removedFromFavorites else s.favorite
        },
        onAddToQueue = if (!isVideo) {
            {
                scope.launch {
                    AppModel.playback.addToQueue(mediaId)
                    UiEvents.notify(strings.library.queuedToPlay)
                }
            }
        } else null,
        onPlayNext = if (!isVideo) {
            {
                scope.launch {
                    AppModel.playback.addNextToQueue(mediaId)
                    UiEvents.notify(strings.library.playNext)
                }
            }
        } else null,
        isDownloaded = true,
        onAddToPlaylist = if (isDownloaded && cloudMediaId != null) {
            onAddToCloudPlaylist?.let { fn -> { fn(trackSummary) } }
        } else null,
        onAddToLocalPlaylist = onAddToLocalPlaylist?.let { fn ->
            { fn(song.path) }
        },
        onRemoveFromPlaylist = onRemoveFromLocalPlaylist,
        onOpenInFileManager = {
            cp.player.app.platform.openInFileManager(song.path)
        },
        onRemoveFromLibrary = onRemoveSong?.let { fn ->
            { fn(song) }
        },
        onDelete = onDeleteSong?.let { fn ->
            { fn(song) }
        },
        deleteLabel = s.deleteLocalFile,
        onShowInfo = onShowInfo?.let { fn ->
            { fn(trackSummary) }
        },
    )

    val menuItems = songContextMenuItems(menuActions)

    SongItem(
        track = trackSummary,
        index = index,
        total = total,
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
                "${strings.downloads.tabLocalPlaylists} · ${playlists.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            FilledTonalButton(onClick = onCreatePlaylist) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                CpText(strings.downloads.newLocalPlaylist)
            }
        }
        val itemsByPath = remember(items) { items.associateBy { it.path } }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = CpSpacing.gridColumns(maxWidth - CpSpacing.pageHorizontal * 2)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
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
                        strings = strings,
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
}

/**
 * 本地歌单格子：方形封面 + 名称 + 「N 首歌曲 · 时长」+ 行尾「更多」。
 *
 * 封面走 [LocalCover] 且**圆角按格子尺寸给 20dp**（见那里的说明）；
 * 整格用 `cpBounceClickable`，与专辑 / 艺术家格子同一手感。
 *
 * ⚠️ 行尾的「更多」是**独立可点**控件：所以整格用 `clickable` 而不是
 * `Surface(onClick)` —— 后者会把子按钮的动作并进父节点，读屏用户就点不到它。
 */
@Composable
private fun LocalPlaylistCell(
    playlist: LocalPlaylist,
    strings: CpStrings,
    coverUri: String?,
    totalDurationMs: Long,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.clip(MaterialTheme.shapes.largeIncreased).cpBounceClickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.largeIncreased),
            contentAlignment = Alignment.Center,
        ) {
            LocalCover(
                coverUri = coverUri,
                fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                modifier = Modifier.fillMaxSize(),
                corner = 20.dp,
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
                        append(strings.downloads.playlistTrackCount(playlist.trackPaths.size))
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
                        contentDescription = strings.downloads.playlistOptions,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { CpText(strings.downloads.playPlaylist) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                        onClick = {
                            menuExpanded = false
                            onPlay()
                        },
                    )
                    DropdownMenuItem(
                        text = { CpText(strings.downloads.rename) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { CpText(strings.library.deletePlaylist, color = MaterialTheme.colorScheme.error) },
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
    onRemoveFromPlaylist: (String) -> Unit,
    currentPlayingId: String?,
    likedIds: Set<String>,
    favoritePaths: Set<String>,
    onToggleCloudFav: (String) -> Unit,
    onToggleLocalFav: (String) -> Unit,
    onAddToCloudPlaylist: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: (String) -> Unit,
    onRemoveSong: (LocalMediaItem) -> Unit,
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
                append(strings.downloads.playlistTrackCount(tracks.size))
                if (totalDurationMs > 0) append(" · ").append(formatDuration(totalDurationMs))
            },
            onBack = onBack,
            trailingActions = {
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = strings.library.renamePlaylist)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = strings.library.deletePlaylist, tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        if (tracks.isNotEmpty()) {
            LocalPlayAllRow(
                playAllLabel = strings.downloads.playAll,
                onPlayAll = { model.playAll(tracks) },
                onShuffle = { model.shuffleAll(tracks) },
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                itemsIndexed(tracks, key = { _, song -> song.path }) { index, song ->
                    val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                    val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                    LocalSongRow(
                        song = song,
                        isCurrentlyPlaying = currentPlayingId == mediaId,
                        likedIds = likedIds,
                        favoritePaths = favoritePaths,
                        model = model,
                        strings = strings,
                        onPlay = { model.playAll(tracks, index) },
                        onRemoveFromLocalPlaylist = { onRemoveFromPlaylist(song.path) },
                        onToggleCloudFav = onToggleCloudFav,
                        onToggleLocalFav = onToggleLocalFav,
                        onAddToCloudPlaylist = onAddToCloudPlaylist,
                        onAddToLocalPlaylist = onAddToLocalPlaylist,
                        onRemoveSong = onRemoveSong,
                        onDeleteSong = onDeleteSong,
                        onShowInfo = onShowInfo,
                        index = index,
                        total = tracks.size,
                    )
                }
            }
        } else {
            LibraryEmptyState {
                ContentState(
                    title = strings.downloads.emptyPlaylistTitle,
                    message = strings.downloads.emptyPlaylistNote,
                )
            }
        }
    }
}

// ============ 对话框：新建与重命名本地歌单 ============

@Composable
private fun CreateLocalPlaylistDialog(
    strings: CpStrings,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(strings.downloads.newLocalPlaylist) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { CpText(strings.downloads.playlistName) },
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
            ) { CpText(strings.playlistSheet.createShort) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText(strings.common.dismiss) }
        },
    )
}

@Composable
private fun RenameLocalPlaylistDialog(
    currentName: String,
    strings: CpStrings,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CpText(strings.library.renamePlaylist) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { CpText(strings.downloads.playlistName) },
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
            ) { CpText(strings.common.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { CpText(strings.common.dismiss) }
        },
    )
}

// ============ 专辑 / 艺术家网格 ============

@Composable
private fun GridHeader(
    title: String,
    current: LocalGridSort,
    strings: CpStrings,
    onSelect: (LocalGridSort) -> Unit,
) {
    Row(
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpText(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        GridSortMenu(current = current, strings = strings, onSelect = onSelect)
    }
}

@Composable
private fun GridSortMenu(
    current: LocalGridSort,
    strings: CpStrings,
    onSelect: (LocalGridSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = strings.downloads.sortOrder,
                tint = if (current == LocalGridSort.NAME) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LocalGridSort.entries.forEach { option ->
                val label = when (option) {
                    LocalGridSort.NAME -> strings.downloads.sortName
                    LocalGridSort.COUNT -> strings.downloads.sortCount
                    LocalGridSort.RECENT -> strings.downloads.sortRecent
                }
                DropdownMenuItem(
                    text = { CpText(label) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * 专辑栅格。
 *
 * 列数按**本容器可用宽度**走 [CpSpacing.gridColumns]（与首页 / 曲库 / 本地歌单栅格同一套换算），
 * 不再是 `GridCells.Adaptive(minSize = 132.dp)`：那只保证「每格不低于 132dp」，
 * 于是宽窗口上会铺出一堆贴着下限的窄封面 —— 正文早已被 `pageMaxWidth`（1400dp）钳住，
 * 列数本身不会涨，问题只是每格被压到最窄。换算后每列落在 180–210dp 的舒适区。
 */
@Composable
private fun ColumnScope.AlbumGrid(
    albums: List<LocalAlbum>,
    gridSort: LocalGridSort,
    onSort: (LocalGridSort) -> Unit,
    strings: CpStrings,
    onClick: (LocalAlbum) -> Unit,
) {
    val gridState = rememberLazyGridState()
    GridHeader(
        title = "${strings.downloads.tabAlbums} · ${albums.size}",
        current = gridSort,
        strings = strings,
        onSelect = onSort,
    )
    BoxWithConstraints(
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize().weight(1f),
    ) {
        val columns = CpSpacing.gridColumns(maxWidth - CpSpacing.pageHorizontal * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
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
}

/**
 * 专辑格子：方形封面 + 标题 + 「艺人 · N 项」。
 *
 * 封面圆角取 20dp（= `shapes.largeIncreased`）而不是原 `medium`（12dp）：
 * 12dp 是**列表缩略图**那一档，用在 180dp 的封面块上显得过方。
 * 点击给 `cpBounceClickable`（按下轻微收缩）—— 与首页 / 曲库的封面卡同一手感。
 */
@Composable
private fun AlbumCell(album: LocalAlbum, strings: CpStrings, onClick: () -> Unit) {
    Column(
        Modifier.cpBounceClickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.largeIncreased),
            contentAlignment = Alignment.Center,
        ) {
            LocalCover(
                coverUri = album.coverUri,
                fallbackIcon = Icons.Filled.Album,
                modifier = Modifier.fillMaxSize(),
                corner = 20.dp,
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

/** 艺术家栅格。列数换算与 [AlbumGrid] 同一套（见那里的 KDoc）。 */
@Composable
private fun ColumnScope.ArtistGrid(
    artists: List<LocalArtist>,
    gridSort: LocalGridSort,
    onSort: (LocalGridSort) -> Unit,
    strings: CpStrings,
    onClick: (LocalArtist) -> Unit,
) {
    GridHeader(
        title = "${strings.downloads.tabArtists} · ${artists.size}",
        current = gridSort,
        strings = strings,
        onSelect = onSort,
    )
    BoxWithConstraints(
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize().weight(1f),
    ) {
        val columns = CpSpacing.gridColumns(maxWidth - CpSpacing.pageHorizontal * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
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
}

@Composable
private fun ArtistCell(artist: LocalArtist, strings: CpStrings, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.largeIncreased).cpBounceClickable(onClick = onClick),
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

/**
 * 文件夹行（树的一行）。
 *
 * 底色取 [settingsRowContainer]（深色 `surfaceContainerHighest` / 浅色 `surfaceContainerLow`），
 * 与本地库其它卡片（专辑 / 艺术家 / 歌单格子）同一档。此前是
 * `surfaceContainerHigh.copy(alpha = 0.6f)`：半透明叠在页面底色上，实际颜色随背景漂移，
 * 与相邻的歌曲行（`SongItem`）差一档 —— 同一页里两种卡片深度。
 *
 * 缩进 `12dp + (depth-1) * 16dp` 是**允许的裸值**：它表达的是「树的层级」这一结构，
 * 服务的是任意深度的树，而非页面栅格（那组令牌在 `CpSpacing` 里）。
 */
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
        color = settingsRowContainer(),
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
    onRemoveSong: (LocalMediaItem) -> Unit,
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
        LocalPlayAllRow(
            playAllLabel = s.playAll,
            onPlayAll = { model.playAll(tracks) },
            onShuffle = { model.shuffleAll(tracks) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            itemsIndexed(tracks, key = { _, song -> song.path }) { index, song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, index) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onRemoveSong = onRemoveSong,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                    index = index,
                    total = tracks.size,
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
    onRemoveSong: (LocalMediaItem) -> Unit,
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
        LocalPlayAllRow(
            playAllLabel = s.playAll,
            onPlayAll = { model.playAll(tracks) },
            onShuffle = { model.shuffleAll(tracks) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            itemsIndexed(tracks, key = { _, song -> song.path }) { index, song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, index) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onRemoveSong = onRemoveSong,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                    index = index,
                    total = tracks.size,
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
    onRemoveSong: (LocalMediaItem) -> Unit,
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
        LocalPlayAllRow(
            playAllLabel = s.playAll,
            onPlayAll = { model.playAll(tracks) },
            onShuffle = { model.shuffleAll(tracks) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
        ) {
            itemsIndexed(tracks, key = { _, song -> song.path }) { index, song ->
                val isVideo = song.mediaType == cp.player.core.media.MediaType.VIDEO
                val mediaId = if (isVideo) "local://video/${song.path}" else "local://audio/${song.path}"
                LocalSongRow(
                    song = song,
                    isCurrentlyPlaying = currentPlayingId == mediaId,
                    likedIds = likedIds,
                    favoritePaths = favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, index) },
                    onToggleCloudFav = onToggleCloudFav,
                    onToggleLocalFav = onToggleLocalFav,
                    onAddToCloudPlaylist = onAddToCloudPlaylist,
                    onAddToLocalPlaylist = onAddToLocalPlaylist,
                    onRemoveSong = onRemoveSong,
                    onDeleteSong = onDeleteSong,
                    onShowInfo = onShowInfo,
                    index = index,
                    total = tracks.size,
                )
            }
        }
    }
}

/**
 * 页内二级视图的头部（专辑 / 艺术家 / 文件夹 / 本地歌单详情）。
 *
 * ⚠️ 返回键用 [CpBackButton] 而不是裸 `IconButton { Icon(ArrowBack) }`：
 * AGENTS.md §6 明确「返回键只认 CpBackButton」—— 这一处是最后残留的裸图标之一，
 * 于是从本地库列表进详情时，返回按钮会突然从填充圆钮变成一根细箭头。
 * 因此水平内边距只留 8dp：填充圆钮自带 40dp 的视觉块，再加 20dp 会把它推得离边太远。
 */
@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    trailingActions: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CpBackButton(onClick = onBack)
        Spacer(Modifier.width(10.dp))
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

/**
 * 本地库**唯一**的空态容器。
 *
 * 与列表同宽（[CpSpacing.pageMaxWidth]）+ 水平 [CpSpacing.pageHorizontal]：不收这两样的话，
 * 宽屏下空态卡片会贴着窗口最左，和上方的工具行 / 子页签明显错开。
 *
 * 收成一个槽而不是每个分支各写一遍 [StateSurface]：五个空态共用同一份度量。
 */
@Composable
private fun LibraryEmptyState(content: @Composable () -> Unit) {
    StateSurface(
        Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
    ) {
        content()
    }
}

/**
 * 「播放全部 / 随机播放」并排两格。
 *
 * 抽出来的理由：这一对按钮在四个二级视图（本地歌单 / 文件夹 / 专辑 / 艺术家详情）
 * 里逐字写了四遍，任何一处改动（比如把「随机播放」换成本地化文案）都要改四遍。
 * 两格等宽是刻意的：文案长度随语言变，不定宽的话英文下两个按钮会被挤成不同宽度。
 */
@Composable
private fun LocalPlayAllRow(
    playAllLabel: String,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val s = cpStrings()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(onClick = onPlayAll, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            CpText(playAllLabel)
        }
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            CpText(s.downloads.shuffleAll)
        }
    }
}

/**
 * 本地曲库的封面块（列表缩略图 / 栅格封面 / 文件夹图标共用的唯一实现）。
 *
 * @param corner 方角圆角（`Dp`）。**栅格封面传 20dp**（= `shapes.largeIncreased`）：
 *   大块封面用 8dp 显得过方，而列表行 40–60dp 的缩略图用 20dp 又显得过圆 ——
 *   同一个组件两种尺寸，圆角必须由调用方按尺寸给。
 * @param circular 圆形头像（艺术家）。与 [corner] 互斥。
 *
 * ⚠️ 占位块**常驻最底层**（而不是「没有封面才画」）：封面 URI 有值但图还没到 / 已经挂了时，
 * 只画 AsyncImage 会留下一块空白，看起来像渲染坏了。
 */
@Composable
private fun LocalCover(
    coverUri: String?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    corner: Dp = 8.dp,
) {
    val shape = if (circular) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(corner)
    Box(
        modifier.clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (fallbackIcon == Icons.Filled.Album || fallbackIcon == Icons.AutoMirrored.Filled.QueueMusic) {
            CpCoverPlaceholder(
                modifier = Modifier.fillMaxSize(),
                corner = corner,
                animated = corner >= 16.dp,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    fallbackIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (!coverUri.isNullOrBlank()) {
            AsyncImage(
                model = coverUri.resized(240),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 歌曲排序菜单。
 *
 * 当前项带**方向箭头**（↑ / ↓）而不是另开一个「升序 / 降序」开关：重复点同一项即可
 * 翻转方向（见 `DownloadsScreenModel.toggleSongSort`）。箭头只在当前项上出现。
 *
 * 图标用 `Sort` 而不是 `MoreVert`：行尾的「更多」已经占用了 MoreVert 的语义，
 * 同一个页面里两个不同的按钮长得一样，用户读不出哪个是排序。
 * 非默认排序生效时图标点亮为主题色 —— 否则「默认顺序」与「已按大小降序」
 * 在界面上完全一样，看不出自己动过排序。
 */
@Composable
private fun SortMenu(
    current: LocalLibrarySort,
    descending: Boolean,
    onSelect: (LocalLibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val isDefault = current == LocalLibrarySort.TITLE && !descending
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = cpStrings().library.sort,
                tint = if (isDefault) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LocalLibrarySort.entries.forEach { option ->
                val active = option == current
                DropdownMenuItem(
                    text = {
                        CpText(
                            if (active) {
                                "${option.label} ${if (descending) "↓" else "↑"}"
                            } else {
                                option.label
                            },
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
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
