package cp.player.app.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import cp.player.app.ui.navigation.pageModel
import androidx.lifecycle.viewModelScope
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRefreshablePage
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.TrackEmptySpec
import cp.player.app.ui.component.TrackListShell
import cp.player.app.ui.component.TrackListStatus
import cp.player.app.ui.component.TrackPrimaryAction
import cp.player.app.ui.component.TrackRows
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

/**
 * 云盘页（独立路由页）。
 *
 * ## 为什么从「我的」页的一个分段独立成页
 *
 * 云盘原本是「我的」页里 `曲库` 分段控件的第二档，与仪表盘、歌单列表**共用同一个
 * LazyColumn**。那个位置有三个绕不开的问题：
 *
 * 1. **入口是假的**：仪表盘上的「云盘」卡只做 `selectTab(1)`，而页面不会滚动 ——
 *    曲库在仪表盘（约 500dp）之下，点了之后屏幕上没有任何变化。
 * 2. **滚动位置共享**：云盘常有几十上百首，往下滚过云盘再切回歌单，列表停在云盘的
 *    滚动位置；反过来也一样。两个内容池挤在一个滚动容器里必然互相干扰。
 * 3. **没有自己的加载边界**：加载中 / 失败 / 空态都得塞进共享列表的 `item {}` 里，
 *    于是「云盘加载失败」的重试按钮和「歌单列表」在同一个视口里打架。
 *
 * 独立成页之后：有标题与返回键（走统一歌曲列表壳）、有独立滚动位置、有自己的
 * 刷新（安卓下拉），并且可以从「我的」页的云盘卡直接跳进来。
 *
 * ## 2026-10-04 重写修掉了什么
 *
 * 1. **永久转圈（用不了的根因）**：旧版 `CloudDriveUiState` 默认 `loading = true`，
 *    而 `init { load() }` 里 `load()` 的第一行是
 *    `if (_state.value.loading && !force) return` —— 初始加载被自己的守卫拦下，
 *    没有任何人再把 loading 置回 false，页面永远停在「正在加载云盘」。
 *    （与心动模式 2026-10-03 那次「永久转圈」同族：守卫拦下 + 无人复位。）
 * 2. **只拉前 200 首**：`user/cloud` 单页上限 200（实测 count=269、hasMore=true），
 *    旧版没有任何翻页，第 200 首之后的歌曲静默丢失。现在滚动接近列表尾部自动
 *    加载下一页（与歌单详情页同一套分页模式）。
 * 3. **动作为空**：旧版只给「播放 / 分享」。实测云盘歌曲的 `simpleSong.id` 就是
 *    标准歌曲 id（`song/url/v1` 正常返回），因此补齐加入队列 / 下一首播放 /
 *    收藏 / 下载，并新增**从云盘删除**（走 [CpConfirmHost] 二次确认）。
 */
class CloudDriveScreen(private val embedded: Boolean = false) : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val model = pageModel { CloudDriveScreenModel() }
        CloudDriveContent(
            model = model,
            embedded = embedded,
            onBack = { navigator.pop() },
        )
    }
}

typealias CloudDriveUiState = cp.player.app.presentation.clouddrive.CloudDriveUiState

/**
 * 云盘页的 Voyager 适配层（Thin Adapter）。
 *
 * 状态机在 [cp.player.app.presentation.clouddrive.CloudDrivePresenter]，
 * 这里只负责生命周期绑定与 UI 副作用注入。
 */
class CloudDriveScreenModel : ViewModel() {
    private val presenter = cp.player.app.presentation.clouddrive.CloudDrivePresenter(
        scope = viewModelScope,
        notify = UiEvents::notify,
        playCoverFlight = { CoverFlight.play(it.id, it.coverUrl) },
    )

    val state: kotlinx.coroutines.flow.StateFlow<CloudDriveUiState> get() = presenter.state
    val refreshing: kotlinx.coroutines.flow.StateFlow<Boolean> get() = presenter.refreshing

    fun load(force: Boolean = false) = presenter.load(force)
    fun loadMore() = presenter.loadMore()
    fun clearLoadMoreError() = presenter.clearLoadMoreError()
    fun play(index: Int) = presenter.play(index)
    fun addToQueue(track: TrackSummary) = presenter.addToQueue(track)
    fun playNext(track: TrackSummary) = presenter.playNext(track)
    fun delete(track: TrackSummary) = presenter.delete(track)
}

@Composable
private fun CloudDriveContent(
    model: CloudDriveScreenModel,
    embedded: Boolean,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsState()
    val refreshing by model.refreshing.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val scope = rememberCoroutineScope()
    var optionsTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var infoTarget by remember { mutableStateOf<TrackSummary?>(null) }

    val confirm = cp.player.app.ui.component.rememberConfirmState()
    val askDelete: (TrackSummary) -> Unit = { track ->
        confirm.request(
            title = "从云盘删除",
            message = "确定从云盘删除「${track.name}」吗？删除后无法恢复。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { model.delete(track) },
        )
    }

    val summary = cp.player.core.music.PlaylistSummary(
        id = Long.MIN_VALUE,
        name = "云盘",
        coverUrl = state.songs.firstOrNull()?.coverUrl,
        trackCount = state.songs.size,
        creatorName = "跨设备音乐",
    )
    val countLabel = when {
        state.loading -> "正在同步…"
        state.hasMore -> "已加载 ${state.songs.size} 首 · 下滑继续加载"
        else -> "${state.songs.size} 首歌曲"
    }
    val rows = TrackRows(
        items = state.songs,
        key = { it.id },
        row = { index, track, rowModifier ->
            SongItem(
                track = track,
                index = index,
                total = state.songs.size,
                isCurrentlyPlaying = track.id == currentTrackId,
                modifier = rowModifier,
                onClick = { model.play(index) },
                onOptionsClick = { optionsTarget = track },
                contextMenu = songContextMenuItems(
                    SongMenuActions(
                        onPlay = { model.play(index) },
                        isFavorite = track.id in likedIds,
                        onToggleFavorite = {
                            scope.launch {
                                val target = track.id !in likedIds
                                AppModel.playback.toggleFavoriteFor(
                                    "${AppModel.activeProviderId()}://song/${track.id}"
                                )
                                UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                            }
                        },
                        onAddToQueue = { model.addToQueue(track) },
                        onPlayNext = { model.playNext(track) },
                        onShare = { shareText(songShareText(track)) },
                        onDelete = { askDelete(track) },
                    )
                ),
            )
        },
    )

    CpRefreshablePage(
        isRefreshing = refreshing,
        onRefresh = { model.load(force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        TrackListShell(
            rows = rows,
            status = TrackListStatus(
                loading = state.loading,
                error = state.error,
                hasMore = state.hasMore,
                loadingMore = state.loadingMore,
                loadMoreError = state.loadMoreError,
            ),
            empty = TrackEmptySpec(
                title = "云盘空空如也",
                message = "把歌曲上传到云盘后会显示在这里",
            ),
            title = { PlaylistTopBarTitle(summary) },
            onBack = onBack,
            header = {
                PlaylistHeaderInfo(
                    summary = summary,
                    trackCountLabel = countLabel,
                    isFavorite = false,
                )
            },
            primaryActions = listOf(TrackPrimaryAction.PlayAll),
            onPrimaryAction = { model.play(0) },
            onPrimaryActionEnabled = state.songs.isNotEmpty() && !state.loading,
            onRetry = { model.load(force = true) },
            onLoadMore = {
                if (state.loadMoreError != null) model.clearLoadMoreError()
                model.loadMore()
            },
            applyStatusBarInset = !embedded,
        )
    }

    optionsTarget?.let { track ->
        val index = state.songs.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        SongOptionsSheet(
            songName = track.name,
            artistName = track.artist,
            coverUrl = track.coverUrl,
            isFavorite = track.id in likedIds,
            isDownloaded = AppModel.isDownloaded(track.id),
            onDismiss = { optionsTarget = null },
            onPlay = { model.play(index) },
            onToggleFavorite = {
                scope.launch {
                    val target = track.id !in likedIds
                    AppModel.playback.toggleFavoriteFor("${AppModel.activeProviderId()}://song/${track.id}")
                    UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                }
            },
            onAddToQueue = { model.addToQueue(track) },
            onPlayNext = { model.playNext(track) },
            onAddToPlaylist = { addToPlaylistTarget = track },
            onDownload = { AppModel.downloadTrack(track) },
            onShowInfo = { infoTarget = track },
            onShare = { shareText(songShareText(track)) },
        )
    }
    addToPlaylistTarget?.let { track ->
        AddToPlaylistSheet(trackId = track.id, onDismiss = { addToPlaylistTarget = null })
    }
    infoTarget?.let { track ->
        SongDetailInfoDialog(track = track, onDismiss = { infoTarget = null })
    }

    CpConfirmHost(confirm)
}
