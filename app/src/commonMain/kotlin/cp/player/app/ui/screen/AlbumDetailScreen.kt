package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AlbumCoverThumb
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpActionButtonGroup
import cp.player.app.ui.component.CpActionGroupItem
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
 * 专辑详情页面：展示专辑元数据、曲目列表与播放控制。
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
                .getOrElse { BackendResult.Error(it.message.orEmpty()) }
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
    val s = cpStrings()
    val navigator = LocalNavigator.current
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val provider = AppModel.activeProviderId()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    var optionsTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    // 「歌曲信息」的目标：与其余页面同款，走共享的 SongDetailInfoDialog。
    var showInfoTarget by remember { mutableStateOf<TrackSummary?>(null) }

    LaunchedEffect(albumId) { model.load(albumId, fallback) }

    val detail = state.detail
    val tracks = detail?.tracks.orEmpty()
    val mediaIds = tracks.map { "$provider://song/${it.id}" }

    val playAt: (Int) -> Unit = { index ->
        tracks.getOrNull(index)?.let { CoverFlight.play(it.id, it.coverUrl) }
        val clicked = mediaIds.getOrNull(index)
        if (clicked != null) {
            AppModel.playTrackClicked(clicked) {
                AppModel.playback.playQueue(mediaIds, index.coerceAtLeast(0))
            }
        }
    }
    val shufflePlay: () -> Unit = {
        scope.launch {
            AppModel.playback.playQueue(mediaIds.shuffled(), 0)
            UiEvents.notify(s.album.shufflePlay)
        }
    }
    val queueAll: () -> Unit = {
        scope.launch {
            mediaIds.forEach { AppModel.playback.addToQueue(it) }
            UiEvents.notify(s.library.queuedToPlay)
        }
    }

    CpRouteScaffold(title = detail?.name ?: fallback?.name ?: s.album.fallbackTitle, onBack = onBack) { pageModifier ->
        BoxWithConstraints(pageModifier.fillMaxSize()) {
            // 宽屏把「封面 + 说明」放到左侧固定栏，曲目占右侧 —— 与歌单详情同一套断点。
            val isWide = cp.player.app.ui.component.CpBreakpoints.isExpanded(maxWidth)
            when {
                state.loading && detail == null -> ContentState(
                    title = s.album.loading,
                    message = s.album.loadingNote,
                    loading = true,
                )
                detail == null -> ContentState(
                    title = s.album.notOpened,
                    // 后端给的 message 可能是空串（见 load() 里的 orEmpty）⇒ 用本地文案兜底。
                    message = state.error?.takeIf { it.isNotBlank() } ?: s.album.loadFailed,
                    error = true,
                    actionLabel = s.album.retry,
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
                            title = s.album.tracks,
                            supportingText = if (tracks.isEmpty()) {
                                detail?.let { state.error ?: s.album.noPlayableTracks }.orEmpty()
                            } else {
                                s.album.trackCount(tracks.size)
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (tracks.isEmpty()) {
                        item {
                            ContentState(
                                title = s.album.emptyTracks,
                                message = state.error ?: s.album.emptyTracksNote,
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
                                        onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                                            { navigator?.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV")) }
                                        },
                                        isFavorite = track.id in likedIds,
                                        onToggleFavorite = {
                                            val target = track.id !in likedIds
                                            scope.launch {
                                                AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                                                UiEvents.notify(if (target) s.album.liked else s.album.unliked)
                                            }
                                        },
                                        onAddToQueue = {
                                            scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                                            UiEvents.notify(s.library.queuedToPlay)
                                        },
                                        onPlayNext = {
                                            scope.launch { AppModel.playback.addNextToQueue("$provider://song/${track.id}") }
                                            UiEvents.notify(s.library.playNext)
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
                    UiEvents.notify(if (target) s.album.liked else s.album.unliked)
                }
            },
            onAddToQueue = {
                scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                UiEvents.notify(s.library.queuedToPlay)
            },
            onPlayNext = {
                scope.launch { AppModel.playback.addNextToQueue("$provider://song/${track.id}") }
                UiEvents.notify(s.library.playNext)
            },
            onAddToPlaylist = { addToPlaylistTrack = track },
            onDownload = { AppModel.downloadTrack(track) },
            onShowInfo = { showInfoTarget = track },
            onShare = { shareText(songShareText(track)) },
            onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                { navigator?.push(VideoPlayerScreen("netease://mv/$mvId", title = "${track.name} - MV")) }
            },
        )
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistSheet(trackId = track.id, onDismiss = { addToPlaylistTrack = null })
    }

    showInfoTarget?.let { track ->
        cp.player.app.ui.component.SongDetailInfoDialog(
            track = track,
            onDismiss = { showInfoTarget = null },
        )
    }
}

/**
 * 专辑头部：封面 + 名称 + 歌手 / 发行信息 + 说明 + 动作组。
 *
 * 按 M3 Expressive 的「hero 详情头」组织（与 `AboutScreen` 的 hero 同一套语言）：
 * 1. **标题用主色 + Bold** —— 一页的门面要一眼认出来；普通 `onSurface` 标题与列表行
 *    里的正文没有层级差，整页看起来像「一堆没排序的文字」；
 * 2. 封面圆角走 `shapes.extraLarge`（28dp），大图配大圆角；
 * 3. 动作操作整合为连体动作组（[CpActionButtonGroup]），主次分明且自适应横向布局。
 *
 * ⚠️ 曲目为空时四个动作**全部禁用**。拿不到曲目时头部仍会画出来（错误态也保留封面与
 * 名称），可点的按钮会让用户以为「点了没反应」—— 实际上队列里什么都没有。
 */
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
    val s = cpStrings()
    val metaLine = buildList {
        detail.artistName?.takeIf { it.isNotBlank() }?.let(::add)
        detail.publishTimeMs?.let { ms ->
            cp.player.app.ui.component.epochMillisToYear(ms)?.let { year -> add(s.album.releasedYear(year)) }
        }
        detail.company?.takeIf { it.isNotBlank() }?.let(::add)
        if (trackCount > 0) add(s.album.albumMeta(trackCount, formatTimeMs(totalDurationMs)))
    }.joinToString(" · ")

    val hasTracks = trackCount > 0
    val actions = listOf(
        CpActionGroupItem(s.library.playAll, Icons.Filled.PlayArrow, onPlayAll, enabled = hasTracks),
        CpActionGroupItem(s.album.shufflePlay, Icons.Filled.Shuffle, onShuffle, enabled = hasTracks),
        CpActionGroupItem(s.library.addToQueue, Icons.AutoMirrored.Filled.PlaylistAdd, onAddToQueue, enabled = hasTracks),
        CpActionGroupItem(s.library.downloadAll, Icons.Filled.Download, onDownloadAll, enabled = hasTracks),
    )

    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AlbumCoverThumb(
                coverUrl = detail.coverUrl,
                corner = AlbumCoverCorner,
                modifier = Modifier.size(220.dp),
            )
            Column(Modifier.weight(1f)) {
                AlbumTitle(detail.name, MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                AlbumMetaLine(metaLine)
                detail.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(12.dp))
                    AlbumDescription(desc, maxLines = 6)
                }
                Spacer(Modifier.height(16.dp))
                CpActionButtonGroup(actions, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            AlbumCoverThumb(
                coverUrl = detail.coverUrl,
                corner = AlbumCoverCorner,
                // ⚠️ `widthIn` 必须在 `fillMaxWidth` **之前**：顺序反了约束已被钉死，
                // `widthIn` 会变成空操作（见 AGENTS.md 的宽高约束约定）。
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.62f)
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(16.dp))
            AlbumTitle(detail.name, MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            AlbumMetaLine(metaLine)
            detail.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Spacer(Modifier.height(12.dp))
                AlbumDescription(desc, maxLines = 5)
            }
            Spacer(Modifier.height(16.dp))
            CpActionButtonGroup(actions, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * 专辑名。**主色 + Bold** —— 详情页的门面。
 *
 * 宽度（`headlineMedium` / `headlineSmall`）由调用方按宽窄屏选：同一个值在手机上
 * 会一行只放得下三四个字，专辑名普遍偏长，会立刻折成三行把封面挤下去。
 */
@Composable
private fun AlbumTitle(name: String, style: TextStyle) {
    Text(
        name,
        style = style,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 元信息行：歌手 · 发行年 · 发行方 · N 首 / 时长。全空时**不画**（否则留一条空行）。 */
@Composable
private fun AlbumMetaLine(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 专辑说明。**不套卡片**：说明本身是长文，再包一层容器只会多一道边框和一层内边距。 */
@Composable
private fun AlbumDescription(text: String, maxLines: Int) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 专辑封面圆角，取 M3 形状刻度的 `extraLarge`（28dp）—— 与卡片 / 弹层同一档。
 *
 * 不沿用列表缩略图的 12dp：那个值是按 60dp 的图定的，套到 220dp 的封面上会显得很方。
 */
private val AlbumCoverCorner = 28.dp
