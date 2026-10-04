package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import cp.player.app.ui.theme.CpMotion
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import cp.player.app.AppModel
import cp.player.app.platform.shareText
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.component.CpBackButton
import cp.player.app.ui.component.CpContextMenu
import cp.player.app.ui.component.CpContextMenuItem
import cp.player.app.ui.component.CpContextMenuSeparator
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.observeBottomBarDrag
import cp.player.app.ui.component.playlistShareText
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

/**
 * 三个主 tab 的 Screen 实例：**会话级稳定，整个 app 生命周期只建一次**。
 *
 * ⚠️ 绝不能放回 `MainScreen.Content()` 的 `remember { }`：MainScreen 被根栈 push 覆盖
 * （手机端进专辑 / 设置 / 账号 …）时整棵组合被丢弃、remember 槽位全丢，返回时会**新建**
 * Screen 实例。而 `rememberScreenModel` 按**实例**键控模型（ScreenLifecycleStore）——
 * 实例一换，首页 / 曲库的模型连同数据全部重建：返回瞬间先闪 loading、整页重新发请求，
 * 等数据回来结构才稳定；这段窗口里 saveable 快照（含各 tab 滚动位置）的恢复时机被打乱，
 * 数据量一变还会让恢复值落空 —— 「从详情页返回后界面回到顶部」的主因之一。
 * 实例稳定后，模型与数据跨 push/pop 存活，返回即出内容、结构零翻转，快照精确恢复。
 */
private val MAIN_TABS = listOf(
    TabItem(HomeScreen(), "首页", Icons.Filled.Home, Icons.Outlined.Home),
    TabItem(SearchScreen(), "搜索", Icons.Filled.Search, Icons.Outlined.Search),
    TabItem(LibraryScreen(), "我的", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
)

/** Responsive application shell for the four primary destinations. */
class MainScreen : Screen {
    @OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        // 选中索引的状态本体要交给 [DesktopContentRootScreen]（内嵌 Navigator 的根页）
        // 持引用，所以这里留一份实例、再用 `by` 委托出读写别名。
        val selectedIndexState = rememberSaveable { mutableIntStateOf(0) }
        var selectedIndex by selectedIndexState
        // ⚠️ 必须是 saveable：MainScreen 被根栈 push 覆盖（手机端进专辑 / 设置 / 账号 …）
        // 时整棵组合被丢弃，非 saveable 的 remember 会把「访问过哪些 tab」清回只剩当前页 ——
        // 返回时其他 tab 不再参与组合，它们的滚动状态只能靠 saveable 快照**懒恢复**
        // （等下次切过去才消费），而 tab 页的模型随实例重建会重载数据，结构一旦翻转
        // 懒恢复就落空 —— 「从详情页返回、切个 tab 就回到顶部」即由此而来。
        // 改为 saveable 后，访问过的 tab 在返回的同一帧全部恢复组合，
        // 与离开时的结构完全一致，快照精确命中。
        val visitedTabs = rememberSaveable(
            saver = listSaver(
                save = { it.toList() },
                restore = { mutableStateListOf<Int>().apply { addAll(it) } },
            ),
        ) { mutableStateListOf(selectedIndex) }
        val navigator = LocalNavigator.current
        val tabs = MAIN_TABS
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
        // 消息面板右栏是否已经打开了某个会话（= 屏幕底部多了一条固定输入栏）。
        // 由 `MessagesPane` 上报：小播放器与输入栏抢同一块底部空间时让位给输入栏 ——
        // 与窄屏 `ChatScreen` 同一条规则（那条在 `App.kt` 的全局宿主里判
        // `navigator.lastItem`，桌面宽屏的对话在面板右栏里，只有这里知道）。
        var messagesChatOpen by remember { mutableStateOf(false) }
        // 面板一旦切走就**立刻**复位「对话已打开」信号（小播放器让位）——
        // MessagesPane 自己的 DisposableEffect 要等 AnimatedContent 淡出（160ms）才 onDispose，
        // 那期间小播放器缺席、表现为收起消息面板后的一次闪动。这里在 desktopPane 变化的同一帧复位。
        androidx.compose.runtime.LaunchedEffect(desktopPane) {
            if (desktopPane != DesktopPane.Messages) messagesChatOpen = false
        }
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

        // 播放页展开态的返回处理器**刻意不在这里注册**：桌面 `DesktopBackDispatcher` 是
        // 「后注册优先」，放在 Content 顶部会排到最底层 —— 宽屏同时开着面板 / 内嵌详情页
        // 并展开播放页时，Esc 会先退**被播放页盖住**的层级，与视觉层级相反。
        // 它注册在播放页覆盖层自己那一段（见下方 SharedTransitionLayout 之前）。

        // 窄屏底栏「上滑自动隐藏」：状态 + 设置项接线。手势由内容区上的
        // `observeBottomBarDrag` 直接喂进来 —— **不走嵌套滚动**（理由见
        // `BottomBarHideState` 的 KDoc：安卓端那条链会被下拉刷新 / 顶栏 scrollBehavior 截断）。
        // 宽屏走侧栏没有底栏，观察器不挂也不会有任何行为。
        val bottomBarAutoHide by AppModel.bottomBarAutoHideFlow.collectAsState()
        // 兜底高度：NavigationBar 实测高度回传前先用它。「测不到高度就不动」会让整个
        // 特性退化成「怎么滑都不收」。密度在这里先读出来 —— `remember` 的 lambda 不是
        // @Composable，不能在它里面读 CompositionLocal。
        val bottomBarFallbackHeightPx = with(LocalDensity.current) { 80.dp.toPx() }
        val bottomBarHide = remember {
            cp.player.app.ui.component.BottomBarHideState(
                scope = scope,
                fallbackHeightPx = bottomBarFallbackHeightPx,
            )
        }
        androidx.compose.runtime.LaunchedEffect(bottomBarAutoHide) {
            bottomBarHide.enabled = bottomBarAutoHide
            if (!bottomBarAutoHide) bottomBarHide.reset()
        }

        // 小播放器的实测高度（px）→ 内容区末尾据此动态留白：有歌时列表最后一项
        // 不再被小播放器盖住。留白量 = 卡片实测高度 + 12dp 呼吸缝，随播放状态动画
        // 进出（无歌归零、出歌恢复）；高度来自 onSizeChanged，卡片改版这里自动跟随。
        //
        // ⚠️ 它只能加在**滚动内容的末尾**（经 `LocalMiniPlayerTailSpace` 下发给
        // `LazyScrollColumn` / `ScrollColumn` 的 contentPadding），**不能**给内容区挂
        // `padding(bottom = …)` —— 那是把内容区裁短，屏幕底部会空出一条，而小播放器
        // 恰好坐在那条空白里，看起来像它自带了背景。理由详见 LocalMiniPlayerTailSpace。
        val density = LocalDensity.current
        var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
        val miniPlayerReserved by animateDpAsState(
            targetValue = if (playbackState.currentTrack != null && miniPlayerHeightPx > 0)
                with(density) { miniPlayerHeightPx.toDp() } + 12.dp else 0.dp,
            animationSpec = cp.player.app.ui.theme.CpMotion.spatial(),
            label = "miniPlayerReserved",
        )

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
                            contentNavSize = contentNavSize,
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
                            // 侧栏那份歌单列表换了内容（换账号 / 切音源 / 删除）之后，
                            // 右侧面板若还挂着其中一个已经消失的歌单就收掉它 ——
                            // 否则会出现「左边列表里已经没有这个歌单了，右边还开着」。
                            onSidebarPlaylistsChanged = { ids ->
                                val pane = desktopPane
                                if (pane is DesktopPane.Playlist && pane.playlist.id !in ids) {
                                    desktopPane = DesktopPane.Tabs
                                }
                            },
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
                                  // 宽屏平板的「消息」与侧栏同一落点：**开面板**（保留侧栏）。
                                  // 旧行为整页 push 会把 MainScreen 整个覆盖、侧栏消失，与宽屏
                                  // 「保留左侧导航」的设计相反，也正是 N5 的「同名两落点」。
                                  onOpenMessages = {
                                      desktopPane = DesktopPane.Messages
                                      AppModel.refreshUnreadMessages()
                                  },
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
                                    // pageCanGoBack 发布使用。
                                    //
                                    // ⚠️ 必须用 DisposableEffect，不能是 SideEffect：内嵌 Navigator
                                    // 只在 **expanded（≥840dp）** 分支装配，窗口拖窄到 <840dp 时
                                    // 整支被 dispose，而 SideEffect 不会把引用置回 null ⇒ 悬空到
                                    // 已销毁的栈。此后窄分支点底栏切 tab（selectTab 的 popUntilRoot）
                                    // 或点标题栏返回，都在操作一个已 dispose 的 Navigator（幽灵返回键 /
                                    // 最坏抛异常）。onDispose 只在仍是自己时置回，避免与后来者互踩。
                                    androidx.compose.runtime.DisposableEffect(nav) {
                                        contentNavigator = nav
                                        onDispose { if (contentNavigator === nav) contentNavigator = null }
                                    }
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
                                                    // embedded：标题与返回由外壳提供（窗口 chrome，
                                                    // 或宽屏平板自己那条顶栏）—— 页内不再自绘，
                                                    // 否则同一屏会出现两条顶栏。
                                                    DownloadsScreen(embedded = true).Content()
                                                DesktopPane.RecentPlays ->
                                                    RecentPlaysScreen(embedded = true).Content()
                                                DesktopPane.Messages ->
                                                    MessagesPane(
                                                        onChatOpenChanged = { messagesChatOpen = it },
                                                    )
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
                        modifier = Modifier.fillMaxSize()
                            // 只挂顶栏的 scrollBehavior（大标题 → 64dp 靠它）。
                            // 底栏自动隐藏**不**走嵌套滚动 —— 它读内容区上的手指位移，
                            // 理由见 `BottomBarHideState` 的 KDoc。
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        topBar = {
                            // 三个 tab 的顶栏完全一致：标题 = tab 名，动作一律在右侧。
                            // 「我的」页不再特殊化（昵称由页内问候区承担，见 LibraryScreen）。
                            AppTopBar(
                                title = tabs[selectedIndex].label,
                                navigator = navigator,
                                scrollBehavior = scrollBehavior,
                                onOpenSettings = { navigator?.push(SettingsScreen()) },
                                onOpenAccount = { navigator?.push(AccountScreen()) },
                                // 窄屏没有内嵌 Navigator、也没有面板，消息走整页 push（原行为）。
                                onOpenMessages = {
                                    navigator?.push(MessagesScreen())
                                    AppModel.refreshUnreadMessages()
                                },
                            )
                        },
                        bottomBar = {
                            AppNavigationBar(
                                tabs,
                                selectedIndex,
                                selectTab,
                                hideFraction = bottomBarHide.fraction,
                                onHeightChanged = { bottomBarHide.barHeightPx = it.toFloat() },
                            )
                        },
                        containerColor = Color.Transparent
                    ) { padding ->
                        // padding.bottom 已随底栏隐藏比例收缩（AppNavigationBar 上报收缩后的
                        // 高度）；小播放器的留白**不下发成内容区的 padding**，而是经
                        // `LocalMiniPlayerTailSpace` 交给各页滚动容器的 contentPadding
                        // —— 内容一直铺到屏幕底，小播放器浮在它上面（浮层观感）。
                        //
                        // 判据与下面真正渲染小播放器的那一处**完全一致**（会话里没开对话），
                        // 否则会出现「内容让了位、小播放器却没出现」的空白。
                        androidx.compose.runtime.CompositionLocalProvider(
                            cp.player.app.ui.component.LocalMiniPlayerTailSpace provides
                                if (playbackState.currentTrack != null && !messagesChatOpen) {
                                    miniPlayerReserved
                                } else 0.dp,
                        ) {
                            TabContent(
                                tabs, visitedTabs, selectedIndex,
                                Modifier.fillMaxSize()
                                    .padding(padding)
                                    // 底栏自动隐藏的手势来源：只读观察内容区上的纵向位移，
                                    // 不消费事件（内容照常滚动）—— 理由见 BottomBarHideState。
                                    .observeBottomBarDrag(bottomBarHide),
                            )
                        }
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

            // 播放页展开态的返回处理器：注册在**内容层 / 面板层之后**，因此最后注册、
            // 最先被派发（DesktopBackDispatcher 后注册优先）—— 与「播放页盖在最上层」的
            // 视觉层级一致。放在 Content 顶部就会被下面的面板 / 内嵌路由页抢走（N4）。
            cp.player.app.platform.BackHandler(enabled = isPlayerExpanded) {
                isPlayerExpanded = false
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
                        // 消息面板右栏开着对话时不画小播放器：它会盖住对话输入栏，
                        // 其 Surface 还会把输入栏的点击一起吞掉。窄屏那一屏由 `App.kt` 的全局宿主
                        // 按 `lastItem !is ChatScreen` 让位；桌面宽屏的对话在面板右栏里，
                        // 只有这里知道 —— 判据是 `MessagesPane` 上报的 messagesChatOpen，
                        // 而它只可能为真于展开态（消息面板只在展开态渲染），所以无需再判 expanded。
                        if (playbackState.currentTrack != null && !messagesChatOpen) {
                            // 底距动态适配：max(底栏可见高度, 系统导航栏 inset) + 12dp 呼吸缝。
                            // 底栏收起时 navVisible 逐帧变小，小播放器贴合下移 —— 与底栏由
                            // 同一个 fraction 驱动，同帧移动；底栏全收后由系统导航栏 inset
                            // 兜底，不会顶到屏幕边。桌面宽屏没有底栏，维持固定 24dp。
                            val navVisibleDp = with(density) {
                                (bottomBarHide.barHeightPx * (1f - bottomBarHide.fraction)).toDp()
                            }
                            val navInsetDp = WindowInsets.navigationBars
                                .asPaddingValues().calculateBottomPadding()
                            val bottomPadding = if (expanded) 24.dp
                            else maxOf(navVisibleDp, navInsetDp) + 12.dp
                            Box(
                                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                    .padding(bottom = bottomPadding)
                                    // 实测高度回传给内容区的动态预留（见 miniPlayerReserved）。
                                    .onSizeChanged { miniPlayerHeightPx = it.height }
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
    onOpenMessages: () -> Unit = {},
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
    // 账号信息在这里读一次：最左的账号入口要用它（右侧动作不再需要）。
    val profile by AppModel.userProfileFlow.collectAsState()
    val titleBar: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    // 最左槽位：路由页放返回键，三个 tab 放**账号入口** —— 任何页面这个位置都是同一颗
    // 填充圆钮（与 `CpBackButton` / `CpTopBarActionButton` 同族），位置与样式不再跟着
    // tab 变。账号入口原先在右侧、且只有「我的」页被挪到最左（还是无底色的裸
    // `IconButton`），现在统一到最左、统一形态。
    val navigationIcon: @Composable () -> Unit = if (showBack) {
        { CpBackButton(onClick = onBack) }
    } else {
        {
            cp.player.app.ui.component.CpTopBarActionButton(
                cp.player.app.ui.component.TopBarAction(
                    icon = { AccountEntryContent(profile?.avatarUrl, "账号") },
                    onClick = onOpenAccount,
                ),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
    // 右侧动作固定两个：消息 / 设置，一律走 CpTopBarActionButton（与最左的账号入口、
    // 路由页的 topBarActions、CpBackButton 同一族）。此前同一个 tab 里「消息」是裸
    // `IconButton`、账号 / 设置是 `FilledIconButton`，两种外观并排；三个 tab 的按钮位置
    // 也各不相同。现在壳层顶栏全平台一致：**左 = 账号**，**右 = 消息 + 设置**。
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        // 这里刻意**不再**判 LocalWindowChromeActive：桌面端整条 AppTopBar 已经在函数开头
        // 提前 return 了（窗口 chrome 接管了标题与这三个入口），能走到这里就说明没有 chrome。
        // 两处都判会留下一条永远走不到的分支。
        // 消息入口：桌面端由窗口标题栏承担，这里只在**没有窗口 chrome**（手机 / 平板）时出现。
        // ⚠️ 刻意**不带未读角标**（2026-10-02 与标题栏一并去掉）—— 理由见
        // `DesktopTitleBar.MessageSlot` 的 KDoc。数据链路保留，只是不再显示。
        cp.player.app.ui.component.CpTopBarActionButton(
            cp.player.app.ui.component.TopBarAction(
                icon = { Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = "消息") },
                // 落点由调用点决定：宽屏平板 = 开面板（保留侧栏），窄屏 = 整页 push。
                // 两处各写一套（旧行为）会出现同名入口两个落点（N5）。
                onClick = onOpenMessages,
            )
        )
        cp.player.app.ui.component.CpTopBarActionButton(
            cp.player.app.ui.component.TopBarAction(
                icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                onClick = onOpenSettings,
            )
        )
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
 * 顶栏账号入口的内容：有头像显示头像（圆形裁切），未登录 / 无头像显示人形占位图标。
 * 只作为 `CpTopBarActionButton` 的图标用 —— 账号入口固定在三个 tab 顶栏的**最左**
 * （路由页那个位置是返回键），位置与形态全平台一致。
 */
@Composable
private fun AccountEntryContent(avatarUrl: String?, contentDescription: String) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = contentDescription,
            modifier = Modifier.size(28.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(Icons.Filled.Person, contentDescription = contentDescription)
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

/**
 * 单个 tab 的切页进出场状态（纯瞬时值，无需 saveable）。
 *
 * ⚠️ [alpha] 初值必须是「创建时是否正是选中页」：首屏 tab 不走切换动画
 * （`selectedIndex == lastSelected` 直接短路），若初值为 0 且无人把它推到 1，
 * 首页启动就是一片透明（离屏出图核对时抓到过）。
 */
private class TabSwitchState(initialAlpha: Float) {
    val alpha = Animatable(initialAlpha)

    /** 0 = 就位；±1 = 从右/左相邻侧滑入。 */
    val slide = Animatable(0f)
}

/**
 * tab 宿主：所有访问过的 tab **常驻组合**（滚动状态赖以保留），只放置选中的那个。
 *
 * 切页动画只动 **placement + graphicsLayer**（旧页原地淡出、新页带方向微滑淡入），
 * 完全不触碰组合与快照 —— 动画进行中滚动状态照常保存；结束后恢复「仅选中页放置」，
 * 其余页不参与命中测试，与无动画时的语义一致。规格取 [CpMotion]：
 * 透明度用 effects（不回弹）、位移用 spatial（允许轻微回弹）。
 */
@Composable
private fun TabContent(
    tabs: List<TabItem>,
    visitedTabs: List<Int>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val retainedIndices = visitedTabs.sorted()
    val switchStates = remember { mutableStateMapOf<Int, TabSwitchState>() }
    var lastSelected by remember { mutableStateOf(selectedIndex) }
    val effectsSpec = CpMotion.effects<Float>()
    val spatialSpec = CpMotion.spatial<Float>()
    val slideDistance = with(LocalDensity.current) { 32.dp.toPx() }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex == lastSelected) return@LaunchedEffect
        val direction = if (selectedIndex > lastSelected) 1f else -1f
        val leaving = switchStates[lastSelected]
        lastSelected = selectedIndex
        val entering = switchStates.getOrPut(selectedIndex) { TabSwitchState(initialAlpha = 0f) }
        entering.slide.snapTo(direction)
        entering.alpha.snapTo(0f)
        coroutineScope {
            launch { entering.alpha.animateTo(1f, effectsSpec) }
            launch { entering.slide.animateTo(0f, spatialSpec) }
            leaving?.let { launch { it.alpha.animateTo(0f, effectsSpec) } }
        }
    }

    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            retainedIndices.forEach { index ->
                val state = switchStates.getOrPut(index) {
                    TabSwitchState(initialAlpha = if (index == selectedIndex) 1f else 0f)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer {
                            alpha = state.alpha.value
                            translationX = state.slide.value * slideDistance
                        },
                ) { tabs[index].screen.Content() }
            }
        },
    ) { measurables, constraints ->
        // 只对「选中页 + 仍在淡出的页」用真实约束测量，其余保留组合（滚动状态在）但用
        // **零尺寸约束** —— 否则切页期间会对每个访问过的 tab 各做一次完整测量，长列表下
        // 就是 3 倍开销（N6）。零约束下的测量几乎不做事，而这些页本就不放置（见下方 place 逻辑）。
        val zeroConstraints = androidx.compose.ui.unit.Constraints.fixed(0, 0)
        val placeables = measurables.mapIndexed { i, m ->
            val index = retainedIndices[i]
            val alpha = switchStates[index]?.alpha?.value ?: 0f
            if (index == selectedIndex || alpha > 0f) m.measure(constraints)
            else m.measure(zeroConstraints)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // placeables 顺序与 retainedIndices 一致。淡出中的旧页垫底，选中页最后放置盖
            // 在最上层；完全透明的页不放置（不参与命中测试 —— 保持原「仅选中页可交互」语义）。
            val selectedPos = retainedIndices.indexOf(selectedIndex)
            val background = retainedIndices.indices.filter { it != selectedPos }
                .filter { pos -> (switchStates[retainedIndices[pos]]?.alpha?.value ?: 0f) > 0f }
            (background + if (selectedPos >= 0) listOf(selectedPos) else emptyList())
                .forEach { pos -> placeables[pos].placeRelative(0, 0) }
        }
    }
}

/**
 * 手机端底部导航栏。
 *
 * @param hideFraction 自动隐藏比例（0 = 全显，1 = 全收），由 [cp.player.app.ui.component.BottomBarHideState]
 *   驱动。实现是**收缩布局高度 + 内容上移 + 裁边**三合一的 layout 修饰符：
 *   高度跟着比例走，Scaffold 给内容区的 bottom padding 因此同步变化（滚动收起时列表
 *   能一直滚到底），而 NavigationBar 本体保持完整尺寸继续上移出界、由 clip 裁掉。
 * @param onHeightChanged 回传 NavigationBar 的完整高度（px），给
 *   [cp.player.app.ui.component.BottomBarHideState] 换算隐藏比例用。
 */
@Composable
private fun AppNavigationBar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    hideFraction: Float = 0f,
    onHeightChanged: (Int) -> Unit = {},
) {
    Box(
        // ⚠️ 顺序不能反：`clipToBounds()` 必须在 `layout{}` **外侧**。
        // `layout{}` 会把它的整个子树（含内侧的裁切节点）一起平移，裁切框跟着内容走
        // 就永远裁不到东西 —— 表现为「上报给 Scaffold 的高度已经是收缩后的 0，画面上
        // 却照画、点击也照命中」（端上症状：逻辑隐藏了，但还显示、还能触摸）。
        // 放外侧后裁切框固定在本节点左上角、尺寸取内侧量出的 `visible`，
        // 越过下界的部分才会真的被裁掉。
        Modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val visible = (placeable.height * (1f - hideFraction.coerceIn(0f, 1f)))
                    .roundToInt()
                    .coerceAtLeast(0)
                // 槽位高度 = 可视高度；NavigationBar 顶对齐，多出来的部分往下越界被裁掉
                // ⇒ 收起时整条底栏向下「沉」出屏幕，而不是被压扁。
                layout(placeable.width, visible) {
                    placeable.placeRelative(0, 0)
                }
            },
    ) {
        NavigationBar(
            modifier = Modifier.onSizeChanged { onHeightChanged(it.height) },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
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
}

@Composable
private fun DesktopSidebar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    selectedPane: DesktopPane,
    /** 内容区内嵌 Navigator 的栈深度：壳层顶栏是否可见取决于它（见 showMessagesEntry）。 */
    contentNavSize: Int,
    onSelect: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenRecentPlays: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenAllPlaylists: () -> Unit,
    /**
     * 上报侧栏当前认得哪些歌单（含收藏夹）。右侧面板正开着的不在这批 id 里时，
     * 宿主应当把它收掉 —— 换账号 / 删除后那份歌单已经不存在了。
     */
    onSidebarPlaylistsChanged: (Set<Long>) -> Unit = {},
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
    // 「消息 / 设置」入口只在**顶栏与窗口标题栏都不可见**时由侧栏承担，避免同一屏出现两套
    // 同名入口（N5）：桌面 → 标题栏有（chromeActive 为真），宽屏平板根页 → 顶栏有
    // （contentNavSize == 1、顶栏未 hide），此时侧栏都不该再画。只有宽屏平板进了内嵌详情页
    // （顶栏 hide）时才轮到侧栏。
    val showMessagesEntry = !chromeActive && contentNavSize > 1
    val showSettingsEntry = !chromeActive && contentNavSize > 1
    // 收藏夹（「xx喜欢的音乐」）已经有独立入口，不再以歌单身份重复出现。
    val allPlaylists = homeState.sidebarPlaylists
    val shownPlaylists = allPlaylists.take(SIDEBAR_PLAYLIST_LIMIT)
    val selectedPlaylistId = (selectedPane as? DesktopPane.Playlist)?.playlist?.id

    // 侧栏歌单「删除 / 取消收藏」的二次确认：删掉自己建的歌单不可恢复，
    // 而它在右键菜单里紧挨着「分享歌单」，一次误触就全没了。
    val confirm = cp.player.app.ui.component.rememberConfirmState()

    // 侧栏歌单右键菜单。动作集合与歌单详情页左栏的 `playlistMenu` 同一族
    // （播放 / 加入队列 / 全部下载 / 分享 / 删除或取消收藏），只是这里手上只有
    // [PlaylistSummary]，曲目要靠 HomeScreenModel 先拉一次详情。
    // 删掉 / 换账号之后该歌单可能已经不在列表里，那时由 MainScreen 收起对应面板
    // （见 onSidebarPlaylistsChanged），否则右栏会一直挂着一个已经不存在的歌单。
    val playlistMenu: (PlaylistSummary) -> List<CpContextMenuItem> = { playlist ->
        val owner = homeModel.isPlaylistOwner(playlist)
        buildList {
            add(CpContextMenuItem("播放", Icons.Filled.PlayArrow, onClick = { homeModel.playPlaylist(playlist) }))
            add(
                CpContextMenuItem(
                    "加入队列", Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = { homeModel.queuePlaylist(playlist) },
                )
            )
            add(
                CpContextMenuItem(
                    "全部下载", Icons.Filled.Download,
                    onClick = { homeModel.downloadPlaylist(playlist) },
                )
            )
            add(CpContextMenuSeparator)
            add(
                CpContextMenuItem(
                    "分享歌单", Icons.Filled.Share,
                    onClick = { shareText(playlistShareText(playlist.id, playlist.name)) },
                )
            )
            // 与歌单详情页同一条可见性规则：owner 才谈得上「删除」，收藏来的只能「取消收藏」。
            add(
                CpContextMenuItem(
                    if (owner) "删除歌单" else "取消收藏",
                    Icons.Filled.Delete,
                    onClick = {
                        confirm.request(
                            title = if (owner) "删除歌单" else "取消收藏",
                            message = if (owner) {
                                "确定删除「${playlist.name}」吗？删除后无法恢复。"
                            } else {
                                "确定取消收藏「${playlist.name}」吗？之后仍可重新收藏。"
                            },
                            confirmLabel = if (owner) "删除" else "取消收藏",
                            destructive = owner,
                            onConfirm = { homeModel.deleteOrUnsubscribePlaylist(playlist) },
                        )
                    },
                    danger = true,
                )
            )
        }
    }

    // 把「侧栏当前认得哪些歌单」报上去：右侧面板若正开着一个已经不在列表里的歌单
    // （切了账号 / 删掉了），MainScreen 据此把它收掉。
    // 收藏夹也算在内 —— 它虽然不在 [HomeUiState.sidebarPlaylists] 里，但有独立入口。
    val visiblePlaylistIds = allPlaylists.mapTo(mutableSetOf()) { it.id }
        .apply { homeState.likedPlaylist?.let { add(it.id) } }
    androidx.compose.runtime.LaunchedEffect(visiblePlaylistIds) {
        onSidebarPlaylistsChanged(visiblePlaylistIds)
    }

    Surface(
        modifier = Modifier.width(248.dp).fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp)) {
            SidebarHeader(profileName = profile?.nickname, avatarUrl = profile?.avatarUrl)
            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))
            // 歌单可能很多、窗口也可能被拖得很矮：中部列表单独滚动（桌面端带滚动条），
            // 设置入口钉在底部——原先是 Spacer(weight) 撑开，窗口一变矮设置项就被顶出可视区。
            //
            // 尾留白：宽屏没有底栏，小播放器改由**本列表末尾**让位 —— 它浮在这条侧栏上。
            // 留白必须走 contentPadding 而不是外层裁短，否则侧栏底部会空出一条，
            // 小播放器坐进去就像自带了背景（同 LocalMiniPlayerTailSpace 的 KDoc）。
            LazyScrollColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    bottom = cp.player.app.ui.component.LocalMiniPlayerTailSpace.current,
                ),
            ) {
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
                        // 右键菜单包在行**外面**（不是改 SidebarPlaylistRow 自己的签名）：
                        // 行本身保持纯粹，`SidebarRowsPreview` 那条离屏预览也就不用跟着改。
                        CpContextMenu(items = playlistMenu(playlist)) {
                            SidebarPlaylistRow(
                                playlist = playlist,
                                selected = selectedPlaylistId == playlist.id,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
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
        // 二次确认框挂在侧栏表面之上，否则会被侧栏自己的背景盖住。
        cp.player.app.ui.component.CpConfirmHost(confirm)
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
