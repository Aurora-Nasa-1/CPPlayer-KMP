package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AlbumCoverCard
import cp.player.app.ui.component.ArtistAvatar
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
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
import cp.player.core.BackendResult
import cp.player.core.music.ProfileBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 用户 / 歌手主页（统一页面）。
 *
 * ## 为什么是**一个**页面而不是两个
 *
 * 上游 `user/detail` 与 `artist/detail` 共用同一段数字 id 空间，同一个 id 两边都可能返回
 * 数据 —— 「点进去之前」根本不知道对方是人还是歌手。硬拆成两页就必须在这里先猜一次，
 * 猜错就是个空页。旧项目 `UserViewModel.fetchOtherUserProfile` 用的也是「先试歌手」的
 * 单页方案，这里沿用。
 *
 * ## 收敛前它不存在
 *
 * 搜索结果里的歌手只能显示一行**纯文字**（连头像都没有），首页「热门歌手」点下去是
 * 「拿歌手名字再搜一遍」。这一页把 `artist/detail` + `artist/top/song` + `artist/album`
 * + `user/detail` + `user/playlist` 真正接起来。
 */
class UserProfileScreen(
    private val uid: Long,
    private val displayName: String? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        UserProfileContent(
            uid = uid,
            displayName = displayName,
            // `rememberScreenModel` 是定义在 `Screen` 上的**扩展函数**，只有 Screen 子类的
            // 成员里才有接收者；顶层的 @Composable 里调用会报 `Unresolved reference`。
            model = rememberScreenModel { UserProfileModel() },
            onBack = { navigator.popOrNotify() },
            onOpenChat = { name -> navigator.push(ChatScreen(uid, name)) },
        )
    }
}

private data class ProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bundle: ProfileBundle? = null,
)

/** 歌手主页默认展开的热门歌曲条数（超出部分折叠，避免把「专辑」推到很下面）。 */
private const val SONG_PREVIEW = 10

private class UserProfileModel : ScreenModel {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state

    private var loadedUid: Long? = null

    /**
     * @param strings 失败兜底文案按**当前语言**组 —— 协程里读不到 CompositionLocal，
     *   由组合侧传进来（见 I18N.md §5.9）。
     */
    fun load(uid: Long, fallbackName: String?, strings: CpStrings) {
        if (loadedUid == uid && _state.value.bundle != null) return
        loadedUid = uid
        screenModelScope.launch {
            _state.value = ProfileUiState(loading = true)
            val result = runCatching { AppModel.musicRepository.getProfileBundle(uid) }
                .getOrElse { BackendResult.Error(it.message ?: strings.account.profileLoadFailed) }
            _state.value = when (result) {
                is BackendResult.Success -> ProfileUiState(loading = false, bundle = result.data)
                is BackendResult.Error -> ProfileUiState(
                    loading = false,
                    error = result.message,
                    bundle = fallbackName?.let {
                        ProfileBundle(
                            uid = uid, isArtist = false, nickname = it,
                            avatarUrl = null, signature = null,
                            primaryCount = 0, follows = 0, followeds = 0,
                        )
                    },
                )
                is BackendResult.Unsupported -> ProfileUiState(loading = false, error = result.message)
            }
        }
    }

    fun reload(uid: Long, fallbackName: String?, strings: CpStrings) {
        loadedUid = null
        load(uid, fallbackName, strings)
    }
}

@Composable
private fun UserProfileContent(
    uid: Long,
    displayName: String?,
    model: UserProfileModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val s = cpStrings()
    val me by AppModel.userProfileFlow.collectAsState()
    val playbackState by AppModel.playback.state.collectAsState()
    val currentTrackId = playbackState.currentTrack?.id
    val likedIds by AppModel.playback.likedIds.collectAsState()
    val scope = rememberCoroutineScope()
    val provider = AppModel.activeProviderId()

    // 歌单列表每行右侧的「更多」动作来源。本页不是有自己 ScreenModel 的复杂页面，
    // 借 HomeScreenModel 现成的歌单动作（播放 / 队列 / 下载 / 分享 / 删除或取消收藏）：
    // 与侧栏、歌单详情页共用「先取详情再拿曲目」的那条取数路径。
    // ⚠️ 用 `remember` 而不是 `rememberScreenModel`：后者按类型键控、会与本页的
    // UserProfileModel 抢槽位（Voyager 一个 Screen 只保留一个同名模型）。
    val playlistActions = remember { cp.player.app.ui.model.HomeScreenModel(loadDiscovery = false) }
    var playlistOptionsTarget by remember {
        mutableStateOf<cp.player.core.music.PlaylistSummary?>(null)
    }
    // 「删除歌单 / 取消收藏」的二次确认：锚定菜单与底部弹层两个入口共用一份。
    val confirm = cp.player.app.ui.component.rememberConfirmState()

    LaunchedEffect(uid) { model.load(uid, displayName, strings = s) }

    val bundle = state.bundle
    val songs = bundle?.songs.orEmpty()
    val mediaIds = remember(songs) { songs.map { "$provider://song/${it.id}" } }
    val isMe = me?.uid == uid
    // 热门歌曲最多 50 首，默认全展开会把下面的「专辑」推到很远的滚动位置。
    var songsExpanded by remember(uid) { mutableStateOf(false) }
    val visibleSongs = if (songsExpanded) songs else songs.take(SONG_PREVIEW)

    CpRouteScaffold(
        title = bundle?.nickname ?: displayName ?: s.account.profileFallbackTitle,
        onBack = onBack,
    ) { pageModifier ->
        when {
            state.loading && bundle == null -> ContentState(
                title = s.account.profileLoading,
                message = s.account.profileLoadingNote,
                loading = true,
            )
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
                            onOptionsClick = {
                                scope.launch {
                                    val target = track.id !in likedIds
                                    AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                                    UiEvents.notify(if (target) s.album.liked else s.album.unliked)
                                }
                            },
                            // 桌面端右键菜单
                            contextMenu = songContextMenuItems(
                                SongMenuActions(
                                    onPlay = {
                                        CoverFlight.play(track.id, track.coverUrl)
                                        scope.launch { AppModel.playback.playQueue(mediaIds, index) }
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
                                Text(if (songsExpanded) s.player.collapse else s.account.expandAllSongs(songs.size))
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
                            items(bundle.albums.size, key = { "album-${bundle.albums[it].id}" }) { index ->
                                val album = bundle.albums[index]
                                AlbumCoverCard(
                                    album = album,
                                    onClick = { navigator.push(AlbumDetailScreen(album.id, album)) },
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
                    items(bundle.playlists.size, key = { "pl-${bundle.playlists[it].id}" }) { index ->
                        val playlist = bundle.playlists[index]
                        PlaylistItem(
                            playlist = playlist,
                            isOwner = isMe,
                            modifier = Modifier.animateItem(),
                            onClick = { navigator.push(PlaylistDetailScreen(playlist)) },
                            // 点击弹歌单动作菜单（原先传的是空 lambda，按钮画得出来、
                            // 点下去毫无反应）。isOwner 跟着 isMe 走：在自己主页上才有
                            // 「删除歌单」，看别人的主页只有「取消收藏」。
                            onOptionsClick = { playlistOptionsTarget = playlist },
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
                onConfirm = { playlistActions.deleteOrUnsubscribePlaylist(playlist) },
            )
        }
        val menuItems = if (playlistActions.isServerPlaylist(playlist)) {
            cp.player.app.ui.component.playlistContextMenuItems(
                cp.player.app.ui.component.PlaylistMenuActions(
                    isOwner = owner,
                    onPlay = {
                        playlistActions.playPlaylist(playlist)
                        playlistOptionsTarget = null
                    },
                    onAddToQueue = {
                        playlistActions.queuePlaylist(playlist)
                        playlistOptionsTarget = null
                    },
                    onDownload = {
                        playlistActions.downloadPlaylist(playlist)
                        playlistOptionsTarget = null
                    },
                    onShare = { shareText(playlistShareText(playlist.id, playlist.name)) },
                    // 收藏状态不在这里查（要额外一次请求），所以菜单不提供「收藏歌单」；
                    // 取消收藏走 [onDelete] 那一支的「删除歌单 / 取消收藏」——
                    // 与侧栏同一条规则：owner 删除、非 owner 取消收藏。
                    // 两者都先弹确认：删掉自己建的歌单不可恢复。
                    onDelete = askDelete,
                )
            )
        } else {
            emptyList()
        }
        val windowChromeActive = cp.player.app.ui.component.LocalWindowChromeActive.current
        if (windowChromeActive && menuItems.isNotEmpty()) {
            Box(Modifier.fillMaxSize()) {
                androidx.compose.material3.DropdownMenu(
                    expanded = true,
                    onDismissRequest = { playlistOptionsTarget = null },
                ) {
                    menuItems.forEach { item ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                playlistOptionsTarget = null
                                item.onClick()
                            },
                        )
                    }
                }
            }
        } else {
            cp.player.app.ui.component.PlaylistOptionsSheet(
                playlistName = playlist.name,
                isOwner = owner,
                onDismiss = { playlistOptionsTarget = null },
                onPlay = {
                    playlistActions.playPlaylist(playlist)
                    playlistOptionsTarget = null
                },
                onAddToQueue = {
                    playlistActions.queuePlaylist(playlist)
                    playlistOptionsTarget = null
                },
                onDelete = if (owner) askDelete else null,
                onShare = { shareText(playlistShareText(playlist.id, playlist.name)) },
                coverUrl = playlist.coverUrl,
            )
        }
    }

    cp.player.app.ui.component.CpConfirmHost(confirm)
}

/** 头部：大头像 + 昵称 + 签名 + 统计 + 私信入口。 */
@Composable
private fun ProfileHero(
    bundle: ProfileBundle,
    isMe: Boolean,
    onOpenChat: (String) -> Unit,
) {
    val s = cpStrings()
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistAvatar(url = bundle.avatarUrl, size = 112.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            bundle.nickname.ifBlank { s.account.unknownUser },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        bundle.signature?.takeIf { it.isNotBlank() }?.let { signature ->
            Spacer(Modifier.height(8.dp))
            Text(
                signature,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
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
        if (!isMe) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onOpenChat(bundle.nickname) }) {
                Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s.account.sendMessage)
            }
        }
    }
}

@Composable
private fun ProfileStat(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
