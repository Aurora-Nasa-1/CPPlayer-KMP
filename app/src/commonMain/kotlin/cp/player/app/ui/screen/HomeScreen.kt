package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LazyScrollRow
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.QuickAccessSection
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.HomeScreenModel
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.app.ui.util.resized
import cp.player.core.BackendResult
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
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
    val hotPlaylists = state.hotPlaylists
    val newSongs = state.newSongs
    val playlistCount = recommendedPlaylists.size + hotPlaylists.size
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

    val dailyPlaylist = remember(dailySongs) {
        PlaylistSummary(
            id = -101L,
            name = "每日推荐",
            coverUrl = dailySongs.firstOrNull()?.coverUrl,
            trackCount = dailySongs.size,
            creatorName = "CPPlayer",
        )
    }
    val similarPlaylist = remember(dailySongs) {
        PlaylistSummary(
            id = -103L,
            name = "相似歌曲",
            coverUrl = dailySongs.firstOrNull()?.coverUrl,
            trackCount = dailySongs.size,
            creatorName = "CPPlayer",
        )
    }
    val intelligencePlaylist = remember(dailySongs) {
        PlaylistSummary(
            id = -104L,
            name = "心动模式",
            coverUrl = dailySongs.firstOrNull()?.coverUrl,
            trackCount = dailySongs.size,
            creatorName = "CPPlayer",
        )
    }
    val playDailyTrack: (TrackSummary) -> Unit = { track ->
        navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs, dailySongs.indexOf(track).coerceAtLeast(0)))
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
            hotPlaylists = hotPlaylists,
            newSongs = newSongs,
            userPlaylists = userPlaylists,
            recentTracks = recentTracks,
            error = error,
            onRefresh = model::refresh,
            onFmRecommendClick = { navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs)) },
            onPersonalFmClick = model::playPersonalFm,
            onIntelligenceClick = {
                navigator.push(
                    HomeGeneratedPlaylistScreen(
                        intelligencePlaylist,
                        emptyList(),
                        0,
                        HomeGeneratedPlaylistKind.IntelligenceFromDaily,
                        seedTrackId = dailySongs.firstOrNull()?.id,
                    )
                )
            },
            onSimilarClick = {
                navigator.push(
                    HomeGeneratedPlaylistScreen(
                        similarPlaylist,
                        emptyList(),
                        0,
                        HomeGeneratedPlaylistKind.SimilarFromDaily,
                        seedTrackId = dailySongs.firstOrNull()?.id,
                    )
                )
            },
            onPlaylistClick = { navigator.push(PlaylistDetailScreen(it)) },
            onSongClick = playDailyTrack,
            onRecentTrackClick = { _, index -> playRecentAt(index) },
            onRecentTrackOptionsClick = { selectedTrack = it },
            onRecentMoreClick = { navigator.push(RecentPlaysScreen()) },
        )
    } else LazyScrollColumn(
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
                fmOnRecommendClick = { navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs)) },
                fmOnPersonalFmClick = model::playPersonalFm,
                onIntelligenceClick = {
                    navigator.push(
                        HomeGeneratedPlaylistScreen(
                            intelligencePlaylist,
                            emptyList(),
                            0,
                            HomeGeneratedPlaylistKind.IntelligenceFromDaily,
                            seedTrackId = dailySongs.firstOrNull()?.id,
                        )
                    )
                },
                onSimilarClick = {
                    navigator.push(
                        HomeGeneratedPlaylistScreen(
                            similarPlaylist,
                            emptyList(),
                            0,
                            HomeGeneratedPlaylistKind.SimilarFromDaily,
                            seedTrackId = dailySongs.firstOrNull()?.id,
                        )
                    )
                },
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
                        onOpenPlaylist = { navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs)) },
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
                LazyScrollRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recommendedPlaylists.take(20)) { playlist ->
                        PlaylistCoverCard(
                            playlist = playlist,
                            onClick = { navigator.push(PlaylistDetailScreen(playlist)) },
                        )
                    }
                }
            }
        }

        if (newSongs.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "推荐新歌",
                    supportingText = "来自网易云推荐新歌",
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    newSongs.take(8).forEachIndexed { index, track ->
                        SongItem(
                            track = track,
                            index = index,
                            total = newSongs.take(8).size,
                            onClick = {
                                scope.launch {
                                    AppModel.playback.playQueue(newSongs.map { toMediaId(it.id) }, index)
                                }
                            },
                            onOptionsClick = { selectedTrack = track },
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
        if (dailySongs.isEmpty() && playlistCount == 0 && error == null) {
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
        val favId = runCatching { cp.player.core.music.CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
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

/** 桌面首页歌单区行数（推荐 / 热门共用）。两行是「一屏看得完」和「有内容感」的平衡点。 */
private const val PLAYLIST_ROWS = 2

/**
 * 桌面首页「最近播放」展示条数。
 *
 * 12 条按两列排是 6 行，高度正好和左侧「每日推荐」的 5 行曲目卡齐平 ——
 * 这两个数字是绑定的，改一个就要回头看另一个，否则底部对齐会重新错开。
 */
private const val DESKTOP_RECENT_COUNT = 12

@Composable
private fun DesktopHomeLayout(
    dailySongs: List<TrackSummary>,
    recommendedPlaylists: List<PlaylistSummary>,
    hotPlaylists: List<PlaylistSummary>,
    newSongs: List<TrackSummary>,
    userPlaylists: List<PlaylistSummary>,
    recentTracks: List<TrackSummary>,
    error: String?,
    onRefresh: () -> Unit,
    onFmRecommendClick: () -> Unit,
    onPersonalFmClick: () -> Unit,
    onIntelligenceClick: () -> Unit,
    onSimilarClick: () -> Unit,
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
        val horizontalPadding = responsiveDp(widthValue, min = 24f, max = 48f, start = 1200f, end = 2200f)
        // 栅格按**实际内容宽度**换算 —— 窗口宽度里含两侧留白与滚动条槽，
        // 宽屏下直接拿它去除会多算出 1–2 列，卡片被压窄。
        val contentWidth = (widthValue - horizontalPadding.value * 2f)
            .coerceAtMost(CpSpacing.pageMaxWidth.value)
        val playlistColumns = CpSpacing.gridColumns(contentWidth.dp)

        val recommendedItems = recommendedPlaylists.take(playlistColumns * PLAYLIST_ROWS)
        val hotItems = hotPlaylists.take(playlistColumns * PLAYLIST_ROWS)
        val recentItems = recentTracks.take(DESKTOP_RECENT_COUNT)

        ScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 24.dp,
                bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // ⚠️ 顺序不能反：`fillMaxWidth()` 会把 min/max 都钉死成可用宽度，
                // 之后 `widthIn(max = …)` 拿到的入参约束已经是固定值，clamp 后等于没写
                // —— 旧版就是 `fillMaxWidth().widthIn(1480…)`，所以正文其实一直铺到边缘，
                // 大屏上卡片被拉到 200dp 以上、一行塞七八张，是「桌面端难看」的主因。
                modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // ── 发现音乐：整宽色带，给页面一条稳定的顶边 ──
                QuickAccessSection(
                    fmOnRecommendClick = onFmRecommendClick,
                    fmOnPersonalFmClick = onPersonalFmClick,
                    onIntelligenceClick = onIntelligenceClick,
                    onSimilarClick = onSimilarClick,
                    userPlaylists = userPlaylists,
                    onPlaylistClick = onPlaylistClick,
                )

                // ── 主体：每日推荐（主内容）+ 最近播放（侧轨）──
                // `IntrinsicSize.Max` 让两张卡**底部对齐**：以前两列各自按内容长高，
                // 差出七八十像素的参差边缘，是大屏上最显眼的「没做完」感。
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (dailySongs.isNotEmpty()) {
                        DailyMixCard(
                            songs = dailySongs,
                            onSongClick = onSongClick,
                            onOpenPlaylist = { onFmRecommendClick() },
                            modifier = Modifier.weight(1.15f).fillMaxHeight(),
                            compact = true,
                        )
                    }
                    DenseSongSection(
                        title = "最近播放",
                        tracks = recentItems,
                        columns = 2,
                        emptyTitle = "还没有最近播放",
                        emptyMessage = "播放歌曲后会显示在这里",
                        onTrackClick = onRecentTrackClick,
                        onTrackOptionsClick = onRecentTrackOptionsClick,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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
                }

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

                if (recommendedItems.isNotEmpty()) {
                    DensePlaylistSection(
                        title = "推荐歌单",
                        supportingText = "点击歌单进入详情，查看完整曲目后再播放",
                        playlists = recommendedItems,
                        columns = playlistColumns,
                        rows = PLAYLIST_ROWS,
                        onPlaylistClick = onPlaylistClick,
                    )
                }

                if (hotItems.isNotEmpty()) {
                    DensePlaylistSection(
                        title = "热门歌单",
                        supportingText = "大家正在收藏的歌单",
                        playlists = hotItems,
                        columns = playlistColumns,
                        rows = PLAYLIST_ROWS,
                        onPlaylistClick = onPlaylistClick,
                    )
                }

                if (dailySongs.isEmpty() && recommendedPlaylists.isEmpty() && hotPlaylists.isEmpty() && error == null) {
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
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val expanded = LocalIsExpanded.current
    val cover = songs.firstOrNull()?.coverUrl
    // 有封面 ⇒ 整张卡走「压图上白字」；没有封面 ⇒ 必须换成主题配色。
    // 旧版无条件铺黑色渐变 + 写死白字：浅色主题下 surfaceContainerLow 几乎是白的，
    // 标题和曲目直接糊成一片（浅色模式实测完全不可读），是「浅色很难看」的主因。
    val overImage = !cover.isNullOrBlank()
    val bgHeight = when {
        compact -> 118.dp
        expanded -> 140.dp
        else -> 200.dp
    }
    val titleColor = if (overImage) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor =
        if (overImage) Color.White.copy(alpha = 0.76f) else MaterialTheme.colorScheme.onSurfaceVariant
    val actionContainer =
        if (overImage) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer
    val actionContent =
        if (overImage) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    // compact（桌面主体左栏）走两列轨道：10 首 = 5 行，正好和右栏「最近播放」6 行等高。
    val previewTracks = if (compact) songs.take(10) else songs.take(4)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (overImage) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover.resized(600),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(bgHeight),
                    contentScale = ContentScale.Crop,
                )
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
            }
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
                            color = titleColor,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (compact) "大屏日推 ${songs.size} 首，优先展示更多可点歌曲" else "${songs.size} 首 · 根据你的口味生成",
                            style = MaterialTheme.typography.bodySmall,
                            color = subtitleColor,
                        )
                    }
                    Surface(
                        onClick = onOpenPlaylist,
                        shape = MaterialTheme.shapes.medium,
                        color = actionContainer,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = actionContent, modifier = Modifier.size(18.dp))
                            Text("播放全部", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = actionContent)
                        }
                    }
                }
                if (compact) {
                    DailySongRail(
                        songs = previewTracks,
                        onSongClick = onSongClick,
                        overImage = overImage,
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
    overImage: Boolean,
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
                // Wide desktop keeps daily recommendations visible in a compact 2-column rail.
                rowTracks.forEach { track ->
                    CompactTrackCard(
                        track = track,
                        onClick = { onSongClick(track) },
                        modifier = Modifier.weight(1f),
                        overImage = overImage,
                    )
                }
                repeat(2 - rowTracks.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 桌面歌单栅格：按 [columns] 列切成若干 `Row`，每张卡 `weight(1f)`。
 *
 * 不用 `LazyHorizontalGrid` + 固定高度：那个高度只能写成 `rows * (cardWidth + 20)`
 * 这种猜出来的公式，而卡片真实高度由封面边长决定 —— 于是窄屏会裁掉最后一行，
 * 宽屏底部留一条空白。改成等分 `Row` 后高度完全由内容决定，而且每行**正好铺满**
 * 内容宽度，右端不会留下半张卡的缺口。
 *
 * 横向滚动也一并去掉了：桌面首页只展示 `columns * rows` 张，
 * 想看更多应该走「更多」入口，而不是在一个纵向页面里再套一层横向滚动。
 */
@Composable
private fun DensePlaylistSection(
    title: String,
    supportingText: String,
    playlists: List<PlaylistSummary>,
    columns: Int,
    rows: Int,
    onPlaylistClick: (PlaylistSummary) -> Unit,
) {
    val visible = playlists.take((columns * rows).coerceAtLeast(1))
    if (visible.isEmpty()) return
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
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                visible.chunked(columns.coerceAtLeast(1)).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        rowItems.forEach { playlist ->
                            PlaylistCoverCard(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist) },
                                modifier = Modifier.weight(1f),
                                fillWidth = true,
                            )
                        }
                        // 末行不足一列时补等宽占位，否则剩下的卡片会被拉成两倍宽。
                        repeat(columns.coerceAtLeast(1) - rowItems.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DenseSongSection(
    title: String,
    tracks: List<TrackSummary>,
    columns: Int,
    emptyTitle: String,
    emptyMessage: String,
    onTrackClick: (TrackSummary, Int) -> Unit,
    onTrackOptionsClick: (TrackSummary) -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    ExpressiveListCard(
        title = title,
        modifier = modifier,
        trailing = action,
    ) {
        if (tracks.isEmpty()) {
            ContentState(
                title = emptyTitle,
                message = emptyMessage,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            // 按「隔列取样」而不是「连续切块」分列：这样左列永远拿到第 1、3、5… 首，
            // 曲目顺序是**从左到右再换行**，符合直觉；连续切块会变成先读完整列。
            val columnCount = columns.coerceAtLeast(1)
            val distributed = remember(tracks, columnCount) {
                List(columnCount) { columnIndex ->
                    tracks.filterIndexed { index, _ -> index % columnCount == columnIndex }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                distributed.forEachIndexed { columnIndex, columnTracks ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        columnTracks.forEachIndexed { indexInColumn, track ->
                            val originalIndex = columnIndex + indexInColumn * columnCount
                            SongItem(
                                track = track,
                                index = indexInColumn,
                                total = columnTracks.size,
                                onClick = { onTrackClick(track, originalIndex) },
                                onOptionsClick = { onTrackOptionsClick(track) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「每日推荐」轨道里的紧凑曲目卡。
 *
 * @param overImage 卡片是否压在封面图上。压在图上时用白色半透明叠加（唯一能保证
 *   在任意封面上都可读的做法）；否则必须回落到主题色 —— 浅色主题下
 *   `Color.White` 文字放在浅灰容器上等于隐形。
 */
@Composable
private fun CompactTrackCard(
    track: TrackSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overImage: Boolean = false,
) {
    val titleColor = if (overImage) Color.White else MaterialTheme.colorScheme.onSurface
    val artistColor =
        if (overImage) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        onClick = onClick,
        color = if (overImage) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                    .background(
                        if (overImage) Color.White.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
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
                        tint = if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = artistColor,
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

enum class HomeGeneratedPlaylistKind {
    Static,
    SimilarFromDaily,
    IntelligenceFromDaily,
}

class HomeGeneratedPlaylistScreen(
    private val playlist: PlaylistSummary,
    private val initialTracks: List<TrackSummary>,
    private val startIndex: Int = 0,
    private val kind: HomeGeneratedPlaylistKind = HomeGeneratedPlaylistKind.Static,
    /** 拉取相似 / 心动歌曲的种子曲目 id（这类页面本身不携带曲目，种子必须由调用方传入）。 */
    private val seedTrackId: String? = null,
) : Screen {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { PlaylistDetailScreenModel() }
        val sourceTracks by rememberUpdatedState(initialTracks)
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(kind, playlist.id, seedTrackId, sourceTracks.firstOrNull()?.id) {
            when (kind) {
                HomeGeneratedPlaylistKind.Static -> Unit
                HomeGeneratedPlaylistKind.SimilarFromDaily -> {
                    val seed = seedTrackId ?: sourceTracks.firstOrNull()?.id ?: return@LaunchedEffect
                    val result = try {
                        AppModel.musicRepository.getSimilarSongs(seed)
                    } catch (e: Exception) {
                        BackendResult.Error(e.message ?: "获取相似歌曲失败", cause = e)
                    }
                    val tracks = (result as? BackendResult.Success)?.data.orEmpty()
                    navigator.replace(HomeGeneratedPlaylistScreen(playlist, tracks))
                }
                HomeGeneratedPlaylistKind.IntelligenceFromDaily -> {
                    val seed = seedTrackId ?: sourceTracks.firstOrNull()?.id ?: return@LaunchedEffect
                    val result = try {
                        AppModel.musicRepository.getIntelligenceSongs(seed)
                    } catch (e: Exception) {
                        BackendResult.Error(e.message ?: "获取心动歌曲失败", cause = e)
                    }
                    val tracks = (result as? BackendResult.Success)?.data.orEmpty()
                    navigator.replace(HomeGeneratedPlaylistScreen(playlist, tracks))
                }
            }
        }
        PlaylistDetailContent(
            playlist = playlist,
            model = model,
            embedded = false,
            onEmbeddedBack = null,
            initialOverrideTracks = initialTracks,
            autoPlayIndex = startIndex,
            isLocalPlaylist = true,
            // 相似 / 心动歌曲要先按种子拉取再 replace，拉取期间保持加载态，
            // 否则会先闪一屏"歌单暂无歌曲"。
            loadingOverride = kind != HomeGeneratedPlaylistKind.Static,
        )
    }
}

class RecentPlaysScreen : Screen {
    @Composable
    override fun Content() {
        val recentTracks by AppModel.recentTracksFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }
        val navigator = LocalNavigator.currentOrThrow

        Column(Modifier.fillMaxSize()) {
            PageTitleBar(
                title = "最近播放",
                subtitle = "完整历史列表",
                onBack = { navigator.pop() },
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
                LazyScrollColumn(
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
            val favId = runCatching { cp.player.core.music.CPMediaId.parse(track.id).resourceId }.getOrDefault(track.id)
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
    onBack: (() -> Unit)? = null,
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
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
        }
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
