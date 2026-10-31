package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.component.CpLinearProgress
import cp.player.app.ui.component.CpPlayingEqualizer
import cp.player.app.ui.component.CpWavyProgress
import cp.player.app.ui.component.cpBounceClickable
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.BentoCard
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRefreshablePage
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.wall.PosterWallIcon
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LazyScrollRow
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.ProvideCpTextFullDisplay
import cp.player.app.ui.component.desktopScrollbarGutter
import cp.player.app.ui.component.ProviderFeatureGate
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.platform.shareText
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.model.HomeScreenModel
import cp.player.app.ui.model.HomeUiState
import cp.player.app.ui.model.NewSongRegion
import cp.player.app.ui.model.PlaylistDetailScreenModel
import cp.player.app.ui.model.PlaylistSource
import cp.player.app.ui.theme.CpMotion
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.core.BackendResult
import cp.player.core.music.AlbumSummary
import cp.player.core.provider.ProviderCapability
import cp.player.core.music.ArtistSummary
import cp.player.core.music.BannerItem
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.RankingSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * 桌面首屏优先展示每日歌曲与播放状态，最近播放直接铺成栅格；
 * 焦点图置于发现歌单之后。移动端保留原有纵向布局。
 */
@Composable
private fun HomeScreenContent(model: HomeScreenModel) {
    val state by model.state.collectAsState()
    val loading = state.loading
    var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    // 「歌曲信息」的目标曲目。与其余页面同款：弹层里点「歌曲信息」→ 打开共享的
    // SongDetailInfoDialog（`song/detail` 元数据）。
    var showInfoTarget by remember { mutableStateOf<TrackSummary?>(null) }
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val recentTracks by AppModel.recentTracksFlow.collectAsState()
    // ⚠️ 位置**先除到秒**再进快照：引擎每 200ms 推一次位置，照原样收会让首页
    // 每秒重组 5 次。首页只用它画一条进度条，秒级精度足够。
    // `distinctUntilChanged` 去掉同一秒内的重复推送 —— 这是省掉绝大部分重组的关键。
    val nowPlaying by remember {
        AppModel.playback.state
            .map { st ->
                NowPlayingSnapshot(
                    track = st.currentTrack,
                    isPlaying = st.isPlaying,
                    positionMs = st.positionMs / 1000L * 1000L,
                    durationMs = st.durationMs,
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = NowPlayingSnapshot(track = null, isPlaying = false))
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.currentOrThrow
    // 响应式读当前音源：切源后这里先重组，保证随后的播放请求打上新源的 mediaId 前缀
    // （数据刷新由 HomeScreenModel 订阅 sourceGeneration 负责，这里只管「接下来播谁」）。
    val activeProvider by AppModel.activeProviderFlow.collectAsState()
    val provider = activeProvider?.id ?: AppModel.activeProviderId()
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
    val intelligencePlaylist = remember(dailySongs) {
        PlaylistSummary(
            id = -104L,
            name = "心动模式",
            coverUrl = dailySongs.firstOrNull()?.coverUrl,
            trackCount = dailySongs.size,
            creatorName = "CPPlayer",
        )
    }

    // 点歌统一走 [AppModel.playTrackClicked]：一起听进行中 = 「下一首播放」，
    // 否则原样立即播放（下同，不再逐处注释）。
    val playRecentAt: (Int) -> Unit = { index ->
        recentTracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val ids = recentTracks.map { toMediaId(it.id) }
        val clicked = ids.getOrNull(index)
        if (clicked != null) {
            AppModel.playTrackClicked(clicked) {
                AppModel.playback.playQueue(ids, startIndex = index)
            }
        }
    }
    val playTracks: (List<TrackSummary>, Int) -> Unit = { tracks, index ->
        tracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val ids = tracks.map { toMediaId(it.id) }
        val clicked = ids.getOrNull(index)
        if (clicked != null) {
            AppModel.playTrackClicked(clicked) {
                AppModel.playback.playQueue(ids, startIndex = index)
            }
        }
    }

    // 播放页的全屏入口要用**根** Navigator（见下面 onOpenPlayer 的说明）。
    // `.current` 是 composable 调用，只能在组合作用域读一次，lambda 里捕获引用。
    val rootNavigator = cp.player.app.ui.util.LocalRootNavigator.current

    val actions = HomeActions(
        onRefresh = { model.refresh() },
        onPlaylistSourceChange = model::selectPlaylistSource,
        onNewSongRegionChange = model::selectNewSongRegion,
        onBannerClick = { banner ->
            if (banner.targetType == BANNER_TARGET_SONG) {
                playTracks(
                    listOf(TrackSummary(banner.targetId, banner.title, "", null, banner.imageUrl, 0L)),
                    0,
                )
            } else {
                val playlistId = banner.targetId.takeIf { it.isNotBlank() }
                if (playlistId == null) {
                    navigator.push(SearchScreen(banner.title))
                } else {
                    navigator.push(
                        PlaylistDetailScreen(
                            PlaylistSummary(
                                id = playlistId.toLongOrNull() ?: 0L,
                                resourceId = playlistId,
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
        onOpenPlaylist = {
            CoverFlight.openPlaylist(it.resourceId, it.coverUrl)
            navigator.push(PlaylistDetailScreen(it))
        },
        onOpenRanking = { ranking ->
            CoverFlight.openPlaylist(ranking.resourceId, ranking.coverUrl)
            navigator.push(
                PlaylistDetailScreen(
                    PlaylistSummary(
                        id = ranking.id,
                        resourceId = ranking.resourceId,
                        name = ranking.name,
                        coverUrl = ranking.coverUrl,
                        trackCount = ranking.trackCount,
                        creatorName = "榜单",
                    )
                )
            )
        },
        onOpenAlbumWall = {
            navigator.push(AlbumWallScreen(initialSource = "recent"))
        },
        // 专辑 / 歌手目前没有独立详情页，落到搜索结果是**真实可用的**落点
        // （上游按专辑名或歌手名搜单曲，命中的就是这张专辑 / 这位歌手的曲目）。
        // 卡片上带搜索图标，点了会发生什么对用户是可见的。
        onOpenAlbum = { navigator.push(SearchScreen(it.name)) },
        onOpenArtist = { navigator.push(SearchScreen(it.name)) },
        onOpenDaily = { navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs)) },
        onPlayDailyTrack = { track ->
            val index = dailySongs.indexOf(track).coerceAtLeast(0)
            playTracks(dailySongs, index)
            navigator.push(HomeGeneratedPlaylistScreen(dailyPlaylist, dailySongs))
        },
        onPlayPersonalFm = model::playPersonalFm,
        onOpenIntelligence = {
            if (AppModel.activeProvider() == null) {
                navigator.push(LocalLibraryScreen())
                return@HomeActions
            }
            // 网易云的心动模式语义是「跟随当前播放列表」：`pid` = 队列的来源歌单，
            // 种子 = 正在播放的那首（eapi `type=fromPlayOne`）。队列没有歌单来源时
            // 按 收藏夹 → 第一个用户歌单 回退（旧项目同款回退链）。
            // ⚠️ 日推是**生成队列不是歌单**，它自己给不出合法 pid —— 这正是
            // 「心动模式恒空」的根因之一，所以 pid 绝不能只依赖入口处的静态数据。
            val queueSource = AppModel.playback.state.value.sourceId
                ?.toLongOrNull()?.takeIf { it > 0 }
            val pid = queueSource
                ?: state.likedPlaylist?.id
                ?: state.userPlaylists.firstOrNull()?.id ?: 0L
            // 种子按 在播曲目 → 红心歌曲 → 日推第一首 回退；在播曲目可能是
            // 带命名空间的 mediaId，还原成裸资源 id 再交给上游。
            val playingSeed = nowPlaying.track?.id?.let { id ->
                runCatching { cp.player.core.music.CPMediaId.parse(id).resourceId }.getOrDefault(id)
            }
            val seedId = playingSeed
                ?: likedIds.shuffled().firstOrNull()
                ?: dailySongs.shuffled().firstOrNull()?.id
            navigator.push(
                HomeGeneratedPlaylistScreen(
                    intelligencePlaylist,
                    emptyList(),
                    // 不传 startIndex：打开心动模式只浏览，点了具体曲目才播。
                    kind = HomeGeneratedPlaylistKind.IntelligenceFromDaily,
                    seedTrackId = seedId,
                    seedPlaylistId = pid,
                )
            )
        },
        // 相似歌曲入口已从首页废弃（2026-10-02）：它现在以**当前在播曲目**为种子，
        // 归属播放页（桌面「相似」页签 / 窄屏第 4 页，见 SimilarSongsPanel）。
        // 首页这个位置换成「最近播放」入口 —— 不依赖登录与日推数据，且与
        // 桌面焦点区有在播时的「继续收听」卡同一语义族。
        onRecentTrackClick = { _, index -> playRecentAt(index) },
        onRecentPlayAll = { playRecentAt(0) },
        onOpenRecentPlays = { navigator.push(RecentPlaysScreen()) },
        onNewSongPlay = playTracks,
        onTrackOptions = { selectedTrack = it },
        // 播放页是**全屏体验**：必须压过整个窗口（含桌面左侧导航栏），所以显式走
        // 根 Navigator（LocalRootNavigator，在组合作用域读一次、lambda 里只捕获引用），
        // 而不是页面就近的 Navigator —— 桌面宽屏上后者是内容区的内嵌栈，
        // push 进去会把播放页塞进侧栏旁边渲染。
        onOpenPlayer = { rootNavigator?.push(PlayerScreen()) },
        onTogglePlayPause = { AppModel.playback.togglePlayPause() },
    )

    if (loading) {
        // 首页骨架屏：结构与真实布局对齐（轮播、日推、歌单、榜单），避免全屏加载卡突兀闪现
        if (LocalIsExpanded.current) {
            DesktopHomeSkeleton()
        } else {
            MobileHomeSkeleton()
        }
        return
    }

    // 页面级刷新入口：桌面 = 空白处右键「刷新」；Android = 下拉刷新。
    // isRefreshing 用模型单独的 refreshing 标记 —— 静默刷新时 loading 保持 false，
    // 旧内容不会被全屏加载态顶掉。
    val refreshing by model.refreshing.collectAsState()
    CpRefreshablePage(
        isRefreshing = refreshing,
        onRefresh = { model.refresh(force = true) },
    ) {
        if (LocalIsExpanded.current) {
            DesktopHomeLayout(
                state = state,
                recentTracks = recentTracks,
                nowPlaying = nowPlaying,
                actions = actions,
            )
        } else {
            MobileHomeLayout(
                state = state,
                recentTracks = recentTracks,
                nowPlaying = nowPlaying,
                actions = actions,
            )
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
            onPlayNext = {
                scope.launch { AppModel.playback.addNextToQueue(toMediaId(track.id)) }
                cp.player.app.ui.util.UiEvents.notify("将在下一首播放")
            },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onDownload = { AppModel.downloadTrack(track) },
            onShowInfo = { showInfoTarget = track },
            onShare = { shareText(songShareText(track)) },
        )
    }

    addToPlaylistTrack?.let { track ->
        cp.player.app.ui.component.AddToPlaylistSheet(
            trackId = track.id,
            onDismiss = { addToPlaylistTrack = null },
        )
    }

    showInfoTarget?.let { track ->
        cp.player.app.ui.component.SongDetailInfoDialog(
            track = track,
            onDismiss = { showInfoTarget = null },
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
internal class HomeActions(
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
    val onRecentTrackClick: (TrackSummary, Int) -> Unit,
    val onRecentPlayAll: () -> Unit,
    val onOpenRecentPlays: () -> Unit,
    val onNewSongPlay: (List<TrackSummary>, Int) -> Unit,
    val onTrackOptions: (TrackSummary) -> Unit,
    /** 点「继续收听」卡回到播放页（L1）。 */
    val onOpenPlayer: () -> Unit,
    /** 打开专辑墙（可携带当前源）。 */
    val onOpenAlbumWall: () -> Unit = {},
    /** 快捷播放 / 暂停切换（M3E）。 */
    val onTogglePlayPause: () -> Unit = {},
)

/**
 * 首页要展示的「正在播放」快照（L1）。
 *
 * ⚠️ [positionMs] / [durationMs] 在**外面**就被节流到「秒」粒度了：
 * 引擎的位置轮询是每 200ms 一次，照原样传进来会让首页每秒重组 5 次。
 * 首页只用来画一条进度条，秒级精度完全够 —— 见 `HomeScreenContent` 里的
 * `map { … / 1000 }`。`distinctUntilChanged` 再去掉同秒内的重复。
 */
internal data class NowPlayingSnapshot(
    val track: TrackSummary?,
    val isPlaying: Boolean,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

// ============================================================ 桌面

/**
 * 桌面首页「发现歌单」行数。两行是「一屏看得完」和「有内容感」的平衡点。
 */
private const val PLAYLIST_ROWS = 2

/**
 * 桌面首页「继续收听」横条展示条数。
 *
 * ⚠️ 改版说明（2026-10-02）：这个值**曾经**与左侧「每日推荐」的 5 行曲目卡绑定
 * （12 条按两列 = 6 行，两卡等高）。L2 之后「最近播放」已从并排巨卡变成一条
 * **横向滚动条**，高度由卡片自身决定、不再参与任何底部对齐，
 * 因此**这层绑定已解除** —— 但仍然只展示 [DESKTOP_RECENT_COUNT] 条，
 * 完整列表走「更多」进 `RecentPlaysScreen`。
 */
private const val DESKTOP_RECENT_COUNT = 12

/** 桌面首页「每日推荐」占整行后的列数。4 列 × 3 行 = 12 首。 */
private const val DESKTOP_DAILY_COLUMNS = 4

/** 与 [DESKTOP_DAILY_COLUMNS] 配套的展示条数：4 列 × 3 行。 */
private const val DESKTOP_DAILY_COUNT = 12

@Composable
internal fun DesktopHomeLayout(
    state: HomeUiState,
    recentTracks: List<TrackSummary>,
    nowPlaying: NowPlayingSnapshot,
    actions: HomeActions,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val widthValue = maxWidth.value
        val horizontalPadding = CpSpacing.pageHorizontal
        // 栅格按**实际内容宽度**换算 —— 窗口宽度里含两侧留白与滚动条槽，
        // 宽屏下直接拿它去除会多算出 1–2 列，卡片被压窄。
        val contentWidth = widthValue.coerceAtMost(CpSpacing.pageMaxWidth.value) -
            horizontalPadding.value * 2f - desktopScrollbarGutter.value
        val playlistColumns = CpSpacing.gridColumns(contentWidth.dp)

        val recommendedItems = state.visiblePlaylists.take(playlistColumns * PLAYLIST_ROWS)
        val recentItems = recentTracks.take(DESKTOP_RECENT_COUNT)
        val artistItems = state.hotArtists.take(playlistColumns)

        ScrollColumn(
            modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = CpSpacing.pageTop,
                bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + CpSpacing.formBottomInset,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                // ⚠️ 顺序不能反：`fillMaxWidth()` 会把 min/max 都钉死成可用宽度，
                // 之后 `widthIn(max = …)` 拿到的入参约束已是固定值，clamp 后等于没写。
                modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
            ) {
                PageHeader(
                    title = "发现音乐",
                    subtitle = "你的音乐，常听常新",
                    action = {
                        TextButton(onClick = actions.onOpenAlbumWall) {
                            Icon(PosterWallIcon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            CpText("唱片墙")
                        }
                    },
                )

                if (state.dailySongs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(CpSpacing.desktopFocusHeight),
                        horizontalArrangement = Arrangement.spacedBy(CpSpacing.pageHorizontal),
                    ) {
                        DailySelectionFocus(state.dailySongs, actions, Modifier.weight(1.65f).fillMaxHeight())
                        // At a narrow desktop content width the shell already has a player.
                        // Never squeeze artwork and song titles into a 190dp side panel.
                        if (contentWidth >= 900f && nowPlaying.track != null) {
                            LandingNowPlaying(nowPlaying, actions, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    val previewColumns = CpSpacing.gridColumns(contentWidth.dp, target = 300.dp, min = 2, max = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(CpSpacing.musicGap)) {
                        state.dailySongs.take(previewColumns).forEach { track ->
                            LandingTrackRow(
                                track, { actions.onPlayDailyTrack(track) }, { actions.onTrackOptions(track) },
                                nowPlaying, Modifier.weight(1f),
                            )
                        }
                    }
                } else if (state.banners.isNotEmpty()) {
                        BannerCarousel(
                            banners = state.banners,
                            onClick = actions.onBannerClick,
                            modifier = Modifier.fillMaxWidth(),
                            height = CpSpacing.desktopFocusHeight,
                        )
                }
                LandingRadioActions(actions, dailyAvailable = state.dailySongs.isNotEmpty())
                DesktopRecentSelection(recentItems, nowPlaying, actions, contentWidth.dp)

                if (state.visiblePlaylists.isNotEmpty() || state.playlistSourceLoading) {
                    HomeSectionCard(
                        title = "发现歌单",
                        subtitle = playlistSourceHint(state.playlistSource),
                        contained = false,
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

                if (state.dailySongs.isNotEmpty() && state.banners.isNotEmpty()) {
                    BannerCarousel(
                        banners = state.banners,
                        onClick = actions.onBannerClick,
                        modifier = Modifier.fillMaxWidth(),
                        height = MobileBannerHeight,
                    )
                }

                // ── 排行榜（多列网格）与新碟上架（与发现歌单统一列数）──
                if (state.rankings.isNotEmpty()) {
                    HomeSectionCard(
                        title = "排行榜",
                        subtitle = "点击进入榜单曲目",
                    ) {
                        val rankingColumns = if (playlistColumns >= 5) 3 else 2
                        RankingGrid(
                            rankings = state.rankings.take(rankingColumns * 2),
                            columns = rankingColumns,
                            onOpenRanking = actions.onOpenRanking,
                        )
                    }
                }

                if (state.newAlbums.isNotEmpty()) {
                    HomeSectionCard(
                        title = "新碟上架",
                        subtitle = "点击查看专辑曲目",
                    ) {
                        CoverTileGrid(
                            columns = playlistColumns,
                            rows = 1,
                            items = state.newAlbums.take(playlistColumns),
                            cover = { it.coverUrl },
                            title = { it.name },
                            subtitle = { it.artistName ?: "未知歌手" },
                            onClick = actions.onOpenAlbum,
                            showSearchHint = true,
                            flightKeyOf = { album -> CoverFlight.playlistKey(album.resourceId) },
                        )
                    }
                }

                if (artistItems.isNotEmpty()) {
                    HomeSectionCard(
                        title = "热门歌手",
                        subtitle = "点击查看歌手曲目",
                        contained = false,
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
                        contained = false,
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

                if (state.error != null && AppModel.activeProvider() != null) {
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
                        ProvideCpTextFullDisplay(true) {
                        if (AppModel.activeProvider() == null) {
                            ContentState(
                                title = "本地音乐模式",
                                message = "当前未导入在线音源插件。点击上方「本地随机」或「本地曲库」可直接播放或扫描本地设备中的音乐。",
                                actionLabel = "打开本地曲库",
                                onAction = actions.onOpenIntelligence,
                            )
                        } else {
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
    }
}

@Composable
private fun DesktopDailySelection(
    songs: List<TrackSummary>,
    columns: Int,
    nowPlaying: NowPlayingSnapshot,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                CpText(
                    "每日推荐",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CpText(
                    "为你精选 ${songs.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = actions.onOpenDaily) {
                CpText("查看全部")
                Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp))
            }
        }
        songs.take(columns * 3).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { track ->
                    CompactTrackCard(
                        track = track,
                        onClick = { actions.onPlayDailyTrack(track) },
                        nowPlaying = nowPlaying,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DesktopRadioShortcuts(actions: HomeActions) {
    val local = AppModel.activeProvider() == null
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderFeatureGate(ProviderCapability.RECOMMEND) {
            FilledTonalButton(onClick = actions.onPlayPersonalFm) {
                Icon(if (local) Icons.Filled.Shuffle else Icons.Filled.Radio, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                CpText(if (local) "本地随机" else "私人 FM")
            }
        }
        FilledTonalButton(onClick = actions.onOpenIntelligence) {
            Icon(if (local) Icons.Filled.FolderOpen else Icons.Filled.Favorite, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            CpText(if (local) "本地曲库" else "心动模式")
        }
        FilledTonalButton(onClick = actions.onOpenRecentPlays) {
            Icon(Icons.Filled.History, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            CpText("最近播放")
        }
    }
}

@Composable
internal fun DesktopRecentSelection(
    tracks: List<TrackSummary>,
    nowPlaying: NowPlayingSnapshot,
    actions: HomeActions,
    contentWidth: Dp,
) {
    if (tracks.isEmpty()) return
    val columns = CpSpacing.gridColumns(contentWidth, target = 280.dp, min = 2, max = 4)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitleRow(
            title = "最近播放",
            subtitle = "熟悉的旋律，一点继续",
            trailing = {
                TextButton(onClick = actions.onOpenRecentPlays) { CpText("全部") }
            },
        )
        tracks.take(columns * 2).chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { columnIndex, track ->
                    LandingTrackRow(
                        track = track,
                        onPlay = { actions.onRecentTrackClick(track, rowIndex * columns + columnIndex) },
                        onOptions = { actions.onTrackOptions(track) },
                        snapshot = nowPlaying,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
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
internal fun MobileHomeLayout(
    state: HomeUiState,
    recentTracks: List<TrackSummary>,
    nowPlaying: NowPlayingSnapshot,
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
        if (state.dailySongs.isNotEmpty()) {
            item {
                DailySelectionFocus(
                    state.dailySongs, actions,
                    Modifier.fillMaxWidth().heightIn(min = CpSpacing.mobileFocusHeight),
                    compact = true,
                )
            }
        }
        item { LandingRadioActions(actions, compact = true, dailyAvailable = state.dailySongs.isNotEmpty()) }
        if (state.dailySongs.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap)) {
                    state.dailySongs.take(3).forEach { track ->
                        LandingTrackRow(track, { actions.onPlayDailyTrack(track) }, { actions.onTrackOptions(track) }, nowPlaying)
                    }
                }
            }
        }
        if (recentTracks.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "最近播放",
                    action = { TextButton(actions.onOpenRecentPlays) { CpText("全部") } },
                )
                Spacer(Modifier.height(CpSpacing.musicGap))
                Column(verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap)) {
                    recentTracks.take(3).forEachIndexed { index, track ->
                        LandingTrackRow(track, { actions.onRecentTrackClick(track, index) }, { actions.onTrackOptions(track) }, nowPlaying)
                    }
                }
            }
        }
        if (state.banners.isNotEmpty()) {
            item {
                BannerCarousel(state.banners, actions.onBannerClick, Modifier.fillMaxWidth(), height = MobileBannerHeight)
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
                        items(state.visiblePlaylists.take(20), key = { it.resourceId }) { playlist ->
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
                    items(state.rankings, key = { it.resourceId }) { ranking ->
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
                    items(state.newAlbums.take(12), key = { it.resourceId }) { album ->
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
                    items(state.hotArtists.take(12), key = { it.resourceId }) { artist ->
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
                                contextMenu = songContextMenuItems(
                                    SongMenuActions(
                                        onPlay = { actions.onNewSongPlay(songs, index) },
                                        onShare = { shareText(songShareText(track)) },
                                    )
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (state.error != null && AppModel.activeProvider() != null) {
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
                    ProvideCpTextFullDisplay {
                    if (AppModel.activeProvider() == null) {
                        ContentState(
                            title = "本地音乐模式",
                            message = "当前未导入在线音源插件。点击上方「本地随机」或「本地曲库」可直接播放或管理本地设备中的音乐。",
                            actionLabel = "打开本地曲库",
                            onAction = actions.onOpenIntelligence,
                        )
                    } else {
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
    height: Dp = CpSpacing.desktopFocusHeight,
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
                modifier = Modifier.fillMaxSize().cpBounceClickable { onClick(banner) },
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
                        CpText(
                            text = banner.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (loaded) Color.White else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
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
 * 焦点区右侧面板。
 *
 * ### L1 改版（2026-10-02）
 * 原先这里是**固定**的三行电台入口（私人 FM / 心动模式 / 相似歌曲）。
 * 那三行是**低频**操作，却占着首屏黄金位置 —— 而它恰恰回答不了
 * 用户打开首页最想知道的两件事之一：「我在听什么 / 接着听什么」。
 *
 * 现在按有没有在播曲目分两态：
 * - **有**在播 → 显示「继续收听」：封面 + 曲名 + 歌手 + 播放进度，点击回到播放页。
 * - **没有** → 退回原来的三行电台入口（保持完全一致的旧行为，零功能损失）。
 *
 * ⚠️ 为什么不两态并存（上面显示在播、下面仍列三行）：260dp 的高度塞不下
 * 「封面 + 曲名 + 进度」再叠三行入口，会每一行都被压到勉强放下一行字。
 * 两态互斥反而让每种状态下的信息量都够。
 *
 * ### L2 改版（2026-10-02）
 * 三行入口里的「相似歌曲」废弃 —— 该功能改以**当前在播曲目**为种子，归属播放页
 * （桌面「相似」页签 / 窄屏第 4 页）。首页留下的空位换成「最近播放」：
 * 它不依赖登录与日推数据（原「相似歌曲」在没有日推时是禁用态，等于摆设），
 * 且与有在播时的「继续收听」卡同属「接着听」语义族。
 */
@Composable
private fun HeroQuickPanel(
    onPlayPersonalFm: () -> Unit,
    onOpenIntelligence: () -> Unit,
    onOpenRecentPlays: () -> Unit,
    /** 有没有最近播放记录可用（没有时「最近播放」行禁用，给出说明）。 */
    recentEnabled: Boolean,
    enabled: Boolean,
    nowPlaying: TrackSummary?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (nowPlaying != null) {
        BentoCard(
            modifier = modifier,
            onClick = onOpenPlayer,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentPadding = PaddingValues(CpSpacing.pageHorizontal),
            fillHeight = true,
        ) {
            HeroNowPlaying(
                track = nowPlaying,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                onTogglePlayPause = onTogglePlayPause,
            )
        }
    } else {
            Column(
                modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val isLocalMode = AppModel.activeProvider() == null
                CpText(
                    if (isLocalMode) "快捷入口" else "快捷电台",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                ProviderFeatureGate(ProviderCapability.RECOMMEND) {
                    HeroActionRow(
                        icon = if (isLocalMode) Icons.Filled.Shuffle else Icons.Filled.Radio,
                        title = if (isLocalMode) "本地随机" else "私人 FM",
                        subtitle = if (isLocalMode) "随机畅听本地音乐" else "连续播放，不用挑歌",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onPlayPersonalFm,
                    )
                }
                HeroActionRow(
                    icon = if (isLocalMode) Icons.Filled.FolderOpen else Icons.Filled.Favorite,
                    title = if (isLocalMode) "本地曲库" else "心动模式",
                    subtitle = if (isLocalMode) "浏览设备音乐文件" else (if (enabled) "围绕今日推荐延展" else "需要先有每日推荐"),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onClick = onOpenIntelligence,
                    enabled = if (isLocalMode) true else enabled,
                )
                HeroActionRow(
                    icon = Icons.Filled.History,
                    title = "最近播放",
                    subtitle = if (recentEnabled) "接着上次继续听" else "听过的歌会出现在这里",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onOpenRecentPlays,
                    enabled = recentEnabled,
                )
            }
    }
}

/**
 * 「继续收听」卡：当前在播曲目的标题 + 歌手 + 波形进度。
 *
 * M3 Expressive 风格：采用 [CpWavyProgress] 波形进度条，播放中流动律动；
 * 右上角集成快捷播放/暂停药丸按钮。
 */
@Composable
private fun HeroNowPlaying(
    track: TrackSummary,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit = {},
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Equalizer else Icons.Filled.Pause,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(CpIconSize.inline),
                )
                CpText(
                    if (isPlaying) "正在播放" else "已暂停",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            // M3E 快速播放/暂停控制按钮
            Surface(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(CpSpacing.touchTarget),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val labels: @Composable () -> Unit = {
            Column {
                CpText(
                    track.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                CpText(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 240.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomePlayingCover(track)
                    labels()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomePlayingCover(track)
                    Box(Modifier.weight(1f)) { labels() }
                }
            }
        }
        // 占位把波形进度条推到卡片底部
        Spacer(Modifier.weight(1f))
        CpWavyProgress(
            progress = progress,
            waveFlowing = isPlaying,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CpText(
                formatTimeMs(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CpText(
                if (durationMs > 0L) formatTimeMs(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomePlayingCover(track: TrackSummary) {
    Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.medium)) {
        // Always keep a readable fallback underneath failed or slow image requests.
        CpCoverPlaceholder(Modifier.fillMaxSize(), corner = 12.dp, animated = false)
        if (!track.coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.coverUrl.resized(180),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun HeroActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    iconColor: Color,
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
                Modifier.size(36.dp).clip(MaterialTheme.shapes.medium)
                    .background(containerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = iconColor.copy(alpha = contentAlpha),
                    modifier = Modifier.size(CpIconSize.inline),
                )
            }
            Column(Modifier.weight(1f)) {
                CpText(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                )
                CpText(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(CpIconSize.inline),
            )
        }
    }
}

/** 移动端快捷电台：三张等宽紧凑卡，M3 Expressive 彩色图标容器与按压弹性。 */
@Composable
private fun HeroQuickRow(
    onPlayPersonalFm: () -> Unit,
    onOpenIntelligence: () -> Unit,
    onOpenRecentPlays: () -> Unit,
) {
    val isLocalMode = AppModel.activeProvider() == null
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroQuickTile(
            icon = if (isLocalMode) Icons.Filled.Shuffle else Icons.Filled.Radio,
            label = if (isLocalMode) "本地随机" else "私人 FM",
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onPlayPersonalFm,
            modifier = Modifier.weight(1f),
        )
        HeroQuickTile(
            icon = if (isLocalMode) Icons.Filled.FolderOpen else Icons.Filled.Favorite,
            label = if (isLocalMode) "本地曲库" else "心动模式",
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onOpenIntelligence,
            modifier = Modifier.weight(1f),
        )
        HeroQuickTile(
            icon = Icons.Filled.History,
            label = "最近播放",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onOpenRecentPlays,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HeroQuickTile(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.cpBounceClickable(onClick = onClick),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(containerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            CpText(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 首页通用区块：标题 + 可选副标题 + 右侧动作，下面是内容。
 *
 * 全页统一走它，避免每个区块各自拼 `Surface` + `Row` + 字号 —— 那正是上一版
 * 「推荐歌单」「热门歌单」两块看起来像两个不同页面拼在一起的原因。
 *
 * @param contained 是否给整块套一层容器卡。
 *   - `true`（默认）：容器 + 内边距，用于**需要成组**的内容（排行榜的列表行、
 *     「快捷电台」的三张入口）——那些子项自身没有轮廓，不套容器会散在地上。
 *   - `false`：**不套容器**，标题直接落在页面背景上，子项自带封面/头像轮廓。
 *     用于「发现歌单 / 新碟上架 / 热门歌手」这类栅格区块。
 *
 *   ⚠️ 为什么要有这个开关：首页原先 7 个区块全是同一套
 *   `Surface(extraLarge, surfaceContainerLow)`，纵向排下来是一屏**等宽色带**，
 *   看不出主次，也读不出区块边界（看起来像一个东西重复了 7 遍）。
 *   把「子项自带轮廓」的那几个去掉容器后，容器只留给真正需要成组的区块，
 *   容器本身就从「每条都有的背景色」变成了**有意义的分组信号**。
 *
 *   ⚠️ 传 `false` 之前先确认子项自带轮廓（封面 / 头像 / 自带底色的行）。
 *   子项是纯文字的话，去掉容器后会直接飘在页面背景上，反而更难读。
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
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contained: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!contained) {
        Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            SectionTitleRow(
                title = title,
                subtitle = subtitle,
                trailing = trailing,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
        }
        return
    }
    BentoCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(bottom = 18.dp),
        fillHeight = false,
    ) {
        SectionTitleRow(
            title = title,
            subtitle = subtitle,
            trailing = trailing,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.padding(if (contentPadding == PaddingValues(0.dp)) PaddingValues(horizontal = 18.dp) else contentPadding)) { content() }
    }
}

/** 区块标题行。统一步调字号与内边距。 */
@Composable
private fun SectionTitleRow(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            CpText(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                CpText(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
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
    flightKeyOf: ((T) -> String?)? = null,
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
                        flightKey = flightKeyOf?.invoke(item),
                    )
                }
                repeat(safeColumns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 桌面端排行榜多列卡片网格。 */
@Composable
private fun RankingGrid(
    rankings: List<RankingSummary>,
    columns: Int,
    onOpenRanking: (RankingSummary) -> Unit,
) {
    val safeColumns = columns.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rankings.chunked(safeColumns).forEach { rowRankings ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowRankings.forEach { ranking ->
                    val originalIdx = rankings.indexOf(ranking)
                    Box(modifier = Modifier.weight(1f)) {
                        RankingRow(
                            ranking = ranking,
                            index = originalIdx,
                            onClick = { onOpenRanking(ranking) },
                        )
                    }
                }
                repeat(safeColumns - rowRankings.size) { Spacer(Modifier.weight(1f)) }
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
    /**
     * 封面圆角。
     *
     * ⚠️ 这里是 `Dp` 而不是 `Shape`，因为同一个值要同时喂给 `AsyncImage` 的
     * `clip()` 和 [CpCoverPlaceholder] 的 `corner` —— 后者只收 `Dp`。
     * **取值必须落在形状刻度上**：默认 20dp = `MaterialTheme.shapes.largeIncreased`；
     * 大卡片传 28dp = `shapes.extraLarge`。不要传 18 / 22 这类非标度值。
     */
    corner: Dp = 20.dp,
    showSearchHint: Boolean = false,
    flightKey: String? = null,
) {
    val overImage = !coverUrl.isNullOrBlank()
    val flightModifier = if (flightKey != null) Modifier.coverFlightSource(flightKey, corner) else Modifier
    Column(
        modifier = modifier.fillMaxWidth().cpBounceClickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).then(flightModifier)) {
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
                CpText(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor,
                    maxLines = 1,
                )
                if (!subtitle.isNullOrBlank()) {
                    CpText(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
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

/** 榜单列表行（同步标准歌曲列表项样式：统一深浅底色、52dp 封面、规范内边距与文字排版）。 */
@Composable
private fun RankingRow(
    ranking: RankingSummary,
    onClick: () -> Unit,
    index: Int = -1,
) {
    val isDark = LocalIsDarkTheme.current
    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val handleRankingClick = {
        CoverFlight.openPlaylist(ranking.resourceId, ranking.coverUrl)
        onClick()
    }

    Surface(
        onClick = handleRankingClick,
        modifier = Modifier.fillMaxWidth().cpBounceClickable(onClick = handleRankingClick),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (index >= 0) {
                val isTop3 = index < 3
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (isTop3) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CpText(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isTop3) FontWeight.Bold else FontWeight.Medium,
                        color = if (isTop3) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                Modifier.size(52.dp)
                    .coverFlightSource(CoverFlight.playlistKey(ranking.resourceId), 12.dp)
            ) {
                if (!ranking.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ranking.coverUrl.resized(200),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
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
                CpText(
                    ranking.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                CpText(
                    rankingSubtitle(ranking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(CpIconSize.inline),
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
    val handleTileClick = {
        CoverFlight.openPlaylist(ranking.resourceId, ranking.coverUrl)
        onClick()
    }
    CoverTile(
        coverUrl = ranking.coverUrl,
        title = ranking.name,
        subtitle = ranking.updateFrequency,
        onClick = handleTileClick,
        flightKey = CoverFlight.playlistKey(ranking.resourceId),
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
        modifier = modifier.cpBounceClickable(onClick = onClick),
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
        CpText(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
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
                        contextMenu = songContextMenuItems(
                            SongMenuActions(
                                onPlay = { onPlay(songs, originalIndex) },
                                onShare = { shareText(songShareText(track)) },
                            )
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 「继续收听」横向错落药丸条（PixelPlayer 经典 M3 Expressive 风格）。
 *
 * 将最近播放曲目按列交替分入两行，在一个平滑滚动的横向视口中并排错落展示。
 * 每颗药丸包含圆形封面、歌曲标题、歌手，并在播放状态下平滑变换圆角与主题高亮底色，
 * 伴随跳动的微型均衡器。
 */
@Composable
private fun RecentPlaysRail(
    tracks: List<TrackSummary>,
    onTrackClick: (TrackSummary, Int) -> Unit,
    onTrackOptions: (TrackSummary) -> Unit,
    onOpenAll: () -> Unit,
    onPlayAll: () -> Unit,
    onOpenAlbumWall: () -> Unit = {},
    nowPlaying: NowPlayingSnapshot? = null,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return

    val sharedScrollState = rememberScrollState()
    val row0Tracks = remember(tracks) { tracks.filterIndexed { idx, _ -> idx % 2 == 0 } }
    val row1Tracks = remember(tracks) { tracks.filterIndexed { idx, _ -> idx % 2 == 1 } }

    Column(modifier.fillMaxWidth()) {
        SectionTitleRow(
            title = "继续收听",
            subtitle = "接着上次继续听",
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = onOpenAlbumWall,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            PosterWallIcon,
                            contentDescription = "唱片墙",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TextButton(onClick = onOpenAll) { CpText("全部") }
                    FilledTonalButton(
                        onClick = onPlayAll,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        CpText("播放", style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(sharedScrollState)
                .padding(vertical = 4.dp),
        ) {
            Spacer(Modifier.width(4.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row0Tracks.forEachIndexed { colIdx, track ->
                        val originalIdx = colIdx * 2
                        RecentPlayPill(
                            track = track,
                            isCurrentSong = nowPlaying?.track?.id == track.id,
                            isPlaying = nowPlaying?.isPlaying == true && nowPlaying.track?.id == track.id,
                            onClick = { onTrackClick(track, originalIdx) },
                        )
                    }
                }
                if (row1Tracks.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row1Tracks.forEachIndexed { colIdx, track ->
                            val originalIdx = colIdx * 2 + 1
                            RecentPlayPill(
                                track = track,
                                isCurrentSong = nowPlaying?.track?.id == track.id,
                                isPlaying = nowPlaying?.isPlaying == true && nowPlaying.track?.id == track.id,
                                onClick = { onTrackClick(track, originalIdx) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
        }
    }
}

/**
 * PixelPlayer 风格的错落药丸卡片。
 *
 * 高度 52dp，左侧 38dp 圆形封面，中间双行文字，右侧正在播放跳动均衡器。
 * 正在播放时圆角由 26dp 平滑收拢为 14dp，底色过渡为 primaryContainer。
 */
@Composable
private fun RecentPlayPill(
    track: TrackSummary,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current
    val targetCorner = if (isCurrentSong) 14.dp else 26.dp
    val animatedCorner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = tween(durationMillis = 280),
        label = "recentPillCorner",
    )
    val defaultContainer = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val targetContainerColor = if (isCurrentSong) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        defaultContainer
    }
    val animatedContainer by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(durationMillis = 280),
        label = "recentPillContainer",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrentSong) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "recentPillTitle",
    )
    val artistColor by animateColorAsState(
        targetValue = if (isCurrentSong) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "recentPillArtist",
    )
    val shape = RoundedCornerShape(animatedCorner)

    Surface(
        modifier = modifier
            .width(218.dp)
            .height(52.dp)
            .cpBounceClickable(onClick = onClick),
        shape = shape,
        color = animatedContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 7.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .coverFlightSource(CoverFlight.trackKey(track.id), 19.dp),
            ) {
                CpCoverPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    corner = 19.dp,
                    animated = false,
                )
                if (!track.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = track.coverUrl.resized(120),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                CpText(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    color = titleColor,
                )
                CpText(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = artistColor,
                )
            }
            if (isCurrentSong) {
                CpPlayingEqualizer(
                    isPlaying = isPlaying,
                    barColor = MaterialTheme.colorScheme.primary,
                    scrimColor = Color.Transparent,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
        }
    }
}

/**
 * 「每日推荐」大卡（PixelPlayer Expressive 风格）。
 *
 * 采用横向渐变头部、三几何形状重叠封面、圆润药丸操作条与带在播高亮的紧凑曲目卡片。
 */
@Composable
private fun DailyMixCard(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    nowPlaying: NowPlayingSnapshot? = null,
) {
    val previewTracks = if (compact) songs.take(DESKTOP_DAILY_COUNT) else songs.take(4)
    val thumbnails = remember(songs) { songs.take(3) }
    val isDark = LocalIsDarkTheme.current
    val cardBackground = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = cardBackground,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // ── PixelPlayer 风格渐变艺术头部 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        CpText(
                            text = "每日推荐",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        CpText(
                            text = "${songs.size} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                        )
                    }

                    // ── PixelPlayer 标志性三几何形状重叠封面 + 移到顶部的「查看全部」胶囊 ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (thumbnails.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy((-14).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                thumbnails.forEachIndexed { index, track ->
                                    val (targetSize, targetShape) = when (index) {
                                        0 -> 48.dp to MaterialTheme.shapes.largeIncreased
                                        1 -> 42.dp to CircleShape
                                        else -> 46.dp to MaterialTheme.shapes.medium
                                    }
                                    Surface(
                                        modifier = Modifier.size(targetSize),
                                        shape = targetShape,
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                                    ) {
                                        if (!track.coverUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = track.coverUrl.resized(160),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
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
                                }
                            }
                        }

                        // 移到顶部的「查看全部」操作胶囊
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.22f),
                            contentColor = Color.White,
                            modifier = Modifier
                                .height(38.dp)
                                .cpBounceClickable(onClick = onOpenPlaylist),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                CpText(
                                    "查看全部",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── 曲目网格 / 列表 ──
            DailySongRail(
                songs = previewTracks,
                onSongClick = onSongClick,
                columns = if (compact) DESKTOP_DAILY_COLUMNS else 1,
                nowPlaying = nowPlaying,
            )
        }
    }
}

@Composable
private fun DailySongRail(
    songs: List<TrackSummary>,
    onSongClick: (TrackSummary) -> Unit,
    columns: Int,
    nowPlaying: NowPlayingSnapshot? = null,
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
                        nowPlaying = nowPlaying,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(safeColumns - rowTracks.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 「每日推荐」紧凑曲目卡片。
 *
 * M3 Expressive 风格：带弹性按压触感，当处于正在播放时，底色过渡高亮、曲名强调，并显示动态均衡器。
 */
@Composable
private fun CompactTrackCard(
    track: TrackSummary,
    onClick: () -> Unit,
    nowPlaying: NowPlayingSnapshot? = null,
    modifier: Modifier = Modifier,
) {
    val isCurrent = nowPlaying?.track?.id == track.id
    val isDark = LocalIsDarkTheme.current
    val defaultContainer = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val containerColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
        } else {
            defaultContainer
        },
        label = "dailyCardContainer",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "dailyCardTitle",
    )
    val artistColor = if (isCurrent) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.cpBounceClickable(onClick = onClick),
        color = containerColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .coverFlightSource(CoverFlight.trackKey(track.id), 12.dp),
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                CpText(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                )
                CpText(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = artistColor,
                    maxLines = 1,
                )
            }
            if (isCurrent && nowPlaying != null) {
                CpPlayingEqualizer(
                    isPlaying = nowPlaying.isPlaying,
                    barColor = MaterialTheme.colorScheme.primary,
                    scrimColor = Color.Transparent,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }
    }
}

