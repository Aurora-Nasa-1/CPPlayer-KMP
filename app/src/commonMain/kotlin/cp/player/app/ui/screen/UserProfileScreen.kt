package cp.player.app.ui.screen

import cp.player.app.ui.component.CpText
import cp.player.app.ui.component.CpExpandableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import cp.player.app.ui.navigation.pageModel
import androidx.lifecycle.viewModelScope
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.presentation.navigation.AppRoute
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AddToPlaylistSheet
import cp.player.app.ui.component.AlbumCoverCard
import cp.player.app.ui.component.ArtistAvatar
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SongDetailInfoDialog
import cp.player.app.ui.component.SongOptionsSheet
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.CpCardRowSkeleton
import cp.player.app.ui.component.CpProfileHeroSkeleton
import cp.player.app.ui.component.CpSectionHeaderSkeleton
import cp.player.app.ui.component.CpSkeletonCaption
import cp.player.app.ui.component.CpSongRowSkeleton
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.LazyScrollRow
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.component.playlistShareText
import cp.player.app.platform.shareText
import cp.player.app.ui.util.UiEvents
import cp.player.core.music.ProfileBundle
import cp.player.core.music.TrackSummary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 用户 / 歌手主页（统一页面）。
 *
 * 用户与歌手个人主页页面：展示资料、热门单曲、专辑与创建的歌单。
 */
class UserProfileScreen(
    private val uid: Long,
    private val displayName: String? = null,
    private val resourceId: String = uid.toString(),
) : RoutePage {
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        UserProfileContent(
            uid = resourceId,
            displayName = displayName,
            // `pageModel` 是定义在 `RoutePage` 上的**扩展函数**，只有 RoutePage 子类的
            // 成员里才有接收者；顶层的 @Composable 里调用会报 `Unresolved reference`。
            model = pageModel { UserProfileModel() },
            onBack = { navigator.pop() },
            onOpenChat = if (uid > 0L && resourceId == uid.toString()) {
                { name -> navigator.push(AppRoute.Chat(uid, name)) }
            } else null,
        )
    }
}

private typealias ProfileUiState = cp.player.app.presentation.userprofile.ProfileUiState

/** 歌手主页默认展开的热门歌曲条数（超出部分折叠，避免把「专辑」推到很下面）。 */
private const val SONG_PREVIEW = 10

private class UserProfileModel : ViewModel() {
    private val presenter = cp.player.app.presentation.userprofile.UserProfilePresenter(
        scope = viewModelScope,
    )

    val state: StateFlow<ProfileUiState> get() = presenter.state

    fun load(uid: String, fallbackName: String?, strings: CpStrings) =
        presenter.load(uid, fallbackName, strings)

    fun reload(uid: String, fallbackName: String?, strings: CpStrings) =
        presenter.reload(uid, fallbackName, strings)
}

@Composable
private fun UserProfileContent(
    uid: String,
    displayName: String?,
    model: UserProfileModel,
    onBack: () -> Unit,
    onOpenChat: ((String) -> Unit)?,
) {
    val state by model.state.collectAsState()
    val navigator = LocalAppNavigator.current
    val s = cpStrings()
    val me by AppModel.userProfileFlow.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val sourceGeneration by AppModel.sourceGeneration.collectAsState()
    val accountGeneration by AppModel.accountGeneration.collectAsState()
    val playlistGeneration by AppModel.playlistGeneration.collectAsState()
    val scope = rememberCoroutineScope()
    val provider = AppModel.activeProviderId()

    // 歌单列表每行右侧的「更多」动作来源。本页不是有自己 ScreenModel 的复杂页面，
    // 借 HomeScreenModel 现成的歌单动作（播放 / 队列 / 下载 / 分享 / 删除或取消收藏）：
    // 与侧栏、歌单详情页共用「先取详情再拿曲目」的那条取数路径。
    // ⚠️ 不能用 `remember { HomeScreenModel(...) }`：Voyager 的 ScreenModel 只有在
    // `pageModel` 托管时才会被 dispose，直接 remember 会让它的
    // viewModelScope 永久留在全局表里。这里用页面自己的 scope 托管纯 Presenter。
    val playlistActions = remember(scope) {
        cp.player.app.presentation.home.HomePresenter(
            scope = scope,
            loadDiscovery = false,
            notify = UiEvents::notify,
        )
    }
    var playlistOptionsTarget by remember {
        mutableStateOf<cp.player.core.music.PlaylistSummary?>(null)
    }
    // 「删除歌单 / 取消收藏」的二次确认：锚定菜单与底部弹层两个入口共用一份。
    val confirm = cp.player.app.ui.component.rememberConfirmState()

    // 热门歌曲行尾「更多」的目标 + 它的二级弹层目标。
    //
    // ⚠️ 二级弹层的目标（加歌单 / 歌曲信息）必须由**本层**持有：SongOptionsSheet 的
    // 动作是「先回调、再自行 onDismiss」，宿主一关就被卸载，状态放弹层里会被一起丢掉
    // （同一坑见 RecentPlaysScreen 的同名注释）。
    var songOptionsTarget by remember { mutableStateOf<TrackSummary?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<TrackSummary?>(null) }
    var showInfoTarget by remember { mutableStateOf<TrackSummary?>(null) }

    LaunchedEffect(uid, sourceGeneration, accountGeneration, playlistGeneration) {
        model.reload(uid, displayName, strings = s)
    }

    val bundle = state.bundle
    val songs = bundle?.songs.orEmpty()
    val mediaIds = remember(songs, provider) { songs.map { "$provider://song/${it.id}" } }
    val isMe = me?.resourceId == uid
    // 热门歌曲最多 50 首，默认全展开会把下面的「专辑」推到很远的滚动位置。
    var songsExpanded by remember(uid) { mutableStateOf(false) }
    val visibleSongs = if (songsExpanded) songs else songs.take(SONG_PREVIEW)

    CpRouteScaffold(
        title = bundle?.nickname ?: displayName ?: s.account.profileFallbackTitle,
        onBack = onBack,
    ) { pageModifier ->
        when {
            state.loading && bundle == null -> ScrollColumn(
                modifier = pageModifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    bottom = CpSpacing.formBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                // 个人主页的结构（头像 + 昵称 + 统计 + 热门歌曲 + 歌单）是固定的，
                // 先摆结构再填数据，比居中转圈更接近「页面正在长出来」。
                CpSkeletonCaption(s.account.profileLoadingNote)
                CpProfileHeroSkeleton()
                Spacer(Modifier.height(8.dp))
                CpSectionHeaderSkeleton(titleWidth = 90.dp, subtitleWidth = 70.dp)
                CpSongRowSkeleton(rows = 6)
                Spacer(Modifier.height(8.dp))
                CpSectionHeaderSkeleton(titleWidth = 72.dp, subtitleWidth = 44.dp)
                // 歌单区在主页是**卡片行**（PlaylistItem：60dp 封面 + 两行字 + 卡片底），
                // 不是封面栅格 —— 摆成栅格的话数据回来后整块会从方形网格塌成横条。
                CpCardRowSkeleton(rows = 3, thumbSize = 60.dp)
            }
            bundle == null -> ContentState(
                title = s.account.profileNotOpened,
                message = state.error,
                error = true,
                actionLabel = s.library.retry,
                onAction = { model.reload(uid, displayName, strings = s) },
            )
            else -> LazyScrollColumn(
                modifier = pageModifier.fillMaxSize(),
                // 与首页、搜索页取同一个页面内边距：这是「栅格 / 卡片页」，不是表单页。
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    bottom = CpSpacing.formBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                item {
                    ProfileHero(
                        bundle = bundle,
                        isMe = isMe,
                        onOpenChat = onOpenChat,
                    )
                }

                if (bundle.isArtist && songs.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = s.account.topSongs,
                            supportingText = s.account.songCount(songs.size),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(visibleSongs.size, key = { "song-${visibleSongs[it].id}" }) { index ->
                        val track = visibleSongs[index]
                        SongItem(
                            track = track,
                            index = index,
                            total = visibleSongs.size,
                            isCurrentlyPlaying = track.id == currentTrackId,
                            modifier = Modifier.animateItem(),
                            onClick = {
                                CoverFlight.play(track.id, track.coverUrl)
                                // 点第 N 首时把**完整**列表交给播放器：否则播完折叠出来的
                                // 10 首队列就断了，用户会以为「这个歌手的歌只有 10 首」。
                                AppModel.playTrackClicked("$provider://song/${track.id}") {
                                    AppModel.playback.playQueue(mediaIds, index)
                                }
                            },
                            onOptionsClick = { songOptionsTarget = track },
                            // 桌面端右键菜单
                            contextMenu = songContextMenuItems(
                                SongMenuActions(
                                    onPlay = {
                                        CoverFlight.play(track.id, track.coverUrl)
                                        scope.launch { AppModel.playback.playQueue(mediaIds, index) }
                                    },
                                    onPlayMv = track.mvId?.takeIf { it > 0 }?.let { mvId ->
                                        { navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId", title = "${track.name} - MV")) }
                                    },
                                    isFavorite = track.id in likedIds,
                                    onToggleFavorite = {
                                        scope.launch {
                                            val target = track.id !in likedIds
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
                                    onShowInfo = { showInfoTarget = track },
                                    onShare = { shareText(songShareText(track)) },
                                )
                            ),
                        )
                    }
                    if (songs.size > SONG_PREVIEW) {
                        item {
                            TextButton(
                                onClick = { songsExpanded = !songsExpanded },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CpText(if (songsExpanded) s.player.collapse else s.account.expandAllSongs(songs.size))
                            }
                        }
                    }
                }

                if (bundle.isArtist && bundle.albums.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = s.account.albumsSection,
                            supportingText = s.account.albumCount(bundle.albums.size),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    // 专辑走**横向封面行**而不是竖排列表：这是主页里唯一一类「一次看全」的内容，
                    // 横排一眼扫完，竖排会把「歌单 / 热门歌曲」挤到很下面。
                    item {
                        LazyScrollRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(bundle.albums.size, key = { "album-${bundle.albums[it].resourceId}" }) { index ->
                                val album = bundle.albums[index]
                                AlbumCoverCard(
                                    album = album,
                                    onClick = { navigator.push(AppRoute.AlbumDetail(album.id, album)) },
                                )
                            }
                        }
                    }
                }

                if (!bundle.isArtist && bundle.playlists.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = s.account.playlistsSection,
                            supportingText = s.account.playlistCount(bundle.playlists.size),
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(bundle.playlists.size, key = { "pl-${bundle.playlists[it].resourceId}" }) { index ->
                        val playlist = bundle.playlists[index]
                        val owner = playlistActions.isPlaylistOwner(playlist)
                        val askDelete: () -> Unit = {
                            confirm.request(
                                title = if (owner) s.library.deletePlaylist else s.library.unfavoritePlaylist,
                                message = if (owner) {
                                    s.library.deletePlaylistMessage(playlist.name)
                                } else {
                                    s.library.unfavoritePlaylistMessage(playlist.name)
                                },
                                confirmLabel = if (owner) s.common.confirm else s.library.unfavoritePlaylist,
                                destructive = owner,
                                onConfirm = { playlistActions.deleteOrUnsubscribePlaylist(playlist, s) },
                            )
                        }
                        val menuActions = if (playlistActions.isServerPlaylist(playlist)) {
                            cp.player.app.ui.component.PlaylistMenuActions(
                                isOwner = owner,
                                isFavorite = true,
                                onPlay = { playlistActions.playPlaylist(playlist, s) },
                                onAddToQueue = { playlistActions.queuePlaylist(playlist, s) },
                                onDownload = {
                                    confirm.requestDownloadAll(s, playlist.name, playlist.trackCount) {
                                        playlistActions.downloadPlaylist(playlist, s)
                                    }
                                },
                                onShare = { shareText(playlistShareText(playlist.resourceId, playlist.name)) },
                                onToggleFavorite = if (!owner) askDelete else null,
                                onDelete = if (owner) askDelete else null,
                            )
                        } else {
                            null
                        }
                        PlaylistItem(
                            playlist = playlist,
                            isOwner = isMe,
                            // 歌单卡片行距 4dp；同一列表里的歌曲行仍是全局 2dp，
                            // 所以这里用 padding 补足差额而不是改整个列表的 arrangement。
                            modifier = Modifier.animateItem().padding(vertical = 1.dp),
                            onClick = { navigator.push(AppRoute.PlaylistDetail(playlist)) },
                            // 点击弹歌单动作菜单（原先传的是空 lambda，按钮画得出来、
                            // 点下去毫无反应）。isOwner 跟着 isMe 走：在自己主页上才有
                            // 「删除歌单」，看别人的主页只有「取消收藏」。
                            onOptionsClick = { playlistOptionsTarget = playlist },
                            menuActions = menuActions,
                        )
                    }
                }

                if (bundle.isArtist && bundle.albums.isEmpty() && songs.isEmpty()) {
                    item {
                        ContentState(
                            title = s.account.artistEmptyTitle,
                            message = state.error ?: s.account.artistEmptyNote,
                        )
                    }
                }
                if (!bundle.isArtist && bundle.playlists.isEmpty()) {
                    item {
                        ContentState(
                            title = s.account.noPublicPlaylists,
                            message = state.error,
                        )
                    }
                }
            }
        }
    }

    // 歌单「更多」：桌面端弹锚定菜单（与右键、侧栏、歌单详情页同一份 items），
    // 非桌面（触屏 / 无窗口 chrome 的宽屏平板）回落到底部弹层 —— 与搜索页一致。
    playlistOptionsTarget?.let { playlist ->
        val owner = playlistActions.isPlaylistOwner(playlist)
        // 两个入口（锚定菜单 / 底部弹层）共用同一个确认请求，文案只写一处。
        val askDelete: () -> Unit = {
            confirm.request(
                title = if (owner) s.library.deletePlaylist else s.library.unfavoritePlaylist,
                message = if (owner) {
                    s.library.deletePlaylistMessage(playlist.name)
                } else {
                    s.library.unfavoritePlaylistMessage(playlist.name)
                },
                // 与侧栏 / 歌单详情页同一条规则：删除用通用「确定」，取消收藏直接点出动作。
                confirmLabel = if (owner) s.common.confirm else s.library.unfavoritePlaylist,
                destructive = owner,
                onConfirm = { playlistActions.deleteOrUnsubscribePlaylist(playlist, s) },
            )
        }
        // 桌面端的「更多 / 右键」已经由 PlaylistItem 内部的锚定菜单处理，
        // 这里只负责触屏形态的底部弹层。
        cp.player.app.ui.component.PlaylistOptionsSheet(
            playlistName = playlist.name,
            isOwner = owner,
            onDismiss = { playlistOptionsTarget = null },
            onPlay = {
                playlistActions.playPlaylist(playlist, s)
                playlistOptionsTarget = null
            },
            onAddToQueue = {
                playlistActions.queuePlaylist(playlist, s)
                playlistOptionsTarget = null
            },
            onDownload = {
                confirm.requestDownloadAll(s, playlist.name, playlist.trackCount) {
                    playlistActions.downloadPlaylist(playlist, s)
                }
                playlistOptionsTarget = null
            },
            onDelete = if (owner) askDelete else null,
            isFavorite = true,
            onToggleFavorite = if (!owner) askDelete else null,
            onShare = { shareText(playlistShareText(playlist.resourceId, playlist.name)) },
            coverUrl = playlist.coverUrl,
        )
    }

    // 热门歌曲行尾「更多」弹层（手机 / 触屏形态）。
    //
    // 桌面端（窗口 chrome 接管）实际走的是 `SongItem` 内部的锚定菜单（`CpAnchoredMenu`，
    // 与右键同一份 items），那时 onOptionsClick **根本不会被回调**；因此这里无需再按
    // `LocalWindowChromeActive` 分流 —— 只有「没有锚定菜单」的形态才会走到这里。
    songOptionsTarget?.let { track ->
        SongOptionsSheet(
            songName = track.name,
            artistName = track.artist,
            coverUrl = track.coverUrl,
            isFavorite = track.id in likedIds,
            isDownloaded = AppModel.isDownloaded(track.id),
            onDismiss = { songOptionsTarget = null },
            onPlay = {
                CoverFlight.play(track.id, track.coverUrl)
                // 与行内点击走**同一条**播放动线：一起听「跟随」开启时点「播放」是
                // 「下一首播放」，直接 playQueue 会绕过那个语义。
                AppModel.playTrackClicked("$provider://song/${track.id}") {
                    val index = songs.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    AppModel.playback.playQueue(mediaIds, index)
                }
            },
            onToggleFavorite = {
                scope.launch {
                    val target = track.id !in likedIds
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
                { navigator.push(AppRoute.VideoPlayer("netease://mv/$mvId", title = "${track.name} - MV")) }
            },
        )
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistSheet(trackId = track.id, onDismiss = { addToPlaylistTrack = null })
    }

    showInfoTarget?.let { track ->
        SongDetailInfoDialog(track = track, onDismiss = { showInfoTarget = null })
    }

    cp.player.app.ui.component.CpConfirmHost(confirm)
}

/** 头部：大头像 + 昵称 + 签名 + 统计 + 私信入口。 */
@Composable
private fun ProfileHero(
    bundle: ProfileBundle,
    isMe: Boolean,
    onOpenChat: ((String) -> Unit)?,
) {
    val s = cpStrings()
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(url = bundle.avatarUrl, size = 112.dp)
        Spacer(Modifier.height(14.dp))
        CpText(
            bundle.nickname.ifBlank { s.account.unknownUser },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        bundle.signature?.takeIf { it.isNotBlank() }?.let { signature ->
            Spacer(Modifier.height(8.dp))
            // 签名是用户自己写的一句话（可能是多行），4 行之外应当能展开；
            // 直接截断会让「他到底写了什么」永远缺失。
            CpExpandableText(
                text = signature.trim(),
                collapsedMaxLines = 4,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (bundle.isArtist) {
                ProfileStat(bundle.primaryCount, s.account.statAlbums)
                ProfileStat(bundle.followeds, s.account.statFollowers)
            } else {
                ProfileStat(bundle.primaryCount, s.account.statPlaylists)
                ProfileStat(bundle.follows, s.account.statFollowing)
                ProfileStat(bundle.followeds, s.account.statFollowers)
            }
        }
        // 自己的主页发不了私信给自己 —— 这个按钮必须消失，而不是点了报错。
        if (!isMe && !bundle.isArtist && onOpenChat != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onOpenChat(bundle.nickname) }) {
                Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                CpText(s.account.sendMessage)
            }
        }
    }
}

@Composable
private fun ProfileStat(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CpText(
            count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        CpText(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
