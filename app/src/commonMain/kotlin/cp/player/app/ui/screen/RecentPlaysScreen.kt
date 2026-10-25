package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.songShareText
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

/**
 * 最近播放的**行**，铺进调用方的 [LazyListScope]。
 *
 * ### 为什么不直接把 [RecentPlaysScreen] 整个塞进别的列表
 * 本页正文自带一个 `LazyColumn` + `fillMaxSize`（它要能当独立整页用）。把它当
 * `item { RecentPlaysScreen(embedded = true).Content() }` 塞进「听歌报告」的列表里会崩：
 * `LazyColumn` 的 item 高度约束是**无界的**，内层滚动容器拿到 `maxHeight = Infinity`
 * ⇒ Compose 直接抛
 * `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints`（离屏探针实测，桌面与安卓同一份代码）。
 *
 * ### 为什么 [columns] 由调用方算好传进来
 * `LazyScrollColumn` 的 content lambda 是 `LazyListScope.() -> Unit`、**不是** `@Composable`，
 * 所以这个扩展里读不了任何 composable 状态（`LocalIsExpanded` 也不行）。
 * 列数判据（`BoxWithConstraints` 里的 `maxWidth >= 900.dp`）必须留在 `@Composable` 那一层。
 */
fun LazyListScope.recentPlaysRows(
    tracks: List<TrackSummary>,
    columns: Int,
    onPlay: (Int) -> Unit,
    onOptions: (TrackSummary) -> Unit,
    contextMenu: @Composable (TrackSummary) -> List<CpContextMenuItem>,
) {
    require(columns >= 1) { "columns 必须 ≥ 1，实际 $columns" }
    // 宽屏两列：一整行只放一首歌时，「歌名」和右端的「更多」按钮相隔上千 dp，
    // 视线要来回跳；两列之后每列 ≈700dp，与歌单详情页的曲目宽度相近。
    val rows = if (columns == 1) tracks.map { listOf(it) } else tracks.chunked(columns)
    itemsIndexed(rows, key = { rowIndex, row -> row.firstOrNull()?.id ?: rowIndex.toString() }) { rowIndex, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEachIndexed { columnIndex, track ->
                val index = rowIndex * columns + columnIndex
                SongItem(
                    track = track,
                    index = index,
                    total = tracks.size,
                    onClick = { onPlay(index) },
                    onOptionsClick = { onOptions(track) },
                    contextMenu = contextMenu(track),
                    modifier = Modifier.weight(1f),
                )
            }
            // 末尾一行是奇数时补等宽空位，否则最后一条会被单独拉满整行。
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * 最近播放完整列表。
 *
 * @param embedded 是否内嵌在桌面右侧面板里，或作为 tab 段落被别的页面收纳。
 *   为真时不画页内返回键 —— 那种形态下标题与返回都由窗口标题栏 / 宿主提供，
 *   而且 `pop()` 会退出 [MainScreen] 本身。
 */
class RecentPlaysScreen(private val embedded: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        val recentTracks by AppModel.recentTracksFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        var selectedTrack by remember { mutableStateOf<TrackSummary?>(null) }
        // ⚠️ 「更多」弹层里点「歌单 / 歌曲信息」会**先回调动作、再自行关闭**弹层
        // （见 SongOptionsSheet 的 onClick）。所以这两个二级弹层的目标必须由**本层**
        // 持有 —— 交给弹层宿主自己 remember 的话，弹层一关宿主就被卸载，目标状态
        // 跟着丢，二级弹层永远不出现。
        var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
        var addToLocalPlaylistPath by remember { mutableStateOf<String?>(null) }
        var showInfoTarget by remember { mutableStateOf<TrackSummary?>(null) }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        val localFavoritePaths by AppModel.localMedia.favorites.paths.collectAsState()
        val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }
        val navigator = LocalNavigator.current
        val expanded = LocalIsExpanded.current

        val topBarActions = remember(recentTracks) {
            if (recentTracks.isNotEmpty()) {
                listOf(
                    TopBarAction(
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "播放全部") },
                        onClick = {
                            recentTracks.firstOrNull()?.let { CoverFlight.play(it.id, it.coverUrl) }
                            scope.launch {
                                AppModel.playback.playQueue(recentTracks.map { toMediaId(it.id) }, startIndex = 0)
                            }
                        }
                    )
                )
            } else emptyList()
        }

        CpRouteScaffold(
            title = "最近播放",
            onBack = if (embedded) null else ({ navigator?.pop() }),
            embedded = embedded,
            topBarActions = topBarActions,
        ) { pageModifier ->
            Box(pageModifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (recentTracks.isEmpty()) {
                    StateSurface(
                        modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxWidth()
                            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 16.dp),
                    ) {
                        ContentState(
                            title = "还没有最近播放",
                            message = "播放歌曲后会显示在这里",
                        )
                    }
                } else {
                    // 宽屏两列：一整行只放一首歌时，「歌名」和右端的「更多」按钮相隔上千 dp，
                    // 视线要来回跳；两列之后每列 ≈700dp，与歌单详情页的曲目宽度相近。
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        // 列数只能在这一层算：`LazyScrollColumn` 的 content lambda 不是
                        // @Composable，里面的扩展函数读不到 `LocalIsExpanded`。
                        val columns = if (maxWidth >= 900.dp) 2 else 1
                        val navInsetDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        LazyScrollColumn(
                            modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = CpSpacing.pageHorizontal,
                                end = CpSpacing.pageHorizontal,
                                top = 8.dp,
                                // 宽屏的 MiniPlayer 浮在底部，末尾要给它留一条安全带；安卓窄屏下考虑导航栏与浮动 MiniPlayer。
                                bottom = if (expanded) 96.dp else (navInsetDp + 84.dp),
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            recentPlaysRows(
                                tracks = recentTracks,
                                columns = columns,
                                onPlay = { index ->
                                    val track = recentTracks[index]
                                    CoverFlight.play(track.id, track.coverUrl)
                                    AppModel.playTrackClicked(toMediaId(track.id)) {
                                        AppModel.playback.playQueue(
                                            recentTracks.map { toMediaId(it.id) },
                                            startIndex = index,
                                        )
                                    }
                                },
                                onOptions = { selectedTrack = it },
                                contextMenu = { track ->
                                    val isLocal = track.id.startsWith("local://")
                                    val cloudId = track.originalMediaId ?: (if (!isLocal) track.id else null)
                                    val cloudFavId = cloudId?.let { runCatching { cp.player.core.music.CPMediaId.parse(it).resourceId }.getOrDefault(it) }
                                    val isCloudFav = cloudFavId != null && cloudFavId in likedIds
                                    val localPath = if (isLocal) track.id.removePrefix("local://audio/").removePrefix("local://video/") else null
                                    val isLocalFav = localPath != null && localPath in localFavoritePaths
                                    val isFav = if (cloudId != null) isCloudFav else isLocalFav

                                    songContextMenuItems(
                                        SongMenuActions(
                                            onPlay = {
                                                CoverFlight.play(track.id, track.coverUrl)
                                                scope.launch {
                                                    AppModel.playback.playQueue(
                                                        recentTracks.map { toMediaId(it.id) },
                                                        startIndex = recentTracks.indexOf(track),
                                                    )
                                                }
                                            },
                                            isFavorite = isFav,
                                            onToggleFavorite = if (cloudId != null) {
                                                {
                                                    scope.launch {
                                                        val target = cloudFavId !in likedIds
                                                        AppModel.playback.toggleFavoriteFor(cloudId)
                                                        cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                                                    }
                                                }
                                            } else if (localPath != null) {
                                                {
                                                    val now = AppModel.localMedia.favorites.toggle(localPath)
                                                    cp.player.app.ui.util.UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
                                                }
                                            } else null,
                                            favoriteLabel = if (cloudId != null) {
                                                if (isCloudFav) "取消云端收藏" else "云端收藏"
                                            } else {
                                                if (isLocalFav) "取消喜欢" else "喜欢"
                                            },
                                            onAddToQueue = {
                                                scope.launch { AppModel.playback.addToQueue(toMediaId(track.id)) }
                                                cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
                                            },
                                            onPlayNext = {
                                                scope.launch { AppModel.playback.addNextToQueue(toMediaId(track.id)) }
                                                cp.player.app.ui.util.UiEvents.notify("将在下一首播放")
                                            },
                                            isDownloaded = cloudId != null && (isLocal || AppModel.isDownloaded(cloudId)),
                                            onDownload = if (cloudId != null && !isLocal) {
                                                { AppModel.downloadTrack(track) }
                                            } else null,
                                            onAddToPlaylist = if (cloudId != null) {
                                                { addToPlaylistTrack = track }
                                            } else null,
                                            onAddToLocalPlaylist = if (localPath != null) {
                                                { addToLocalPlaylistPath = localPath }
                                            } else null,
                                            onOpenInFileManager = if (localPath != null) {
                                                { cp.player.app.platform.openInFileManager(localPath) }
                                            } else null,
                                            onShare = if (cloudId != null) {
                                                { shareText(songShareText(track)) }
                                            } else null,
                                            onShowInfo = { showInfoTarget = track },
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        selectedTrack?.let { track ->
            RecentPlaysOptionsHost(
                track = track,
                onDismiss = { selectedTrack = null },
                onAddToPlaylist = { addToPlaylistTrack = it },
                onShowInfo = { showInfoTarget = it },
                onAddToLocalPlaylist = { addToLocalPlaylistPath = it },
            )
        }

        addToPlaylistTrack?.let { track ->
            val targetId = track.originalMediaId ?: track.id
            cp.player.app.ui.component.AddToPlaylistSheet(
                trackIds = listOf(targetId),
                onDismiss = { addToPlaylistTrack = null },
            )
        }

        addToLocalPlaylistPath?.let { path ->
            cp.player.app.ui.component.AddToLocalPlaylistDialog(
                trackPaths = listOf(path),
                onDismiss = { addToLocalPlaylistPath = null },
            )
        }

        showInfoTarget?.let { track ->
            cp.player.app.ui.component.SongDetailInfoDialog(
                track = track,
                onDismiss = { showInfoTarget = null },
            )
        }
    }
}

/**
 * 「最近播放」行的选项弹层宿主。
 *
 * 独立整页与听歌报告的「最近」tab 共用同一份 —— 两处各写一遍的话，
 * 收藏判定与文案迟早会漂。
 *
 * ⚠️ 这里只负责**弹层本身**；弹层里的「歌单 / 歌曲信息」二级弹层的目标由调用方持有，
 * 经 [onAddToPlaylist] / [onShowInfo] 回调出去。原因见调用点的注释 —— 弹层会先回调
 * 动作、再自行 `onDismiss`，宿主随之卸载，状态放这里会被一起丢掉。
 */
@Composable
fun RecentPlaysOptionsHost(
    track: TrackSummary?,
    onDismiss: () -> Unit,
    onAddToPlaylist: (TrackSummary) -> Unit,
    onShowInfo: (TrackSummary) -> Unit,
    onAddToLocalPlaylist: ((String) -> Unit)? = null,
) {
    if (track == null) return
    val scope = rememberCoroutineScope()
    val provider = AppModel.activeProviderId()
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val localFavorites by AppModel.localMedia.favorites.paths.collectAsState()
    val toMediaId = { id: String -> if (id.contains("://")) id else "$provider://song/$id" }

    val isLocal = track.id.startsWith("local://")
    val cloudId = track.originalMediaId ?: (if (!isLocal) track.id else null)
    val cloudFavId = cloudId?.let { runCatching { cp.player.core.music.CPMediaId.parse(it).resourceId }.getOrDefault(it) }
    val localPath = if (isLocal) track.id.removePrefix("local://audio/").removePrefix("local://video/") else null
    val isLocalFav = localPath != null && localPath in localFavorites
    val isCloudFav = cloudFavId != null && cloudFavId in likedIds
    val isFav = if (cloudId != null) isCloudFav else isLocalFav

    SongOptionsSheet(
        songName = track.name,
        artistName = track.artist,
        coverUrl = track.coverUrl,
        isFavorite = isFav,
        isDownloaded = cloudId != null && (isLocal || AppModel.isDownloaded(cloudId)),
        onDismiss = onDismiss,
        onPlay = {
            CoverFlight.play(track.id, track.coverUrl)
            scope.launch {
                AppModel.playback.playQueue(listOf(toMediaId(track.id)), startIndex = 0)
            }
        },
        onToggleFavorite = {
            if (cloudId != null) {
                scope.launch {
                    val target = cloudFavId !in likedIds
                    AppModel.playback.toggleFavoriteFor(cloudId)
                    cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏到云端" else "已取消云端收藏")
                }
            } else if (localPath != null) {
                val now = AppModel.localMedia.favorites.toggle(localPath)
                cp.player.app.ui.util.UiEvents.notify(if (now) "已添加到本地喜欢" else "已从本地喜欢移除")
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
        onAddToPlaylist = {
            if (cloudId != null) {
                onAddToPlaylist(track)
            } else if (localPath != null) {
                onAddToLocalPlaylist?.invoke(localPath)
            }
        },
        onDownload = {
            if (cloudId != null && !isLocal) {
                AppModel.downloadTrack(track)
            }
        },
        onShowInfo = { onShowInfo(track) },
        onShare = {
            if (cloudId != null) {
                shareText(songShareText(track))
            } else {
                cp.player.app.ui.util.UiEvents.notify("本地歌曲无在线分享链接")
            }
        },
    )
}
