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
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.MutableIntState
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import cp.player.app.AppModel
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.util.next
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
        // 选中索引的状态本体要交给 [DesktopContentRootScreen]（内嵌 Navigator 的根页）
        // 持引用，所以这里留一份实例、再用 `by` 委托出读写别名。
        val selectedIndexState = rememberSaveable { mutableIntStateOf(0) }
        var selectedIndex by selectedIndexState
        val visitedTabs = remember { mutableStateListOf(selectedIndex) }
        val navigator = LocalNavigator.current
        val tabs = remember {
            listOf(
                TabItem(HomeScreen(), "首页", Icons.Filled.Home, Icons.Outlined.Home),
                TabItem(SearchScreen(), "搜索", Icons.Filled.Search, Icons.Outlined.Search),
                TabItem(LibraryScreen(), "我的", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
            )
        }
        // 内容区的**内嵌 Navigator**（宽屏才有，见展开态分支）：详情页（专辑 / 歌手 / 歌单 /
        // 搜索结果 / 账号 …）都在这条栈里导航，左侧导航栏因此常驻。这里持有引用是为了三件事：
        // ① 发布 pageCanGoBack；② 消费标题栏的返回 / 账号 / 搜索指令；③ 切 tab 时弹回栈根。
        // 窄屏分支不装配内嵌 Navigator，此引用恒为 null，所有 `?.` 都是空操作。
        var contentNavigator by remember { mutableStateOf<Navigator?>(null) }
        val contentRootScreen = remember(tabs) {
            DesktopContentRootScreen(tabs, visitedTabs, selectedIndexState)
        }
        val playbackState by AppModel.playback.state.collectAsState()
        val controller = AppModel.playback
        // 宽屏 / 窄屏两套播放页共用同一个循环切换。原先这段 when 在两处各写了一份，
        // 现在收敛到 RepeatMode.next()（见 ui/util/RepeatModeCycle.kt）。
        val cycleRepeat = { controller.setRepeatMode(playbackState.repeatMode.next()) }
        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
        // 宽屏右侧面板的当前形态。原本是「desktopPlaylist + desktopSettingsOpen」两个
        // 独立状态，再加「下载管理 / 最近播放」两个面板就会变成四份状态彼此清理 ——
        // 漏清一份，两个面板就会同时留在屏上。收敛成一个互斥量。
        //
        // 注意「面板」与「内容区路由」是两层：desktopPane 只管盖在内容层上的**面板**；
        // 详情页路由走内嵌 Navigator，不经过这里。
        var desktopPane by remember { mutableStateOf<DesktopPane>(DesktopPane.Tabs) }
        val selectTab: (Int) -> Unit = { index ->
            desktopPane = DesktopPane.Tabs
            // 内容区若有内嵌详情页（专辑 / 搜索结果 …），切 tab 前先弹回栈根 ——
            // 否则目标 tab 被压在栈下，看起来「点了没反应」。
            contentNavigator?.popUntilRoot()
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

        // 标题栏上的「消息」：与 settingsRequested 逐字同一套协议（入口在窗口 chrome 上，
        // 面板开关在这里）。消费时顺手刷一次未读数 —— 用户点它就是要看有没有新消息。
        val messagesRequested = cp.player.app.ui.util.DesktopShell.messagesRequested
        androidx.compose.runtime.LaunchedEffect(messagesRequested) {
            if (messagesRequested) {
                desktopPane = DesktopPane.Messages
                AppModel.refreshUnreadMessages()
                cp.player.app.ui.util.DesktopShell.messagesRequested = false
            }
        }

        // 标题栏上的「账号」：同一来路，但消费动作是**内容区路由** —— push 进内嵌
        // Navigator（侧栏保留、返回回到原处），不是切面板。账号页内部的二级跳转
        // （我的主页 / 消息 / 切换音源）由 `LocalNavigator` 自动落进同一条栈。
        // 窄屏没有内嵌 Navigator，回落到根 Navigator（手机顶栏入口本来也是这么走的）。
        val accountRequested = cp.player.app.ui.util.DesktopShell.accountRequested
        androidx.compose.runtime.LaunchedEffect(accountRequested) {
            if (accountRequested) {
                cp.player.app.ui.util.DesktopShell.accountRequested = false
                desktopPane = DesktopPane.Tabs
                val nav = contentNavigator
                if (nav != null) nav.push(AccountScreen()) else navigator?.push(AccountScreen())
            }
        }

        // 标题栏全局搜索框投递的关键词：这里**只切 tab、不消费** —— 关键词要留给
        // SearchScreen 喂给它自己的 ScreenModel。两个消费者会互相抢，见 DesktopShell 的说明。
        val pendingSearchQuery = cp.player.app.ui.util.DesktopShell.pendingSearchQuery
        androidx.compose.runtime.LaunchedEffect(pendingSearchQuery) {
            if (!pendingSearchQuery.isNullOrBlank()) {
                desktopPane = DesktopPane.Tabs
                // 与 selectTab 同理：先把内嵌详情页弹回栈根，否则搜索 tab 被压在栈下。
                contentNavigator?.popUntilRoot()
                // 索引 1 对应上面 tabs 里的「搜索」，与构造顺序绑定。
                if (1 !in visitedTabs) visitedTabs.add(1)
                selectedIndex = 1
            }
        }

        // 内嵌内容栈的深度：**读在组合里**（Navigator 的 items 是快照列表），push / pop
        // 都会驱动本层重组并重新发布下面的 pageCanGoBack —— 标题栏的返回键因此能跟着
        // 「内容区是否有详情页」走，而不只是跟着面板开关走。
        val contentNavSize = contentNavigator?.size ?: 1

        // 当前页面的标题：桌面标题栏要显示它，而标题栏在 Navigator 之上、拿不到这里的局部状态，
        // 所以用 SideEffect 发布到 DesktopShell。同值写入不触发重组，每次重组都写一遍没有开销。
        val pageTitle = when (val pane = desktopPane) {
            DesktopPane.Tabs -> tabs[selectedIndex].label
            DesktopPane.Settings -> "设置"
            DesktopPane.Downloads -> "下载管理"
            DesktopPane.RecentPlays -> "最近播放"
            DesktopPane.Messages -> "消息"
            is DesktopPane.Playlist -> pane.playlist.name
        }
        androidx.compose.runtime.SideEffect {
            cp.player.app.ui.util.DesktopShell.pageTitle = pageTitle
            // 可返回 = 面板开着（可收起）**或**内容区内嵌栈有详情页（可出栈）。
            // 内嵌栈里的路由页自己会经 `CpRouteScaffold` 声明 DesktopRouteTitle 盖住标题，
            // 但「有没有返回键」必须由这里给出 —— 标题栏只读 pageCanGoBack。
            cp.player.app.ui.util.DesktopShell.pageCanGoBack =
                desktopPane != DesktopPane.Tabs || contentNavSize > 1
        }

        // 标题栏上的返回键 → 先弹内嵌内容栈，弹不掉了再收内嵌面板。
        // 消费后立刻置回 false，协议见 DesktopShell。
        val backRequested = cp.player.app.ui.util.DesktopShell.backRequested
        androidx.compose.runtime.LaunchedEffect(backRequested) {
            if (backRequested) {
                val nav = contentNavigator
                if (desktopPane == DesktopPane.Tabs && nav != null && nav.size > 1) {
                    nav.pop()
                } else {
                    closeDesktopOverlay()
                }
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
                            onOpenMessages = {
                                desktopPane = DesktopPane.Messages
                                AppModel.refreshUnreadMessages()
                            },
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
                                  // 宽屏平板（无窗口 chrome）的账号入口与桌面标题栏语义一致：
                                  // 走内容区路由，侧栏保留。桌面 chrome 接管时整条顶栏不可见，
                                  // 这条分支只有平板用得上。
                                  onOpenAccount = {
                                      desktopPane = DesktopPane.Tabs
                                      val nav = contentNavigator
                                      if (nav != null) nav.push(AccountScreen()) else navigator?.push(AccountScreen())
                                  },
                                  // 内容区有详情页时收起壳层顶栏：路由页自带 AppScaffold（平板
                                  // 无 chrome），不收就是双顶栏。桌面端 chrome 接管，无此问题。
                                  hide = contentNavSize > 1, ) },
                            containerColor = Color.Transparent
                        ) { padding ->
                            // 两个层次，都必须常驻组合：
                            //
                            // 内容层：**内嵌 Navigator**。详情页（专辑 / 歌手 / 歌单 / 搜索结果 /
                            // 账号 …）在这条栈里导航 —— 页面读 `LocalNavigator` 拿到最近的它，
                            // push 全落内容区，左侧导航栏因此**不会**随着 push 消失。
                            // 它常驻还有第二个理由：面板层盖上来时（设置 / 消息 …）内嵌栈
                            // 原样留在下面，面板一收，「返回还能回到原处」自动成立。
                            //
                            // 面板层：desktopPane 非 Tabs 时盖在内容层上，跨淡入淡出切换。
                            // Tabs 时是透明占位 —— 空 Box 没有 pointer input，点击穿透到内容层。
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                Navigator(contentRootScreen) { nav ->
                                    // 捕获内嵌 Navigator 引用（见上）—— 供返回链 / 指令消费 /
                                    // pageCanGoBack 发布使用。Navigator 常驻组合，此引用全程新鲜。
                                    androidx.compose.runtime.SideEffect { contentNavigator = nav }
                                    ScreenTransition(
                                        navigator = nav,
                                        transition = {
                                            // 与根 Navigator（App.kt）同一套动效约定：歌单详情
                                            // fade（配合封面飞行器），其余按 Push/Pop 方向 slide。
                                            if (targetState is PlaylistDetailScreen ||
                                                targetState is HomeGeneratedPlaylistScreen
                                            ) {
                                                fadeIn(tween(300)) togetherWith fadeOut(tween(220))
                                            } else {
                                                val spec = spring<IntOffset>(
                                                    stiffness = 400f,
                                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                                )
                                                if (nav.lastEvent == StackEvent.Pop) {
                                                    slideInHorizontally(spec) { -it } togetherWith
                                                        slideOutHorizontally(spec) { it }
                                                } else {
                                                    slideInHorizontally(spec) { it } togetherWith
                                                        slideOutHorizontally(spec) { -it }
                                                }
                                            }
                                        },
                                    )
                                }
                                AnimatedContent(
                                    targetState = desktopPane,
                                    transitionSpec = {
                                        fadeIn(tween(240)) togetherWith fadeOut(tween(160))
                                    },
                                    label = "DesktopPane",
                                    modifier = Modifier.fillMaxSize(),
                                ) { current ->
                                    // 面板层两种形态，两种都必须「完整」：
                                    // Tabs —— 透明占位，没有指针处理器，点击穿透到内容层（有意）。
                                    // 其余 —— ① 必须自画**不透明背景**：面板内容（设置表单 /
                                    // 消息列表 …）从来不画自己的底色，改造前靠「when 分支替换、
                                    // 屏上只有我」成立；现在面板与内容层是叠放关系，不画就把
                                    // 底下的 tab 页透出来了。② 必须**吞掉指针事件**：Compose 的
                                    // hit-test 只命中带指针处理器的节点，纯背景挡不住点击 ——
                                    // 不拦的话面板空白处一点，底下首页的卡片被点开。
                                    if (current == DesktopPane.Tabs) {
                                        Box(Modifier.fillMaxSize())
                                    } else {
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(MaterialTheme.colorScheme.background)
                                        ) {
                                            // 输入黑洞垫底：盖住内容层，挡住穿透点击。
                                            // ⚠️ 必须是面板正文的**兄弟**（垫在同一 Box 最底下），
                                            // 不能挂在正文祖先上 —— 后者的消费次序会连自己的
                                            // 交互一起吞掉（第一次实现翻的车，见 blockClicksThrough）。
                                            Box(Modifier.matchParentSize().blockClicksThrough())
                                            when (current) {
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
                                                DesktopPane.Messages ->
                                                    MessagesPane()
                                                DesktopPane.Tabs -> Unit
                                            }
                                        }
                                    }
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
                        // 播放页展开期间整屏拦截穿透点击：变暗层只有背景没有指针处理器，
                        // 播放页的空白处也一样（背景不算 hit-test 目标）——不拦的话，
                        // 点播放页没按钮的地方，底下壳层的歌单/卡片会被点开。
                        // 这里挂在这层修饰符上是安全的：它没有任何子内容（播放页是
                        // 叠在它上方的兄弟），不会误伤 —— 铁律见 blockClicksThrough 的 KDoc。
                        .blockClicksThrough()
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
                            onRepeat = cycleRepeat,
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
                        onRepeat = cycleRepeat,
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
    hide: Boolean = false,
) {    // 桌面自绘标题栏已经承担了「页面标题 + 返回 + 账号 / 设置」四件事（见 DesktopTitleBar），
    // 这里整条让位 —— 否则窗口顶部会叠两条 chrome（44dp 标题栏 + 64dp 顶栏），而顶栏里其实
    // 什么都不剩。判据见 LocalWindowChromeActive：按「标题栏是否接管」而不是按平台，
    // 因为展开态分支的条件是宽度 ≥840dp，宽屏 Android 平板走的也是这套。
    //
    // `hide`：内容区有内嵌详情页（平板，无 chrome）时也整条让位 —— 路由页自带 AppScaffold，
    // 不让位就是双顶栏。桌面端 chrome 判据已提前 return，此参数只对平板生效。
    if (cp.player.app.ui.component.LocalWindowChromeActive.current || hide) return

    val isDesktopExpanded = cp.player.app.ui.component.LocalIsExpanded.current
    val topBarInsets = if (isDesktopExpanded) WindowInsets.statusBars else TopAppBarDefaults.windowInsets
    val titleBar: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    val navigationIcon: @Composable () -> Unit = if (showBack) {
        { CpBackButton(onClick = onBack) }
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
        // 消息入口：桌面端由窗口标题栏承担，这里只在**没有窗口 chrome**（手机 / 平板）时出现。
        // ⚠️ 刻意**不带未读角标**（2026-10-02 与标题栏一并去掉）—— 理由见
        // `DesktopTitleBar.MessageSlot` 的 KDoc。数据链路保留，只是不再显示。
        androidx.compose.material3.FilledIconButton(
            onClick = {
                navigator?.push(MessagesScreen())
                AppModel.refreshUnreadMessages()
            },
            modifier = Modifier.padding(end = 4.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Message,
                contentDescription = "消息",
            )
        }
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

/**
 * 「输入黑洞」：挡住穿透到**下方兄弟子树**的点击与滚动。
 *
 * ⚠️ 两个使用铁律（都来自真实翻车，见 2026-10-02 工作日志）：
 *
 * 1. **只能挂在「没有任何可交互后代」的节点上**（自己、或包一层全屏空 Box 当兄弟）。
 *    直接挂在带可交互内容的祖先上，事件消费次序会把后代的手势一起吞掉 ——
 *    第一次实现就因此把整个设置面板点死了。
 * 2. 实现用 [detectTapGestures] 而不是手写 `awaitPointerEvent + consume` 循环：
 *    它对 down **立即 consume** —— 下方所有要求「事件未被消费」的手势检测
 *    （clickable / scrollable / 拖拽 …）在第一条事件就判死，move/up 根本轮不到；
 *    而位于它**上方**的兄弟子树（面板正文、播放页）不受影响 ——
 *    这正是 Material3 弹层 scrim 挡住后面内容的同款机制。
 */
private fun Modifier.blockClicksThrough(): Modifier = pointerInput(Unit) {
    detectTapGestures { }
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
    onOpenMessages: () -> Unit,
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
    // ⚠️ 侧栏**不再订阅** `unreadMessagesFlow`（2026-10-02 去红点后它一个消费者都没有了）。
    // 留着会白白让整个侧栏跟着未读数重组，而它什么都不显示 —— 删掉是对的。
    // ⚠️ 「设置 / 消息」两个入口是否由侧栏承担，必须在这里读一次。
    // CompositionLocal 的 `.current` 是一次 composable 调用，而 `LazyScrollColumn` 的
    // content builder 不是 @Composable 作用域 —— 直接写在里面编译不过
    // （`@Composable invocations can only happen from the context of a @Composable`）。
    val chromeActive = cp.player.app.ui.component.LocalWindowChromeActive.current
    val showMessagesEntry = !chromeActive
    val showSettingsEntry = !chromeActive
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
                // 「消息」不再放这里：它已经搬到窗口标题栏（与「设置」同一处境 ——
                // 全局入口在窗口 chrome 上，侧栏留的是内容入口）。判据用
                // `LocalWindowChromeActive`，宽屏平板上标题栏不存在，这一项必须还在。
                //
                // ⚠️ 读取必须提到 `LazyScrollColumn` 的 builder **外面**（见函数开头的
                // `showSettingsEntry`）：`CompositionLocal.current` 是一次 composable 调用，
                // 而 lazy 的 content builder 不是 @Composable 作用域，写进去直接编译不过。
                if (showMessagesEntry) {
                    item {
                        SidebarAction(
                            icon = Icons.AutoMirrored.Filled.Message,
                            label = "消息",
                            // 不传 badge：与窗口标题栏 / 顶栏同一条决定（2026-10-02 去红点）。
                            // `SidebarAction` 的 badge 是「>0 才画」，不传即 0，等于关掉。
                            selected = selectedPane is DesktopPane.Messages,
                            onClick = onOpenMessages,
                        )
                    }
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
            if (showSettingsEntry) {
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
 *
 * 注意与**内容区路由**是两层：详情页（专辑 / 歌手 / 搜索结果 / 账号 …）不走这里，
 * 它们 push 进内容区的内嵌 Navigator（见 [DesktopContentRootScreen]），侧栏不消失、
 * 返回回到原处。这里只管「侧栏有对应入口的」目的地。
 */
private sealed interface DesktopPane {
    data object Tabs : DesktopPane
    data object Settings : DesktopPane
    data object Downloads : DesktopPane
    data object RecentPlays : DesktopPane
    data object Messages : DesktopPane
    data class Playlist(val playlist: PlaylistSummary) : DesktopPane
}

/**
 * 内容区内嵌 Navigator 的**根页**：三个 tab 的宿主（[TabContent]）。
 *
 * 持有的全是宿主（`MainScreen`）传进来的**状态引用**—— tab 列表、已访问集合、选中索引的
 * 状态本体。自己不拥有状态：切 tab 只改 [selectedIndexState]，根页实例不变、内嵌栈的根
 * 保持稳定，不会因为 tab 切换就把整条栈重置。
 *
 * tab 页面（首页 / 搜索 / 曲库）从 `LocalNavigator` 读到的就是内嵌 Navigator 本身，
 * 它们的一切 `push`（专辑详情、歌手页、搜索结果 …）自然落进内容区 —— 这正是
 * 「大部分操作保留左侧导航栏」的实现机制，各页面**零改动**。
 */
private class DesktopContentRootScreen(
    private val tabs: List<TabItem>,
    private val visitedTabs: SnapshotStateList<Int>,
    private val selectedIndexState: MutableIntState,
) : Screen {
    @Composable
    override fun Content() {
        TabContent(tabs, visitedTabs, selectedIndexState.intValue, Modifier.fillMaxSize())
    }
}
