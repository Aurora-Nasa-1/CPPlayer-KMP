package cp.player.app.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.StateSurface
import cp.player.kmp.BackendResult
import cp.player.kmp.music.MusicSourceFromApi
import cp.player.kmp.music.PlaylistDetail
import cp.player.kmp.music.PlaylistSummary
import kotlinx.coroutines.launch

class PlaylistDetailScreen(private val playlist: PlaylistSummary) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        var detail by remember(playlist.id) { mutableStateOf<PlaylistDetail?>(null) }
        var loading by remember(playlist.id) { mutableStateOf(true) }
        var error by remember(playlist.id) { mutableStateOf<String?>(null) }
        var reloadKey by remember { mutableStateOf(0) }
        var selectedTrack by remember { mutableStateOf<cp.player.kmp.music.TrackSummary?>(null) }
        var addToPlaylistTrack by remember { mutableStateOf<cp.player.kmp.music.TrackSummary?>(null) }
        val likedIds by AppModel.playback.likedIds.collectAsState()

        LaunchedEffect(playlist.id, reloadKey) {
            loading = true
            error = null
            when (val response = MusicSourceFromApi.getPlaylistDetail(AppModel.api, playlist.id)) {
                is BackendResult.Success -> detail = response.data
                is BackendResult.Error -> error = response.message
                is BackendResult.Unsupported -> error = response.message
            }
            loading = false
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = 12.dp,
                    end = CpSpacing.pageHorizontal,
                    top = CpSpacing.pageTop,
                    bottom = 12.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledIconButton(onClick = navigator::pop) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                PageHeader(
                    title = "歌单",
                    subtitle = playlist.creatorName?.let { "由 $it 创建" },
                    modifier = Modifier.weight(1f),
                )
            }

            when {
                loading -> StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(title = "正在加载歌单", loading = true)
                }
                error != null -> StateSurface(Modifier.padding(CpSpacing.pageHorizontal)) {
                    ContentState(
                        title = "歌单加载失败",
                        message = error,
                        error = true,
                        actionLabel = "重试",
                        onAction = { reloadKey++ },
                    )
                }
                detail != null -> PlaylistDetailContent(
                    detail = detail!!,
                    onPlayAll = {
                        val tracks = detail!!.tracks
                        if (tracks.isNotEmpty()) {
                            scope.launch {
                                AppModel.playback.playQueue(
                                    tracks.map { "$provider://song/${it.id}" },
                                    startIndex = 0,
                                )
                            }
                        }
                    },
                    onTrackClick = { index ->
                        scope.launch {
                            AppModel.playback.playQueue(
                                detail!!.tracks.map { "$provider://song/${it.id}" },
                                startIndex = index,
                            )
                        }
                    },
                    onTrackOptions = { selectedTrack = it },
                )
            }
        }

        selectedTrack?.let { track ->
            cp.player.app.ui.component.SongOptionsSheet(
                songName = track.name,
                artistName = track.artist,
                isFavorite = track.id in likedIds,
                isDownloaded = false,
                onDismiss = { selectedTrack = null },
                onPlay = {
                    scope.launch {
                        AppModel.playback.playQueue(listOf("$provider://song/${track.id}"), startIndex = 0)
                    }
                },
                onToggleFavorite = {
                    scope.launch {
                        val target = track.id !in likedIds
                        AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                        cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                    }
                },
                onAddToQueue = {
                    scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                    cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
                },
                onAddToPlaylist = { addToPlaylistTrack = track },
            )
        }

        addToPlaylistTrack?.let { track ->
            cp.player.app.ui.component.AddToPlaylistSheet(
                trackId = track.id,
                onDismiss = { addToPlaylistTrack = null },
            )
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    detail: PlaylistDetail,
    onPlayAll: () -> Unit,
    onTrackClick: (Int) -> Unit,
    onTrackOptions: (cp.player.kmp.music.TrackSummary) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        Modifier.size(112.dp).clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!detail.summary.coverUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = detail.summary.coverUrl,
                                contentDescription = detail.summary.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(Icons.Filled.MusicNote, null, Modifier.size(40.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.summary.name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${detail.tracks.size} 首歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!detail.description.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                detail.description!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onPlayAll, enabled = detail.tracks.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.size(8.dp))
                            Text("播放全部")
                        }
                    }
                }
            }
        }

        if (detail.tracks.isEmpty()) {
            item {
                ContentState(title = "歌单中暂无歌曲", message = "稍后再来看看")
            }
        } else {
            itemsIndexed(detail.tracks, key = { _, track -> track.id }) { index, track ->
                SongItem(
                    track = track,
                    showIndex = true,
                    index = index,
                    total = detail.tracks.size,
                    onClick = { onTrackClick(index) },
                    onOptionsClick = { onTrackOptions(track) },
                )
            }
        }
    }
}
