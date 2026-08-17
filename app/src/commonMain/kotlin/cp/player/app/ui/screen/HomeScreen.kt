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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.ExpressiveListCard
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.QuickAccessSection
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.HomeScreenModel
import cp.player.app.ui.util.resized
import cp.player.kmp.music.PlaylistSummary
import cp.player.kmp.music.TrackSummary
import kotlinx.coroutines.launch
import kotlin.math.max
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
    val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }

    if (loading) {
        Column(Modifier.fillMaxSize()) {
            StateSurface(
                Modifier.padding(horizontal = CpSpacing.pageHorizontal)
                    .widthIn(max = 1320.dp),
            ) {
                ContentState(
                    title = "正在准备推荐",
                    message = "正在同步每日歌曲与歌单",
                    loading = true,
                )
            }
        }
        return
    }

    val playDailyQueue = {
        if (dailySongs.isNotEmpty()) {
            scope.launch {
                AppModel.playback.playQueue(
                    dailySongs.map { toMediaId(it.id) },
                    startIndex = 0,
                )
            }
        }
    }
    val playDailyTrack: (TrackSummary) -> Unit = { track ->
        scope.launch {
            AppModel.playback.playQueue(
                dailySongs.map { toMediaId(it.id) },
                startIndex = dailySongs.indexOf(track).coerceAtLeast(0),
            )
        }
    }
    val playRecentAt: (Int) -> Unit = { index ->
        scope.launch {
            AppModel.playback.playQueue(
                recentTracks.map { toMediaId(it.id) },
                startIndex = index,
            )
        }
    }

    if (LocalIsExpanded.current) {
        DesktopHomeLayout(
            dailySongs = dailySongs,
            recommendedPlaylists = recommendedPlaylists,
            userPlaylists = userPlaylists,
            recentTracks = recentTracks,
            error = error,
            onRefresh = model::refresh,
            onFmRecommendClick = playDailyQueue,
            onPersonalFmClick = model::playPersonalFm,
            onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
            onSongClick = playDailyTrack,
            onRecentTrackClick = { _, index -> playRecentAt(index) },
            onRecentTrackOptionsClick = { selectedTrack = it },
            onRecentMoreClick = { navigator.push(RecentPlaysScreen()) },
        )
    } else LazyColumn(
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
                fmOnRecommendClick = playDailyQueue,
                fmOnPersonalFmClick = model::playPersonalFm,
                userPlaylists = userPlaylists,
                onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
            )
        }

        if (dailySongs.isNotEmpty()) {
            item {
                val expanded = LocalIsExpanded.current
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    DailyMixCard(
                        songs = dailySongs,
                        onSongClick = playDailyTrack,
                        modifier = if (expanded) Modifier.widthIn(max = 700.dp) else Modifier,
                    )
                }
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
                    items(recommendedPlaylists.take(20)) { playlist ->
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
                        onClick = { playRecentAt(0) },
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
                            onClick = { playRecentAt(index) },
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
        // 最近播放记录里保存的是完整 mediaId（如 netease://song/123），收藏集合是裸 id，需解析后再比较
        val favId = runCatching { cp.player.kmp.music.CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
        SongOptionsSheet(
            songName = track.name,
            artistName = track.artist,
            coverUrl = track.coverUrl,
            isFavorite = favId in likedIds,
            isDownloaded = AppModel.isDownloaded(track.id),
            onDismiss = { selectedTrack = null },
            onPlay = {
                scope.launch {
                    AppModel.playback.playQueue(listOf(toMediaId(track.id)), startIndex = 0)
                }
            },
            onToggleFavorite = {
                scope.launch {
                    val target = favId !in likedIds
                    AppModel.playback.toggleFavoriteFor(toMediaId(track.id))
                    cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                }
            },
            onAddToQueue = {
                scope.launch { AppModel.playback.addToQueue(toMediaId(track.id)) }
                cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
            },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onDownload = { AppModel.downloadTrack(track) },
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
private fun DesktopHomeLayout(
    dailySongs: List<TrackSummary>,
    recommendedPlaylists: List<PlaylistSummary>,
    userPlaylists: List<PlaylistSummary>,
    recentTracks: List<TrackSummary>,
    error: String?,
    onRefresh: () -> Unit,
    onFmRecommendClick: () -> Unit,
    onPersonalFmClick: () -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onSongClick: (TrackSummary) -> Unit,
    onRecentTrackClick: (TrackSummary, Int) -> Unit,
    onRecentTrackOptionsClick: (TrackSummary) -> Unit,
    onRecentMoreClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val widthValue = maxWidth.value
        val horizontalPadding = responsiveDp(widthValue, min = 24f, max = 52f, start = 1200f, end = 2200f)
        val contentMaxWidth = responsiveDp(widthValue, min = 1480f, max = 1840f, start = 1360f, end = 2200f)
        val mainWeight = responsiveFloat(widthValue, min = 1.22f, max = 1.52f, start = 1280f, end = 2200f)
        val sideWeight = responsiveFloat(widthValue, min = 0.94f, max = 1.1f, start = 1280f, end = 2200f)
        val playlistCardWidth = responsiveDp(widthValue, min = 144f, max = 184f, start = 1280f, end = 2200f)
        val recentCardWidth = responsiveDp(widthValue, min = 296f, max = 380f, start = 1280f, end = 2200f)
        val recommendedCapacity = ((contentMaxWidth.value / playlistCardWidth.value) * 2.3f).toInt().coerceIn(12, 21)
        val recentCapacity = ((maxWidth.value / recentCardWidth.value) * 3.2f).toInt().coerceIn(8, 14)
        val recommendedItems = recommendedPlaylists.take(recommendedCapacity)
        val recentItems = recentTracks.take(recentCapacity)
        val recommendedRows = (((recommendedItems.size * playlistCardWidth.value) / contentMaxWidth.value).toInt() + 1)
            .coerceIn(2, 4)
        val recentRows = (((recentItems.size * recentCardWidth.value) / (contentMaxWidth.value * 0.42f)).toInt() + 1)
            .coerceIn(2, 4)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 20.dp,
                    bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 20.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = contentMaxWidth),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(mainWeight),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    QuickAccessSection(
                        fmOnRecommendClick = onFmRecommendClick,
                        fmOnPersonalFmClick = onPersonalFmClick,
                        userPlaylists = userPlaylists,
                        onPlaylistClick = onPlaylistClick,
                    )
                    if (dailySongs.isNotEmpty()) {
                        DailyMixCard(
                            songs = dailySongs,
                            onSongClick = onSongClick,
                            modifier = Modifier.fillMaxWidth(),
                            compact = true,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(sideWeight),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    DenseSongSection(
                        title = "最近播放",
                        supportingText = "接着上次的节奏继续",
                        tracks = recentItems,
                        rows = recentRows,
                        cardWidth = recentCardWidth,
                        emptyTitle = "还没有最近播放",
                        emptyMessage = "播放歌曲后会显示在这里",
                        onTrackClick = onRecentTrackClick,
                        onTrackOptionsClick = onRecentTrackOptionsClick,
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onRecentMoreClick) {
                                    Text("更多")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        if (recentTracks.isNotEmpty()) onRecentTrackClick(recentTracks.first(), 0)
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text("播放", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        },
                    )
                    if (error != null) {
                        StateSurface {
                            ContentState(
                                title = "推荐内容未完全加载",
                                message = error,
                                error = true,
                                actionLabel = "重试",
                                onAction = onRefresh,
                            )
                        }
                    }
                }
            }

            if (recommendedPlaylists.isNotEmpty()) {
                DensePlaylistSection(
                    title = "推荐歌单",
                    supportingText = "参考旧版信息编排，保留当前高效数据流",
                    playlists = recommendedItems,
                    rows = recommendedRows,
                    cardWidth = playlistCardWidth,
                    onPlaylistClick = onPlaylistClick,
                )
            }

                if (dailySongs.isEmpty() && recommendedPlaylists.isEmpty() && error == null) {
                    StateSurface {
                        ContentState(
                            title = "还没有个性化推荐",
                            message = "登录账号后即可同步每日歌曲与歌单",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyMixCard(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val expanded = LocalIsExpanded.current
    val coverUrl = songs.firstOrNull()?.coverUrl
    val bgHeight = when {
        compact -> 118.dp
        expanded -> 140.dp
        else -> 200.dp
    }
    val previewTracks = if (compact) songs.take(6) else songs.take(4)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl.resized(600),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(bgHeight),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.fillMaxWidth().height(bgHeight).background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.5f),
                            0.38f to Color.Black.copy(alpha = 0.18f),
                            0.72f to MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f),
                            1.0f to MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                ),
            )
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "每日推荐",
                            style = if (compact) MaterialTheme.typography.titleLarge else if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (compact) "大屏重排 ${songs.size} 首，优先给你更多可点内容" else "${songs.size} 首 · 根据你的口味生成",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.76f),
                        )
                    }
                    Surface(
                        onClick = { songs.firstOrNull()?.let(onSongClick) },
                        shape = MaterialTheme.shapes.medium,
                        color = Color.White.copy(alpha = 0.2f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text("播放全部", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                }
                if (compact) {
                    DailySongRail(
                        songs = previewTracks,
                        onSongClick = onSongClick,
                    )
                } else {
                    MosaicCoverGrid(
                        songs = songs,
                        onSongClick = onSongClick,
                        gridRows = if (expanded) 3 else 4,
                    )
                }
            }
        }
    }
}

@Composable
private fun DailySongRail(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        songs.chunked(2).forEach { rowTracks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTracks.forEach { track ->
                    CompactTrackCard(
                        track = track,
                        onClick = { onSongClick(track) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(2 - rowTracks.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DensePlaylistSection(
    title: String,
    supportingText: String,
    playlists: List<PlaylistSummary>,
    rows: Int,
    cardWidth: androidx.compose.ui.unit.Dp,
    onPlaylistClick: (PlaylistSummary) -> Unit,
) {
    ExpressiveListCard(title = title, trailing = null) {
        Column(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyHorizontalGrid(
                rows = GridCells.Fixed(rows),
                modifier = Modifier.fillMaxWidth().height((rows * (cardWidth + 20.dp).value).dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(playlists) { _, playlist ->
                    PlaylistCoverCard(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun DenseSongSection(
    title: String,
    supportingText: String,
    tracks: List<TrackSummary>,
    rows: Int,
    cardWidth: androidx.compose.ui.unit.Dp,
    emptyTitle: String,
    emptyMessage: String,
    onTrackClick: (TrackSummary, Int) -> Unit,
    onTrackOptionsClick: (TrackSummary) -> Unit,
    action: @Composable (() -> Unit)? = null,
) {
    ExpressiveListCard(
        title = title,
        trailing = action,
    ) {
        if (tracks.isEmpty()) {
            ContentState(
                title = emptyTitle,
                message = emptyMessage,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(rows),
                modifier = Modifier.fillMaxWidth().height((rows * 82).dp)
                    .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(tracks) { index, track ->
                    SongItem(
                        track = track,
                        index = index,
                        total = tracks.size,
                        onClick = { onTrackClick(track, index) },
                        onOptionsClick = { onTrackOptionsClick(track) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactTrackCard(
    track: TrackSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        color = Color.White.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                if (!track.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.coverUrl.resized(180),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MosaicCoverGrid(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    gridRows: Int = 4,
) {
    val urls = songs.map { it.coverUrl ?: "" }.filter { it.isNotEmpty() }
    if (urls.isEmpty()) return
    val gridCols = 6
    val gap = 2.dp

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
                occupied[r][c] = true
                occupied[r][c + 1] = true
                occupied[r + 1][c] = true
                occupied[r + 1][c + 1] = true
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
                val wPx = s * cellW + (s - 1) * gapPx
                val hPx = s * cellPx + (s - 1) * gapPx
                val xPx = tile.col * (cellW + gapPx)
                val yPx = tile.row * (cellPx + gapPx)
                val song = songs.getOrNull(tile.urlIndex)
                AsyncImage(
                    model = urls[tile.urlIndex % urls.size].resized(200),
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

private fun responsiveFloat(width: Float, min: Float, max: Float, start: Float, end: Float): Float {
    if (width <= start) return min
    if (width >= end) return max
    val progress = (width - start) / (end - start)
    return min + (max - min) * progress
}

private fun responsiveDp(width: Float, min: Float, max: Float, start: Float, end: Float) =
    responsiveFloat(width, min, max, start, end).dp

class RecentPlaysScreen : Screen {
    @Composable
    override fun Content() {
        val recentTracks by AppModel.recentTracksFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }

        Column(Modifier.fillMaxSize()) {
            PageTitleBar(
                title = "最近播放",
                subtitle = "完整历史列表",
                action = {
                    if (recentTracks.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    AppModel.playback.playQueue(recentTracks.map { toMediaId(it.id) }, startIndex = 0)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("播放全部")
                        }
                    }
                },
            )

            if (recentTracks.isEmpty()) {
                StateSurface(
                    modifier = Modifier.padding(horizontal = CpSpacing.pageHorizontal, vertical = 16.dp),
                ) {
                    ContentState(
                        title = "还没有最近播放",
                        message = "播放歌曲后会显示在这里",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = CpSpacing.pageHorizontal,
                        end = CpSpacing.pageHorizontal,
                        top = 8.dp,
                        bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(recentTracks) { index, track ->
                        SongItem(
                            track = track,
                            index = index,
                            total = recentTracks.size,
                            onClick = {
                                scope.launch {
                                    AppModel.playback.playQueue(
                                        recentTracks.map { toMediaId(it.id) },
                                        startIndex = index,
                                    )
                                }
                            },
                            onOptionsClick = { selectedTrack = track },
                        )
                    }
                }
            }
        }

        selectedTrack?.let { track ->
            val favId = runCatching { cp.player.kmp.music.CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
            SongOptionsSheet(
                songName = track.name,
                artistName = track.artist,
                coverUrl = track.coverUrl,
                isFavorite = favId in likedIds,
                isDownloaded = AppModel.isDownloaded(track.id),
                onDismiss = { selectedTrack = null },
                onPlay = {
                    scope.launch {
                        AppModel.playback.playQueue(listOf(toMediaId(track.id)), startIndex = 0)
                    }
                },
                onToggleFavorite = {
                    scope.launch {
                        val target = favId !in likedIds
                        AppModel.playback.toggleFavoriteFor(toMediaId(track.id))
                        cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                    }
                },
                onAddToQueue = {
                    scope.launch { AppModel.playback.addToQueue(toMediaId(track.id)) }
                    cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
                },
                onAddToPlaylist = {},
                onDownload = { AppModel.downloadTrack(track) },
            )
        }
    }
}

@Composable
private fun PageTitleBar(
    title: String,
    subtitle: String,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
            top = CpSpacing.pageTop,
            bottom = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.invoke()
    }
}
