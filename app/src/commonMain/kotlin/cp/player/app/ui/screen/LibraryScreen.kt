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
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DownloadDone
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
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.currentOrThrow

    val filters = listOf(
        FilterTab("歌单", Icons.Rounded.QueueMusic),
        FilterTab("下载", Icons.Rounded.DownloadDone),
        FilterTab("云盘", Icons.Rounded.CloudQueue),
        FilterTab("Live", Icons.Rounded.AutoGraph),
    )
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { filters.size })

    Column(Modifier.fillMaxSize()) {
        LibraryTopFilters(
            filters = filters,
            selectedIndex = pagerState.currentPage,
            onFilterSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
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
                    1 -> PlaceholderTab("下载管理", "下载的歌曲将在此显示。")
                    2 -> PlaceholderTab("云盘歌曲", "登录后可查看云盘歌曲。")
                    3 -> PlaceholderTab("Live Sort", "实时排序功能。")
                }
            }
        }
    }

    selectedPlaylist?.let { playlist ->
        PlaylistOptionsSheet(
            playlistName = playlist.name,
            isOwner = true,
            onDismiss = { selectedPlaylist = null },
            onPlay = {
                model.play(playlist)
            },
            onAddToQueue = {
                model.play(playlist, addOnly = true)
            },
        )
    }
}

@Composable
private fun LibraryTopFilters(
    filters: List<FilterTab>,
    selectedIndex: Int,
    onFilterSelected: (Int) -> Unit,
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
