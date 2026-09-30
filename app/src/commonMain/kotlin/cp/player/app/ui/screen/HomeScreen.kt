package cp.player.app.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LazyScrollRow
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.HomeScreenModel
import cp.player.app.ui.model.HomeUiState
import cp.player.app.ui.model.NewSongRegion
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.app.ui.model.PlaylistSource
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.util.resized
import cp.player.core.BackendResult
import cp.player.core.music.AlbumSummary
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.RankingSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeScreen : Screen {
    @Composable
    override fun Content() {
        HomeScreenContent(rememberScreenModel { HomeScreenModel() })
    }
}

/**
 * 首页。
 *
 * ### 数据来源
 * 页面上的每一块都对应一条真实的后端读路径，没有纯装饰的入口卡片：
 * 焦点图 `banner`、每日推荐 `recommend/songs`、发现歌单 `personalized` / `top_playlist` /
 * `top_playlist_highquality`、排行榜 `toplist`、新碟上架 `top_album`、热门歌手 `top_artists`、
 * 新歌速递 `top_song`、最近播放（本地历史）。
 *
 * ### 与上一版的差异
 * - **删掉**：桌面顶部四张等宽「每日推荐 / 私人FM / 心动模式 / 相似歌曲」入口卡 ——
 *   它们没有任何数据，其中「每日推荐」还与正下方的日推大卡完全重复；
 * - **删掉**：日推卡里的随机封面拼图 —— 布局每次进页面都变，点击命中的还是另一首歌
 *   （封面列表过滤了空值、曲目列表没有，两个下标错位），既不可预期也没有信息量；
 * - **删掉**：与「推荐歌单」同构的「热门歌单」区块，改为同一区块内的来源分段切换；
 * - **新增**：焦点图轮播、排行榜、新碟上架、热门歌手、带地区切换的新歌速递。
 */
@Composable
private fun HomeScreenContent(model: HomeScreenModel) {
    val state by model.state.collectAsState()
    val loading = state.loading
    var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val recentTracks by AppModel.recentTracksFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.currentOrThrow
    val provider = AppModel.activeProviderId()
    val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }

    val dailySongs = state.dailySongs
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

    val playRecentAt: (Int) -> Unit = { index ->
        recentTracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        scope.launch {
            AppModel.playback.playQueue(recentTracks.map { toMediaId(it.id) }, startIndex = index)
        }
    }
    val playTracks: (List<TrackSummary>, Int) -> Unit = { tracks, index ->
        tracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        scope.launch {
            AppModel.playback.playQueue(tracks.map { toMediaId(it.id) }, startIndex = index)
        }
    }

    val actions = HomeActions(
        onRefresh = model::refresh,
        onPlaylistSourceChange = model::selectPlaylistSource,
        onNewSongRegionChange = model::selectNewSongRegion,
        onBannerClick = { banner ->
            if (banner.targetType == BANNER_TARGET_SONG) {
                playTracks(
                    listOf(TrackSummary(banner.targetId, banner.title, "", null, banner.imageUrl, 0L)),
                    0,
                )
            } else {
                val playlistId = banner.targetId.toLongOrNull()
                // 拿不到数字 id 时退回搜索，而不是把用户丢在一个点不动的卡片上。
                if (playlistId == null) {
                    navigator.push(SearchScreen(banner.title))
                } else {
                    navigator.push(
                        PlaylistDetailScreen(
                            PlaylistSummary(
                                id = playlistId,
                                name = banner.title.ifBlank { "推荐歌单" },
                                coverUrl = banner.imageUrl,
                                trackCount = 0,
                                creatorName = null,
                            )
                        )
                    )
                }
            }
        },
        onOpenPlaylist = { navigator.push(PlaylistDetailScreen(it)) },
        onOpenRanking = { ranking ->
            navigator.push(
                PlaylistDetailScreen(
                    PlaylistSummary(
                        id = ranking.id,
                        name = ranking.name,
                        coverUrl = ranking.coverUrl,
                        trackCount = ranking.trackCount,
                        creatorName = "榜单",
                    )
                )
            )
        },
        // 专辑 / 歌手目前没有独立详情页，落到搜索结果是**真实可用的**落点
        // （上游按专辑名或歌手名搜单曲，命中的就是这张专辑 / 这位歌手的曲目）。
        // 卡片上带搜索图标，点了会发生什么对用户是可见的。
        onOpenAlbum = { navigator.push(SearchScreen(it.name)) },
        onOpenArtist = { navigator.push(SearchScreen(it.name)) },
        onOpenDaily = { navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs)) },
        onPlayDailyTrack = { track ->
            navigator.push(
                HomeGeneratedPlaylistScreen(
                    dailyPlaylist,
                    dailySongs,
                    dailySongs.indexOf(track).coerceAtLeast(0),
                )
            )
        },
        onPlayPersonalFm = model::playPersonalFm,
        onOpenIntelligence = {
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
        onOpenSimilar = {
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
        onRecentTrackClick = { _, index -> playRecentAt(index) },
        onRecentPlayAll = { playRecentAt(0) },
        onOpenRecentPlays = { navigator.push(RecentPlaysScreen()) },
        onNewSongPlay = playTracks,
        onTrackOptions = { selectedTrack = it },
    )

    if (loading) {
        // 加载态也走统一的页面宽度上限，否则「加载中」那张卡会比加载完的正文窄一圈，
        // 内容一到位就横向撑开。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StateSurface(
                Modifier.padding(horizontal = CpSpacing.pageHorizontal)
                    .widthIn(max = CpSpacing.pageMaxWidth),
            ) {
                ContentState(
                    title = "正在准备推荐",
                    message = "正在同步每日歌曲、焦点图与榜单",
                    loading = true,
                )
            }
        }
        return
    }

    if (LocalIsExpanded.current) {
        DesktopHomeLayout(state = state, recentTracks = recentTracks, actions = actions)
    } else {
        MobileHomeLayout(state = state, recentTracks = recentTracks, actions = actions)
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
                CoverFlight.play(track.id, track.coverUrl)
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

/** 焦点图里 `targetType = 1` 表示单曲。与 `MusicSourceFromApi` 的过滤名单保持一致。 */
private const val BANNER_TARGET_SONG = 1

/**
 * 首页各模块的回调集合。
 *
 * 桌面与移动两套布局需要几乎同一组回调，逐个当参数传会到二十个上下、且极易传错顺序。
 * ⚠️ 刻意**不用 `remember` 包**：它捕获了 `dailySongs` / `recentTracks` 这些会变的值，
 * 一旦被记住就会拿着旧快照去播错队列。每次重组重建这个对象是最省心的做法。
 */
private class HomeActions(
    val onRefresh: () -> Unit,
    val onPlaylistSourceChange: (PlaylistSource) -> Unit,
    val onNewSongRegionChange: (NewSongRegion) -> Unit,
    val onBannerClick: (BannerItem) -> Unit,
    val onOpenPlaylist: (PlaylistSummary) -> Unit,
    val onOpenRanking: (RankingSummary) -> Unit,
    val onOpenAlbum: (AlbumSummary) -> Unit,
    val onOpenArtist: (ArtistSummary) -> Unit,
    val onOpenDaily: () -> Unit,
    val onPlayDailyTrack: (TrackSummary) -> Unit,
    val onPlayPersonalFm: () -> Unit,
    val onOpenIntelligence: () -> Unit,
    val onOpenSimilar: () -> Unit,
    val onRecentTrackClick: (TrackSummary, Int) -> Unit,
    val onRecentPlayAll: () -> Unit,
    val onOpenRecentPlays: () -> Unit,
    val onNewSongPlay: (List<TrackSummary>, Int) -> Unit,
    val onTrackOptions: (TrackSummary) -> Unit,
)

// ============================================================ 桌面

/**
 * 桌面首页「发现歌单」行数。两行是「一屏看得完」和「有内容感」的平衡点。
 */
private const val PLAYLIST_ROWS = 2

/**
 * 桌面首页「最近播放」展示条数。
 *
 * 12 条按两列排是 6 行，高度正好和左侧「每日推荐」的 5 行曲目卡齐平 ——
 * 这两个数字是绑定的，改一个就要回头看另一个，否则底部对齐会重新错开。
 */
private const val DESKTOP_RECENT_COUNT = 12

/** 桌面焦点图高度。按 1400dp 内容宽、hero 占 2/3 算，约 4.2:1，与上游横幅素材比例接近。 */
private val DesktopBannerHeight = 224.dp

@Composable
private fun DesktopHomeLayout(
    state: HomeUiState,
    recentTracks: List<TrackSummary>,
    actions: HomeActions,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthValue = maxWidth.value
        val horizontalPadding = responsiveDp(widthValue, min = 24f, max = 48f, start = 1200f, end = 2200f)
        // 栅格按**实际内容宽度**换算 —— 窗口宽度里含两侧留白与滚动条槽，
        // 宽屏下直接拿它去除会多算出 1–2 列，卡片被压窄。
        val contentWidth = (widthValue - horizontalPadding.value * 2f)
            .coerceAtMost(CpSpacing.pageMaxWidth.value)
        val playlistColumns = CpSpacing.gridColumns(contentWidth.dp)

        val recommendedItems = state.visiblePlaylists.take(playlistColumns * PLAYLIST_ROWS)
        val recentItems = recentTracks.take(DESKTOP_RECENT_COUNT)
        val artistItems = state.hotArtists.take(playlistColumns)

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
                // 之后 `widthIn(max = …)` 拿到的入参约束已是固定值，clamp 后等于没写。
                modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // ── 焦点区：轮播图 + 快捷电台 ──
                Row(
                    modifier = Modifier.fillMaxWidth().height(DesktopBannerHeight),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.banners.isNotEmpty()) {
                        BannerCarousel(
                            banners = state.banners,
                            onClick = actions.onBannerClick,
                            modifier = Modifier.weight(2f).fillMaxHeight(),
                            height = DesktopBannerHeight,
                        )
                    } else {
                        // 没有焦点图时不留空框：换成一张**有真实数据**的速览卡，
                        // 而不是把快捷入口摊成一整条等宽卡片去填满版面。
                        DailySummaryCard(
                            dailyCount = state.dailySongs.size,
                            recentCount = recentTracks.size,
                            onOpenDaily = actions.onOpenDaily,
                            onOpenRecent = actions.onOpenRecentPlays,
                            modifier = Modifier.weight(2f).fillMaxHeight(),
                        )
                    }
                    HeroQuickPanel(
                        onPlayPersonalFm = actions.onPlayPersonalFm,
                        onOpenIntelligence = actions.onOpenIntelligence,
                        onOpenSimilar = actions.onOpenSimilar,
                        enabled = state.dailySongs.isNotEmpty(),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                // ── 每日推荐（主）+ 最近播放（侧轨）──
                // `IntrinsicSize.Max` 让两张卡**底部对齐**：各自按内容长高会差出几十像素的
                // 参差边缘，是大屏上最显眼的「没做完」感。
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (state.dailySongs.isNotEmpty()) {
                        DailyMixCard(
                            songs = state.dailySongs,
                            onSongClick = actions.onPlayDailyTrack,
                            onOpenPlaylist = actions.onOpenDaily,
                            modifier = Modifier.weight(1.15f).fillMaxHeight(),
                            compact = true,
                        )
                    }
                    RecentPlaysSection(
                        tracks = recentItems,
                        columns = 2,
                        onTrackClick = actions.onRecentTrackClick,
                        onTrackOptions = actions.onTrackOptions,
                        onOpenAll = actions.onOpenRecentPlays,
                        onPlayAll = actions.onRecentPlayAll,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                if (state.visiblePlaylists.isNotEmpty() || state.playlistSourceLoading) {
                    HomeSectionCard(
                        title = "发现歌单",
                        subtitle = playlistSourceHint(state.playlistSource),
                        trailing = {
                            PlaylistSourceChips(
                                selected = state.playlistSource,
                                onChange = actions.onPlaylistSourceChange,
                            )
                        },
                    ) {
                        if (state.playlistSourceLoading && recommendedItems.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                CpLoadingIndicator(Modifier.size(32.dp))
                            }
                        } else {
                            PlaylistGrid(
                                playlists = recommendedItems,
                                columns = playlistColumns,
                                onOpen = actions.onOpenPlaylist,
                            )
                        }
                    }
                }

                // ── 排行榜（列表）+ 新碟上架（栅格）──
                if (state.rankings.isNotEmpty() || state.newAlbums.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (state.rankings.isNotEmpty()) {
                            HomeSectionCard(
                                title = "排行榜",
                                subtitle = "点击进入榜单曲目",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    state.rankings.take(RANKING_ROWS).forEach { ranking ->
                                        RankingRow(
                                            ranking = ranking,
                                            onClick = { actions.onOpenRanking(ranking) },
                                        )
                                    }
                                }
                            }
                        }
                        if (state.newAlbums.isNotEmpty()) {
                            HomeSectionCard(
                                title = "新碟上架",
                                subtitle = "点击查看专辑曲目",
                                modifier = Modifier.weight(1.35f).fillMaxHeight(),
                            ) {
                                CoverTileGrid(
                                    columns = 3,
                                    rows = 2,
                                    items = state.newAlbums.take(6),
                                    cover = { it.coverUrl },
                                    title = { it.name },
                                    subtitle = { it.artistName ?: "未知歌手" },
                                    onClick = actions.onOpenAlbum,
                                    showSearchHint = true,
                                )
                            }
                        }
                    }
                }

                if (artistItems.isNotEmpty()) {
                    HomeSectionCard(
                        title = "热门歌手",
                        subtitle = "点击查看歌手曲目",
                    ) {
                        ArtistGrid(
                            artists = artistItems,
                            columns = playlistColumns,
                            onOpen = actions.onOpenArtist,
                        )
                    }
                }

                if (state.newSongs.isNotEmpty() || state.newSongsLoading) {
                    HomeSectionCard(
                        title = "新歌速递",
                        subtitle = "按地区查看最新发布",
                        trailing = {
                            NewSongRegionChips(
                                selected = state.newSongRegion,
                                onChange = actions.onNewSongRegionChange,
                            )
                        },
                    ) {
                        if (state.newSongsLoading && state.newSongs.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CpLoadingIndicator(Modifier.size(32.dp))
                            }
                        } else {
                            NewSongGrid(
                                songs = state.newSongs.take(8),
                                columns = 2,
                                onPlay = actions.onNewSongPlay,
                                onOptions = actions.onTrackOptions,
                            )
                        }
                    }
                }

                if (state.error != null) {
                    StateSurface {
                        ContentState(
                            title = "推荐内容未完全加载",
                            message = state.error,
                            error = true,
                            actionLabel = "重试",
                            onAction = actions.onRefresh,
                        )
                    }
                }

                if (state.isEmptyHome) {
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

/** 榜单列表在桌面侧最多展示几条 —— 与右侧「新碟上架」两行栅格的高度大致齐平。 */
private const val RANKING_ROWS = 5

private fun playlistSourceHint(source: PlaylistSource): String = when (source) {
    PlaylistSource.Recommended -> "根据近期收听持续更新"
    PlaylistSource.Hot -> "大家正在收藏的歌单"
    PlaylistSource.Premium -> "编辑精选的高质量歌单"
}

// ============================================================ 移动

private val MobileBannerHeight = 168.dp

@Composable
private fun MobileHomeLayout(
    state: HomeUiState,
    recentTracks: List<TrackSummary>,
    actions: HomeActions,
) {
    LazyScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = CpSpacing.pageTop,
            bottom = 32.dp,
            start = CpSpacing.pageHorizontal,
            end = CpSpacing.pageHorizontal,
        ),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
    ) {
        if (state.banners.isNotEmpty()) {
            item {
                BannerCarousel(
                    banners = state.banners,
                    onClick = actions.onBannerClick,
                    modifier = Modifier.fillMaxWidth(),
                    height = MobileBannerHeight,
                )
            }
        }

        item {
            HeroQuickRow(
                onPlayPersonalFm = actions.onPlayPersonalFm,
                onOpenIntelligence = actions.onOpenIntelligence,
                onOpenSimilar = actions.onOpenSimilar,
            )
        }

        if (state.dailySongs.isNotEmpty()) {
            item {
                DailyMixCard(
                    songs = state.dailySongs,
                    onSongClick = actions.onPlayDailyTrack,
                    onOpenPlaylist = actions.onOpenDaily,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.visiblePlaylists.isNotEmpty() || state.playlistSourceLoading) {
            item {
                // 分段放在标题**下方**而不是标题右侧：窄屏上两者同行必然横向溢出。
                Column {
                    SectionHeader(
                        title = "发现歌单",
                        supportingText = playlistSourceHint(state.playlistSource),
                    )
                    Spacer(Modifier.height(10.dp))
                    PlaylistSourceChips(
                        selected = state.playlistSource,
                        onChange = actions.onPlaylistSourceChange,
                    )
                }
            }
            item {
                if (state.playlistSourceLoading && state.visiblePlaylists.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CpLoadingIndicator(Modifier.size(32.dp))
                    }
                } else {
                    LazyScrollRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.visiblePlaylists.take(20)) { playlist ->
                            PlaylistCoverCard(
                                playlist = playlist,
                                onClick = { actions.onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }
        }

        if (state.rankings.isNotEmpty()) {
            item { SectionHeader(title = "排行榜", supportingText = "点击进入榜单曲目") }
            item {
                LazyScrollRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.rankings) { ranking ->
                        RankingTile(
                            ranking = ranking,
                            onClick = { actions.onOpenRanking(ranking) },
                            modifier = Modifier.width(148.dp),
                        )
                    }
                }
            }
        }

        if (state.newAlbums.isNotEmpty()) {
            item { SectionHeader(title = "新碟上架", supportingText = "点击查看专辑曲目") }
            item {
                LazyScrollRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.newAlbums.take(12)) { album ->
                        CoverTile(
                            coverUrl = album.coverUrl,
                            title = album.name,
                            subtitle = album.artistName ?: "未知歌手",
                            onClick = { actions.onOpenAlbum(album) },
                            modifier = Modifier.width(140.dp),
                            showSearchHint = true,
                        )
                    }
                }
            }
        }

        if (state.hotArtists.isNotEmpty()) {
            item { SectionHeader(title = "热门歌手", supportingText = "点击查看歌手曲目") }
            item {
                LazyScrollRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.hotArtists.take(12)) { artist ->
                        ArtistTile(
                            artist = artist,
                            onClick = { actions.onOpenArtist(artist) },
                            modifier = Modifier.width(112.dp),
                        )
                    }
                }
            }
        }

        if (state.newSongs.isNotEmpty() || state.newSongsLoading) {
            item {
                Column {
                    SectionHeader(title = "新歌速递", supportingText = "按地区查看最新发布")
                    Spacer(Modifier.height(10.dp))
                    NewSongRegionChips(
                        selected = state.newSongRegion,
                        onChange = actions.onNewSongRegionChange,
                    )
                }
            }
            item {
                if (state.newSongsLoading && state.newSongs.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CpLoadingIndicator(Modifier.size(32.dp))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val songs = state.newSongs.take(8)
                        songs.forEachIndexed { index, track ->
                            SongItem(
                                track = track,
                                index = index,
                                total = songs.size,
                                onClick = { actions.onNewSongPlay(songs, index) },
                                onOptionsClick = { actions.onTrackOptions(track) },
                            )
                        }
                    }
                }
            }
        }

        if (recentTracks.isNotEmpty()) {
            item {
                SectionHeader(title = "最近播放") {
                    FilledTonalButton(
                        onClick = actions.onRecentPlayAll,
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
                            onClick = { actions.onRecentTrackClick(track, index) },
                            onOptionsClick = { actions.onTrackOptions(track) },
                        )
                    }
                }
            }
        }

        if (state.error != null) {
            item {
                StateSurface {
                    ContentState(
                        title = "推荐内容未完全加载",
                        message = state.error,
                        error = true,
                        actionLabel = "重试",
                        onAction = actions.onRefresh,
                    )
                }
            }
        }
        if (state.isEmptyHome) {
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
}

// ============================================================ 模块

/**
 * 焦点图轮播。
 *
 * 自动翻页只做「用户没在手动翻」的时候：手动滑动期间再触发一次 `animateScrollToPage`
 * 会和手势抢控制权，表现为「滑到一半被弹走」。
 */
@Composable
internal fun BannerCarousel(
    banners: List<BannerItem>,
    onClick: (BannerItem) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = DesktopBannerHeight,
) {
    if (banners.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { banners.size })
    val scope = rememberCoroutineScope()

    if (banners.size > 1) {
        LaunchedEffect(banners.size) {
            while (true) {
                delay(BANNER_AUTO_ADVANCE_MS)
                if (!pagerState.isScrollInProgress) {
                    scope.launch {
                        pagerState.animateScrollToPage((pagerState.currentPage + 1) % banners.size)
                    }
                }
            }
        }
    }

    // ⚠️ `height` 必须真的挂在 Box 上。只把它当参数往下传、忘了用，Box 就会退化成
    // 「按内容高度」—— `HorizontalPager(fillMaxSize)` 在里面量不到确定高度，
    // 整条横幅会塌成一行标题那么高（移动端实测就是这样）。
    Box(modifier.height(height), contentAlignment = Alignment.BottomCenter) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val banner = banners[page]
            // 同 DailyMixCard：白字只在图**真的画出来**之后才用。否则浅色主题下
            // 标题是白字压浅色容器，等于没有。
            var loaded by remember(banner.imageUrl) { mutableStateOf(false) }
            Surface(
                onClick = { onClick(banner) },
                modifier = Modifier.fillMaxSize(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(Modifier.fillMaxSize()) {
                    // 占位块常驻底层：图没到 / 挂了时横幅仍是一块有内容的色块，
                    // 而不是一块空壳。
                    CpCoverPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        corner = 28.dp,
                        animated = false,
                    )
                    AsyncImage(
                        model = banner.imageUrl.resized(1000),
                        contentDescription = banner.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onSuccess = { loaded = true },
                        onError = { loaded = false },
                    )
                    if (loaded) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    // ⚠️ 用分数 colorStops：写绝对像素时 `startY` 一旦越过元素高度，
                                    // 方向会翻转、整块被 clamp 成末色。
                                    0.35f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.62f),
                                )
                            )
                        )
                    }
                    if (banner.title.isNotBlank()) {
                        Text(
                            text = banner.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (loaded) Color.White else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(
                                    start = 20.dp,
                                    // 指示器钉在右下角：标题必须给它让出位置，
                                    // 否则长标题会被圆点压住尾巴。
                                    end = if (banners.size > 1) 100.dp else 20.dp,
                                    bottom = 30.dp,
                                ),
                        )
                    }
                    if (banners.size > 1) {
                        // 指示器画在**页内**而不是 pager 外面：它要跟着这一页的
                        // 「图到底加载出来没有」换配色 —— 压在图上是白点，
                        // 落在浅色占位块上必须是主题色，否则白点直接看不见。
                        BannerDots(
                            count = banners.size,
                            current = pagerState.currentPage,
                            onImage = loaded,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 20.dp, bottom = 30.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val BANNER_AUTO_ADVANCE_MS = 7000L

/**
 * 焦点图指示器。
 *
 * @param onImage 当前这一页的图是否真的画出来了。压在图上的白点落在浅色占位块上会消失，
 *   所以这个状态必须传进来，不能写死颜色。
 */
@Composable
private fun BannerDots(
    count: Int,
    current: Int,
    onImage: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateFloatAsState(
                targetValue = if (selected) 20f else 8f,
                // 尺寸属于 spatial（可回弹），与 M3 指示器的行为一致。
                animationSpec = CpMotion.spatialFast(),
                label = "bannerDotWidth",
            )
            val active = if (onImage) Color.White else MaterialTheme.colorScheme.primary
            val inactive = if (onImage) Color.White.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            Box(
                Modifier
                    .size(width = width.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (selected) active else inactive)
            )
        }
    }
}

/**
 * 焦点区右侧的快捷电台。
 *
 * 与上一版被删掉的四张等宽入口卡的区别：这里是**一列**紧凑动作行，
 * 每行都有明确副标题说明会发生什么，不再占据一整条横向版面去撑高度。
 */
@Composable
private fun HeroQuickPanel(
    onPlayPersonalFm: () -> Unit,
    onOpenIntelligence: () -> Unit,
    onOpenSimilar: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "快捷电台",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
            HeroActionRow(
                icon = Icons.Filled.Radio,
                title = "私人 FM",
                subtitle = "连续播放，不用挑歌",
                onClick = onPlayPersonalFm,
            )
            HeroActionRow(
                icon = Icons.Filled.Favorite,
                title = "心动模式",
                subtitle = if (enabled) "围绕今日推荐延展" else "需要先有每日推荐",
                onClick = onOpenIntelligence,
                enabled = enabled,
            )
            HeroActionRow(
                icon = Icons.Filled.MusicNote,
                title = "相似歌曲",
                subtitle = if (enabled) "找和今日推荐相近的歌" else "需要先有每日推荐",
                onClick = onOpenSimilar,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun HeroActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = contentAlpha),
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 移动端快捷电台：三张等宽紧凑卡。 */
@Composable
private fun HeroQuickRow(
    onPlayPersonalFm: () -> Unit,
    onOpenIntelligence: () -> Unit,
    onOpenSimilar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroQuickTile(Icons.Filled.Radio, "私人 FM", onPlayPersonalFm, Modifier.weight(1f))
        HeroQuickTile(Icons.Filled.Favorite, "心动模式", onOpenIntelligence, Modifier.weight(1f))
        HeroQuickTile(Icons.Filled.MusicNote, "相似歌曲", onOpenSimilar, Modifier.weight(1f))
    }
}

@Composable
private fun HeroQuickTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 焦点图缺失时的替代卡片 —— 用真实的日推 / 最近播放数量撑起版面，
 * 而不是把快捷入口摊平成一整条等宽卡去填满空位。
 */
@Composable
private fun DailySummaryCard(
    dailyCount: Int,
    recentCount: Int,
    onOpenDaily: () -> Unit,
    onOpenRecent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "今日速览",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (dailyCount > 0) "已为你准备好今日推荐" else "登录后即可获得每日推荐",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryAction(
                    label = "每日推荐",
                    value = "$dailyCount 首",
                    onClick = onOpenDaily,
                    enabled = dailyCount > 0,
                    modifier = Modifier.weight(1f),
                )
                SummaryAction(
                    label = "最近播放",
                    value = "$recentCount 首",
                    onClick = onOpenRecent,
                    enabled = recentCount > 0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryAction(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f),
            )
        }
    }
}

/**
 * 首页通用区块卡片：标题 + 可选副标题 + 右侧动作，下面是内容。
 *
 * 全页统一走它，避免每个区块各自拼 `Surface` + `Row` + 字号 —— 那正是上一版
 * 「推荐歌单」「热门歌单」两块看起来像两个不同页面拼在一起的原因。
 *
 * @param contentPadding 内容区左右内边距。默认 18dp；列表类内容（每行自带内边距）
 *   可以调小，否则会出现双重缩进。
 */
@Composable
private fun HomeSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(bottom = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(contentPadding)) { content() }
        }
    }
}

/**
 * 歌单来源分段。**横向可滚动**：窄屏上三个 ToggleButton 加标题会横向溢出，
 * 而分段控件一旦被裁掉一半，用户就看不到「还有别的来源」。
 */
@Composable
private fun PlaylistSourceChips(
    selected: PlaylistSource,
    onChange: (PlaylistSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlaylistSource.entries.forEach { source ->
            CpToggleChip(
                checked = selected == source,
                onCheckedChange = { checked -> if (checked) onChange(source) },
                label = source.label,
            )
        }
    }
}

/** 新歌速递的地区分段。五个选项在窄屏上必然放不下，同样走横向滚动。 */
@Composable
private fun NewSongRegionChips(
    selected: NewSongRegion,
    onChange: (NewSongRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NewSongRegion.entries.forEach { region ->
            CpToggleChip(
                checked = selected == region,
                onCheckedChange = { checked -> if (checked) onChange(region) },
                label = region.label,
            )
        }
    }
}

/**
 * 歌单栅格：按 [columns] 列切成若干 `Row`，每张卡 `weight(1f)`。
 *
 * 不用 `LazyHorizontalGrid` + 固定高度：那个高度只能写成 `rows * (cardWidth + 20)`
 * 这种猜出来的公式，窄屏会裁掉最后一行、宽屏底部留一条空白。等分 `Row` 后高度完全
 * 由内容决定，且每行**正好铺满**内容宽度。
 */
@Composable
private fun PlaylistGrid(
    playlists: List<PlaylistSummary>,
    columns: Int,
    onOpen: (PlaylistSummary) -> Unit,
) {
    if (playlists.isEmpty()) return
    val safeColumns = columns.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        playlists.chunked(safeColumns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowItems.forEach { playlist ->
                    PlaylistCoverCard(
                        playlist = playlist,
                        onClick = { onOpen(playlist) },
                        modifier = Modifier.weight(1f),
                        fillWidth = true,
                    )
                }
                // 末行不足一列时补等宽占位，否则剩下的卡片会被拉成两倍宽。
                repeat(safeColumns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 通用封面栅格。
 *
 * 排行榜 / 新碟上架共用同一套卡片，避免两处各写一份「封面 + 标题 + 副标题」的
 * 近似实现 —— 上一版首页里同一个「封面方块」就有三种画法。
 */
@Composable
private fun <T> CoverTileGrid(
    columns: Int,
    rows: Int,
    items: List<T>,
    cover: (T) -> String?,
    title: (T) -> String,
    subtitle: (T) -> String,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier,
    showSearchHint: Boolean = false,
) {
    if (items.isEmpty()) return
    val safeColumns = columns.coerceAtLeast(1)
    val visible = items.take((safeColumns * rows).coerceAtLeast(1))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        visible.chunked(safeColumns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowItems.forEach { item ->
                    CoverTile(
                        coverUrl = cover(item),
                        title = title(item),
                        subtitle = subtitle(item),
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f),
                        showSearchHint = showSearchHint,
                    )
                }
                repeat(safeColumns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 封面方块卡：1:1 封面 + 底部压暗渐变 + 标题 / 副标题。
 *
 * @param showSearchHint 点击后落到**搜索结果**而不是详情页时置 true，卡片右上角会出现
 *   一枚搜索图标 —— 让「点了会去哪」在点击之前就是可见的。
 */
@Composable
private fun CoverTile(
    coverUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp,
    showSearchHint: Boolean = false,
) {
    val overImage = !coverUrl.isNullOrBlank()
    Column(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            // 统一占位块永远铺在最底层。封面 URL 有值但图还没到 / 已经挂了时，
            // 只靠 AsyncImage 会留下一块空壳 —— 一个 1:1 的空白加底部一条孤零零的暗带，
            // 看起来像「渲染坏了」而不是「这张图暂时没有」。
            CpCoverPlaceholder(
                modifier = Modifier.fillMaxSize(),
                corner = corner,
                animated = false,
            )
            if (overImage) {
                AsyncImage(
                    model = coverUrl.resized(400),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(corner)),
                    contentScale = ContentScale.Crop,
                )
            }
            // 压在图上的白字必须有兜底：没有封面时回落到主题色。
            val titleColor = if (overImage) Color.White else MaterialTheme.colorScheme.onSurface
            val subtitleColor =
                if (overImage) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
            if (overImage) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(
                        // ⚠️ 分数 colorStops：绝对像素的 startY 一旦越过元素高度会翻转方向。
                        Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.62f),
                        )
                    )
                )
            }
            Column(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showSearchHint) {
                Surface(
                    shape = CircleShape,
                    color = if (overImage) Color.Black.copy(alpha = 0.42f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Search, "查看曲目",
                            tint = if (overImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 榜单列表行（桌面侧栏用）。 */
@Composable
private fun RankingRow(
    ranking: RankingSummary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(48.dp)) {
                if (!ranking.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ranking.coverUrl.resized(200),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    CpCoverPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        corner = 12.dp,
                        animated = false,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    ranking.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    rankingSubtitle(ranking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.TrendingUp, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun rankingSubtitle(ranking: RankingSummary): String {
    val frequency = ranking.updateFrequency?.takeIf { it.isNotBlank() }
    val count = if (ranking.trackCount > 0) "${ranking.trackCount} 首" else null
    return listOfNotNull(frequency, count).joinToString(" · ").ifBlank { "榜单" }
}

/** 榜单封面卡（移动端横向列表用）。 */
@Composable
private fun RankingTile(
    ranking: RankingSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CoverTile(
        coverUrl = ranking.coverUrl,
        title = ranking.name,
        subtitle = ranking.updateFrequency,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ArtistGrid(
    artists: List<ArtistSummary>,
    columns: Int,
    onOpen: (ArtistSummary) -> Unit,
) {
    if (artists.isEmpty()) return
    val safeColumns = columns.coerceAtLeast(1)
    val visible = artists.take(safeColumns)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        visible.forEach { artist ->
            ArtistTile(artist = artist, onClick = { onOpen(artist) }, modifier = Modifier.weight(1f))
        }
        repeat(safeColumns - visible.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ArtistTile(
    artist: ArtistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val avatarShape = CircleShape
            // 占位块常驻最底层，并勾一圈描边：深色主题下 primaryContainer 与卡片色非常接近，
            // 不描边的话整排歌手看起来就是 7 个「洞」，而不是 7 个待填充的头像位。
            Box(
                Modifier.fillMaxSize().clip(avatarShape).border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    shape = avatarShape,
                )
            ) {
                CpCoverPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    corner = 100.dp,
                    animated = false,
                )
            }
            if (!artist.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artist.avatarUrl.resized(300),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(avatarShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 新歌速递的曲目栅格：桌面两列、移动一列。 */
@Composable
private fun NewSongGrid(
    songs: List<TrackSummary>,
    columns: Int,
    onPlay: (List<TrackSummary>, Int) -> Unit,
    onOptions: (TrackSummary) -> Unit,
) {
    if (songs.isEmpty()) return
    val safeColumns = columns.coerceAtLeast(1)
    // 按「隔列取样」而不是「连续切块」分列：这样左列拿到第 1、3、5… 首，
    // 曲目顺序是从左到右再换行，符合直觉；连续切块会变成先读完整列。
    val distributed = remember(songs, safeColumns) {
        List(safeColumns) { columnIndex ->
            songs.filterIndexed { index, _ -> index % safeColumns == columnIndex }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        distributed.forEachIndexed { columnIndex, columnTracks ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                columnTracks.forEachIndexed { indexInColumn, track ->
                    val originalIndex = columnIndex + indexInColumn * safeColumns
                    SongItem(
                        track = track,
                        index = originalIndex,
                        total = songs.size,
                        onClick = { onPlay(songs, originalIndex) },
                        onOptionsClick = { onOptions(track) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 「最近播放」区块（桌面）。空态时给出说明，而不是留一张空卡片。
 */
@Composable
private fun RecentPlaysSection(
    tracks: List<TrackSummary>,
    columns: Int,
    onTrackClick: (TrackSummary, Int) -> Unit,
    onTrackOptions: (TrackSummary) -> Unit,
    onOpenAll: () -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeSectionCard(
        title = "最近播放",
        subtitle = if (tracks.isEmpty()) "播放过的歌会出现在这里" else "接着上次继续听",
        modifier = modifier,
        trailing = {
            if (tracks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenAll) { Text("更多") }
                    FilledTonalButton(
                        onClick = onPlayAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("播放", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp),
    ) {
        if (tracks.isEmpty()) {
            ContentState(
                title = "还没有最近播放",
                message = "播放歌曲后会显示在这里",
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            val safeColumns = columns.coerceAtLeast(1)
            val distributed = remember(tracks, safeColumns) {
                List(safeColumns) { columnIndex ->
                    tracks.filterIndexed { index, _ -> index % safeColumns == columnIndex }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                distributed.forEachIndexed { columnIndex, columnTracks ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        columnTracks.forEachIndexed { indexInColumn, track ->
                            val originalIndex = columnIndex + indexInColumn * safeColumns
                            SongItem(
                                track = track,
                                index = indexInColumn,
                                total = columnTracks.size,
                                onClick = { onTrackClick(track, originalIndex) },
                                onOptionsClick = { onTrackOptions(track) },
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
 * 「每日推荐」大卡。
 *
 * @param compact 桌面主体左栏用 true：两列轨道、展示 10 首，高度与右栏「最近播放」齐平。
 */
@Composable
private fun DailyMixCard(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cover = songs.firstOrNull()?.coverUrl
    val hasCover = !cover.isNullOrBlank()
    // ⚠️ 「有没有封面」不能只看 URL 非空。URL 有值但图还没到 / 已经挂了时，压在图上的
    // 那套白字配色会落到浅色容器上 —— 浅色主题下标题、副标题、以及整排曲目卡会**全部消失**
    // （离屏渲染实测，不是理论风险）。只有 AsyncImage 真的成功后才切到白字配色，
    // 失败或加载中就老老实实用主题色。
    var coverLoaded by remember(cover) { mutableStateOf(false) }
    val overImage = hasCover && coverLoaded
    val bgHeight = if (compact) 118.dp else 132.dp
    val titleColor = if (overImage) Color.White else MaterialTheme.colorScheme.onSurface
    val subtitleColor =
        if (overImage) Color.White.copy(alpha = 0.76f) else MaterialTheme.colorScheme.onSurfaceVariant
    val actionContainer =
        if (overImage) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer
    val actionContent =
        if (overImage) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    // compact（桌面主体左栏）走两列轨道：10 首 = 5 行，正好和右栏「最近播放」6 行齐平。
    val previewTracks = if (compact) songs.take(10) else songs.take(4)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (overImage) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (hasCover) {
                AsyncImage(
                    model = cover.resized(600),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(bgHeight),
                    contentScale = ContentScale.Crop,
                    onSuccess = { coverLoaded = true },
                    onError = { coverLoaded = false },
                )
            }
            if (overImage) {
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
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = titleColor,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (compact) "${songs.size} 首 · 大屏展示更多可点歌曲"
                            else "${songs.size} 首 · 根据你的口味生成",
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
                            Text(
                                "播放全部",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = actionContent,
                            )
                        }
                    }
                }
                DailySongRail(
                    songs = previewTracks,
                    onSongClick = onSongClick,
                    overImage = overImage,
                    columns = if (compact) 2 else 1,
                )
            }
        }
    }
}

@Composable
private fun DailySongRail(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    overImage: Boolean,
    columns: Int,
) {
    val safeColumns = columns.coerceAtLeast(1)
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        songs.chunked(safeColumns).forEach { rowTracks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTracks.forEach { track ->
                    CompactTrackCard(
                        track = track,
                        onClick = { onSongClick(track) },
                        modifier = Modifier.weight(1f),
                        overImage = overImage,
                    )
                }
                repeat(safeColumns - rowTracks.size) { Spacer(Modifier.weight(1f)) }
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

private fun responsiveFloat(width: Float, min: Float, max: Float, start: Float, end: Float): Float {
    if (width <= start) return min
    if (width >= end) return max
    val progress = (width - start) / (end - start)
    return min + (max - min) * progress
}

private fun responsiveDp(width: Float, min: Float, max: Float, start: Float, end: Float) =
    responsiveFloat(width, min, max, start, end).dp

// ============================================================ 附属页面

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
            // 否则会先闪一屏「歌单暂无歌曲」。
            loadingOverride = kind != HomeGeneratedPlaylistKind.Static,
        )
    }
}

/**
 * 最近播放完整列表。
 *
 * @param embedded 是否内嵌在桌面右侧面板里。为真时不画页内返回键 ——
 *   那种形态下标题与返回都由窗口标题栏提供，而且 `pop()` 会退出 [MainScreen] 本身。
 */
class RecentPlaysScreen(private val embedded: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        val recentTracks by AppModel.recentTracksFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }
        val navigator = LocalNavigator.current
        val expanded = LocalIsExpanded.current

        Column(Modifier.fillMaxSize()) {
            // 标题与正文同宽、一起居中：宽屏下标题贴着窗口最左而正文居中，两段会明显错位。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()) {
                    PageTitleBar(
                        title = "最近播放",
                        subtitle = "完整历史列表 · 共 ${recentTracks.size} 首",
                        onBack = if (embedded) null else { { navigator?.pop() } },
                        action = {
                            if (recentTracks.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = {
                                        recentTracks.firstOrNull()?.let { CoverFlight.play(it.id, it.coverUrl) }
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
                }
            }

            if (recentTracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    StateSurface(
                        modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
                            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 16.dp),
                    ) {
                        ContentState(
                            title = "还没有最近播放",
                            message = "播放歌曲后会显示在这里",
                        )
                    }
                }
            } else {
                // 宽屏两列：一整行只放一首歌时，「歌名」和右端的「更多」按钮相隔上千 dp，
                // 视线要来回跳；两列之后每列 ≈700dp，与歌单详情页的曲目宽度相近。
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val columns = if (maxWidth >= 900.dp) 2 else 1
                    val rows = if (columns == 1) {
                        recentTracks.map { listOf(it) }
                    } else {
                        recentTracks.chunked(columns)
                    }
                    LazyScrollColumn(
                        modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = CpSpacing.pageHorizontal,
                            end = CpSpacing.pageHorizontal,
                            top = 8.dp,
                            // 宽屏的 MiniPlayer 浮在底部，末尾要给它留一条安全带。
                            bottom = if (expanded) 96.dp else 32.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(rows) { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEachIndexed { columnIndex, track ->
                                    val index = rowIndex * columns + columnIndex
                                    SongItem(
                                        track = track,
                                        index = index,
                                        total = recentTracks.size,
                                        onClick = {
                                            CoverFlight.play(track.id, track.coverUrl)
                                            scope.launch {
                                                AppModel.playback.playQueue(
                                                    recentTracks.map { toMediaId(it.id) },
                                                    startIndex = index,
                                                )
                                            }
                                        },
                                        onOptionsClick = { selectedTrack = track },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                // 末尾一行是奇数时补等宽空位，否则最后一条会被单独拉满整行。
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
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
                    CoverFlight.play(track.id, track.coverUrl)
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
