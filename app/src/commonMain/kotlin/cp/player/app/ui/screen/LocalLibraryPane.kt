package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cp.player.app.i18n.CpStrings
import cp.player.app.platform.rememberDirectoryPicker
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.DownloadsUiState
import cp.player.core.local.LocalAlbum
import cp.player.core.local.LocalArtist
import cp.player.core.local.LocalFolderNode
import cp.player.core.local.LocalFolderTree
import cp.player.core.local.LocalGridSort
import cp.player.core.local.LocalLibraryAggregator
import cp.player.core.local.LocalLibrarySort
import cp.player.core.local.LocalScanSettings
import cp.player.core.media.LocalMediaItem
import cp.player.app.ui.util.resized

/**
 * 本地曲库浏览面板（下载页 Tab 3）。
 *
 * 相对早期的「两个分组平铺所有文件」，这里把它升级成一个真正的**音乐库**：
 * 歌曲 / 专辑 / 艺术家 / 收藏四个子页，配搜索框与排序菜单。
 *
 * 专辑与艺术家点进去用**页内二级视图**而不是 push 新路由：本地库的数据全部来自
 * 已经在内存里的 [LocalMediaItem] 列表，没有网络请求，页内切换比压栈更轻，
 * 也不会在返回时丢掉搜索词与滚动位置。
 */
@Composable
internal fun LocalLibraryTab(
    state: DownloadsUiState,
    model: DownloadsScreenModel,
    strings: CpStrings,
) {
    val s = strings.downloads
    val pickFolder = rememberDirectoryPicker { path ->
        if (path != null) model.importFolder(path, strings)
    }
    var openedAlbum by remember { mutableStateOf<LocalAlbum?>(null) }
    var openedArtist by remember { mutableStateOf<LocalArtist?>(null) }

    // 页面内二级视图（专辑 / 艺术家详情）优先于列表视图
    val album = openedAlbum
    if (album != null) {
        // 二级视图自己就是 ColumnScope 扩展，这里补一层同构容器（宽度收口与列表视图一致）
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LocalAlbumDetail(
                album = album,
                items = state.localAudioItems,
                model = model,
                strings = strings,
                onBack = { openedAlbum = null },
            )
        }
        return
    }
    val artist = openedArtist
    if (artist != null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LocalArtistDetail(
                artist = artist,
                items = state.localAudioItems,
                model = model,
                strings = strings,
                onBack = { openedArtist = null },
            )
        }
        return
    }

    // 聚合结果：以 localItems 为 key，避免每次重组重算（get() 每次返回新 list）。
    // 浏览筛选（只看无损 / 重复 / 缺封面 / 缺标签）在最前面应用 —— 筛完之后
    // 专辑与艺术家的计数也要跟着变，否则「3 张专辑」点进去只有 1 张。
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
    val favorites = remember(base, state.favoritePaths, state.songSort, state.songSortDescending) {
        LocalLibraryAggregator.sortSongs(
            base.filter { it.path in state.favoritePaths },
            state.songSort,
            state.songSortDescending,
        )
    }
    val folders = remember(base) { LocalFolderTree.build(base) }

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

        // 子页切换：有内容时才显示，空库时直接给空态更清爽
        if (state.localAudioItems.isNotEmpty() || query.isNotBlank()) {
            LocalLibrarySubTabs(
                selected = state.libraryTab,
                favoriteCount = favorites.size,
                songCount = songs.size,
                albumCount = albums.size,
                artistCount = artists.size,
                folderCount = folders.size,
                strings = strings,
                onSelect = { model.setLibraryTab(it) },
            )
        }

        when {
            // 整库为空且没有搜索词 —— 引导扫描 / 导入
            state.localItems.isEmpty() -> {
                StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(
                        title = s.emptyLocalTitle,
                        message = s.emptyLocalNote,
                    )
                }
            }

            state.libraryTab == 3 && favorites.isEmpty() -> {
                StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(
                        title = s.emptyFavoritesTitle,
                        message = s.emptyFavoritesNote,
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
                    title = s.favoriteSongs,
                    songs = favorites,
                    model = model,
                    strings = strings,
                    favorites = state.favoritePaths,
                    showSort = true,
                    state = state,
                )
                // 文件夹树只认「当前筛选后的曲目」，与歌曲页保持同一份数据视角
                4 -> FolderTreeView(
                    nodes = folders,
                    items = base,
                    model = model,
                    strings = strings,
                )
                else -> SongList(
                    title = null,
                    songs = songs,
                    model = model,
                    strings = strings,
                    favorites = state.favoritePaths,
                    showSort = true,
                    state = state,
                )
            }
        }
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
                    Text(
                        state.scanProgress?.let { s.scanningFound(it.scanned) } ?: s.scanning,
                        maxLines = 1,
                    )
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.scanDevice, maxLines = 1)
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
                    Text(s.importing, maxLines = 1)
                } else {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.importFolder, maxLines = 1)
                }
            }
        }
        // 有内容才给搜索框：空库放一个搜不到东西的输入框只会让人困惑
        if (state.localItems.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.libraryQuery,
                    onValueChange = { model.setLibraryQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(s.librarySearchHint) },
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

/**
 * 筛选入口：浏览期开关（只看无损 / 重复 / 缺封面 / 缺标签）+ 扫描规则对话框。
 *
 * 两类开关放在同一个菜单里，是因为用户心里想的是同一件事 ——「这个曲库健不健康」。
 * 但语义上要区分清楚：上面的开关只影响当前视图，下面那个会影响下次扫描的结果。
 */
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
                    text = { Text("清除筛选") },
                    onClick = {
                        expanded = false
                        model.clearLibraryFilter()
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
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
        text = { Text(label) },
        trailingIcon = {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        },
        onClick = onToggle,
    )
}

/**
 * 扫描规则对话框。
 *
 * 关键提示语：这些规则**改变的是索引内容**，所以要重扫才生效 —— 不写清楚的话，
 * 用户改完设置看着列表没变化，会以为功能坏了。
 */
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
        title = { Text("扫描设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "改动会在下一次扫描时生效，并同步清理已从规则中排除的条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = minDuration,
                    onValueChange = { minDuration = it.filter(Char::isDigit).take(4) },
                    label = { Text("最短时长（秒），0 表示不过滤") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = filterVideo, onCheckedChange = { filterVideo = it })
                    Spacer(Modifier.width(4.dp))
                    Text("扫描时跳过视频文件")
                }
                OutlinedTextField(
                    value = excludeText,
                    onValueChange = { excludeText = it },
                    label = { Text("排除目录（每行一个）") },
                    placeholder = { Text("/storage/emulated/0/Music/Recordings") },
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
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
    folderCount: Int,
    strings: CpStrings,
    onSelect: (Int) -> Unit,
) {
    val s = strings.downloads
    val labels = listOf(
        s.tabSongs to songCount,
        s.tabAlbums to albumCount,
        s.tabArtists to artistCount,
        s.tabFavorites to favoriteCount,
        s.tabFolders to folderCount,
    )
    Box(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()) {
        TabRow(selectedTabIndex = selected) {
            labels.forEachIndexed { index, (label, count) ->
                Tab(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(label, maxLines = 1)
                            if (count > 0) {
                                Text(
                                    "$count",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }
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
    favorites: Set<String>,
    showSort: Boolean,
    state: DownloadsUiState,
) {
    val s = strings.downloads
    Column(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth().weight(1f)) {
        if (title != null || showSort) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CpSpacing.pageHorizontal, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title ?: "${s.tabSongs} · ${songs.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (songs.isNotEmpty()) {
                    IconButton(onClick = { model.playAll(songs) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = s.playAll)
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
                LocalSongRow(
                    song = song,
                    isFavorite = song.path in favorites,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(songs, songs.indexOf(song)) },
                )
            }
        }
    }
}

@Composable
private fun LocalSongRow(
    song: LocalMediaItem,
    isFavorite: Boolean,
    model: DownloadsScreenModel,
    strings: CpStrings,
    onPlay: () -> Unit,
) {
    val s = strings.downloads
    Surface(
        onClick = onPlay,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalCover(
                coverUri = song.coverUri,
                fallbackIcon = if (song.mediaType == cp.player.core.media.MediaType.VIDEO) {
                    Icons.Filled.Album
                } else {
                    Icons.Filled.MusicNote
                },
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副标题：艺术家 · 音质标签（有元数据时）· 时长
                val subtitle = buildList {
                    add(song.artist ?: s.unknownArtist)
                    song.qualityLabel?.let { add(it) }
                    if (song.durationMs > 0) add(formatDuration(song.durationMs))
                }.joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { model.toggleFavorite(song.path, strings) }) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = s.favorite,
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { model.removeLocalItem(song, strings) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = s.removeFromLibrary,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
        Text(
            album.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(album.artist ?: strings.downloads.unknownArtist)
                append(" · ")
                append(strings.downloads.itemCount(album.songCount))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
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
            overflow = TextOverflow.Ellipsis,
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
) {
    val s = strings.downloads
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var openedFolder by remember { mutableStateOf<LocalFolderNode?>(null) }

    val opened = openedFolder
    if (opened != null) {
        LocalFolderDetail(
            node = opened,
            items = items,
            model = model,
            strings = strings,
            onBack = { openedFolder = null },
        )
        return
    }

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
                onShowDetail = { openedFolder = node },
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
            // 用 depth 做缩进：树形结构靠左内边距表达层级，比画连接线更省空间
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
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
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
                    overflow = TextOverflow.Ellipsis,
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
                Text(s.playAll)
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
                LocalSongRow(
                    song = song,
                    isFavorite = song.path in model.state.value.favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
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
) {
    val s = strings.downloads
    val pathSet = album.paths.toSet()
    // 保持专辑内的「碟号 → 轨号 → 标题」顺序，而不是按当前列表排序
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
                Text(s.playAll)
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
                LocalSongRow(
                    song = song,
                    isFavorite = song.path in model.state.value.favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
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
) {
    val s = strings.downloads
    val tracks = remember(items, artist.name) {
        items.filter { (it.artist?.takeIf { v -> v.isNotBlank() } ?: s.unknownArtist) == artist.name }
            .sortedWith(
                // 带 comparator 的 compareBy 有两个类型参数（T, K），只写 T 会匹配失败
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
                Text(s.playAll)
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
                LocalSongRow(
                    song = song,
                    isFavorite = song.path in model.state.value.favoritePaths,
                    model = model,
                    strings = strings,
                    onPlay = { model.playAll(tracks, tracks.indexOf(song)) },
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ============ 通用零件 ============

/**
 * 本地封面。
 *
 * 之所以不复用任务封面的 `TaskCover`：那个组件带下载进度语义，且尺寸写死。
 * 这里只关心「有图显示图 / 无图给图标占位」，并复用在线封面那套 [resized]
 * （它对 `file://` / `content://` 会原样返回，不会把本地路径拼坏）。
 */
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
                        Text(
                            if (option == current) {
                                // 给当前项标出方向，省得用户不确定点第二次会发生什么
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

/** 时长格式化：`3:45` / `1:02:03`。commonMain 无 String.format，手工补零。 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = if (hours > 0 && minutes < 10) "0$minutes" else "$minutes"
    val ss = if (seconds < 10) "0$seconds" else "$seconds"
    return if (hours > 0) "$hours:$mm:$ss" else "$minutes:$ss"
}
