package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.core.music.PlaylistSummary

/** Responsive application shell for the four primary destinations. */
class MainScreen : Screen {
    @OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val visitedTabs = remember { mutableStateListOf(selectedIndex) }
        val navigator = LocalNavigator.current
        val tabs = remember {
            listOf(
                TabItem(HomeScreen(), "首页", Icons.Filled.Home, Icons.Outlined.Home),
                TabItem(SearchScreen(), "搜索", Icons.Filled.Search, Icons.Outlined.Search),
                TabItem(LibraryScreen(), "我的", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
            )
        }
        val playbackState by AppModel.playback.state.collectAsState()
        val controller = AppModel.playback
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
        var desktopPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }
        var desktopSettingsOpen by remember { mutableStateOf(false) }
        val selectTab: (Int) -> Unit = { index ->
            desktopPlaylist = null
            desktopSettingsOpen = false
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndex = index
        }
        val closeDesktopOverlay = {
            desktopPlaylist = null
            desktopSettingsOpen = false
        }

        // 全局 Snackbar：收集各 Screen/ScreenModel 发出的操作反馈
        androidx.compose.runtime.LaunchedEffect(Unit) {
            cp.player.app.ui.util.UiEvents.messages.collect { message ->
                snackbarHostState.showSnackbar(
                    message,
                    withDismissAction = true,
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                )
            }
        }

        // seek 没能生效时给出明确反馈。
        //
        // 没有这条通道时，失败只能表现为「进度条自己弹回原位」——用户无法区分
        // 「我拖错了」和「这个音源根本不能定位」，也就是最初的「拖了没反应」。
        androidx.compose.runtime.LaunchedEffect(controller) {
            controller.seekFailures.collect { failure ->
                cp.player.app.ui.util.UiEvents.notify(
                    "无法定位到 ${cp.player.app.ui.util.formatTimeMs(failure.targetMs)}，" +
                        "该音源可能不支持拖动进度",
                )
            }
        }

        var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        
        // 捕捉返回键
        cp.player.app.platform.BackHandler(enabled = isPlayerExpanded) {
            isPlayerExpanded = false
        }

        // 展开播放页的进度：走主题的 spatial 动效（带回弹），打开/收起时背景会有
        // 一点「过冲再回落」，比原来的 LinearEasing 匀速有生气得多。
        val expandProgress by animateFloatAsState(
            targetValue = if (isPlayerExpanded) 1f else 0f,
            animationSpec = cp.player.app.ui.theme.CpMotion.spatialSlow(),
            label = "expandProgress"
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp

            CompositionLocalProvider(cp.player.app.ui.component.LocalIsExpanded provides expanded) {
            // 主内容，应用缩放和变暗
            Box(Modifier.fillMaxSize().graphicsLayer {
                // Desktop keeps the window layout stable; only compact player expansion scales.
                if (!expanded) {
                    val scale = 1f - expandProgress * 0.03f
                    scaleX = scale
                    scaleY = scale
                    translationY = expandProgress * 8f
                }
            }) {
                val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                
                if (expanded) {
                    Row(Modifier.fillMaxSize()) {
                        DesktopSidebar(
                            tabs = tabs,
                            selectedIndex = selectedIndex,
                            selectedPlaylist = desktopPlaylist,
                            onSelect = selectTab,
                            onOpenSettings = {
                                desktopPlaylist = null
                                desktopSettingsOpen = true
                            },
                            onOpenPlaylist = {
                                desktopSettingsOpen = false
                                selectedIndex = 2
                                desktopPlaylist = it
                            },
                        )
                        androidx.compose.material3.Scaffold(
                            modifier = Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection),
                            topBar = { AppTopBar(
                                 title = when {
                                     desktopSettingsOpen -> "设置"
                                     desktopPlaylist != null -> desktopPlaylist!!.name
                                     else -> tabs[selectedIndex].label
                                 },
                                 navigator = navigator,
                                 scrollBehavior = null,
                                 showBack = desktopSettingsOpen,
                                 onBack = closeDesktopOverlay,
                                 onOpenSettings = {
                    desktopPlaylist = null
                    desktopSettingsOpen = true
                }) },
                            containerColor = Color.Transparent
                        ) { padding ->
                            when {
                                desktopSettingsOpen -> {
                                    Box(Modifier.fillMaxSize().padding(padding)) {
                                        SettingsScreen(embedded = true).Content()
                                    }
                                }
                                desktopPlaylist != null -> {
                                    Box(Modifier.fillMaxSize().padding(padding)) {
                                        PlaylistDetailScreen(
                                            playlist = desktopPlaylist!!,
                                            embedded = true,
                                            onEmbeddedBack = { desktopPlaylist = null },
                                        ).Content()
                                    }
                                }
                                else -> {
                                    TabContent(tabs, visitedTabs, selectedIndex, Modifier.fillMaxSize().padding(padding))
                                }
                            }
                        }
                    }
                } else {
                    androidx.compose.material3.Scaffold(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        topBar = {
                            AppTopBar(
                                title = tabs[selectedIndex].label,
                                navigator = navigator,
                                scrollBehavior = scrollBehavior,
                                
                                onOpenSettings = { navigator?.push(SettingsScreen()) },
                            )
                        },
                        bottomBar = { AppNavigationBar(tabs, selectedIndex, selectTab) },
                        containerColor = Color.Transparent
                    ) { padding ->
                        TabContent(tabs, visitedTabs, selectedIndex, Modifier.fillMaxSize().padding(padding))
                    }
                }
            }

            if (expandProgress > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = expandProgress * 0.3f))
                )
            }

            // 全局 Snackbar（操作反馈）
            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(bottom = if (playbackState.currentTrack != null) 180.dp else 96.dp),
            ) { data ->
                androidx.compose.material3.Snackbar(
                    snackbarData = data,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }

            SharedTransitionLayout(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = isPlayerExpanded,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                    },
                    label = "PlayerTransition",
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                ) { isExpanded ->
                    if (isExpanded) {
                    if (expanded) {
                        DesktopPlayerScreen(
                            state = playbackState,
                            onBack = { isPlayerExpanded = false },
                            onTogglePlay = controller::togglePlayPause,
                            onSeek = controller::seekTo,
                            onSkipNext = controller::skipNext,
                            onSkipPrev = controller::skipPrevious,
                            onRepeat = {
                                controller.setRepeatMode(
                                    when (playbackState.repeatMode) {
                                        cp.player.core.playback.RepeatMode.OFF -> cp.player.core.playback.RepeatMode.ALL
                                        cp.player.core.playback.RepeatMode.ALL -> cp.player.core.playback.RepeatMode.ONE
                                        cp.player.core.playback.RepeatMode.ONE -> cp.player.core.playback.RepeatMode.OFF
                                    }
                                )
                            },
                            onShuffle = controller::toggleShuffle,
                            onLike = { scope.launch { controller.toggleFavorite() } },
                            onPlayAt = { idx -> scope.launch { controller.playAt(idx) } },
                        )
                    } else PlayerScreenContent(
                        state = playbackState,
                        animatedVisibilityScope = this@AnimatedContent,
                        onBack = { isPlayerExpanded = false },
                        onTogglePlay = controller::togglePlayPause,
                        onSeek = controller::seekTo,
                        onSkipNext = controller::skipNext,
                        onSkipPrev = controller::skipPrevious,
                        onRepeat = {
                            controller.setRepeatMode(
                                when (playbackState.repeatMode) {
                                    cp.player.core.playback.RepeatMode.OFF -> cp.player.core.playback.RepeatMode.ALL
                                    cp.player.core.playback.RepeatMode.ALL -> cp.player.core.playback.RepeatMode.ONE
                                    cp.player.core.playback.RepeatMode.ONE -> cp.player.core.playback.RepeatMode.OFF
                                }
                            )
                        },
                        onShuffle = controller::toggleShuffle,
                        onClearQueue = controller::clearQueue,
                        onPlayAt = { idx -> scope.launch { controller.playAt(idx) } },
                        onRemoveQueue = { idx -> scope.launch { controller.removeQueueItem(idx) } },
                        onMoveQueue = { from, to -> scope.launch { controller.moveQueueItem(from, to) } },
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        // 在底部渲染 MiniPlayer
                        if (playbackState.currentTrack != null) {
                            val bottomPadding = if (expanded) 24.dp else 104.dp // 增加与底栏的间距，提升视觉呼吸感
                            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = bottomPadding)) {
                                MiniPlayer(
                                    state = playbackState,
                                    animatedVisibilityScope = this@AnimatedContent,
                                    onClick = { isPlayerExpanded = true },
                                    onTogglePlay = controller::togglePlayPause,
                                    onSkipPrev = controller::skipPrevious,
                                    onSkipNext = controller::skipNext,
                                )
                            }
                        }
                    }
                }
            }
        } // End of SharedTransitionLayout
        } // End of CompositionLocalProvider
        } // End of BoxWithConstraints
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    title: String,
    navigator: cafe.adriel.voyager.navigator.Navigator?,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior? = null,
    onOpenSettings: () -> Unit = {},
    showBack: Boolean = false,
    onBack: () -> Unit = {},
) {
    val isDesktopExpanded = cp.player.app.ui.component.LocalIsExpanded.current
    val topBarInsets = if (isDesktopExpanded) WindowInsets.statusBars else TopAppBarDefaults.windowInsets
    val titleBar: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    if (isDesktopExpanded) {
        TopAppBar(
            navigationIcon = if (showBack) {
                {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            } else {
                {}
            },
            title = titleBar,
            actions = {
                val profile by AppModel.userProfileFlow.collectAsState()
                val avatarUrl = profile?.avatarUrl
                androidx.compose.material3.FilledIconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(end = 4.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "用户头像",
                            modifier = Modifier.size(24.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                        )
                    }
                }
            },
            windowInsets = topBarInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
    } else {
        androidx.compose.material3.LargeTopAppBar(
            navigationIcon = if (showBack) {
                {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            } else {
                {}
            },
            title = titleBar,
            actions = {
                val profile by AppModel.userProfileFlow.collectAsState()
                val avatarUrl = profile?.avatarUrl
                androidx.compose.material3.FilledIconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(end = 4.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "用户头像",
                            modifier = Modifier.size(24.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "设置",
                        )
                    }
                }
            },
            scrollBehavior = scrollBehavior,
            windowInsets = topBarInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
    }
}

@Composable
private fun TabContent(
    tabs: List<TabItem>,
    visitedTabs: List<Int>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val retainedIndices = visitedTabs.sorted()
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            retainedIndices.forEach { index ->
                Box(Modifier.fillMaxSize()) { tabs[index].screen.Content() }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.getOrNull(retainedIndices.indexOf(selectedIndex))?.placeRelative(0, 0)
        }
    }
}

@Composable
private fun AppNavigationBar(tabs: List<TabItem>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        tabs.forEachIndexed { index, tab ->
            val selected = selectedIndex == index
            // 选中图标「弹」一下 —— M3 Expressive 的导航反馈。
            // 指示器胶囊本身由 material3 内部驱动，这里只补图标这一层，
            // 两层叠起来才有「弹」的手感（只靠指示器会显得发闷）。
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.14f else 1f,
                animationSpec = cp.player.app.ui.theme.CpMotion.spatialFast(),
                label = "navIconScale$index",
            )
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(
                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                        tab.label,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    )
                },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun DesktopSidebar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    selectedPlaylist: PlaylistSummary?,
    onSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
) {
    val profile by AppModel.userProfileFlow.collectAsState()
    val homeModel = remember { cp.player.app.ui.model.HomeScreenModel() }
    val homeState by homeModel.state.collectAsState()
    Surface(
        modifier = Modifier.width(248.dp).fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
            Text("CPPlayer", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text(profile?.nickname ?: "音乐空间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.height(22.dp))
            // 歌单可能很多、窗口也可能被拖得很矮：中部列表单独滚动（桌面端带滚动条），
            // 设置入口钉在底部——原先是 Spacer(weight) 撑开，窗口一变矮设置项就被顶出可视区。
            LazyScrollColumn(Modifier.weight(1f)) {
                item {
                    Text("发现音乐", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                items(tabs.size) { index ->
                    val tab = tabs[index]
                    val selected = selectedIndex == index
                    androidx.compose.material3.NavigationDrawerItem(
                        label = { Text(tab.label) },
                        selected = selected,
                        onClick = { onSelect(index) },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, tab.label) },
                        // Expressive 侧边栏用**全圆角胶囊**，而不是 12dp 的方角块。
                        shape = cp.player.app.ui.theme.CpShapes.full,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                item {
                    Text("我的音乐", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp,))
                }
                item {
                    SidebarAction(Icons.Filled.FavoriteBorder, "我喜欢的音乐") {
                        homeState.likedPlaylist?.let(onOpenPlaylist) ?: onSelect(2)
                    }
                }
                item { SidebarAction(Icons.Filled.History, "最近播放") { onSelect(0) } }
                item { SidebarAction(Icons.Filled.Download, "下载管理") { onSelect(2) } }
                item { SidebarAction(Icons.Filled.MusicNote, "我的歌单") { onSelect(2) } }
                val sidebarPlaylists = homeState.userPlaylists.take(8)
                items(sidebarPlaylists.size) { index ->
                    val playlist = sidebarPlaylists[index]
                    SidebarAction(Icons.Filled.MusicNote, playlist.name, selected = selectedPlaylist?.id == playlist.id) { onOpenPlaylist(playlist) }
                }
            }
            SidebarAction(Icons.Filled.Settings, "设置", selected = false, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun SidebarAction(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class TabItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
