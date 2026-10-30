package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.shareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpCoverPlaceholder
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSearchField
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.PlaylistOptionsSheet
import cp.player.app.ui.component.RenamePlaylistDialog
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.playlistShareText
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.model.DownloadsScreenModel
import cp.player.app.ui.model.LibraryScreenModel
import cp.player.app.ui.model.LibrarySearchScreenModel
import cp.player.app.ui.model.LibraryTrackHit
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.util.resized
import cp.player.core.media.LocalMediaItem
import cp.player.core.music.PlaylistSummary
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.launch

/**
 * 曲库检索页：在「我的」范围内找东西（歌单、歌单内歌曲 + 本地音乐）。
 *
 * 与 [SearchScreen] 的分工：那一页搜**在线曲库**（歌曲 / 专辑 / 歌手 / 歌单），
 * 这一页只搜**已经属于我的东西** —— 我的歌单、歌单里的歌曲、已下载 / 导入的本地歌曲。
 * 两者刻意分开：把本地条目混进在线搜索结果会让「搜索结果为空」变得不可信，
 * 而用户找自己歌单时又不想先等一次网络请求。
 *
 * 底部留一个「在在线音乐中搜索」的跳板：本地找不到时一键转在线搜索。
 */
class LibrarySearchScreen(private val initialQuery: String = "") : Screen {
    /**
     * 每个实例一份模型（Voyager 默认按 Screen 类名键控）。
     *
     * 与 [SearchScreen] 同理：不带 tag 时两次进入会共用同一份模型，
     * [initialQuery] 在第二次被静默丢弃。⚠️ 因此本类不能改成 `data class`。
     */
    private val modelTag = "library-search-${hashCode()}"

    @Composable
    override fun Content() {
        val library = rememberScreenModel(tag = "$modelTag-library") { LibraryScreenModel() }
        val downloads = rememberScreenModel(tag = "$modelTag-downloads") { DownloadsScreenModel() }
        val search = rememberScreenModel(tag = "$modelTag-tracks") { LibrarySearchScreenModel() }
        LibrarySearchContent(library, downloads, search, initialQuery)
    }
}

@Composable
private fun LibrarySearchContent(
    library: LibraryScreenModel,
    downloads: DownloadsScreenModel,
    search: LibrarySearchScreenModel,
    initialQuery: String,
) {
    val s = cpStrings()
    val navigator = LocalNavigator.currentOrThrow
    val libraryState by library.state.collectAsState()
    val downloadsState by downloads.state.collectAsState()
    val searchState by search.state.collectAsState()
    var query by remember { mutableStateOf(initialQuery) }
    var selectedPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val confirm = rememberConfirmState()

    LaunchedEffect(Unit) {
        // 桌面端从「曲库」标题行的放大镜进来时直接可打字；安卓同样受益。
        runCatching { focusRequester.requestFocus() }
    }

    val keyword = query.trim()
    LaunchedEffect(keyword, libraryState.playlists) {
        search.search(keyword, libraryState.playlists)
    }
    val playlists = remember(libraryState.playlists, keyword) {
        if (keyword.isEmpty()) emptyList()
        else libraryState.playlists.filter { playlist ->
            playlist.name.contains(keyword, ignoreCase = true) ||
                playlist.creatorName?.contains(keyword, ignoreCase = true) == true
        }
    }
    val localTracks = remember(downloadsState.localAudioItems, keyword) {
        if (keyword.isEmpty()) emptyList()
        else downloadsState.localAudioItems.filter { item ->
            item.title.contains(keyword, ignoreCase = true) ||
                item.artist?.contains(keyword, ignoreCase = true) == true ||
                item.album?.contains(keyword, ignoreCase = true) == true
        }.take(MAX_LOCAL_RESULTS)
    }

    CpRouteScaffold(
        title = s.library.searchLibrary,
        onBack = { navigator.popOrNotify() },
    ) { modifier ->
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CpSearchField(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { focusManager.clearFocus() },
                modifier = Modifier
                    .widthIn(max = CpSpacing.pageMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = CpSpacing.pageHorizontal, vertical = 12.dp),
                placeholder = s.library.searchLibraryHint,
                focusRequester = focusRequester,
            )

            LazyScrollColumn(
                modifier = Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    keyword.isEmpty() -> item {
                        StateSurface {
                            ContentState(
                                title = s.library.searchLibraryEmpty,
                                message = s.library.searchLibraryEmptyNote,
                            )
                        }
                    }
                    playlists.isEmpty() && localTracks.isEmpty() && searchState.trackHits.isEmpty() &&
                        !searchState.loadingPlaylistTracks -> item {
                        StateSurface {
                            ContentState(
                                title = s.library.noSearchResults,
                                actionLabel = s.library.searchOnlineHint(keyword),
                                onAction = { navigator.push(SearchScreen(keyword)) },
                            )
                        }
                    }
                    else -> {
                        if (playlists.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = s.library.searchPlaylistSection,
                                    supportingText = "${playlists.size}",
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            items(playlists, key = { "p-${it.id}" }) { playlist ->
                                PlaylistItem(
                                    playlist = playlist,
                                    isOwner = library.isOwner(playlist),
                                    onClick = { navigator.push(PlaylistDetailScreen(playlist)) },
                                    onOptionsClick = { selectedPlaylist = playlist },
                                )
                            }
                        }
                        if (searchState.trackHits.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = s.library.searchPlaylistTracksSection,
                                    supportingText = "${searchState.trackHits.size}",
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            itemsIndexed(
                                searchState.trackHits,
                                key = { _, hit -> "pt-${hit.playlist.id}-${hit.track.id}" },
                            ) { index, hit ->
                                PlaylistTrackRow(
                                    hit = hit,
                                    index = index,
                                    total = searchState.trackHits.size,
                                    allTracks = searchState.trackHits.map { it.track },
                                )
                            }
                        }
                        if (searchState.loadingPlaylistTracks && searchState.trackHits.isEmpty() &&
                            playlists.isNotEmpty()
                        ) {
                            item {
                                StateSurface {
                                    ContentState(
                                        title = s.library.searchPlaylistTracksLoading,
                                        message = null,
                                    )
                                }
                            }
                        }
                        if (localTracks.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = s.library.searchLocalSection,
                                    supportingText = "${localTracks.size}",
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            itemsIndexed(localTracks, key = { _, item -> "l-${item.path}" }) { index, item ->
                                LocalTrackRow(
                                    item = item,
                                    index = index,
                                    total = localTracks.size,
                                    onClick = { downloads.playAll(localTracks, index) },
                                )
                            }
                        }
                        item {
                            TextButton(
                                onClick = { navigator.push(SearchScreen(keyword)) },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                CpText(
                                    s.library.searchOnlineHint(keyword),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 搜索结果里的「更多」：与曲库列表共用同一套歌单动作（播放 / 队列 / 分享 /
    // 重命名 / 删除或取消收藏），不在这里另写一份。
    selectedPlaylist?.let { playlist ->
        val owner = library.isOwner(playlist)
        val requestDelete: () -> Unit = {
            confirm.request(
                title = if (owner) s.library.deletePlaylist else s.library.unfavoritePlaylist,
                message = if (owner) {
                    s.library.deletePlaylistMessage(playlist.name)
                } else {
                    s.library.unfavoritePlaylistMessage(playlist.name)
                },
                confirmLabel = if (owner) s.common.confirm else s.library.unfavoritePlaylist,
                destructive = owner,
                onConfirm = { library.deleteOrUnsubscribe(playlist) },
            )
        }
        PlaylistOptionsSheet(
            playlistName = playlist.name,
            isOwner = owner,
            onDismiss = { selectedPlaylist = null },
            onPlay = { library.play(playlist) },
            onAddToQueue = { library.play(playlist, addOnly = true) },
            onShare = { shareText(playlistShareText(playlist.id, playlist.name)) },
            onDelete = { selectedPlaylist = null; requestDelete() },
            onRename = if (owner) {
                { selectedPlaylist = null; renameTarget = playlist }
            } else {
                null
            },
            coverUrl = playlist.coverUrl,
            isFavorite = true,
            onToggleFavorite = { selectedPlaylist = null; requestDelete() },
        )
    }

    renameTarget?.let { target ->
        RenamePlaylistDialog(
            playlistId = target.id,
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onRenamed = {
                renameTarget = null
                library.refresh()
            },
        )
    }

    CpConfirmHost(confirm)
}

/** 本地歌曲行：封面 + 标题 + 歌手 / 专辑。点击播放整个结果列表。 */
@Composable
private fun LocalTrackRow(
    item: LocalMediaItem,
    index: Int,
    total: Int,
    onClick: () -> Unit,
) {
    LegacyListItem(
        index = index,
        total = total,
        onClick = onClick,
        containerColor = cp.player.app.ui.component.settingsRowContainer(),
        leadingContent = {
            Box(Modifier.size(44.dp)) {
                if (!item.coverUri.isNullOrBlank()) {
                    AsyncImage(
                        model = item.coverUri.resized(120),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    CpCoverPlaceholder(
                        modifier = Modifier.fillMaxSize(),
                        corner = 10.dp,
                        animated = false,
                    )
                }
            }
        },
        headlineContent = {
            CpText(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        },
        supportingContent = {
            val subtitle = listOfNotNull(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                CpText(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    )
}

@Composable
private fun PlaylistTrackRow(
    hit: LibraryTrackHit,
    index: Int,
    total: Int,
    allTracks: List<TrackSummary>,
) {
    val scope = rememberCoroutineScope()
    val track = hit.track
    val mediaIds = allTracks.map { "${AppModel.activeProviderId()}://song/${it.id}" }
    SongItem(
        track = track,
        index = index,
        total = total,
        supportingText = listOfNotNull(hit.playlist.name, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        onClick = {
            CoverFlight.play(track.id, track.coverUrl)
            scope.launch { AppModel.playback.playQueue(mediaIds, startIndex = index) }
        },
    )
}

/** 本地结果的展示上限：曲库检索是「找东西」，不是浏览；再多的命中交给本地曲库页。 */
private const val MAX_LOCAL_RESULTS = 80
