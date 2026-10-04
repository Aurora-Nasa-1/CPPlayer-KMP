package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.BentoCard
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConfirmState
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRefreshablePage
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.popOrNotify
import cp.player.core.BackendResult
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 独立成页之后：有标题与返回键（走 [CpRouteScaffold]）、有独立滚动位置、有自己的
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
class CloudDriveScreen(private val embedded: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        // rememberScreenModel 是 Screen 上的扩展函数，只能在 Content() 里调（见 AGENTS.md）。
        val model = rememberScreenModel { CloudDriveScreenModel() }
        CpRouteScaffold(
            title = "云盘",
            onBack = { navigator?.popOrNotify() },
            embedded = embedded,
        ) { pageModifier ->
            CloudDriveContent(model, pageModifier)
        }
    }
}

data class CloudDriveUiState(
    val songs: List<TrackSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** 服务端还有更多（上一页返回的数量达到单页上限时为真）。 */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

/**
 * 云盘取数。
 *
 * 刷新信号只认 `sourceGeneration` / `accountGeneration`（见 AppModel 的 KDoc）：
 * 云盘内容属于**账号**，换音源或换账号后必须重拉，而这两个 Flow 不会在启动时
 * 白发射一次（不像 `activeProviderFlow` / `userProfileFlow`）。
 */
class CloudDriveScreenModel : ScreenModel {
    private val _state = MutableStateFlow(CloudDriveUiState())
    val state: StateFlow<CloudDriveUiState> = _state.asStateFlow()

    /** 刷新在途标记（驱动下拉刷新指示器，与全屏 loading 分开）。 */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 在途协程与世代号：切源 / 换账号 / 手动刷新 / 加载更多可能并发，后到者不该覆盖新结果。 */
    private var job: Job? = null
    private var moreJob: Job? = null
    private var gen = 0

    init {
        // ⚠️ 初始加载**不带任何守卫**：初态本来就是 loading=true，
        // 旧版在这里用 `if (loading && !force) return` 把唯一一次加载拦掉了。
        load()
        screenModelScope.launch {
            AppModel.sourceGeneration.drop(1).collect { load(force = true) }
        }
        screenModelScope.launch {
            AppModel.accountGeneration.drop(1).collect { load(force = true) }
        }
    }

    /** 从第一页重拉（首次 / 下拉 / 切源 / 换账号）。IO 线程只取数，状态回写在 Main。 */
    fun load(force: Boolean = false) {
        if (_state.value.loading && job?.isActive == true && !force) return
        job?.cancel()
        moreJob?.cancel()
        val myGen = ++gen
        job = screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _refreshing.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { AppModel.musicRepository.getUserCloud(limit = PAGE_SIZE, offset = 0) }.getOrNull()
                }
                if (myGen != gen) return@launch
                _state.value = when (result) {
                    is BackendResult.Success -> CloudDriveUiState(
                        songs = result.data,
                        loading = false,
                        hasMore = result.data.size >= PAGE_SIZE,
                    )
                    is BackendResult.Error -> CloudDriveUiState(loading = false, error = result.message)
                    is BackendResult.Unsupported -> CloudDriveUiState(loading = false, error = result.message)
                    null -> CloudDriveUiState(loading = false, error = "云盘加载失败")
                }
            } finally {
                if (myGen == gen) _refreshing.value = false
            }
        }
    }

    /** 追加下一页：offset 取当前已加载数量；追加时按 id 去重（同一首歌上传两次会拿到同 id）。 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.error != null || !s.hasMore || s.loadingMore) return
        val myGen = gen
        moreJob?.cancel()
        moreJob = screenModelScope.launch {
            _state.update { it.copy(loadingMore = true, loadMoreError = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppModel.musicRepository.getUserCloud(limit = PAGE_SIZE, offset = s.songs.size)
                }.getOrNull()
            }
            if (myGen != gen) return@launch
            when (result) {
                is BackendResult.Success -> _state.update { cur ->
                    val known = cur.songs.mapTo(mutableSetOf()) { it.id }
                    val appended = result.data.filterNot { it.id in known }
                    cur.copy(
                        songs = cur.songs + appended,
                        loadingMore = false,
                        // 追加去重后为空时以返回数量为准，避免反复拉同一页（与歌单详情页同一约定）。
                        hasMore = if (appended.isEmpty()) false else result.data.size >= PAGE_SIZE,
                    )
                }
                is BackendResult.Error -> _state.update { it.copy(loadingMore = false, loadMoreError = result.message) }
                is BackendResult.Unsupported -> _state.update { it.copy(loadingMore = false, loadMoreError = result.message) }
                null -> _state.update { it.copy(loadingMore = false, loadMoreError = "加载更多失败") }
            }
        }
    }

    /** 清除分页错误（点击重试后重新触发 [loadMore]）。 */
    fun clearLoadMoreError() {
        moreJob?.cancel()
        _state.update { it.copy(loadMoreError = null) }
    }

    fun play(index: Int) {
        val songs = _state.value.songs
        if (songs.isEmpty()) return
        songs.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val provider = AppModel.activeProviderId()
        screenModelScope.launch {
            // 云盘歌曲的 simpleSong.id 是标准歌曲 id，song/url/v1 实测可用，
            // 直接按普通歌曲入列（带上列表上下文，跟随播放列表的功能才有落点）。
            AppModel.playback.playQueue(songs.map { "$provider://song/${it.id}" }, startIndex = index)
        }
    }

    fun addToQueue(track: TrackSummary) {
        screenModelScope.launch {
            AppModel.playback.addToQueue("${AppModel.activeProviderId()}://song/${track.id}")
            UiEvents.notify("已加入播放队列")
        }
    }

    fun playNext(track: TrackSummary) {
        screenModelScope.launch {
            AppModel.playback.addNextToQueue("${AppModel.activeProviderId()}://song/${track.id}")
            UiEvents.notify("将在下一首播放")
        }
    }

    /** 从云盘删除（上游 `user/cloud/del`）。成功后就地移除，不整页重拉。 */
    fun delete(track: TrackSummary) {
        screenModelScope.launch {
            val ok = runCatching { AppModel.musicRepository.deleteUserCloud(track.id) }
                .getOrDefault(false)
            if (ok) {
                _state.update { it.copy(songs = it.songs.filterNot { s -> s.id == track.id }) }
                UiEvents.notify("已从云盘删除「${track.name}」")
            } else {
                UiEvents.notify("删除失败")
            }
        }
    }

    private companion object {
        /** `user/cloud` 单页上限（服务端实测 200 封顶，传更大值也只回 200）。 */
        const val PAGE_SIZE = 200
    }
}

@Composable
private fun CloudDriveContent(
    model: CloudDriveScreenModel,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val refreshing by model.refreshing.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val scope = rememberCoroutineScope()

    // 「从云盘删除」是破坏性操作：只认 CpConfirmHost（AGENTS.md 硬规则）。
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

    CpRefreshablePage(
        isRefreshing = refreshing,
        onRefresh = { model.load(force = true) },
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyScrollColumn(
                modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxHeight(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    top = 8.dp,
                    bottom = CpSpacing.formBottomInset,
                ),
                // ⚠️ 歌曲行之间必须是**分组列表缝**（4dp），不是仪表盘的卡片缝（12dp）。
                // 云盘原先挂在「我的」页那个以 BentoGap 为行距的共享列表里，
                // 于是每行都被 12dp 的缝割成一张张独立卡片，一屏只放得下 7 行。
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                item {
                    CloudDriveHeader(
                        count = state.songs.size,
                        hasMore = state.hasMore,
                        loading = state.loading,
                        onPlayAll = { model.play(0) },
                    )
                }
                when {
                    state.loading -> item {
                        StateSurface {
                            ContentState(
                                title = "正在加载云盘",
                                message = "正在同步云盘歌曲",
                                loading = true,
                            )
                        }
                    }
                    state.error != null -> item {
                        StateSurface {
                            ContentState(
                                title = "云盘加载失败",
                                message = state.error,
                                error = true,
                                actionLabel = "重试",
                                onAction = { model.load(force = true) },
                            )
                        }
                    }
                    state.songs.isEmpty() -> item {
                        StateSurface {
                            ContentState(
                                title = "云盘空空如也",
                                message = "把歌曲上传到云盘后会显示在这里",
                            )
                        }
                    }
                    else -> {
                        items(state.songs.size) { index ->
                            val track = state.songs[index]
                            // 滚动接近尾部自动翻页（与歌单详情页同一套触发方式）。
                            if (index >= state.songs.size - 5 && state.hasMore &&
                                !state.loadingMore && state.loadMoreError == null
                            ) {
                                LaunchedEffect(index) { model.loadMore() }
                            }
                            SongItem(
                                track = track,
                                index = index,
                                total = state.songs.size,
                                isCurrentlyPlaying = track.id == currentTrackId,
                                onClick = { model.play(index) },
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
                        }
                        item {
                            when {
                                state.loadMoreError != null -> StateSurface {
                                    ContentState(
                                        title = "加载更多失败",
                                        message = state.loadMoreError,
                                        error = true,
                                        actionLabel = "重试",
                                        onAction = {
                                            model.clearLoadMoreError()
                                            model.loadMore()
                                        },
                                    )
                                }
                                state.loadingMore -> StateSurface {
                                    ContentState(title = "正在加载更多", message = "正在同步云盘歌曲", loading = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    CpConfirmHost(confirm)
}

/**
 * 顶部信息条：云盘说明 + 歌曲数 + 全部播放。
 *
 * 整页只有这一处「面」，下面是纯列表 —— 云盘最常用的动作就是「从头放一遍」。
 */
@Composable
private fun CloudDriveHeader(
    count: Int,
    hasMore: Boolean,
    loading: Boolean,
    onPlayAll: () -> Unit,
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(18.dp),
        // 内容高度由文字决定：撑满会把「全部播放」按钮顶到卡片下边缘之外。
        fillHeight = false,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Filled.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("云盘", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        loading -> "正在同步…"
                        count == 0 -> "上传后可跨设备播放"
                        hasMore -> "已加载 $count 首 · 下滑继续加载"
                        else -> "$count 首歌曲 · 上传后可跨设备播放"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onPlayAll, enabled = !loading && count > 0) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("全部播放", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
