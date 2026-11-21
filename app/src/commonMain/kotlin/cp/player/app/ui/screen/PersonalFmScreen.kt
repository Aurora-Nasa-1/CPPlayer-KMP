package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cp.player.app.AppModel
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.i18n.cpStrings
import cp.player.core.BackendResult
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

class PersonalFmScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val scope = rememberCoroutineScope()
        val strings = cpStrings()
        var mode by remember { mutableStateOf("DEFAULT") }
        var tracks by remember { mutableStateOf<List<TrackSummary>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        suspend fun load(requestMode: String = mode) {
            loading = true
            error = null
            when (val result = AppModel.musicRepository.getPersonalFmMode(requestMode, limit = 12)) {
                is BackendResult.Success -> tracks = result.data
                is BackendResult.Error -> error = result.message
                is BackendResult.Unsupported -> error = result.message
            }
            loading = false
        }

        LaunchedEffect(mode) { load(mode) }
        val playAll = {
            if (tracks.isNotEmpty()) {
                scope.launch {
                    AppModel.playback.playQueue(tracks.map { AppModel.toMediaId(it.id) })
                }
            }
        }

        CpRouteScaffold(
            title = strings.home.personalFm,
            onBack = { navigator.pop() },
            topBarActions = listOf(
                TopBarAction(
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = strings.library.playAll) },
                    onClick = playAll,
                ),
                TopBarAction(
                    icon = { Icon(Icons.Default.Refresh, contentDescription = strings.common.refresh) },
                    onClick = { scope.launch { load() } },
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
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("DEFAULT", "EXPLORE").forEach { value ->
                                androidx.compose.material3.FilterChip(
                                    selected = mode == value,
                                    onClick = { mode = value },
                                    label = {
                                        Text(if (value == "DEFAULT") "推荐" else "探索")
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
                                onClick = {
                                    scope.launch {
                                        AppModel.playback.playQueue(
                                            tracks.map { AppModel.toMediaId(it.id) },
                                            tracks.indexOf(track).coerceAtLeast(0),
                                        )
                                    }
                                },
                                onOptionsClick = {
                                    scope.launch {
                                        AppModel.musicRepository.dislikeSong(track.id)
                                        tracks = tracks.filterNot { it.id == track.id }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
