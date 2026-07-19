package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.QuickAccessSection
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.HomeScreenModel
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.launch
import kotlin.random.Random

class HomeScreen : Screen {
    @Composable
    override fun Content() {
        HomeScreenContent(rememberScreenModel { HomeScreenModel() })
    }
}

@Composable
private fun HomeScreenContent(model: HomeScreenModel) {
    val state by model.state.collectAsState()
    val dailySongs = state.dailySongs
    val recommendedPlaylists = state.recommendedPlaylists
    val userPlaylists = state.userPlaylists
    val loading = state.loading
    val error = state.error
    var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val recentTracks by AppModel.recentTracksFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.currentOrThrow
    val provider = AppModel.activeProviderId()

    if (loading) {
        Column(Modifier.fillMaxSize()) {
            StateSurface(Modifier.padding(horizontal = CpSpacing.pageHorizontal)) {
                ContentState(
                    title = "正在准备推荐",
                    message = "正在同步每日歌曲与歌单",
                    loading = true,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = CpSpacing.pageTop,
            bottom = 32.dp,
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
        ),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
    ) {
        item {
            QuickAccessSection(
                fmOnRecommendClick = {
                    if (dailySongs.isNotEmpty()) {
                        scope.launch {
                            AppModel.playback.playQueue(
                                dailySongs.map { "$provider://song/${it.id}" }, startIndex = 0,
                            )
                        }
                    }
                },
                fmOnPersonalFmClick = model::playPersonalFm,
                userPlaylists = userPlaylists,
                onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
            )
        }

        if (dailySongs.isNotEmpty()) {
            item {
                DailyMixCard(
                    songs = dailySongs,
                    onSongClick = { track ->
                        scope.launch {
                            AppModel.playback.playQueue(
                                dailySongs.map { "$provider://song/${it.id}" },
                                startIndex = dailySongs.indexOf(track).coerceAtLeast(0),
                            )
                        }
                    },
                )
            }
        }

        if (recommendedPlaylists.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "推荐歌单",
                    supportingText = "根据近期收听持续更新",
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(recommendedPlaylists.take(20)) { _, playlist ->
                        PlaylistCoverCard(
                            playlist = playlist,
                            onClick = { navigator.push(PlaylistDetailScreen(playlist)) },
                        )
                    }
                }
            }
        }

        if (recentTracks.isNotEmpty()) {
            item {
                SectionHeader(title = "最近播放") {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                AppModel.playback.playQueue(
                                    recentTracks.map { "$provider://song/${it.id}" },
                                    startIndex = 0,
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("播放", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recentTracks.take(5).forEachIndexed { index, track ->
                        SongItem(
                            track = track,
                            index = index,
                            total = recentTracks.take(5).size,
                            onClick = {
                                scope.launch {
                                    AppModel.playback.playQueue(
                                        recentTracks.map { "$provider://song/${it.id}" }, startIndex = index,
                                    )
                                }
                            },
                            onOptionsClick = { selectedTrack = track },
                        )
                    }
                }
            }
        }

        if (error != null) {
            item {
                StateSurface {
                    ContentState(
                        title = "推荐内容未完全加载",
                        message = error,
                        error = true,
                        actionLabel = "重试",
                        onAction = model::refresh,
                    )
                }
            }
        }
        if (dailySongs.isEmpty() && recommendedPlaylists.isEmpty() && error == null) {
            item {
                StateSurface {
                    ContentState(
                        title = "还没有个性化推荐",
                        message = "登录账号后即可同步每日歌曲与歌单",
                    )
                }
            }
        }
    }

    selectedTrack?.let { track ->
        SongOptionsSheet(
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

@Composable
private fun DailyMixCard(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverUrl = songs.firstOrNull()?.coverUrl
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(200.dp), contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(200.dp).background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.48f),
                            0.4f to Color.Black.copy(alpha = 0.16f),
                            0.72f to MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.42f),
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                ),
            )
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每日推荐", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(2.dp))
                        Text("${songs.size} 首 · 根据你的口味生成", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                    Surface(
                        onClick = { onSongClick(songs.first()) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.White.copy(alpha = 0.2f),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("播放全部", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                }
                MosaicCoverGrid(songs = songs, onSongClick = onSongClick)
            }
        }
    }
}

@Composable
private fun MosaicCoverGrid(songs: List<TrackSummary>, onSongClick: (TrackSummary) -> Unit) {
    val urls = songs.map { it.coverUrl ?: "" }.filter { it.isNotEmpty() }
    if (urls.isEmpty()) return
    val gridCols = 6; val gridRows = 4; val gap = 2.dp

    val seed = remember(songs) { songs.take(6).fold(System.currentTimeMillis()) { acc, s -> acc * 31 + s.id.hashCode().toLong() } }
    val tiles = remember(seed) {
        val rng = Random(seed)
        val occupied = Array(gridRows) { BooleanArray(gridCols) }
        val result = mutableListOf<MosaicTile>()
        var urlIdx = 0
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until gridRows - 1) for (c in 0 until gridCols - 1) candidates.add(c to r)
        candidates.shuffle(rng)
        var big = 0
        for ((c, r) in candidates) {
            if (big >= 4) break
            if (!occupied[r][c] && !occupied[r][c + 1] && !occupied[r + 1][c] && !occupied[r + 1][c + 1]) {
                occupied[r][c] = true; occupied[r][c + 1] = true; occupied[r + 1][c] = true; occupied[r + 1][c + 1] = true
                result.add(MosaicTile(c, r, 2, urlIdx++ % urls.size))
                big++
            }
        }
        for (r in 0 until gridRows) for (c in 0 until gridCols) {
            if (!occupied[r][c]) result.add(MosaicTile(c, r, 1, urlIdx++ % urls.size))
        }
        result.take(12)
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val totalW = constraints.maxWidth.toFloat()
        val gapPx = with(density) { gap.toPx() }
        val cellW = (totalW - (gridCols - 1) * gapPx) / gridCols
        val cellPx = cellW
        val totalH = gridRows * cellPx + (gridRows - 1) * gapPx
        val totalHDp = with(density) { totalH.toDp() }
        val cr = 6.dp

        Box(Modifier.fillMaxWidth().height(totalHDp)) {
            for (tile in tiles) {
                val s = tile.span
                val wPx = s * cellW + (s - 1) * gapPx; val hPx = s * cellPx + (s - 1) * gapPx
                val xPx = tile.col * (cellW + gapPx); val yPx = tile.row * (cellPx + gapPx)
                val song = songs.getOrNull(tile.urlIndex)
                AsyncImage(
                    model = urls[tile.urlIndex % urls.size],
                    contentDescription = song?.name,
                    modifier = Modifier
                        .offset(x = with(density) { xPx.toDp() }, y = with(density) { yPx.toDp() })
                        .size(width = with(density) { wPx.toDp() }, height = with(density) { hPx.toDp() })
                        .clip(RoundedCornerShape(cr))
                        .clickable(enabled = song != null) { song?.let { onSongClick(it) } },
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

private data class MosaicTile(val col: Int, val row: Int, val span: Int, val urlIndex: Int)
