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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import cp.player.app.ui.anim.coverFlightSource
import cp.player.app.ui.component.CpLinearProgress
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.BentoCard
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRefreshablePage
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LazyScrollRow
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.PlaylistCoverCard
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SectionHeader
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
import cp.player.app.ui.util.formatTimeMs
import cp.player.app.ui.util.resized
import cp.player.core.BackendResult
import cp.player.core.music.AlbumSummary
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
            navigator.push(
                HomeGeneratedPlaylistScreen(
                    intelligencePlaylist,
                    emptyList(),
                    // 不传 startIndex：打开心动模式只浏览，点了具体曲目才播。
                    kind = HomeGeneratedPlaylistKind.IntelligenceFromDaily,
                    seedTrackId = playingSeed
                        ?: likedIds.firstOrNull()
                        ?: dailySongs.firstOrNull()?.id,
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
            MobileHomeLayout(state = state, recentTracks = recentTracks, actions = actions)
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

/**
 * 桌面焦点图高度。
 *
 * 按 1400dp 内容宽、hero 占 2/3 算约 4.2:1，与上游横幅素材比例接近。
 * 260dp（原 224dp）：L1 之后右侧面板由「三张固定电台入口」改成「继续收听」，
 * 需要放下封面 + 曲名 + 进度条三行，224dp 会把进度条挤到贴边。
 */
private val DesktopBannerHeight = 260.dp

@Composable
internal fun DesktopHomeLayout(
    state: HomeUiState,
    recentTracks: List<TrackSummary>,
    nowPlaying: NowPlayingSnapshot,
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
                //
                // 没有焦点图时**不留替代卡**：原先这里塞的是「今日速览」，而它内部两块
                // 正是下方「每日推荐 / 最近播放」的**计数 + 跳转**，只是把同一目的地
                // 在首屏最贵的位置（2/3 宽）又说了一遍；真正的列表本来就在同一屏下方。
                // 无焦点图对应的恰恰是「未登录 / 新用户」——那正是最不需要「我的数据统计」
                // 的时候（两个计数都会是 0）。移动端（MobileHomeLayout）从一开始就没有
                // 这张卡，也从未出现版面容不下内容的问题，可见这个位置空着是成立的。
                // 现在的做法：把「快捷电台」提升成一个常规区块（同 `HomeSectionCard` 规格），
                // 用**可操作内容**填版面，而不是拿一张信息量为零的统计卡去占坑。
                if (state.banners.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(DesktopBannerHeight),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        BannerCarousel(
                            banners = state.banners,
                            onClick = actions.onBannerClick,
                            modifier = Modifier.weight(2f).fillMaxHeight(),
                            height = DesktopBannerHeight,
                        )
                        HeroQuickPanel(
                            onPlayPersonalFm = actions.onPlayPersonalFm,
                            onOpenIntelligence = actions.onOpenIntelligence,
                            onOpenRecentPlays = actions.onOpenRecentPlays,
                            recentEnabled = recentItems.isNotEmpty(),
                            enabled = state.dailySongs.isNotEmpty(),
                            nowPlaying = nowPlaying.track,
                            isPlaying = nowPlaying.isPlaying,
                            positionMs = nowPlaying.positionMs,
                            durationMs = nowPlaying.durationMs,
                            onOpenPlayer = actions.onOpenPlayer,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else {
                    // 无焦点图时快捷电台**包进常规区块容器**，而不是三张裸卡横在页首 ——
                    // 裸卡会让顶端显得孤立、且与下方「每日推荐」的左边缘对不齐。
                    HomeSectionCard(
                        title = "快捷电台",
                        subtitle = "不用挑歌，直接开听",
                    ) {
                        HeroQuickRow(
                            onPlayPersonalFm = actions.onPlayPersonalFm,
                            onOpenIntelligence = actions.onOpenIntelligence,
                            onOpenRecentPlays = actions.onOpenRecentPlays,
                        )
                    }
                }

                // ── 每日推荐（独占整行，主角）──
                //
                // 改版前这里是 `Row(每日推荐 weight(1.15) + 最近播放 weight(1))` 的
                // **等高巨卡**，靠 `IntrinsicSize.Max` 让两者底部对齐。问题不在对齐，
                // 在**版面分配与使用频率倒挂**：每日推荐是每天都要看一眼的核心内容，
                // 「最近播放」是低频回溯操作，两者却各拿一半宽。
                // 更糟的是那个对齐约束反过来压低了日推的信息密度 —— 它被限制在
                // 「和右栏 6 行列表一样高」，只能放 10 首（两列 5 行）。
                //
                // 现在：日推独占整行、四列三行 12 首；最近播放降级为下方一条
                // **横向滚动**的「继续收听」（见 RecentPlaysRail），
                // 它本来就只需要"扫一眼、点一个"，不需要 12 行列表那么大的版面。
                if (state.dailySongs.isNotEmpty()) {
                    DailyMixCard(
                        songs = state.dailySongs,
                        onSongClick = actions.onPlayDailyTrack,
                        onOpenPlaylist = actions.onOpenDaily,
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                    )
                }

                // ── 继续收听（横向条，替代原「最近播放」巨卡）──
                RecentPlaysRail(
                    tracks = recentItems,
                    onTrackClick = actions.onRecentTrackClick,
                    onTrackOptions = actions.onTrackOptions,
                    onOpenAll = actions.onOpenRecentPlays,
                    onPlayAll = actions.onRecentPlayAll,
                )

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
                                contained = false,
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
                                contained = false,
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
                onOpenRecentPlays = actions.onOpenRecentPlays,
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
                            contextMenu = songContextMenuItems(
                                SongMenuActions(
                                    onPlay = { actions.onRecentTrackClick(track, index) },
                                    onShare = { shareText(songShareText(track)) },
                                )
                            ),
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
    modifier: Modifier = Modifier,
) {
    // 走 BentoCard 而不是手写 Surface：多拿到两层东西 ——
    // ① 在播态整卡可点时的**按压微缩**（原先是裸 Modifier.clickable，点下去没有任何反馈）；
    // ② bentoOutline() 那层极淡描边。
    //
    // 两态共用一个 BentoCard，靠 onClick 是否为 null 切换：为 null 时 BentoCard 自己
    // 退化成静态容器。高度由调用方钉死（modifier 带 fillMaxHeight），所以 fillHeight
    // 可以取 true —— 在播态的 Spacer(weight(1f)) 需要**有界高度**才能把进度条压到底。
    BentoCard(
        modifier = modifier,
        onClick = if (nowPlaying != null) onOpenPlayer else null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        fillHeight = true,
    ) {
        if (nowPlaying != null) {
            HeroNowPlaying(
                track = nowPlaying,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
            )
        } else {
            // 不套 fillMaxHeight：内容本来就靠上，撑满了反而会被 SpaceBetween 拉散。
            Column(
                Modifier.fillMaxWidth(),
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
                    icon = Icons.Filled.History,
                    title = "最近播放",
                    subtitle = if (recentEnabled) "接着上次继续听" else "听过的歌会出现在这里",
                    onClick = onOpenRecentPlays,
                    enabled = recentEnabled,
                )
            }
        }
    }
}

/**
 * 「继续收听」卡：当前在播曲目的标题 + 歌手 + 进度。
 *
 * 进度条走 [CpLinearProgress]（见 `AGENTS.md` §6：不许直接调 material3 的
 * Expressive 实验 API），与全局观感一致。
 *
 * ⚠️ **进度必须由调用方传入，这里不读 `AppModel`**：`AppModel.playback` 的 getter
 * 会取 `MusicBackend` 单例，未 `init()` 时**直接抛 `IllegalStateException`**
 * （离屏渲染实测）。而且这里读全局单例会让这个 Composable 无法独立测试。
 * 调用方（`DesktopHomeLayout`）负责把进度喂进来。
 *
 * ⚠️ **本组件只画内容，不负责点击与内边距** —— 那两层由外层 `BentoCard` 提供
 * （`HeroQuickPanel`）。要复用这块内容到别处时，记得自己补一个可点容器。
 */
@Composable
private fun HeroNowPlaying(
    track: TrackSummary,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
) {
    // 进度只在拿到合法区间时才画：durationMs 为 0（流媒体还没探到时长）时
    // 除法会得到 0/NaN，进度条要么空着要么直接崩。
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    // 点击与内边距都归外层 BentoCard 管：这里只负责内容。
    // ⚠️ 必须 fillMaxHeight —— 下面那个 Spacer(weight(1f)) 要靠**有界高度**
    // 才能把进度条推到卡片底部，否则它会紧贴着歌手名。
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Equalizer else Icons.Filled.Pause,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(CpIconSize.inline),
            )
            Text(
                if (isPlaying) "正在播放" else "已暂停",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            track.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 占位把进度条推到卡片底部：上方文字行数变化时进度条不会跟着上下跳。
        Spacer(Modifier.weight(1f))
        CpLinearProgress(
            progress = progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTimeMs(positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (durationMs > 0L) formatTimeMs(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    modifier = Modifier.size(CpIconSize.inline),
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
                modifier = Modifier.size(CpIconSize.inline),
            )
        }
    }
}

/** 移动端快捷电台：三张等宽紧凑卡。第三张是「最近播放」（原「相似歌曲」，已迁往播放页）。 */
@Composable
private fun HeroQuickRow(
    onPlayPersonalFm: () -> Unit,
    onOpenIntelligence: () -> Unit,
    onOpenRecentPlays: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroQuickTile(Icons.Filled.Radio, "私人 FM", onPlayPersonalFm, Modifier.weight(1f))
        HeroQuickTile(Icons.Filled.Favorite, "心动模式", onOpenIntelligence, Modifier.weight(1f))
        HeroQuickTile(Icons.Filled.History, "最近播放", onOpenRecentPlays, Modifier.weight(1f))
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
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    contained: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!contained) {
        // 无容器形态：**必须用和容器形态相同的标题内边距**（start 22 / top 20），
        // 否则同一页里两种区块的标题左边缘会差出 22dp，比原来更乱。
        Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            SectionTitleRow(
                title = title,
                subtitle = subtitle,
                trailing = trailing,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(contentPadding)) { content() }
        }
        return
    }
    // 走 BentoCard 而不是手写 Surface：拿到统一的 extraLarge 圆角、语义容器色，
    // 以及 bentoOutline() 那层极淡描边。
    // ⚠️ fillHeight 必须为 false：内容高度由区块自己决定，传 true 会让它去撑父容器
    // 的 maxHeight 并用 SpaceBetween 把标题和正文撕开（见 BentoCard 的 KDoc）。
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
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** 区块标题行。两种容器形态共用，保证标题的字号与内边距完全一致。 */
@Composable
private fun SectionTitleRow(
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
                    // titleSmall 的字重规范就是 Medium(500)，这里原先又写了一遍
                    // `fontWeight = FontWeight.Medium` —— 与 token 同值，纯噪声。
                    // 删掉：以后改字阶只需要动 Type.kt 一处。
                    style = MaterialTheme.typography.titleSmall,
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
                        // 缩略图圆角走 token（medium = 12dp），不要再写裸值 ——
                        // 写裸值的话改刻度时这里会被漏掉，出现「同一页两种圆角」。
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
 * 「继续收听」横向条（桌面）—— 替代原先的「最近播放」两列 12 行列表。
 *
 * ### 为什么改成横条
 * 「最近播放」是**低频回溯**操作：用户打开首页多半是想看今天推荐了什么，
 * 而不是翻两周前听过什么。原来它和「每日推荐」并排成两块等高巨卡，
 * 占掉一半首屏宽度去铺 12 行列表；把它收成一条横向滚动条后，
 * 首屏腾出来的高度直接还给了「每日推荐」。
 *
 * 需要完整列表时走 trailing 的「更多」进 `RecentPlaysScreen()` —— 功能不缩水。
 *
 * ### 视觉
 * 刻意**不做成容器卡**（`contained = false` 的等价形态）：它上方是有封面的
 * 「每日推荐」大卡，这里再用一层同规格容器会把两个区块糊成一块。
 * 卡片自带封面轮廓，标题直接落在页面背景上、与其余无容器区块对齐。
 */
@Composable
private fun RecentPlaysRail(
    tracks: List<TrackSummary>,
    onTrackClick: (TrackSummary, Int) -> Unit,
    onTrackOptions: (TrackSummary) -> Unit,
    onOpenAll: () -> Unit,
    onPlayAll: () -> Unit,
) {
    // 没有最近播放时**整块不出现**：一条空态的横向条既占高度又没有信息，
    // 而且它下方紧接着就是「发现歌单」——留着只会把首屏往后推。
    if (tracks.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        // 标题行与 HomeSectionCard 的无容器形态同规格（start 22 / top 20），
        // 保证和「发现歌单」「热门歌手」的标题左边缘落在同一条竖线上。
        SectionTitleRow(
            title = "继续收听",
            subtitle = "接着上次继续听",
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenAll) { Text("更多") }
                    FilledTonalButton(
                        onClick = onPlayAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("播放", style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
        )
        Spacer(Modifier.height(14.dp))
        LazyScrollRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(tracks) { index, track ->
                RecentPlayCard(
                    track = track,
                    onClick = { onTrackClick(track, index) },
                    onOptionsClick = { onTrackOptions(track) },
                )
            }
        }
    }
}

/**
 * 「继续收听」里的一张封面卡：1:1 封面 + 曲名 + 歌手。
 *
 * 宽度刻意做窄（160dp）：横向条一屏能露出 6–7 张，用户一眼就看出「可以往右滑」；
 * 卡片再宽就会看起来像「只有 3 张、右边没了」。
 */
@Composable
private fun RecentPlayCard(
    track: TrackSummary,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(160.dp).clickable { onClick() },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .coverFlightSource(CoverFlight.trackKey(track.id), 20.dp),
        ) {
            // 占位块常驻最底层，与 PlaylistCoverCard 同一套做法：
            // URL 有值但图还没到时也要有东西可看。
            CpCoverPlaceholder(
                modifier = Modifier.fillMaxSize(),
                corner = 20.dp,
            )
            if (!track.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.coverUrl.resized(300),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            // 更多按钮压右上角：封面本身就占满整块，不覆盖上去就没有别的位置可放。
            Surface(
                onClick = onOptionsClick,
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.34f),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MoreVert, "更多",
                        tint = Color.White,
                        modifier = Modifier.size(CpIconSize.inline),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            track.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    // compact（桌面独占整行）走**四列轨道**：12 首 = 3 行。
    // 原先 compact 是两列 10 首，与右栏「最近播放」的 6 行列表刻意保持等高；
    // 改版后最近播放已挪走（见 L2 的「继续收听」横条），不再有对齐约束，
    // 于是把列数提上来换更高的信息密度 —— 同样高度下多放 20% 的曲目。
    val previewTracks = if (compact) songs.take(DESKTOP_DAILY_COUNT) else songs.take(4)

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
                            Icon(Icons.Filled.PlayArrow, null, tint = actionContent, modifier = Modifier.size(CpIconSize.inline))
                            Text(
                                "播放全部",
                                // labelLarge 的字重规范就是 Medium(500)，原先是重复声明，已删。
                                style = MaterialTheme.typography.labelLarge,
                                color = actionContent,
                            )
                        }
                    }
                }
                DailySongRail(
                    songs = previewTracks,
                    onSongClick = onSongClick,
                    overImage = overImage,
                    // 桌面独占整行 ⇒ 4 列；窄屏（移动）仍是单列。
                    columns = if (compact) DESKTOP_DAILY_COLUMNS else 1,
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
                // 14dp 是历史遗留的**非标度值**（既不是 small/medium 也不是 large）。
                // 收到 medium（12dp）：这个 46dp 的方形缩略图本来就该跟列表缩略图同一档。
                modifier = Modifier.size(46.dp).clip(MaterialTheme.shapes.medium)
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
    IntelligenceFromDaily,
}

class HomeGeneratedPlaylistScreen(
    private val playlist: PlaylistSummary,
    private val initialTracks: List<TrackSummary>,
    /**
     * 进入后自动播放的曲目下标；**null（默认）表示只浏览、不播放**。
     *
     * 只有「点击某一首曲目」的入口才传具体下标（`HomeScreen.onPlayDailyTrack`）；
     * 「打开歌单」的入口一律不传 —— 点开每日推荐就自动出声是打扰，用户此时多半
     * 只是想看看今天推荐了什么。原先默认值是 `0`，于是「打开日推」等价于「播放第 1 首」。
     */
    private val startIndex: Int? = null,
    private val kind: HomeGeneratedPlaylistKind = HomeGeneratedPlaylistKind.Static,
    /** 拉取心动歌曲的种子曲目 id（这类页面本身不携带曲目，种子必须由调用方传入）。 */
    private val seedTrackId: String? = null,
    /**
     * 心动模式的 `pid`（歌单上下文，约定传「我喜欢的音乐」收藏夹 id）。
     * 上游把 `pid=0` 当非法请求 —— 没有收藏夹时该入口拿不到推荐，属于正常降级。
     */
    private val seedPlaylistId: Long? = null,
) : Screen {
    @Composable
    override fun Content() {
        val model = rememberScreenModel { PlaylistDetailScreenModel() }
        val sourceTracks by rememberUpdatedState(initialTracks)
        val navigator = LocalNavigator.currentOrThrow
        LaunchedEffect(kind, playlist.id, seedTrackId, seedPlaylistId, sourceTracks.firstOrNull()?.id) {
            when (kind) {
                HomeGeneratedPlaylistKind.Static -> Unit
                HomeGeneratedPlaylistKind.IntelligenceFromDaily -> {
                    val seed = seedTrackId ?: sourceTracks.firstOrNull()?.id ?: return@LaunchedEffect
                    val result = try {
                        AppModel.musicRepository.getIntelligenceSongs(seed, seedPlaylistId ?: 0L)
                    } catch (e: Exception) {
                        BackendResult.Error(e.message ?: "获取心动歌曲失败", cause = e)
                    }
                    val tracks = (result as? BackendResult.Success)?.data.orEmpty()
                    if (tracks.isEmpty()) {
                        // 空结果必须给出原因：恒空会显得像按钮坏了，而真实原因
                        // 可能是音源不支持 / 上游报错 / 账号还没有红心歌曲。
                        cp.player.app.ui.util.UiEvents.notify(
                            when (result) {
                                is BackendResult.Unsupported -> "当前音源不支持心动模式"
                                is BackendResult.Error -> result.message
                                else -> "心动模式暂无推荐：需要有红心歌曲（登录并收藏过歌曲）"
                            }
                        )
                    }
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
        // 桌面自绘标题栏接管时，返回入口统一在窗口 chrome 上 —— 页内再画一个就重复了。
        val chromeActive = cp.player.app.ui.component.LocalWindowChromeActive.current
        if (!embedded) cp.player.app.ui.util.DesktopRouteTitle("最近播放")

        Column(Modifier.fillMaxSize()) {
            // 标题与正文同宽、一起居中：宽屏下标题贴着窗口最左而正文居中，两段会明显错位。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()) {
                    PageTitleBar(
                        title = "最近播放",
                        subtitle = "完整历史列表 · 共 ${recentTracks.size} 首",
                        onBack = if (embedded || chromeActive) null else { { navigator?.pop() } },
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
                                        contextMenu = songContextMenuItems(
                                            SongMenuActions(
                                                onPlay = {
                                                    CoverFlight.play(track.id, track.coverUrl)
                                                    scope.launch {
                                                        AppModel.playback.playQueue(
                                                            recentTracks.map { toMediaId(it.id) },
                                                            startIndex = index,
                                                        )
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
                                                onShare = { shareText(songShareText(track)) },
                                            )
                                        ),
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
                onPlayNext = {
                    scope.launch { AppModel.playback.addNextToQueue(toMediaId(track.id)) }
                    cp.player.app.ui.util.UiEvents.notify("将在下一首播放")
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
            CpBackButton(onClick = onBack)
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
