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
import cp.player.app.ui.component.CpRefreshablePage
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.util.popOrNotify
import cp.player.core.BackendResult
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
 * 刷新（桌面右键 / 安卓下拉），并且可以从「我的」页的云盘卡**直接跳进来**。
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

    /** 在途协程与世代号：切源 / 换账号 / 手动刷新可能并发，后到者不该覆盖新结果。 */
    private var job: Job? = null
    private var gen = 0

    init {
        load()
        screenModelScope.launch {
            AppModel.sourceGeneration.drop(1).collect { load(force = true) }
        }
        screenModelScope.launch {
            AppModel.accountGeneration.drop(1).collect { load(force = true) }
        }
    }

    fun load(force: Boolean = false) {
        if (_state.value.loading && !force) return
        job?.cancel()
        val myGen = ++gen
        job = screenModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            _refreshing.value = true
            try {
                // IO 线程只负责取数据，状态回写在 Main（与 LibraryScreenModel 同一套约定）。
                val result = withContext(Dispatchers.IO) {
                    runCatching { AppModel.musicRepository.getUserCloud() }.getOrNull()
                }
                if (myGen != gen) return@launch
                _state.value = when (result) {
                    is BackendResult.Success -> CloudDriveUiState(songs = result.data, loading = false)
                    is BackendResult.Error -> CloudDriveUiState(loading = false, error = result.message)
                    is BackendResult.Unsupported -> CloudDriveUiState(loading = false, error = result.message)
                    null -> CloudDriveUiState(loading = false, error = "云盘加载失败")
                }
            } finally {
                if (myGen == gen) _refreshing.value = false
            }
        }
    }

    fun play(index: Int) {
        val songs = _state.value.songs
        if (songs.isEmpty()) return
        songs.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val provider = AppModel.activeProviderId()
        screenModelScope.launch {
            AppModel.playback.playQueue(songs.map { "$provider://song/${it.id}" }, startIndex = index)
        }
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
                    else -> items(state.songs.size) { index ->
                        val track = state.songs[index]
                        SongItem(
                            track = track,
                            index = index,
                            total = state.songs.size,
                            isCurrentlyPlaying = track.id == currentTrackId,
                            onClick = { model.play(index) },
                            // 云盘歌曲的 id 不是标准歌曲 id，加入队列的 mediaId 拼法不通用，
                            // 这里只给「播放」与「分享」两个确定可用的动作。
                            contextMenu = songContextMenuItems(
                                SongMenuActions(
                                    onPlay = { model.play(index) },
                                    onShare = { shareText(songShareText(track)) },
                                )
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部信息条：云盘说明 + 歌曲数 + 全部播放。
 *
 * 整页只有这一处「面」，下面是纯列表 —— 云盘最常用的动作就是「从头放一遍」。
 */
@Composable
private fun CloudDriveHeader(
    count: Int,
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
                    if (loading) "正在同步…" else "$count 首歌曲 · 上传后可跨设备播放",
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
