package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AlbumCoverThumb
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.platform.shareText
import cp.player.app.ui.util.UiEvents
import cp.player.app.ui.util.formatTimeMs
import cp.player.core.BackendResult
import cp.player.core.music.AlbumDetail
import cp.player.core.music.AlbumSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 专辑详情页。
 *
 * 收敛前**根本不存在**：首页的「新碟上架」与搜索的「专辑」页签点下去都只能退回搜索框，
 * 用户看到的是「点了没反应 / 又搜了一遍」。这一页把 `album` 接口真正接进来。
 *
 * 版式与 [PlaylistDetailContent] 对齐 —— 同一个应用里「一坨内容的详情页」只该有一种长相：
 * 顶部是封面 + 元信息 + 三个动作（播放 / 打乱 / 加入队列），下面是曲目列表。
 */
class AlbumDetailScreen(
    private val albumId: Long,
    private val fallback: AlbumSummary? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        AlbumDetailContent(
            albumId = albumId,
            fallback = fallback,
            // ⚠️ `rememberScreenModel` 是**定义在 `Screen` 上的扩展函数**
            // （`javap` 核实：`rememberScreenModel(Screen, String, Function0, ...)`），
            // 只有在 Screen 子类的成员里才有接收者。放到顶层的私有 @Composable 里会
            // 直接报 `Unresolved reference`。所以模型一律在这里建好往下传 ——
            // 与 [PlaylistDetailScreen] 同一套写法。
            model = rememberScreenModel { AlbumDetailModel() },
            onBack = { navigator.popOrNotify() },
        )
    }
}

private data class AlbumUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: AlbumDetail? = null,
)

private class AlbumDetailModel : ScreenModel {
    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state

    private var loadedId: Long? = null

    fun load(albumId: Long, fallback: AlbumSummary?) {
        if (loadedId == albumId && _state.value.detail != null) return
        loadedId = albumId
        screenModelScope.launch {
            _state.value = AlbumUiState(loading = true)
            val result = runCatching { AppModel.musicRepository.getAlbumDetail(albumId) }
                .getOrElse { BackendResult.Error(it.message ?: "加载专辑失败") }
            _state.value = when (result) {
                is BackendResult.Success -> AlbumUiState(loading = false, detail = result.data)
                is BackendResult.Error -> AlbumUiState(
                    loading = false,
                    error = result.message,
                    // 拿不到详情时**保留列表页传来的封面**：让用户至少知道自己点开了哪一张。
                    detail = fallback?.let {
                        AlbumDetail(
                            id = it.id,
                            name = it.name,
                            coverUrl = it.coverUrl,
                            artistName = it.artistName,
                            artistId = it.artistId,
                            publishTimeMs = it.publishTimeMs,
                            company = null,
                            description = null,
                            tracks = emptyList(),
                        )
                    },
                )
                is BackendResult.Unsupported -> AlbumUiState(
                    loading = false,
                    error = result.message,
                )
            }
        }
    }
}

@Composable
private fun AlbumDetailContent(
    albumId: Long,
    fallback: AlbumSummary?,
    model: AlbumDetailModel,
    onBack: () -> Unit,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val provider = AppModel.activeProviderId()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    var optionsTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }

    LaunchedEffect(albumId) { model.load(albumId, fallback) }

    val detail = state.detail
    val tracks = detail?.tracks.orEmpty()
    val mediaIds = tracks.map { "$provider://song/${it.id}" }

    val playAt: (Int) -> Unit = { index ->
        tracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        scope.launch { AppModel.playback.playQueue(mediaIds, index.coerceAtLeast(0)) }
    }
    val shufflePlay: () -> Unit = {
        scope.launch {
            AppModel.playback.playQueue(mediaIds.shuffled(), 0)
            UiEvents.notify("已打乱播放")
        }
    }
    val queueAll: () -> Unit = {
        scope.launch {
            mediaIds.forEach { AppModel.playback.addToQueue(it) }
            UiEvents.notify("已加入播放队列")
        }
    }

    CpRouteScaffold(title = detail?.name ?: fallback?.name ?: "专辑", onBack = onBack) { pageModifier ->
        BoxWithConstraints(pageModifier.fillMaxSize()) {
            // 宽屏把「封面 + 说明」放到左侧固定栏，曲目占右侧 —— 与歌单详情同一套断点。
            val isWide = cp.player.app.ui.component.CpBreakpoints.isExpanded(maxWidth)
            when {
                state.loading && detail == null -> ContentState(
                    title = "正在载入专辑",
                    message = "正在从当前音源读取专辑信息",
                    loading = true,
                )
                detail == null -> ContentState(
                    title = "没有打开这张专辑",
                    message = state.error,
                    error = true,
                    actionLabel = "重试",
                    onAction = { model.load(albumId, fallback) },
                )
                else -> LazyScrollColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = CpSpacing.pageHorizontal,
                        end = CpSpacing.pageHorizontal,
                        bottom = CpSpacing.formBottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                ) {
                    item {
                        AlbumHeader(
                            detail = detail,
                            trackCount = tracks.size,
                            totalDurationMs = tracks.sumOf { it.durationMs },
                            wide = isWide,
                            onPlayAll = { playAt(0) },
                            onShuffle = shufflePlay,
                            onAddToQueue = queueAll,
                            onDownloadAll = { AppModel.downloadTracks(tracks) },
                        )
                    }
                    item {
                        SectionHeader(
                            title = "曲目",
                            supportingText = if (tracks.isEmpty()) {
                                detail?.let { state.error ?: "这张专辑没有可播放的曲目" }.orEmpty()
                            } else {
                                "${tracks.size} 首"
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (tracks.isEmpty()) {
                        item {
                            ContentState(
                                title = "暂无曲目",
                                message = state.error ?: "换一张专辑，或在音源设置里换一个音源试试",
                            )
                        }
                    } else {
                        items(tracks.size, key = { tracks[it].id }) { index ->
                            val track = tracks[index]
                            SongItem(
                                track = track,
                                index = index,
                                total = tracks.size,
                                isCurrentlyPlaying = track.id == currentTrackId,
                                modifier = Modifier.animateItem(),
                                onClick = { playAt(index) },
                                onOptionsClick = { optionsTarget = track },
                                // 桌面端右键菜单：动作集合与 SongOptionsSheet 对齐
                                contextMenu = songContextMenuItems(
                                    SongMenuActions(
                                        onPlay = { playAt(index) },
                                        isFavorite = track.id in likedIds,
                                        onToggleFavorite = {
                                            val target = track.id !in likedIds
                                            scope.launch {
                                                AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                                                UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                                            }
                                        },
                                        onAddToQueue = {
                                            scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                                            UiEvents.notify("已加入播放队列")
                                        },
                                        isDownloaded = AppModel.isDownloaded(track.id),
                                        onDownload = { AppModel.downloadTrack(track) },
                                        onAddToPlaylist = { addToPlaylistTrack = track },
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

    optionsTarget?.let { track ->
        SongOptionsSheet(
            songName = track.name,
            artistName = track.artist,
            coverUrl = track.coverUrl,
            isFavorite = track.id in likedIds,
            isDownloaded = AppModel.isDownloaded(track.id),
            onDismiss = { optionsTarget = null },
            onPlay = {
                playAt(tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
                optionsTarget = null
            },
            onToggleFavorite = {
                val target = track.id !in likedIds
                scope.launch {
                    AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                    UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                }
            },
            onAddToQueue = {
                scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                UiEvents.notify("已加入播放队列")
            },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onDownload = { AppModel.downloadTrack(track) },
        )
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistSheet(trackId = track.id, onDismiss = { addToPlaylistTrack = null })
    }
}

/** 专辑头部：封面 + 名称 + 歌手 / 发行信息 + 说明 + 三个动作。 */
@Composable
private fun AlbumHeader(
    detail: AlbumDetail,
    trackCount: Int,
    totalDurationMs: Long,
    wide: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    val metaLine = buildList {
        detail.artistName?.takeIf { it.isNotBlank() }?.let(::add)
        detail.publishTimeMs?.let { ms ->
            cp.player.app.ui.component.epochMillisToYear(ms)?.let { year -> add("$year 年发行") }
        }
        detail.company?.takeIf { it.isNotBlank() }?.let(::add)
        if (trackCount > 0) add("$trackCount 首 · ${formatTimeMs(totalDurationMs)}")
    }.joinToString(" · ")

    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AlbumCoverThumb(
                coverUrl = detail.coverUrl,
                corner = 24.dp,
                modifier = Modifier.size(220.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    detail.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    metaLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                detail.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(16.dp))
                AlbumActions(onPlayAll, onShuffle, onAddToQueue, onDownloadAll)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            AlbumCoverThumb(
                coverUrl = detail.coverUrl,
                corner = 24.dp,
                // ⚠️ `widthIn` 必须在 `fillMaxWidth` **之前**：顺序反了约束已被钉死，
                // `widthIn` 会变成空操作（见 AGENTS.md 的宽高约束约定）。
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                detail.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                metaLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            detail.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(12.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            AlbumActions(onPlayAll, onShuffle, onAddToQueue, onDownloadAll)
        }
    }
}

@Composable
private fun AlbumActions(
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAll: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.FilledTonalButton(onClick = onPlayAll) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("播放全部")
        }
        TextButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("打乱")
        }
        TextButton(onClick = onAddToQueue) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("加入队列")
        }
        TextButton(onClick = onDownloadAll) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("下载")
        }
    }
}
