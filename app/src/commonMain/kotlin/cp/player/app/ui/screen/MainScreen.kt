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
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.AppModel
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.util.resized
import cp.player.core.music.PlaylistSummary

/**
 * 侧栏最多展示的歌单条数。
 *
 * 歌单可以有上百个，侧栏不是歌单管理器 —— 超出部分走「查看全部」落到「我的」页，
 * 那里才有完整的增删改。
 */
private const val SIDEBAR_PLAYLIST_LIMIT = 8

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
        // 宽屏右侧面板的当前形态。原本是「desktopPlaylist + desktopSettingsOpen」两个
        // 独立状态，再加「下载管理 / 最近播放」两个面板就会变成四份状态彼此清理 ——
        // 漏清一份，两个面板就会同时留在屏上。收敛成一个互斥量。
        var desktopPane by remember { mutableStateOf<DesktopPane>(DesktopPane.Tabs) }
        val selectTab: (Int) -> Unit = { index ->
            desktopPane = DesktopPane.Tabs
            if (index !in visitedTabs) visitedTabs.add(index)
            selectedIndex = index
        }
        val closeDesktopOverlay = { desktopPane = DesktopPane.Tabs }

        // 桌面自绘标题栏渲染在 Navigator **之上**，够不到这里的局部状态，所以标题栏上的
        // 「设置」走 DesktopShell 这条单向指令。消费后**立刻**置回 false —— 否则从设置面板
        // 切回其它面板时，会被残留的 true 再弹回设置页。协议说明见 DesktopShell 的 KDoc。
        val settingsRequested = cp.player.app.ui.util.DesktopShell.settingsRequested
        androidx.compose.runtime.LaunchedEffect(settingsRequested) {
            if (settingsRequested) {
                desktopPane = DesktopPane.Settings
                cp.player.app.ui.util.DesktopShell.settingsRequested = false
            }
        }

        // 标题栏全局搜索框投递的关键词：这里**只切 tab、不消费** —— 关键词要留给
        // SearchScreen 喂给它自己的 ScreenModel。两个消费者会互相抢，见 DesktopShell 的说明。
        val pendingSearchQuery = cp.player.app.ui.util.DesktopShell.pendingSearchQuery
        androidx.compose.runtime.LaunchedEffect(pendingSearchQuery) {
            if (!pendingSearchQuery.isNullOrBlank()) {
                desktopPane = DesktopPane.Tabs
                // 索引 1 对应上面 tabs 里的「搜索」，与构造顺序绑定。
                if (1 !in visitedTabs) visitedTabs.add(1)
                selectedIndex = 1
            }
        }

        // 当前页面的标题：桌面标题栏要显示它，而标题栏在 Navigator 之上、拿不到这里的局部状态，
        // 所以用 SideEffect 发布到 DesktopShell。同值写入不触发重组，每次重组都写一遍没有开销。
        val pageTitle = when (val pane = desktopPane) {
            DesktopPane.Tabs -> tabs[selectedIndex].label
            DesktopPane.Settings -> "设置"
            DesktopPane.Downloads -> "下载管理"
            DesktopPane.RecentPlays -> "最近播放"
            is DesktopPane.Playlist -> pane.playlist.name
        }
        androidx.compose.runtime.SideEffect {
            cp.player.app.ui.util.DesktopShell.pageTitle = pageTitle
            cp.player.app.ui.util.DesktopShell.pageCanGoBack = desktopPane != DesktopPane.Tabs
        }

        // 标题栏上的返回键 → 收起内嵌面板。消费后立刻置回 false，协议见 DesktopShell。
        val backRequested = cp.player.app.ui.util.DesktopShell.backRequested
        androidx.compose.runtime.LaunchedEffect(backRequested) {
            if (backRequested) {
                closeDesktopOverlay()
                cp.player.app.ui.util.DesktopShell.backRequested = false
            }
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
            val expanded = cp.player.app.ui.component.CpBreakpoints.isExpanded(maxWidth)

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
                            selectedPane = desktopPane,
                            onSelect = selectTab,
                            onOpenSettings = { desktopPane = DesktopPane.Settings },
                            onOpenPlaylist = {
                                selectedIndex = 2
                                desktopPane = DesktopPane.Playlist(it)
                            },
                            onOpenDownloads = { desktopPane = DesktopPane.Downloads },
                            onOpenRecentPlays = { desktopPane = DesktopPane.RecentPlays },
                            onOpenAllPlaylists = { selectTab(2) },
                        )
                        androidx.compose.material3.Scaffold(
                            modifier = Modifier.weight(1f).nestedScroll(scrollBehavior.nestedScrollConnection),
                            topBar = { AppTopBar(
                                 // 与发布给桌面标题栏的是同一个值（见上面的 pageTitle），
                                 // 桌面端整条顶栏会提前 return，这里只在没有窗口 chrome 时才用得上。
                                 title = pageTitle,
                                 navigator = navigator,
                                 scrollBehavior = null,
                                 showBack = desktopPane != DesktopPane.Tabs,
                                  onBack = closeDesktopOverlay,
                                  onOpenSettings = { desktopPane = DesktopPane.Settings },
                                  onOpenAccount = { navigator?.push(AccountScreen()) }) },
                            containerColor = Color.Transparent
                        ) { padding ->
                            // 桌面内嵌面板：跨淡入淡出切换，替代原来的瞬间硬切。
                            AnimatedContent(
                                targetState = desktopPane,
                                transitionSpec = {
                                    fadeIn(tween(240)) togetherWith fadeOut(tween(160))
                                },
                                label = "DesktopPane",
                                modifier = Modifier.fillMaxSize().padding(padding),
                            ) { current ->
                                when (current) {
                                    DesktopPane.Tabs ->
                                        TabContent(tabs, visitedTabs, selectedIndex, Modifier.fillMaxSize())
                                    DesktopPane.Settings ->
                                        SettingsScreen(embedded = true).Content()
                                    is DesktopPane.Playlist ->
                                        PlaylistDetailScreen(
                                            playlist = current.playlist,
                                            embedded = true,
                                            onEmbeddedBack = closeDesktopOverlay,
                                        ).Content()
                                    DesktopPane.Downloads ->
                                        DownloadsScreen().Content()
                                    DesktopPane.RecentPlays ->
                                        RecentPlaysScreen(embedded = true).Content()
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
                                onOpenAccount = { navigator?.push(AccountScreen()) },
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
                    shape = MaterialTheme.shapes.large,
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
                        // 在底部渲染 MiniPlayer。出现时淡入（约 150ms），让封面飞行有柔和的承接；
                        // 消失时 currentTrack 已被清空、内容无法再渲染，保持即时移除。
                        val miniAlpha by animateFloatAsState(
                            targetValue = if (playbackState.currentTrack != null) 1f else 0f,
                            animationSpec = cp.player.app.ui.theme.CpMotion.effectsFast(),
                            label = "miniPlayerAlpha",
                        )
                        if (playbackState.currentTrack != null) {
                            val bottomPadding = if (expanded) 24.dp else 104.dp // 增加与底栏的间距，提升视觉呼吸感
                            Box(
                                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                    .padding(bottom = bottomPadding)
                                    .graphicsLayer { alpha = miniAlpha },
                            ) {
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
    onOpenAccount: () -> Unit = {},
    showBack: Boolean = false,
    onBack: () -> Unit = {},
) {
    // 桌面自绘标题栏已经承担了「页面标题 + 返回 + 账号 / 设置」四件事（见 DesktopTitleBar），
    // 这里整条让位 —— 否则窗口顶部会叠两条 chrome（44dp 标题栏 + 64dp 顶栏），而顶栏里其实
    // 什么都不剩。判据见 LocalWindowChromeActive：按「标题栏是否接管」而不是按平台，
    // 因为展开态分支的条件是宽度 ≥840dp，宽屏 Android 平板走的也是这套。
    if (cp.player.app.ui.component.LocalWindowChromeActive.current) return

    val isDesktopExpanded = cp.player.app.ui.component.LocalIsExpanded.current
    val topBarInsets = if (isDesktopExpanded) WindowInsets.statusBars else TopAppBarDefaults.windowInsets
    val titleBar: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    val navigationIcon: @Composable () -> Unit = if (showBack) {
        {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            }
        }
    } else {
        {}
    }
    // 账号 / 设置两个入口在桌面与手机两套 TopAppBar 里原本是**逐字重复的两份**（约 100 行）。
    // 两套唯一的真实差别只有「普通 TopAppBar vs LargeTopAppBar」，所以这里把它抽成一份，
    // 否则改一次按钮样式要记得改两处，迟早只改到一处。
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        // 这里刻意**不再**判 LocalWindowChromeActive：桌面端整条 AppTopBar 已经在函数开头
        // 提前 return 了（窗口 chrome 接管了标题与这两个入口），能走到这里就说明没有 chrome。
        // 两处都判会留下一条永远走不到的分支。
        val profile by AppModel.userProfileFlow.collectAsState()
        val avatarUrl = profile?.avatarUrl
        // 账号入口（有头像显示头像）：进「账号与登录」
        androidx.compose.material3.FilledIconButton(
            onClick = onOpenAccount,
            modifier = Modifier.padding(end = 4.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "账号",
                    modifier = Modifier.size(24.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = "账号",
                )
            }
        }
        androidx.compose.material3.FilledIconButton(
            onClick = onOpenSettings,
            modifier = Modifier.padding(end = 4.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
            )
        }
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    if (isDesktopExpanded) {
        TopAppBar(
            navigationIcon = navigationIcon,
            title = titleBar,
            actions = actions,
            windowInsets = topBarInsets,
            colors = colors,
        )
    } else {
        androidx.compose.material3.LargeTopAppBar(
            navigationIcon = navigationIcon,
            title = titleBar,
            actions = actions,
            scrollBehavior = scrollBehavior,
            windowInsets = topBarInsets,
            colors = colors,
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
        val haptics = cp.player.app.ui.feedback.LocalCpHaptics.current
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
                onClick = {
                    // 只在真的换了 tab 时给轻点：重复点当前 tab 没有状态变化，
                    // 给反馈反而是撒谎。
                    if (!selected) haptics.perform(cp.player.app.ui.feedback.CpHaptic.Tick)
                    onSelect(index)
                },
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
    selectedPane: DesktopPane,
    onSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenRecentPlays: () -> Unit,
    onOpenAllPlaylists: () -> Unit,
) {
    val profile by AppModel.userProfileFlow.collectAsState()
    // 侧栏只用到 likedPlaylist / userPlaylists，关掉发现内容的加载：
    // 否则首页那一屏七八个并发请求会被整份重打一遍，侧栏还跟着首页一起刷。
    val homeModel = remember { cp.player.app.ui.model.HomeScreenModel(loadDiscovery = false) }
    val homeState by homeModel.state.collectAsState()
    // 进行中的下载条数：直接收集后端 StateFlow，不另建 ScreenModel ——
    // 侧栏不是 Screen，没有 ScreenModel 的生命周期可以托管它。
    val downloadTasks by AppModel.downloads.tasksFlow.collectAsState()
    val activeDownloadCount = downloadTasks.count {
        it.status == cp.player.core.model.DownloadStatus.PENDING ||
            it.status == cp.player.core.model.DownloadStatus.DOWNLOADING
    }
    // 收藏夹（「xx喜欢的音乐」）已经有独立入口，不再以歌单身份重复出现。
    val allPlaylists = homeState.sidebarPlaylists
    val shownPlaylists = allPlaylists.take(SIDEBAR_PLAYLIST_LIMIT)
    val selectedPlaylistId = (selectedPane as? DesktopPane.Playlist)?.playlist?.id

    Surface(
        modifier = Modifier.width(248.dp).fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
            SidebarHeader(profileName = profile?.nickname, avatarUrl = profile?.avatarUrl)
            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
            // 歌单可能很多、窗口也可能被拖得很矮：中部列表单独滚动（桌面端带滚动条），
            // 设置入口钉在底部——原先是 Spacer(weight) 撑开，窗口一变矮设置项就被顶出可视区。
            LazyScrollColumn(Modifier.weight(1f)) {
                item { SidebarSection("发现音乐") }
                items(tabs.size) { index ->
                    val tab = tabs[index]
                    // 打开内嵌面板（设置 / 歌单 / 下载 …）时 tab 不再高亮：
                    // 否则「右侧已经是下载管理了，左边还亮着『我的』」看起来像没切过去。
                    val selected = selectedIndex == index && selectedPane is DesktopPane.Tabs
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

                // 功能区：图标装在圆角方片里 —— 与下方歌单区的封面缩略图形成对照。
                item { SidebarSection("我的音乐") }
                item {
                    SidebarAction(
                        icon = Icons.Filled.FavoriteBorder,
                        label = "我喜欢的音乐",
                        selected = selectedPlaylistId != null &&
                            selectedPlaylistId == homeState.likedPlaylist?.id,
                        onClick = {
                            homeState.likedPlaylist?.let(onOpenPlaylist) ?: onSelect(2)
                        },
                    )
                }
                item {
                    SidebarAction(
                        icon = Icons.Filled.History,
                        label = "最近播放",
                        selected = selectedPane is DesktopPane.RecentPlays,
                        onClick = onOpenRecentPlays,
                    )
                }
                item {
                    SidebarAction(
                        icon = Icons.Filled.Download,
                        label = "下载管理",
                        badge = activeDownloadCount,
                        selected = selectedPane is DesktopPane.Downloads,
                        onClick = onOpenDownloads,
                    )
                }

                if (shownPlaylists.isNotEmpty()) {
                    item { SidebarSection("我的歌单") }
                    items(shownPlaylists.size) { index ->
                        val playlist = shownPlaylists[index]
                        SidebarPlaylistRow(
                            playlist = playlist,
                            selected = selectedPlaylistId == playlist.id,
                            onClick = { onOpenPlaylist(playlist) },
                        )
                    }
                }
                // 只在真的还有更多歌单时才给「查看全部」：总共 3 个歌单还挂一条
                // 「查看全部」是纯粹的噪音。
                if (allPlaylists.size > shownPlaylists.size) {
                    item {
                        SidebarAction(
                            icon = Icons.Filled.LibraryMusic,
                            label = "查看全部 ${allPlaylists.size} 个歌单",
                            selected = false,
                            onClick = onOpenAllPlaylists,
                        )
                    }
                }
            }
            // 桌面自绘标题栏接管后，这里不再重复放一个「设置」。
            // 判据见 LocalWindowChromeActive：按宽度而不是按平台，宽屏平板必须保留这一项。
            if (!cp.player.app.ui.component.LocalWindowChromeActive.current) {
                SidebarAction(
                    icon = Icons.Filled.Settings,
                    label = "设置",
                    selected = selectedPane is DesktopPane.Settings,
                    onClick = onOpenSettings,
                )
            }
        }
    }
}

/** 侧栏分组标题。 */
@Composable
private fun SidebarSection(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 侧栏顶部：头像 + 品牌 + 昵称。 */
@Composable
private fun SidebarHeader(profileName: String?, avatarUrl: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl.resized(96),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(CircleShape),
            )
        } else {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "CPPlayer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                profileName ?: "音乐空间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 侧栏**功能项**：图标装在一枚圆角方片里 + 文字标签。
 *
 * 刻意与 [SidebarPlaylistRow] 长成两套样子：功能一律「方片里的图标」，
 * 歌单一律「封面缩略图」。同一份列表里有两种语义，就必须有两种长相 ——
 * 否则用户只能靠读文字分辨「点下去是去一个功能页，还是去一个歌单」。
 *
 * @param badge 尾部计数（>0 才画），目前只有「下载管理」用它显示进行中的任务数。
 */
@Composable
private fun SidebarAction(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int = 0,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(cp.player.app.ui.theme.CpShapes.full)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).padding(8.dp),
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge > 0) {
            Surface(
                shape = cp.player.app.ui.theme.CpShapes.full,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 侧栏**歌单项**：封面缩略图 + 歌单名 + 曲目数。
 *
 * 不复用 [SidebarAction]：歌单是「内容」不是「功能」，用封面而不是图标，
 * 名称也用 onSurface（比功能项的 onSurfaceVariant 高一档）——它才是这一区的主体。
 */
@Composable
private fun SidebarPlaylistRow(
    playlist: PlaylistSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(cp.player.app.ui.theme.CpShapes.full)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        // 占位块常驻最底层、封面叠在上面：写成「有 URL 才画封面」的话，
        // 图没到之前这里会是一块空的方角壳。
        Box(
            Modifier.size(38.dp).clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            if (!playlist.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.coverUrl.resized(96),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${playlist.trackCount} 首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 临时：侧栏条目的离屏渲染入口（渲完必删）。
 *
 * 只为把 [SidebarHeader] / [SidebarSection] / [SidebarAction] / [SidebarPlaylistRow]
 * 暴露给 desktopTest —— 它们都是 private，而整条 [DesktopSidebar] 依赖 AppModel，
 * 在测试 JVM 里起不来。
 */
@Composable
internal fun SidebarRowsPreview() {
    Column(Modifier.fillMaxWidth()) {
        SidebarHeader(profileName = "听歌的人", avatarUrl = null)
        SidebarSection("我的音乐")
        SidebarAction(Icons.Filled.FavoriteBorder, "我喜欢的音乐", selected = false, onClick = {})
        SidebarAction(Icons.Filled.History, "最近播放", selected = true, onClick = {})
        SidebarAction(Icons.Filled.Download, "下载管理", badge = 3, selected = false, onClick = {})
        SidebarSection("我的歌单")
        SidebarPlaylistRow(
            playlist = PlaylistSummary(1L, "深夜通勤", null, 128, null),
            selected = false,
            onClick = {},
        )
        SidebarPlaylistRow(
            playlist = PlaylistSummary(2L, "2026 年度最爱的一百首歌单合集", null, 100, null),
            selected = true,
            onClick = {},
        )
        SidebarPlaylistRow(
            playlist = PlaylistSummary(3L, "跑步 BPM 170", null, 42, null),
            selected = false,
            onClick = {},
        )
    }
}

private data class TabItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/**
 * 桌面宽屏下右侧面板的形态（tabs / 设置 / 歌单 / 下载管理 / 最近播放）。
 *
 * 互斥：同一时刻只有一个面板，切换即整体替换 —— 否则「点开下载管理再点歌单」会
 * 留下两个面板叠在一起。
 */
private sealed interface DesktopPane {
    data object Tabs : DesktopPane
    data object Settings : DesktopPane
    data object Downloads : DesktopPane
    data object RecentPlays : DesktopPane
    data class Playlist(val playlist: PlaylistSummary) : DesktopPane
}
