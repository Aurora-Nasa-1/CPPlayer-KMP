package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.shareText
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSongRowSkeleton
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpViewSwitcher
import cp.player.app.ui.component.CpViewSwitcherItem
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.ui.theme.cpCardSurface
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import cp.player.core.playback.QueueItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class FmModeTab(
    val mode: String,
    val label: String,
    val icon: ImageVector,
)

private fun isMatchingFmSource(sourceId: String?, mode: String): Boolean {
    if (sourceId == null) return false
    if (sourceId == "personal_fm") return mode == "DEFAULT"
    if (!sourceId.startsWith("personal_fm:")) return false
    return sourceId.split(':').getOrElse(1) { "DEFAULT" } == mode
}

private fun QueueItem.toTrackSummaryOrNull(): TrackSummary? {
    if (title.isBlank() || (title == "加载中…" && artist.isBlank() && durationMs <= 0L)) {
        return null
    }
    return TrackSummary(
        id = mediaId,
        name = title,
        artist = artist,
        album = album,
        coverUrl = coverUrl,
        durationMs = durationMs,
    )
}

class PersonalFmScreen : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val scope = rememberCoroutineScope()
        val strings = cpStrings()
        var mode by remember { mutableStateOf("DEFAULT") }
        var fetchedTracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var refreshCount by remember { mutableStateOf(0) }
        var preferLiveQueue by remember(mode) { mutableStateOf(true) }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        val fmPlaybackSnapshot by remember {
            AppModel.playback.state
                .map { it.sourceId to it.queue }
                .distinctUntilChanged()
        }.collectAsState(initial = null to emptyList())
        val activeSourceId = fmPlaybackSnapshot.first
        val activeQueue = fmPlaybackSnapshot.second

        var optionsTrack by remember { mutableStateOf<TrackSummary?>(null) }
        var playlistTrack by remember { mutableStateOf<TrackSummary?>(null) }
        var infoTrack by remember { mutableStateOf<TrackSummary?>(null) }

        val modes = remember(strings) {
            listOf(
                FmModeTab("DEFAULT", strings.home.fmRecommended, Icons.Filled.AutoAwesome),
                FmModeTab("EXPLORE", strings.home.fmExplore, Icons.Filled.Explore),
                FmModeTab("FAMILIAR", strings.home.fmFamiliar, Icons.Filled.Headphones),
                FmModeTab("PUZZLE_MODE_RCMD", strings.home.fmPuzzle, Icons.Filled.Extension),
            )
        }

        LaunchedEffect(activeSourceId) {
            if (activeSourceId != null) {
                preferLiveQueue = true
            }
        }

        LaunchedEffect(mode, refreshCount) {
            loading = true
            error = null
            when (val result = AppModel.musicRepository.getPersonalFmMode(mode)) {
                is BackendResult.Success -> fetchedTracks = result.data.distinctBy { it.id }
                is BackendResult.Error -> {
                    error = result.message
                    if (fetchedTracks.isNotEmpty()) UiEvents.notify(result.message)
                }
                is BackendResult.Unsupported -> {
                    error = result.message
                    if (fetchedTracks.isNotEmpty()) UiEvents.notify(result.message)
                }
            }
            loading = false
        }

        val isLiveSyncing = preferLiveQueue && isMatchingFmSource(activeSourceId, mode) && activeQueue.isNotEmpty()
        val loadedByRawId = remember(fetchedTracks) {
            fetchedTracks.associateBy { CPMediaId.toRawResourceId(it.id) }
        }
        val displayTracks = remember(fetchedTracks, loadedByRawId, activeQueue, isLiveSyncing) {
            if (!isLiveSyncing) {
                fetchedTracks
            } else {
                activeQueue.mapNotNull { item ->
                    val rawId = CPMediaId.toRawResourceId(item.mediaId)
                    loadedByRawId[rawId] ?: item.toTrackSummaryOrNull()
                }.distinctBy { CPMediaId.toRawResourceId(it.id) }
            }
        }

        fun playFrom(track: TrackSummary? = null) {
            val batch = displayTracks
            if (batch.isEmpty()) return
            val targetRawId = track?.let { CPMediaId.toRawResourceId(it.id) }
            if (isLiveSyncing) {
                val queueIdx = if (targetRawId != null) {
                    activeQueue.indexOfFirst { CPMediaId.toRawResourceId(it.mediaId) == targetRawId }
                } else {
                    0
                }
                if (queueIdx >= 0) {
                    scope.launch { AppModel.playback.playAt(queueIdx) }
                    return
                }
            }
            val start = track?.let(batch::indexOf)?.takeIf { it >= 0 } ?: 0
            preferLiveQueue = true
            scope.launch {
                AppModel.playback.playQueue(
                    batch.map { AppModel.toMediaId(it.id) },
                    startIndex = start,
                    sourceId = AppModel.personalFmSource(mode, ""),
                )
            }
        }

        fun songActions(track: TrackSummary): SongMenuActions {
            val mediaId = AppModel.toMediaId(track.id)
            return SongMenuActions(
                onPlay = { playFrom(track) },
                onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                    { navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId", title = "${track.name} - MV")) }
                },
                isFavorite = CPMediaId.parse(mediaId).resourceId in likedIds,
                onToggleFavorite = { scope.launch { AppModel.playback.toggleFavoriteFor(mediaId) } },
                onPlayNext = { scope.launch { AppModel.playback.addNextToQueue(mediaId) } },
                onAddToQueue = { scope.launch { AppModel.playback.addToQueue(mediaId) } },
                onDownload = { AppModel.downloadTrack(track) },
                isDownloaded = AppModel.isDownloaded(mediaId),
                onAddToPlaylist = { playlistTrack = track },
                onShare = { shareText(songShareText(track.copy(id = CPMediaId.parse(mediaId).resourceId))) },
                onShowInfo = { infoTrack = track },
                onDislike = {
                    scope.launch {
                        if (AppModel.musicRepository.dislikeSong(mediaId)) {
                            fetchedTracks = fetchedTracks.filterNot {
                                CPMediaId.toRawResourceId(it.id) == CPMediaId.toRawResourceId(track.id)
                            }
                            val state = AppModel.playback.state.value
                            if (isMatchingFmSource(state.sourceId, mode)) {
                                val index = state.queue.indexOfFirst {
                                    CPMediaId.toRawResourceId(it.mediaId) == CPMediaId.toRawResourceId(mediaId)
                                }
                                if (index >= 0) AppModel.playback.removeQueueItem(index)
                            }
                        } else {
                            UiEvents.notify(strings.library.operationFailed)
                        }
                    }
                },
            )
        }

        CpRouteScaffold(
            title = strings.home.personalFm,
            onBack = { navigator.pop() },
            topBarActions = listOf(
                TopBarAction(
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = strings.library.playAll) },
                    onClick = { playFrom() },
                ),
                TopBarAction(
                    icon = { Icon(Icons.Default.Refresh, contentDescription = strings.common.refresh) },
                    onClick = {
                        if (!loading) {
                            preferLiveQueue = false
                            refreshCount++
                        }
                    },
                ),
            ),
        ) { pageModifier ->
            Box(pageModifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CpSpacing.pageHorizontal, vertical = 8.dp),
                    ) {
                        val selectedIndex = modes.indexOfFirst { it.mode == mode }.coerceAtLeast(0)
                        CpViewSwitcher(
                            items = modes.map { CpViewSwitcherItem(it.label, it.icon) },
                            selectedIndex = selectedIndex,
                            onSelect = { idx ->
                                val nextMode = modes[idx].mode
                                if (mode != nextMode) {
                                    fetchedTracks = emptyList()
                                    error = null
                                    loading = true
                                    mode = nextMode
                                }
                            },
                            compact = maxWidth < 600.dp,
                        )
                    }

                    LazyScrollColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(
                            start = CpSpacing.pageHorizontal,
                            end = CpSpacing.pageHorizontal,
                            top = 4.dp,
                            bottom = CpSpacing.formBottomInset,
                        ),
                        verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                    ) {
                        item(key = "__fm_guide__") {
                            FmGuideCard(
                                title = strings.home.fmGuideTitle,
                                body = strings.home.fmGuideBody,
                                statusLabel = when {
                                    isLiveSyncing -> strings.home.fmLiveStatus(displayTracks.size)
                                    displayTracks.isNotEmpty() -> strings.home.fmReadyStatus(displayTracks.size)
                                    else -> null
                                },
                                live = isLiveSyncing,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        if (loading && displayTracks.isEmpty()) {
                            item(key = "__fm_loading__") {
                                CpSongRowSkeleton(rows = 6)
                            }
                        } else if (error != null && displayTracks.isEmpty()) {
                            item(key = "__fm_error__") {
                                StateSurface(Modifier.fillMaxWidth()) {
                                    ContentState(
                                        title = strings.library.personalFmUnavailable,
                                        message = error,
                                        error = true,
                                        actionLabel = strings.library.retry,
                                        onAction = {
                                            if (!loading) {
                                                preferLiveQueue = false
                                                refreshCount++
                                            }
                                        },
                                    )
                                }
                            }
                        } else if (displayTracks.isEmpty()) {
                            item(key = "__fm_empty__") {
                                StateSurface(Modifier.fillMaxWidth()) {
                                    ContentState(
                                        title = strings.library.personalFmUnavailable,
                                        actionLabel = strings.library.retry,
                                        onAction = {
                                            if (!loading) {
                                                preferLiveQueue = false
                                                refreshCount++
                                            }
                                        },
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = displayTracks,
                                key = { _, track -> CPMediaId.toRawResourceId(track.id) },
                            ) { index, track ->
                                SongItem(
                                    track = track,
                                    index = index,
                                    total = displayTracks.size,
                                    onClick = {
                                        CoverFlight.play(track.id, track.coverUrl)
                                        playFrom(track)
                                    },
                                    onOptionsClick = { optionsTrack = track },
                                    contextMenu = songContextMenuItems(songActions(track)),
                                    modifier = Modifier.fillMaxWidth().animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }

        optionsTrack?.let { track ->
            val actions = songActions(track)
            SongOptionsSheet(
                songName = track.name,
                artistName = track.artist,
                coverUrl = track.coverUrl,
                isFavorite = actions.isFavorite,
                isDownloaded = actions.isDownloaded,
                onDismiss = { optionsTrack = null },
                onPlay = requireNotNull(actions.onPlay),
                onToggleFavorite = requireNotNull(actions.onToggleFavorite),
                onAddToQueue = requireNotNull(actions.onAddToQueue),
                onPlayNext = requireNotNull(actions.onPlayNext),
                onAddToPlaylist = requireNotNull(actions.onAddToPlaylist),
                onDownload = requireNotNull(actions.onDownload),
                onShowInfo = requireNotNull(actions.onShowInfo),
                onShare = requireNotNull(actions.onShare),
                onDislike = actions.onDislike,
            )
        }
        playlistTrack?.let { track ->
            AddToPlaylistSheet(trackId = track.id, onDismiss = { playlistTrack = null })
        }
        infoTrack?.let { track ->
            SongDetailInfoDialog(track = track, onDismiss = { infoTrack = null })
        }
    }
}

@Composable
private fun FmGuideCard(
    title: String,
    body: String,
    statusLabel: String?,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = cpCardSurface(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (live) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Radio,
                    contentDescription = null,
                    tint = if (live) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CpText(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (!statusLabel.isNullOrBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = if (live) MaterialTheme.colorScheme.primaryContainer
                            else cpNestedSurface(),
                        ) {
                            CpText(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (live) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                CpText(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }
    }
}
