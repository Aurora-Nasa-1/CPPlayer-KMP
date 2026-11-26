package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.util.UiEvents
import cp.player.app.i18n.cpStrings
import cp.player.core.BackendResult
import cp.player.core.music.CPMediaId
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

class PersonalFmScreen : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val scope = rememberCoroutineScope()
        val strings = cpStrings()
        var mode by remember { mutableStateOf("DEFAULT") }
        var tracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var refreshCount by remember { mutableStateOf(0) }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        var optionsTrack by remember { mutableStateOf<TrackSummary?>(null) }
        var playlistTrack by remember { mutableStateOf<TrackSummary?>(null) }
        var infoTrack by remember { mutableStateOf<TrackSummary?>(null) }

        LaunchedEffect(mode, refreshCount) {
            loading = true
            error = null
            when (val result = AppModel.musicRepository.getPersonalFmMode(mode)) {
                is BackendResult.Success -> tracks = result.data.distinctBy { it.id }
                is BackendResult.Error -> {
                    error = result.message
                    if (tracks.isNotEmpty()) UiEvents.notify(result.message)
                }
                is BackendResult.Unsupported -> {
                    error = result.message
                    if (tracks.isNotEmpty()) UiEvents.notify(result.message)
                }
            }
            loading = false
        }

        fun playFrom(track: TrackSummary? = null) {
            val batch = tracks
            if (batch.isNotEmpty()) {
                val start = track?.let(batch::indexOf)?.takeIf { it >= 0 } ?: 0
                scope.launch {
                    AppModel.playback.playQueue(
                        batch.map { AppModel.toMediaId(it.id) },
                        startIndex = start,
                        sourceId = AppModel.personalFmSource(mode, ""),
                    )
                }
            }
        }

        fun songActions(track: TrackSummary): SongMenuActions {
            val mediaId = AppModel.toMediaId(track.id)
            return SongMenuActions(
                onPlay = { playFrom(track) },
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
                            tracks = tracks.filterNot { it.id == track.id }
                            val state = AppModel.playback.state.value
                            if (state.sourceId?.startsWith("personal_fm:") == true) {
                                val index = state.queue.indexOfFirst { it.mediaId == mediaId }
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
                    onClick = { if (!loading) refreshCount++ },
                ),
            ),
        ) { pageModifier ->
            Box(pageModifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyScrollColumn(
                    modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = CpSpacing.pageHorizontal,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "DEFAULT" to strings.home.fmRecommended,
                                "EXPLORE" to strings.home.fmExplore,
                                "FAMILIAR" to strings.home.fmFamiliar,
                                "PUZZLE_MODE_RCMD" to strings.home.fmPuzzle,
                            ).forEach { (value, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = mode == value,
                                    onClick = {
                                        if (mode != value) {
                                            tracks = emptyList()
                                            loading = true
                                            mode = value
                                        }
                                    },
                                    label = {
                                        Text(label)
                                    },
                                )
                            }
                        }
                    }
                    if (loading && tracks.isEmpty()) {
                        item {
                            StateSurface(Modifier.fillMaxWidth()) {
                                ContentState(title = strings.library.syncingLibrary)
                            }
                        }
                    } else if (error != null && tracks.isEmpty()) {
                        item {
                            StateSurface(Modifier.fillMaxWidth()) {
                                ContentState(title = strings.library.personalFmUnavailable, message = error)
                            }
                        }
                    } else if (tracks.isEmpty()) {
                        item {
                            StateSurface(Modifier.fillMaxWidth()) {
                                ContentState(title = strings.library.personalFmUnavailable)
                            }
                        }
                    } else {
                        items(tracks, key = { it.id }) { track ->
                            SongItem(
                                track = track,
                                onClick = { playFrom(track) },
                                onOptionsClick = { optionsTrack = track },
                                contextMenu = songContextMenuItems(songActions(track)),
                                modifier = Modifier.fillMaxWidth(),
                            )
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
