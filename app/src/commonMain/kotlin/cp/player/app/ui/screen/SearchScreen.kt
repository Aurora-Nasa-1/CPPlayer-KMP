package cp.player.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.component.AlbumItem
import cp.player.app.ui.component.ArtistItem
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSearchField
import cp.player.app.ui.component.CpSearchFieldHeight
import cp.player.app.ui.component.CpSearchSuggestionPanel
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.PageHeader
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LazyScrollRow
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongItem
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.PlaylistItem
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.component.playlistShareText
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.shareText
import cp.player.app.ui.model.SearchScreenModel
import cp.player.core.api.MusicApiMethod
import kotlinx.coroutines.launch

/**
 * 搜索框与它上方那条边的距离。
 *
 * 12dp 而不是区块间距（[CpSpacing.section] 28dp）：搜索框、类型切换行、结果列表是**同一组
 * 控件**，内部的节奏要紧；区块间距留给「最近搜索」↔「热门搜索」那种真正的分组。
 */
private val SearchFieldTopPadding = 12.dp

class SearchScreen(private val initialQuery: String = "") : Screen {
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val model = rememberScreenModel { SearchScreenModel(initialQuery) }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()
        val provider = AppModel.activeProviderId()
        val navigator = LocalNavigator.currentOrThrow

        // 歌单搜索结果那一行右侧的「更多」需要一个动作来源：本页没有自己的
        // ScreenModel，就借 HomeScreenModel 现成的那几个歌单动作（播放 / 队列 /
        // 下载 / 分享 / 删除或取消收藏）—— 与侧栏、歌单详情页共用同一套实现，
        // 不必在这里再抄一遍「先取详情再拿曲目」的取数逻辑。
        // ⚠️ 必须用 `remember`：写成 `rememberScreenModel { … }` 也行（本页是 Screen），
        // 但 SearchScreenModel 已经占了那个槽，两个模型指同一个 key 只会互相顶掉。
        val playlistActions = androidx.compose.runtime.remember {
            cp.player.app.ui.model.HomeScreenModel(loadDiscovery = false)
        }
        var playlistOptionsTarget by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<cp.player.core.music.PlaylistSummary?>(null)
        }

        // 本页有**两种角色**：
        //  (a) 「搜索」tab 的根内容 —— 由 TabContent 渲染，外壳已经画了顶栏（标题=tab 名），
        //      这里再套一层路由外壳就是**双顶栏**，所以必须原样输出正文；
        //  (b) 被首页 push 出来的路由页 —— 需要自己的标题与返回键（否则宽屏平板无顶栏、
        //      无返回，桌面窗口标题也会停在「首页」）。
        // 判据用「本实例是不是导航栈顶」：tab 形态下栈顶是 tab 宿主，不是本页。
        val isRoutePage = navigator.lastItem === this

        // 桌面标题栏的全局搜索框把关键词投递到这里。它是唯一消费者（MainScreen 只负责切 tab，
        // 不消费），所以这里喂给 ScreenModel 后立刻置回 null。见 DesktopShell 的 KDoc。
        val pendingSearchQuery = cp.player.app.ui.util.DesktopShell.pendingSearchQuery
        androidx.compose.runtime.LaunchedEffect(pendingSearchQuery) {
            if (!pendingSearchQuery.isNullOrBlank()) {
                model.search(pendingSearchQuery)
                cp.player.app.ui.util.DesktopShell.pendingSearchQuery = null
            }
        }
        val likedIds by AppModel.playback.likedIds.collectAsState()
        var selectedTrack by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<cp.player.core.music.TrackSummary?>(null)
        }
        var addToPlaylistTrack by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<cp.player.core.music.TrackSummary?>(null)
        }

        // 搜索框焦点：既是建议下拉的显示判据（失焦即收起），也是本页「点别处关闭下拉」的
        // 唯一手段 —— 下拉是浮层，只能靠焦点语义判断「用户已经去干别的了」。
        val searchFocusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        var fieldFocused by remember { mutableStateOf(false) }
        // 桌面 / 宽屏平板进到搜索这一页就应当能直接打字（否则还得先在输入框里点一下）。
        // ⚠️ 触屏不自动聚焦：进页面就弹软键盘，会把「最近搜索 / 热门搜索」整块顶掉一半。
        LaunchedEffect(Unit) {
            if (!isAndroidPlatform()) searchFocusRequester.requestFocus()
        }

        // 正文整体收进一个 lambda，便于按上面两种角色决定是否套 CpRouteScaffold。
        val routeBody: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(contentModifier.fillMaxSize()) {
            // 搜索框与建议下拉共用一层容器，宽度按 [CpSpacing.pageMaxWidth] 收口、居中 ——
            // ⚠️ `widthIn` 必须写在 `fillMaxWidth` **之前**，否则后者先把约束钉死、前者是空操作。
            //
            // ⚠️ 这一层 Box 的高度**钉死**在搜索框高度上：建议下拉必须是**浮层**，超出这层
            // 容器的部分不参与外层 Column 的布局（靠组件自带的 `wrapContentHeight(unbounded)`）。
            // 收敛前建议列表是内联的一段 Surface —— 它一出现，整页内容就往下跳一次。
            Box(
                Modifier
                    // 浮层要盖住下方所有兄弟（类型切换行、结果列表）—— 本 Box 是 Column 的
                    // 第一个孩子，**绘制顺序在后续兄弟之前**，不加 zIndex 的话「热门搜索 /
                    // 搜索结果」的文字会画在下拉上面（离屏渲染抓出来的）。zIndex 不影响布局。
                    .zIndex(1f)
                    .widthIn(max = CpSpacing.pageMaxWidth)
                    .fillMaxWidth()
                    .height(CpSearchFieldHeight)
                    .align(Alignment.CenterHorizontally)
                    .padding(
                        start = CpSpacing.pageHorizontal,
                        end = CpSpacing.pageHorizontal,
                        top = SearchFieldTopPadding,
                    ),
            ) {
                CpSearchField(
                    query = state.query,
                    onQueryChange = model::setQuery,
                    onSubmit = { model.search() },
                    onFocusChange = { fieldFocused = it },
                    focusRequester = searchFocusRequester,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fieldFocused && state.query.isNotBlank() && state.suggestions.isNotEmpty() && state.result == null) {
                    CpSearchSuggestionPanel(
                        suggestions = state.suggestions,
                        onPick = { picked ->
                            // 先收焦点再搜索：下拉的显示判据就是「输入框有焦点」，
                            // 不收的话它会一直浮在结果列表上面。
                            focusManager.clearFocus()
                            model.search(picked)
                        },
                        // 从搜索框下沿再往下浮 6dp —— 不贴着框底，才看得出是两个层次。
                        modifier = Modifier.fillMaxWidth().offset(y = CpSearchFieldHeight + 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state.query.isNotBlank() || state.result != null) {
                LazyScrollRow(
                    modifier = Modifier
                        .widthIn(max = CpSpacing.pageMaxWidth)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = CpSpacing.pageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val types = listOf(
                        MusicApiMethod.SEARCH_TYPE_SONG to "歌曲",
                        MusicApiMethod.SEARCH_TYPE_ALBUM to "专辑",
                        MusicApiMethod.SEARCH_TYPE_ARTIST to "歌手",
                        MusicApiMethod.SEARCH_TYPE_PLAYLIST to "歌单",
                    )
                    items(types.size) { index ->
                        val (type, label) = types[index]
                        // Expressive 切换按钮：选中态由**形状**表达（圆角方形 ↔ 胶囊），
                        // 而不是只有底色变化 —— 与桌面播放页的三个页签保持同一套语言。
                        cp.player.app.ui.component.CpToggleChip(
                            checked = state.searchType == type,
                            onCheckedChange = { model.selectSearchType(type) },
                            label = label,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when {
                    // 加载 / 错误这两张卡也按页面宽度收口，否则宽屏上它们比搜索框宽一圈。
                    state.loading -> StateSurface(
                        Modifier.widthIn(max = CpSpacing.pageMaxWidth).padding(20.dp),
                    ) {
                        ContentState(title = "正在搜索", message = "正在从当前音源查找内容", loading = true)
                    }
                    state.error != null -> StateSurface(
                        Modifier.widthIn(max = CpSpacing.pageMaxWidth).padding(20.dp),
                    ) {
                        ContentState(
                            title = "没有完成搜索",
                            message = state.error,
                            error = true,
                            actionLabel = "重试",
                            onAction = { model.search() },
                        )
                    }
                    state.result == null -> {
                        Column(
                            Modifier
                                .widthIn(max = CpSpacing.pageMaxWidth)
                                .fillMaxSize()
                                .padding(horizontal = CpSpacing.pageHorizontal),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (state.query.isBlank() && state.searchHistory.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SectionHeader(title = "最近搜索")
                                    TextButton(onClick = model::clearHistory) { Text("清空") }
                                }
                                state.searchHistory.forEach { keyword ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { model.search(keyword) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(12.dp))
                                        Text(keyword)
                                    }
                                }
                            }
                            if (state.query.isBlank()) {
                                SectionHeader(title = "热门搜索")
                                state.hotSearches.forEachIndexed { index, hot ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { model.search(hot.keyword) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            modifier = Modifier.width(28.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (index < 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(hot.keyword)
                                            if (hot.description.isNotBlank()) {
                                                Text(hot.description, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                            }
                                        }
                                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    }
                                }
                                ContentState(
                                    title = "发现下一首喜欢的音乐",
                                    message = "输入关键词后按搜索键",
                                    modifier = Modifier.padding(top = 20.dp),
                                )
                            }
                        }
                    }
                    state.result == null -> Unit
                    else -> {
                        val result = state.result!!
                        // ⚠️ 每个页签只能数**它自己那个数组**。
                        // 收敛前这里是 `ALBUM, PLAYLIST -> result.playlists.size`，
                        // 而专辑搜索返回的是 `result.albums` —— 计数恒为 0，
                        // 于是专辑页签永远显示「没有找到结果」，即使服务端返回了 30 张专辑。
                        val count = when (state.searchType) {
                            MusicApiMethod.SEARCH_TYPE_SONG -> result.songs.size
                            MusicApiMethod.SEARCH_TYPE_ALBUM -> result.albums.size
                            MusicApiMethod.SEARCH_TYPE_PLAYLIST -> result.playlists.size
                            else -> result.artists.size
                        }
                        if (count == 0) {
                            ContentState(
                                title = "没有找到结果",
                                message = "试试更短的关键词或切换搜索类型",
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        } else {
                            LazyScrollColumn(
                                // ⚠️ `widthIn` 必须写在 `fillMaxSize` **之前**。宽屏上不收口的话，
                                // 结果列表会比上面的搜索框 / 类型切换行宽出一圈（两侧常留白不同）。
                                Modifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                                // 与上方的搜索框、类型切换行取同一个页面内边距。原先这里是 12dp ——
                                // 结果列表比搜索框左右各缩进 8dp，同一屏里两套边距。
                                contentPadding = PaddingValues(
                                    start = CpSpacing.pageHorizontal,
                                    end = CpSpacing.pageHorizontal,
                                    bottom = 32.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
                            ) {
                                item {
                                    SectionHeader(
                                        title = "搜索结果",
                                        supportingText = "$count 项",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    )
                                }
                                when (state.searchType) {
                                    MusicApiMethod.SEARCH_TYPE_SONG -> itemsIndexed(result.songs, key = { _, track -> track.id }) { index, track ->
                                        SongItem(
                                            track = track, index = index, total = result.songs.size,
                                            // 重新搜索时结果整体换一批：没有 animateItem 的话
                                            // 每一行都是原地闪现，看起来像「刷新了一下」而不是「换了一批」。
                                            modifier = Modifier.animateItem(),
                                            onClick = {
                                                CoverFlight.play(track.id, track.coverUrl)
                                                scope.launch { AppModel.playback.playQueue(result.songs.map { "$provider://song/${it.id}" }, index) }
                                            },
                                            onOptionsClick = { selectedTrack = track },
                                            // 桌面端右键菜单：动作集合与 SongOptionsSheet 对齐
                                            contextMenu = songContextMenuItems(
                                                SongMenuActions(
                                                    onPlay = {
                                                        CoverFlight.play(track.id, track.coverUrl)
                                                        scope.launch { AppModel.playback.playQueue(result.songs.map { "$provider://song/${it.id}" }, index) }
                                                    },
                                                    isFavorite = track.id in likedIds,
                                                    onToggleFavorite = {
                                                        scope.launch {
                                                            val target = track.id !in likedIds
                                                            AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                                                            cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                                                        }
                                                    },
                                                    onAddToQueue = {
                                                        scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                                                        cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
                                                    },
                                                    onPlayNext = {
                                                        scope.launch { AppModel.playback.addNextToQueue("$provider://song/${track.id}") }
                                                        cp.player.app.ui.util.UiEvents.notify("将在下一首播放")
                                                    },
                                                    isDownloaded = AppModel.isDownloaded(track.id),
                                                    onDownload = { AppModel.downloadTrack(track) },
                                                    onAddToPlaylist = { addToPlaylistTrack = track },
                                                    onShare = { shareText(songShareText(track)) },
                                                )
                                            ),
                                        )
                                    }
                                    MusicApiMethod.SEARCH_TYPE_ALBUM -> itemsIndexed(result.albums, key = { _, album -> "album-${album.id}" }) { _, album ->
                                        AlbumItem(
                                            album = album,
                                            modifier = Modifier.animateItem(),
                                            // 进得去真正的专辑详情页 —— 这一条以前是**打不开任何东西**的。
                                            onClick = { navigator.push(AlbumDetailScreen(album.id, album)) },
                                        )
                                    }
                                    MusicApiMethod.SEARCH_TYPE_PLAYLIST -> itemsIndexed(result.playlists, key = { _, playlist -> "playlist-${playlist.id}" }) { _, playlist ->
                                        PlaylistItem(
                                            playlist = playlist,
                                            isOwner = false,
                                            onClick = { navigator.push(PlaylistDetailScreen(playlist)) },
                                            // 点击弹歌单动作菜单（原先传的是空 lambda，
                                            // 按钮画得出来、点下去毫无反应）。
                                            // 搜索结果里的歌单都来自上游，isOwner 恒 false。
                                            onOptionsClick = { playlistOptionsTarget = playlist },
                                        )
                                    }
                                    MusicApiMethod.SEARCH_TYPE_ARTIST -> itemsIndexed(result.artists, key = { _, artist -> "artist-${artist.id}" }) { _, artist ->
                                        ArtistItem(
                                            artist = artist,
                                            subtitle = "歌手",
                                            modifier = Modifier.animateItem(),
                                            onClick = { navigator.push(UserProfileScreen(artist.id, artist.name)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        if (isRoutePage) {
            // 路由页形态：标题与返回交给唯一外壳（桌面窗口 chrome / 窄屏自绘顶栏，
            // 双栏右栏则由容器接管）。
            CpRouteScaffold(
                title = "搜索",
                onBack = if (navigator.size > 1) ({ navigator.pop() }) else null,
            ) { routeBody(it) }
        } else {
            // tab 形态：正文原样输出，顶栏由外壳承担。
            routeBody(Modifier)
        }

        selectedTrack?.let { track ->
            cp.player.app.ui.component.SongOptionsSheet(
                songName = track.name,
                artistName = track.artist,
                coverUrl = track.coverUrl,
                isFavorite = track.id in likedIds,
                isDownloaded = AppModel.isDownloaded(track.id),
                onDismiss = { selectedTrack = null },
                onPlay = {
                    CoverFlight.play(track.id, track.coverUrl)
                    scope.launch {
                        AppModel.playback.playQueue(listOf("$provider://song/${track.id}"), startIndex = 0)
                    }
                },
                onToggleFavorite = {
                    scope.launch {
                        val target = track.id !in likedIds
                        AppModel.playback.toggleFavoriteFor("$provider://song/${track.id}")
                        cp.player.app.ui.util.UiEvents.notify(if (target) "已收藏" else "已取消收藏")
                    }
                },
                onAddToQueue = {
                    scope.launch { AppModel.playback.addToQueue("$provider://song/${track.id}") }
                    cp.player.app.ui.util.UiEvents.notify("已加入播放队列")
                },
                onPlayNext = {
                    scope.launch { AppModel.playback.addNextToQueue("$provider://song/${track.id}") }
                    cp.player.app.ui.util.UiEvents.notify("将在下一首播放")
                },
                onAddToPlaylist = { addToPlaylistTrack = track },
                onDownload = { AppModel.downloadTrack(track) },
            )
        }

        addToPlaylistTrack?.let { track ->
            cp.player.app.ui.component.AddToPlaylistSheet(
                trackId = track.id,
                onDismiss = { addToPlaylistTrack = null },
            )
        }

        // 歌单「更多」：桌面端弹锚定菜单（与右键、歌单详情页左栏同一份 items），
        // 非桌面（触屏 / 无窗口 chrome 的宽屏平板）回落到底部弹层。
        // PlaylistItem 的更多按钮位置不固定（列表很长时会滚到屏外），
        // 所以菜单/弹层挂在**页面级**状态上，而不是行内联一个 CpAnchoredMenu。
        playlistOptionsTarget?.let { playlist ->
            // 本地虚拟歌单（id ≤ 0）没有服务端实体，搜索页理论上拿不到，
            // 但判据与侧栏 / 详情页共用一条，避免哪天上游塞进来一个负数 id。
            val menuItems = if (playlistActions.isServerPlaylist(playlist)) {
                cp.player.app.ui.component.playlistContextMenuItems(
                    cp.player.app.ui.component.PlaylistMenuActions(
                        // 搜索结果里的歌单不可能是「我建的」，删除入口因此不会出现。
                        isOwner = false,
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
                    )
                )
            } else {
                emptyList()
            }
            val windowChromeActive = cp.player.app.ui.component.LocalWindowChromeActive.current
            if (windowChromeActive && menuItems.isNotEmpty()) {
                // 桌面端没有可锚定的按钮（列表项可能已经滚出可视区），
                // 用一层全屏透明点击层把菜单挂在页面中心偏上 —— 点空白处即收起。
                Box(Modifier.fillMaxSize()) {
                    androidx.compose.material3.DropdownMenu(
                        expanded = true,
                        onDismissRequest = { playlistOptionsTarget = null },
                    ) {
                        menuItems.forEach { item ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = {
                                    // 与 CpContextMenuPanel 同一顺序：先收面板再执行动作。
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
                    isOwner = false,
                    onDismiss = { playlistOptionsTarget = null },
                    onPlay = {
                        playlistActions.playPlaylist(playlist)
                        playlistOptionsTarget = null
                    },
                    onAddToQueue = {
                        playlistActions.queuePlaylist(playlist)
                        playlistOptionsTarget = null
                    },
                    onShare = { shareText(playlistShareText(playlist.id, playlist.name)) },
                    coverUrl = playlist.coverUrl,
                )
            }
        }
    }
}
