package cp.player.app.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.util.UiEvents
import cp.player.core.BackendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PosterWallScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    initialSource: String = "queue",
    modifier: Modifier = Modifier
) {
    val s = cpStrings()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val playback by AppModel.playback.state.collectAsState()
    val recentTracks by AppModel.recentTracksFlow.collectAsState()

    var queueSongs by remember { mutableStateOf<List<PosterSong>>(emptyList()) }
    var librarySongs by remember { mutableStateOf<List<PosterSong>>(emptyList()) }
    var likedSongs by remember { mutableStateOf<List<PosterSong>>(emptyList()) }
    var localSongs by remember { mutableStateOf<List<PosterSong>>(emptyList()) }
    val recentSongs = remember(recentTracks) {
        recentTracks.map { tr ->
            PosterSong(
                id = tr.id,
                title = tr.name,
                artist = tr.artist,
                album = tr.album.orEmpty(),
                coverUrl = tr.coverUrl,
                durationMs = tr.durationMs,
                sourceKind = "recent"
            )
        }
    }

    // Observe queue
    LaunchedEffect(playback.queue) {
        queueSongs = playback.queue.mapIndexed { index, item ->
            PosterSong(
                id = item.mediaId,
                title = item.title,
                artist = item.artist,
                album = item.album.orEmpty(),
                coverUrl = item.coverUrl,
                durationMs = item.durationMs,
                sourceKind = "queue",
                rawId = "${item.mediaId}_$index"
            )
        }
    }

    // Load library, liked, and local songs
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            runCatching {
                val albumsRes = AppModel.musicRepository.getUserAlbums(limit = 100)
                if (albumsRes is BackendResult.Success) {
                    librarySongs = albumsRes.data.map { album ->
                        PosterSong(
                            id = album.id.toString(),
                            title = album.name,
                            artist = album.artistName.orEmpty(),
                            album = album.name,
                            coverUrl = album.coverUrl,
                            sourceKind = "album"
                        )
                    }
                }
            }
            runCatching {
                val playlistsRes = AppModel.musicRepository.getCurrentUserPlaylists()
                if (playlistsRes is BackendResult.Success) {
                    val liked = playlistsRes.data.firstOrNull {
                        it.name.contains("喜欢") || it.name.contains("Like", ignoreCase = true)
                    }
                    if (liked != null) {
                        val tracksRes = AppModel.musicRepository.getPlaylistTracks(liked.id, limit = 200)
                        if (tracksRes is BackendResult.Success) {
                            likedSongs = tracksRes.data.tracks.map { tr ->
                                PosterSong(
                                    id = tr.id,
                                    title = tr.name,
                                    artist = tr.artist,
                                    album = tr.album.orEmpty(),
                                    coverUrl = tr.coverUrl,
                                    durationMs = tr.durationMs,
                                    sourceKind = "liked"
                                )
                            }
                        }
                    }
                }
            }
            runCatching {
                val localItems = AppModel.localMedia.items().first()
                localSongs = localItems.filter { it.mediaType == cp.player.core.media.MediaType.AUDIO }.map { lm ->
                    PosterSong(
                        id = lm.path,
                        title = lm.title,
                        artist = lm.artist.orEmpty(),
                        album = lm.album.orEmpty(),
                        coverUrl = lm.coverUri,
                        sourceKind = "local"
                    )
                }
            }
        }
    }

    var infinite by rememberSaveable { mutableStateOf(false) }
    var stacked by rememberSaveable { mutableStateOf(false) }
    var stackLayoutSaved by rememberSaveable { mutableStateOf("") }
    var wallFullscreen by rememberSaveable { mutableStateOf(false) }
    var lyricTheater by rememberSaveable { mutableStateOf(false) }

    var source by rememberSaveable { mutableStateOf(initialSource) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var locateRequest by remember { mutableIntStateOf(0) }
    var shuffleSeed by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<ExpandedPoster?>(null) }
    var actionSong by remember { mutableStateOf<PosterSong?>(null) }
    var addToPlaylistSongId by remember { mutableStateOf<String?>(null) }

    val currentSongId = playback.currentTrack?.id
    val queueIndex = playback.currentIndex

    val sourceSongs = when (source) {
        "queue" -> queueSongs
        "recent" -> if (recentSongs.isNotEmpty()) recentSongs else queueSongs
        "liked" -> if (likedSongs.isNotEmpty()) likedSongs else queueSongs
        "local" -> if (localSongs.isNotEmpty()) localSongs else queueSongs
        else -> if (librarySongs.isNotEmpty()) librarySongs else queueSongs
    }

    val dataset by produceState<PosterWallDataset?>(null, sourceSongs, query, source, shuffleSeed) {
        value = withContext(Dispatchers.Default) {
            PosterWallDataset(
                source,
                query,
                rearrangePosterWallItems(buildPosterWallItems(sourceSongs, source, query), shuffleSeed),
                shuffleSeed
            )
        }
    }

    val ready = dataset?.let { it.source == source && it.query == query } == true
    val items = if (ready) dataset?.items.orEmpty() else emptyList()
    val stackLayout = remember(items, stackLayoutSaved) { PosterStackLayout.resolve(items, stackLayoutSaved) }
    val geometry = remember(items.size, infinite, stacked, stackLayout) {
        PosterWallGeometry(
            items.size,
            infinite = infinite,
            stacked = stacked,
            stackGroups = if (stacked) stackLayout.indices(items) else null,
            stackKeys = items.map { it.stackKey }
        )
    }
    val wall = rememberSaveable(source, query, stacked, saver = PosterWallState.Saver) { PosterWallState() }

    val currentIndex = remember(items, currentSongId, queueIndex, source) {
        if (source == "queue") {
            items.indexOfFirst { it.sourceIndex == queueIndex }
        } else {
            items.indexOfFirst { it.song.id == currentSongId }
        }
    }

    val stackKeys = if (stacked && expanded?.stackKey != null) {
        stackLayout.members(expanded!!.stackKey!!)
    } else emptyList()
    val stackSongs = remember(stackKeys, items) {
        val byKey = items.associateBy { it.stackKey }
        stackKeys.mapNotNull { byKey[it]?.song }
    }
    fun swipeStack(direction: Int) {
        val currentExpanded = expanded ?: return
        val currentKey = currentExpanded.stackKey ?: return
        val members = stackLayout.members(currentKey)
        if (members.size < 2) return
        val currentIdx = members.indexOf(currentKey)
        if (currentIdx < 0) return
        val nextIdx = (currentIdx + direction + members.size) % members.size
        val nextKey = members[nextIdx]
        val targetItem = items.firstOrNull { it.stackKey == nextKey } ?: return
        expanded = ExpandedPoster(
            song = targetItem.song,
            sourceIndex = targetItem.sourceIndex,
            origin = currentExpanded.origin,
            originCornerRadius = currentExpanded.originCornerRadius,
            stackKey = nextKey,
            originRotation = geometry.rotation(targetItem.sourceIndex)
        )
    }

    PosterWallPositioning(wall, items, geometry, currentIndex, locateRequest)
    DisposableEffect(wall) { onDispose { wall.stop() } }

    fun playPoster(poster: ExpandedPoster) {
        scope.launch {
            if (source == "queue") {
                val idx = poster.sourceIndex
                if (idx in queueSongs.indices) {
                    AppModel.playback.playQueue(queueSongs.map { it.id }, startIndex = idx)
                }
            } else if (source == "local") {
                val list = items.map { "local://audio/${it.song.id}" }
                val idx = items.indexOfFirst { it.song.id == poster.song.id }.coerceAtLeast(0)
                AppModel.playback.playQueue(list, startIndex = idx)
            } else if (poster.song.sourceKind == "album") {
                val albumId = poster.song.id.toLongOrNull()
                if (albumId != null) {
                    val detail = runCatching { AppModel.musicRepository.getAlbumDetail(albumId) }.getOrNull()
                    val provider = AppModel.activeProviderId()
                    val ids = (detail as? BackendResult.Success)?.data?.tracks?.map {
                        if (it.id.contains("://")) it.id else "$provider://song/${it.id}"
                    }.orEmpty()
                    if (ids.isNotEmpty()) {
                        AppModel.playback.playQueue(ids, startIndex = 0, sourceId = albumId.toString())
                    }
                }
            } else {
                val provider = AppModel.activeProviderId()
                val songIds = items.map {
                    val id = it.song.id
                    if (id.contains("://")) id else "$provider://song/$id"
                }
                val idx = items.indexOfFirst { it.song.id == poster.song.id }.coerceAtLeast(0)
                AppModel.playback.playQueue(songIds, startIndex = idx)
            }
        }
    }

    val density = LocalDensity.current.density
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(PosterInk)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                    if (lyricTheater) {
                        lyricTheater = false
                        true
                    } else if (expanded != null) {
                        expanded = null
                        true
                    } else if (wallFullscreen) {
                        wallFullscreen = false
                        true
                    } else if (search || query.isNotBlank()) {
                        search = false
                        query = ""
                        focusManager.clearFocus()
                        true
                    } else {
                        onBack()
                        true
                    }
                } else false
            }
    ) {
        val focusViewport = PosterRect(0f, 0f, maxWidth.value, maxHeight.value)

        Column(Modifier.fillMaxSize()) {
            if (!wallFullscreen) {
                // Top header bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PosterIconButton(s.common.back, onBack) {
                        Icon(
                            Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = s.common.back,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            s.wall.screenTitle,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            s.wall.posterWallCount(items.size),
                            color = PosterMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    PosterIconButton(s.wall.posterWallRefresh, {
                        wall.stop()
                        expanded = null
                        focusManager.clearFocus()
                        val seed = shuffleSeed
                        scope.launch {
                            val next = withContext(Dispatchers.Default) { nextPosterWallShuffleSeed(items, seed) }
                            if (shuffleSeed == seed) shuffleSeed = next
                        }
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = s.wall.posterWallRefresh,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    PosterIconButton(s.wall.posterWallFullscreen, {
                        focusManager.clearFocus()
                        search = false
                        expanded = null
                        wallFullscreen = true
                    }) {
                        Icon(
                            PosterFullscreenIcon,
                            contentDescription = s.wall.posterWallFullscreen,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    PosterIconButton(s.common.search, {
                        search = !search
                        if (!search) {
                            query = ""
                            focusManager.clearFocus()
                        }
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = s.common.search,
                            tint = if (search) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    PosterIconButton(s.wall.posterWallLocate, {
                        focusManager.clearFocus()
                        query = ""
                        locateRequest++
                    }) {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = s.wall.posterWallLocate,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Sources row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sources = listOf(
                        "queue" to s.wall.posterWallQueue,
                        "recent" to s.library.recentPlays,
                        "library" to s.wall.posterWallLibrary,
                        "liked" to s.wall.posterWallLiked,
                        "local" to s.wall.posterWallLocal
                    )
                    sources.forEach { (id, label) ->
                        val selected = source == id
                        Text(
                            text = label,
                            color = if (selected) Color.White else PosterMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) PosterPanel else Color.Transparent)
                                .border(0.5.dp, if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
                                .clickable {
                                    source = id
                                    expanded = null
                                    query = ""
                                    focusManager.clearFocus()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                if (search) {
                    PosterWallSearch(
                        query = query,
                        onQueryChange = { query = it },
                        onDismiss = {
                            search = false
                            query = ""
                            focusManager.clearFocus()
                        }
                    )
                }
            }

            // Canvas Box
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .onGloballyPositioned { canvasOrigin = it.positionInParent() / density }
                    .background(
                        Brush.radialGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), Color.Transparent),
                            radius = 1200f
                        )
                    )
            ) {
                if (items.isNotEmpty()) {
                    PosterWallCanvas(
                        items = items,
                        geometry = geometry,
                        state = wall,
                        currentIndex = currentIndex,
                        artwork = { song, mod, size -> PosterArtwork(song, mod, sizePx = size) },
                        scope = scope,
                        onExpand = { index, rect ->
                            focusManager.clearFocus()
                            val item = items[index]
                            expanded = ExpandedPoster(
                                item.song,
                                item.sourceIndex,
                                rect.copy(x = rect.x + canvasOrigin.x, y = rect.y + canvasOrigin.y),
                                13f * wall.camera.scale,
                                stackKey = if (stacked) item.stackKey else null,
                                originRotation = geometry.rotation(index)
                            )
                        },
                        onMore = { actionSong = it },
                        remoteEnabled = expanded == null && !lyricTheater && actionSong == null,
                        fullscreen = wallFullscreen,
                        searching = search,
                        onMoveStack = { fromIndex, targetIndex, before ->
                            val from = items.getOrNull(fromIndex)?.stackKey ?: return@PosterWallCanvas
                            val target = items.getOrNull(targetIndex)?.stackKey ?: return@PosterWallCanvas
                            val next = stackLayout.move(from, target, before) ?: return@PosterWallCanvas
                            stackLayoutSaved = next.encode()
                        }
                    )

                    if (!wallFullscreen) {
                        Column(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(PosterPanel.copy(alpha = 0.92f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        s.wall.posterWallStacked,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Switch(
                                        checked = stacked,
                                        onCheckedChange = {
                                            expanded = null
                                            stacked = it
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                Row(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(PosterPanel.copy(alpha = 0.92f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        s.wall.posterWallInfinite,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Switch(
                                        checked = infinite,
                                        onCheckedChange = { infinite = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                Row(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(PosterPanel.copy(alpha = 0.92f))
                                        .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    fun zoom(factor: Float) {
                                        wall.stop()
                                        wall.transform(Offset.Zero, factor, wall.viewport / 2f, geometry)
                                    }
                                    PosterIconButton(s.wall.posterWallZoomOut, { zoom(0.85f) }) {
                                        Text("−", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                    val percent by remember(wall) {
                                        derivedStateOf { (wall.camera.scale * 100).roundToInt() }
                                    }
                                    Text(
                                        "$percent%",
                                        color = PosterMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(42.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    PosterIconButton(s.wall.posterWallZoomIn, { zoom(1.18f) }) {
                                        Text("+", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(
                                if (stacked) s.wall.posterWallStackGestureHint else s.wall.posterWallGestureHint,
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clip(CircleShape)
                                    .background(PosterInk.copy(alpha = 0.65f))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            PosterWallIcon,
                            contentDescription = null,
                            tint = PosterMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(54.dp)
                        )
                        Text(
                            text = if (!ready) s.wall.posterWallLoading
                            else if (query.isNotBlank()) s.wall.posterWallNoResults
                            else if (source == "queue") s.wall.posterWallEmptyQueue
                            else s.wall.posterWallEmptyLibrary,
                            color = PosterMuted,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 18.dp)
                        )
                        if (ready && source == "queue" && query.isBlank()) {
                            Text(
                                s.wall.posterWallBrowseLibrary,
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .padding(top = 18.dp)
                                    .clip(CircleShape)
                                    .background(PosterPanel)
                                    .clickable { source = "library" }
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Expanded modal card
        if (!lyricTheater) {
            expanded?.let { poster ->
                val isCurrent = poster.song.id == currentSongId && (source != "queue" || poster.sourceIndex == queueIndex)
                PosterExpandedCard(
                    poster = poster,
                    viewport = focusViewport,
                    current = isCurrent,
                    artwork = { song, mod -> PosterArtwork(song, mod, large = true) },
                    lyrics = { mod -> PosterPlayerLyrics(playback, onOpenPlayer, mod) },
                    seekBar = { compact -> PosterPlayerProgress(playback, compact, { AppModel.playback.seekTo(it) }) },
                    controls = {
                        PosterPlayControls(
                            playing = playback.isPlaying,
                            onPrevious = { AppModel.playback.skipPrevious() },
                            onToggle = { AppModel.playback.togglePlayPause() },
                            onNext = { AppModel.playback.skipNext() },
                            showSkipButtons = true
                        )
                    },
                    onPlay = { playPoster(poster) },
                    onMore = {
                        actionSong = poster.song
                        expanded = null
                    },
                    onClosed = { expanded = null },
                    onFullscreen = if (isCurrent) ({ lyricTheater = true }) else null,
                    onStackSwipe = if (stacked && poster.stackKey != null) ({ direction -> swipeStack(direction) }) else null,
                    stackPosition = poster.stackKey?.let { key -> "${stackKeys.indexOf(key) + 1} / ${stackKeys.size}" },
                    stackSongs = stackSongs,
                    stackIndex = stackKeys.indexOf(poster.stackKey).coerceAtLeast(0)
                )
            }
        }

        // Fullscreen Lyric Theater Screen
        if (lyricTheater) {
            PosterLyricTheaterScreen(
                playback = playback,
                onBack = { lyricTheater = false },
                onPlayPause = { AppModel.playback.togglePlayPause() },
                onSkipPrev = { AppModel.playback.skipPrevious() },
                onSkipNext = { AppModel.playback.skipNext() },
                onSeek = { AppModel.playback.seekTo(it) }
            )
        }

        // More options sheet & Add to playlist sheet
        actionSong?.let { song ->
            val likedIds by AppModel.playback.likedIds.collectAsState()
            val provider = AppModel.activeProviderId()
            val songTrackUri = if (song.sourceKind == "local") {
                "local://audio/${song.id}"
            } else if (song.id.contains("://")) {
                song.id
            } else {
                "$provider://song/${song.id}"
            }
            SongOptionsSheet(
                songName = song.title,
                artistName = song.artist,
                coverUrl = song.coverUrl,
                isFavorite = song.id in likedIds,
                isDownloaded = AppModel.isDownloaded(song.id),
                onDismiss = { actionSong = null },
                onPlay = {
                    playPoster(
                        ExpandedPoster(
                            song = song,
                            sourceIndex = items.indexOfFirst { it.song.id == song.id }.coerceAtLeast(0),
                            origin = PosterRect(0f, 0f, 240f, 240f)
                        )
                    )
                },
                onToggleFavorite = {
                    scope.launch {
                        val target = song.id !in likedIds
                        AppModel.playback.toggleFavoriteFor(songTrackUri)
                        UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                    }
                },
                onAddToQueue = {
                    scope.launch {
                        AppModel.playback.addToQueue(songTrackUri)
                        UiEvents.notify("已加入播放队列")
                    }
                },
                onPlayNext = {
                    scope.launch {
                        AppModel.playback.addNextToQueue(songTrackUri)
                        UiEvents.notify("将在下一首播放")
                    }
                },
                onAddToPlaylist = {
                    addToPlaylistSongId = song.id
                },
                onDownload = {
                    val summary = cp.player.core.music.TrackSummary(
                        id = song.id,
                        name = song.title,
                        artist = song.artist,
                        album = song.album,
                        coverUrl = song.coverUrl,
                        durationMs = song.durationMs
                    )
                    AppModel.downloadTrack(summary)
                }
            )
        }

        addToPlaylistSongId?.let { trackId ->
            AddToPlaylistSheet(
                trackId = trackId,
                onDismiss = { addToPlaylistSongId = null }
            )
        }
    }
}
