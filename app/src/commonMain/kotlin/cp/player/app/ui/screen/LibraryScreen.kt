package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.LibraryScreenModel
import cp.player.kmp.music.PlaylistSummary
import kotlinx.coroutines.launch

class LibraryScreen : Screen {
    @Composable
    override fun Content() {
        LibraryScreenContent(rememberScreenModel { LibraryScreenModel() })
    }
}

private data class FilterTab(val label: String, val icon: ImageVector)

@Composable
private fun LibraryScreenContent(model: LibraryScreenModel) {
    val state by model.state.collectAsState()
    var selectedPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }
    var confirmDelete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.currentOrThrow

    val filters = listOf(
        FilterTab("歌单", Icons.Rounded.QueueMusic),
        FilterTab("云盘", Icons.Rounded.CloudQueue),
    )
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { filters.size })

    Column(Modifier.fillMaxSize()) {
        LibraryTopFilters(
            filters = filters,
            selectedIndex = pagerState.currentPage,
            onFilterSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            onCreatePlaylist = { showCreateDialog = true },
        )

        Surface(
            Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> PlaylistsTab(
                        playlists = state.playlists,
                        loading = state.loading,
                        error = state.error,
                        onRetry = model::refresh,
                        onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
                        onPlaylistOptions = { selectedPlaylist = it },
                    )
                    1 -> CloudTab(
                        songs = state.cloudSongs,
                        loading = state.cloudLoading,
                        error = state.cloudError,
                        loaded = state.cloudLoaded,
                        onLoad = { model.loadCloud() },
                        onRetry = { model.loadCloud(force = true) },
                        onSongClick = model::playCloud,
                    )
                }
            }
        }
    }

    selectedPlaylist?.let { playlist ->
        PlaylistOptionsSheet(
            playlistName = playlist.name,
            isOwner = model.isOwner(playlist),
            onDismiss = { selectedPlaylist = null },
            onPlay = {
                model.play(playlist)
            },
            onAddToQueue = {
                model.play(playlist, addOnly = true)
            },
            onDelete = {
                confirmDelete = playlist
            },
        )
    }

    confirmDelete?.let { playlist ->
        val owner = model.isOwner(playlist)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (owner) "删除歌单" else "取消收藏") },
            text = {
                Text(
                    if (owner) "确定删除「${playlist.name}」吗？此操作不可恢复。"
                    else "确定取消收藏「${playlist.name}」吗？"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        model.deleteOrUnsubscribe(playlist)
                        confirmDelete = null
                    },
                ) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (showCreateDialog) {
        cp.player.app.ui.component.CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreated = { created ->
                showCreateDialog = false
                if (created != null) model.refresh()
            },
        )
    }
}

@Composable
private fun CloudTab(
    songs: List<cp.player.kmp.music.TrackSummary>,
    loading: Boolean,
    error: String?,
    loaded: Boolean,
    onLoad: () -> Unit,
    onRetry: () -> Unit,
    onSongClick: (Int) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onLoad() }
    when {
        loading -> StateSurface(Modifier.padding(16.dp)) {
            ContentState(title = "正在加载云盘", message = "正在同步云盘歌曲", loading = true)
        }
        error != null -> StateSurface(Modifier.padding(16.dp)) {
            ContentState(
                title = "云盘加载失败",
                message = error,
                error = true,
                actionLabel = "重试",
                onAction = onRetry,
            )
        }
        songs.isEmpty() -> StateSurface(Modifier.padding(16.dp)) {
            ContentState(
                title = "云盘空空如也",
                message = if (loaded) "把歌曲上传到云盘后会显示在这里" else "登录后可查看云盘歌曲",
            )
        }
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(songs.size) { index ->
                cp.player.app.ui.component.SongItem(
                    track = songs[index],
                    index = index,
                    total = songs.size,
                    onClick = { onSongClick(index) },
                )
            }
        }
    }
}

@Composable
private fun LibraryTopFilters(
    filters: List<FilterTab>,
    selectedIndex: Int,
    onFilterSelected: (Int) -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(filters.size) { index ->
                val filter = filters[index]
                val isSelected = selectedIndex == index
                Surface(
                    onClick = { onFilterSelected(index) },
                    shape = RoundedCornerShape(percent = 50),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.padding(
                            horizontal = if (isSelected) 20.dp else 14.dp,
                            vertical = 10.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            filter.icon, null,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
                                + expandHorizontally(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)),
                            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150)),
                        ) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(filter.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        // 新建歌单按钮
        Surface(
            onClick = onCreatePlaylist,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        ) {
            Icon(
                Icons.Rounded.Add, "新建歌单",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp).size(20.dp),
            )
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<PlaylistSummary>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onPlaylistOptions: (PlaylistSummary) -> Unit,
) {
    if (loading) {
        StateSurface(Modifier.padding(16.dp)) {
            ContentState(title = "正在同步媒体库", message = "正在加载你的歌单", loading = true)
        }
        return
    }
    if (error != null) {
        StateSurface(Modifier.padding(16.dp)) {
            ContentState(
                title = "媒体库加载失败",
                message = error,
                error = true,
                actionLabel = "重试",
                onAction = onRetry,
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
    ) {
        items(playlists.size) { index ->
            PlaylistItem(
                playlist = playlists[index],
                index = index,
                total = playlists.size,
                onClick = { onPlaylistClick(playlists[index]) },
                onOptionsClick = { onPlaylistOptions(playlists[index]) },
            )
        }
        if (playlists.isEmpty()) {
            item {
                ContentState(
                    title = "这里还没有歌单",
                    message = "登录账号后即可同步收藏与创建的歌单",
                )
            }
        }
    }
}
