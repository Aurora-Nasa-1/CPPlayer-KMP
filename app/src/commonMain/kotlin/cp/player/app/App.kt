package cp.player.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import cp.player.app.ui.anim.CoverFlightHost
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.component.ProvideIsExpanded
import cp.player.app.ui.screen.BackendErrorScreen
import cp.player.app.ui.screen.ChatScreen
import cp.player.app.ui.screen.HomeGeneratedPlaylistScreen
import cp.player.app.ui.screen.MainScreen
import cp.player.app.ui.screen.OnboardingScreen
import cp.player.app.ui.screen.PlaylistDetailScreen
import cp.player.app.ui.screen.PlayerScreen
import cp.player.app.ui.screen.StartupScreen
import cp.player.app.platform.PlatformMediaControlsEffect
import cp.player.app.ui.util.popToMainShell
import cp.player.core.MusicBackend
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 应用根 Composable。
 *
 * 假定平台入口已在调用前完成 [MusicBackend.init]（由 androidMain/desktopMain 执行）。
 * 负责主题应用与 Voyager 根 Navigator 的初始路由判定：
 * - [BackendState.NoProvider] 或 Ready 且 `onboarding_done` 未置位 → [OnboardingScreen]
 *   （无音源与首启引导已合并为同一条流程，不再单设 Setup 欢迎页）
 * - 其余 Ready → [MainScreen]
 *
 * 通过观察 [MusicBackend.stateFlow] 响应 Provider 增删导致的瞬态切换，
 * 根 Navigator 起点由首次组合决定；后续 Ready 状态变化通过 LaunchedEffect 自动导航。
 *
 * @param titleBar 桌面端自绘窗口标题栏的槽位，由 `desktopMain` 的 `Main.kt` 注入。
 *   它被渲染在**主题之内、Navigator 之上**，这两个位置都是硬约束：
 *   放到主题外拿不到配色；放到 Navigator 之下（例如塞进 `MainScreen`）会在 push 到
 *   `AccountScreen` / `SettingsScreen` 时被目标页面盖住 —— 窗口已经没有系统边框了，
 *   那意味着用户既移不动也关不掉窗口。Android 侧传 null，布局与改动前完全一致。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App(
    titleBar: (@Composable (Navigator) -> Unit)? = null,
) {
    PlaybackMediaControlsBridge()

    // 系统通知被点击 → 把目标会话记进 AppModel（**不在这里直接导航**）。
    //
    // 为什么不直接 push：Android 冷启动时点击发生在**组合之前**，此刻还没有 navigator。
    // 平台层把这次点击缓存下来、在注册这一刻补投（见 PlatformNotifications 的说明），
    // 这里只负责落成状态，真正的导航在下面的 Navigator 作用域里消费。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        cp.player.app.platform.setOnMessageNotificationClick { providerId, peerUid, title ->
            AppModel.onMessageNotificationClicked(providerId, peerUid, title)
        }
    }

    // 启动：应用持久化音质到播放控制器 + 拉取用户资料/收藏 + 启动播放历史记录 + 补齐最近播放缺失字段
    // + 启动封面取色（「跟随封面」主题用）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        AppModel.syncPlaybackQuality()
        AppModel.syncAudioEffect()
        AppModel.syncFade()
        AppModel.restoreLocalServer()
        AppModel.restoreAggressiveStandby()
        AppModel.restoreLanSync()
        AppModel.restoreLanVisibility()
        AppModel.refreshUserProfile()
        AppModel.startHistoryRecorder()
        AppModel.startListeningRecorder()
        AppModel.startRecentTracksEnrich()
        AppModel.startCoverColorTracking()
    }

    // 网络类型变化（WiFi ↔ 蜂窝/热点）：按当前网络重选音质档位并同步给播放控制器。
    // 与上面的启动块分开一个 LaunchedEffect：这条流要持续收集整个会话，
    // Android 侧首发射会纠正初始 metered 状态（冷启动就在蜂窝上的情形）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        cp.player.app.platform.networkMeteredChanges()
            .collect { AppModel.onNetworkMeteredChanged(it) }
    }

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val initialized by AppModel.initialized.collectAsState()
            val state by AppModel.backendState.collectAsState()
            // onboarding_done 必须进 remember key：否则引导完成时 startDestination 还是旧值，
            // 下面的 LaunchedEffect(startDestination) 不会触发，replaceAll 会把人留在引导页。
            val onboardingDone by AppModel.onboardingDoneFlow.collectAsState()
            val startDestination = remember(initialized, state, onboardingDone) {
                AppState.startDestination(initialized, state, onboardingDone)
            }
            val start = remember(startDestination) {
                when (startDestination) {
                    AppStartDestination.Loading -> StartupScreen("正在初始化后端…")
                    AppStartDestination.Onboarding -> OnboardingScreen()
                    AppStartDestination.Main -> MainScreen()
                    is AppStartDestination.Error -> BackendErrorScreen(startDestination.message)
                }
            }
            Navigator(start) { navigator ->
                // 当 state 变为 Ready 时自动替换为 MainScreen
                LaunchedEffect(startDestination) {
                    val target = when (startDestination) {
                        AppStartDestination.Loading -> StartupScreen("正在初始化后端…")
                        AppStartDestination.Onboarding -> OnboardingScreen()
                        AppStartDestination.Main -> MainScreen()
                        is AppStartDestination.Error -> BackendErrorScreen(startDestination.message)
                    }
                    val current = runCatching { navigator.lastItem }.getOrNull()
                    if (current?.javaClass != target.javaClass) {
                        navigator.replaceAll(target)
                    }
                }

                // 通知点击 → 跳到对应会话。
                //
                // 必须在 Navigator 作用域内：要 push 到根栈。先 `popToMainShell()` 是因为
                // 点击可能发生在任意路由页（播放页 / 设置 / 引导）之上 —— 直接 push 会叠在
                // 那层之上，用户返回时看到的还是原来的页面，观感像「跳歪了」。
                // 引导流程（尚未进主壳层）里不导航：那时还没有消息页可回。
                val pendingOpen = AppModel.pendingMessageOpenFlow.collectAsState().value
                LaunchedEffect(pendingOpen) {
                    val target = pendingOpen ?: return@LaunchedEffect
                    AppModel.consumePendingMessageOpen()
                    if (startDestination !is AppStartDestination.Main) return@LaunchedEffect
                    navigator.popToMainShell()
                    navigator.push(
                        cp.player.app.ui.screen.ChatScreen(
                            peerUid = target.peerUid,
                            peerName = target.title.ifBlank { null },
                        )
                    )
                }

                // Esc（桌面）在没有任何页面注册处理器时，退化为「Navigator 出栈」。
                //
                // 必须注册在**最外层**：`DesktopBackDispatcher` 是「后注册优先」，页面自己的
                // 处理器（播放页展开态、歌单多选）在更深处注册，会先于这条被派发。
                // 没有它的话，push 出去的路由页（账号 / 关于 / 诊断…）**按 Esc 完全没有反应** ——
                // 那些页面并不注册 BackHandler。
                //
                // 桌面宽屏的详情页如今在**内容区内嵌 Navigator** 里（`MainScreen`），根栈仍是
                // 1 —— 所以退栈之外还要把 `DesktopShell.pageCanGoBack`（内嵌栈可退 / 面板可收）
                // 纳入 enabled 判据，并让兜底动作与标题栏返回键**同链**：内嵌 pop → 根 pop →
                // 收面板。两处各写一套判据的话，「Esc 能退、点返回键不能退」的漂移迟早出现。
                //
                // 安卓端不注册：Voyager 的 `Navigator` 已经接管了系统返回键，再加一条会双重出栈。
                if (!cp.player.app.platform.isAndroidPlatform()) {
                    cp.player.app.platform.BackHandler(
                        enabled = navigator.size > 1 || cp.player.app.ui.util.DesktopShell.pageCanGoBack,
                    ) {
                        if (navigator.size > 1) navigator.pop()
                        else if (cp.player.app.ui.util.DesktopShell.pageCanGoBack) {
                            cp.player.app.ui.util.DesktopShell.backRequested = true
                        }
                    }
                }

                // 桌面全局快捷键「打开设置」（默认 Ctrl + ,）。
                //
                // 必须在这里消费，不能在按键回调里直接置 `settingsRequested` —— 后者在
                // `MainScreen` 离开组合时没人接（见 `DesktopShell.openSettingsFromShortcut` 的
                // KDoc）。先 `popToMainShell()` 把 `MainScreen` 拉回栈顶，它随后的组合里
                // `LaunchedEffect(settingsRequested)` 就会读到刚置上的 `true` 并打开面板。
                // 顺序不能反：先置标志，`MainScreen` 那时还没回到栈顶，指令又会悬空。
                val openSettingsFromShortcut =
                    cp.player.app.ui.util.DesktopShell.openSettingsFromShortcut
                LaunchedEffect(openSettingsFromShortcut) {
                    if (openSettingsFromShortcut) {
                        cp.player.app.ui.util.DesktopShell.openSettingsFromShortcut = false
                        navigator.popToMainShell()
                        cp.player.app.ui.util.DesktopShell.settingsRequested = true
                    }
                }

                // 把「窗口是否够宽」发布给**整棵 Navigator**。
                // 必须在这里 provide 一次：MainScreen 内部也 provide 了同一个 local，
                // 但 push 出去的路由页与 MainScreen 是 Navigator 里的兄弟节点，拿不到它，
                // 于是设置 / 账号 / 引导这些页面在宽窗口上也会读到默认 false（手机布局）。
                ProvideIsExpanded {
                    Column(Modifier.fillMaxSize()) {
                        // 桌面自绘窗口标题栏。必须在 ScreenTransition 之前、且在 Navigator 作用域内：
                        // 前者保证 push 任何页面都不会盖住它（无边框窗口只能靠它移动 / 关闭），
                        // 后者让它能拿到 navigator 去跳账号页 / 设置页。详见 App 的 KDoc。
                        titleBar?.invoke(navigator)

                        // 把「窗口级顶栏是否已接管账号 / 设置入口」告诉下层。
                        // 判据是**槽位是否被注入**，不是「是不是桌面」—— 展开态分支的条件是宽度，
                        // 宽屏平板也走同一套顶栏；而且将来若给无边框加「回退到系统标题栏」的开关，
                        // 那时 isDesktop 仍为 true 但并没有标题栏。理由详见 LocalWindowChromeActive。
                        androidx.compose.runtime.CompositionLocalProvider(
                            cp.player.app.ui.component.LocalWindowChromeActive provides (titleBar != null),
                            // 根 Navigator 下发给整棵树：桌面宽屏的内容区里有内嵌 Navigator，
                            // 页面读 LocalNavigator 拿到的是内嵌那一条；要整窗路由（播放页）
                            // 的调用点必须显式用这一条。见 LocalRootNavigator 的 KDoc。
                            cp.player.app.ui.util.LocalRootNavigator provides navigator,
                        ) {
                            // 整窗「空白处右键 → 返回上一级」兜底。
                            //
                            // 为什么不放在各页面里：桌面的返回有**四条**来源（根栈出栈 /
                            // 内容区内嵌栈出栈 / 收起内嵌面板 / 页面自己的处理器），
                            // 而设置面板、歌单面板、播放页这些页面**看不到全部四条**，
                            // 页面自己判就只能判到一部分 —— 用户遇到的就是「在设置页 /
                            // 歌单页右键，菜单里没有返回」。判据与动作由
                            // `rememberBackContextMenuItem` 与标题栏返回键逐字同链。
                            //
                            // `passive = true`：页面里自己的右键菜单（歌曲行 / 卡片 /
                            // 页面级「刷新」）都是**后代**节点，先于这里收到 Main 阶段的 Press
                            // ⇒ 只有它们都没接手的空白 / 纯文本区域才弹这一层
                            // （默认的 Initial 模式会让父子同时弹两个菜单）。
                            //
                            // ⚠️ 已经自带空白处右键容器的页面（`CpRefreshablePage`）
                            // 不会被这里覆盖 —— 它的菜单在内层先消费，且它自己也并进了
                            // 同一个「返回上一级」项，不会出现两种菜单。
                            val backItem = cp.player.app.ui.component.rememberBackContextMenuItem()
                            cp.player.app.ui.component.CpContextMenu(
                                items = backItem?.let { listOf(it) },
                                modifier = Modifier.fillMaxSize().weight(1f),
                                passive = true,
                            ) {
                                // ⚠️ 整个根 Navigator 只此一个 SharedTransitionLayout，且必须同时
                                // 罩住「页面转场」与「全局 MiniPlayer」两端：共享元素只有在
                                // **同一个** SharedTransitionScope 里才能配对。此前 MiniPlayer
                                // 自带一个独立的 SharedTransitionLayout、而路由版 PlayerScreen
                                // 又自建一个 —— 三方互不相识，从歌单等其它页面点 MiniPlayer
                                // 展开播放页时配不上对，展开动画直接消失（tab 页里正常，
                                // 因为那一对走的是 MainScreen 自己的 scope）。
                                SharedTransitionLayout(Modifier.fillMaxSize()) {
                                    CompositionLocalProvider(
                                        cp.player.app.ui.anim.LocalSharedTransitionScope provides this,
                                    ) {
                                        // MainScreen already owns this overlay; all other pages get the
                                        // same controller here so playback remains accessible globally.
                                        //
                                        // ⚠️ 页面（ScreenTransition）必须留在宿主**里面**（pageContent）：
                                        // 小播放器的尾留白（LocalMiniPlayerTailSpace）由宿主 provide，
                                        // 各页滚动容器的 contentPadding 读的就是它 —— 页面若在 provider
                                        // 外面（曾经的写法），读到的永远是默认 0，整页路由（一起听 /
                                        // 歌单详情 / 账号 / 设置 …）的列表末尾就会被浮层小播放器压住。
                                        // 安卓上所有非 tab 页面都推在这条根栈上，症状最明显。
                                        GlobalMiniPlayerHost(
                                            // 聊天页（窄屏整页）也让位：那一页底部是固定输入栏，
                                            // 小播放器浮层会把它盖住并吞掉点击。
                                            // 桌面宽屏的对话在 `MessagesPane` 右栏里、不在这条根栈上，
                                            // 由 `MainScreen` 自己那份宿主处理。
                                            show = startDestination is AppStartDestination.Main &&
                                                navigator.lastItem !is MainScreen &&
                                                navigator.lastItem !is PlayerScreen &&
                                                navigator.lastItem !is ChatScreen,
                                            onClick = { navigator.push(PlayerScreen()) },
                                        ) {
                                            ScreenTransition(
                                                navigator = navigator,
                                                transition = {
                                                    // 播放页路由（从全局 MiniPlayer 展开）：fade，
                                                    // 让 sharedBounds 的「小卡片长成全屏」形变唱主角 ——
                                                    // 叠一层 slide 会和形变抢戏。
                                                    if (targetState is PlayerScreen ||
                                                        initialState is PlayerScreen
                                                    ) {
                                                        fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                                                    } else if (targetState is PlaylistDetailScreen ||
                                                        targetState is HomeGeneratedPlaylistScreen
                                                    ) {
                                                        // 歌单打开：fade 交叉淡入（目标位置静态，飞行器叠加其上，
                                                        // 见 CoverFlight）；返回 tab 时仍走下方 slide，保持「返回」的方向感。
                                                        fadeIn(tween(300)) togetherWith fadeOut(tween(220))
                                                    } else {
                                                        // 复刻 Voyager SlideTransition 默认值：spring + Push/Pop 方向。
                                                        val spec = spring<IntOffset>(
                                                            stiffness = 400f,
                                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                                        )
                                                        if (navigator.lastEvent == StackEvent.Pop) {
                                                            slideInHorizontally(spec) { -it } togetherWith
                                                                slideOutHorizontally(spec) { it }
                                                        } else {
                                                            slideInHorizontally(spec) { it } togetherWith
                                                                slideOutHorizontally(spec) { -it }
                                                        }
                                                    }
                                                },
                                                // 每个页面（进 / 退场双方）各自拿到本次转场的
                                                // AnimatedVisibilityScope —— 路由页的 sharedBounds
                                                // 靠它挂到同一条转场时间线上。
                                                content = { screen ->
                                                    CompositionLocalProvider(
                                                        cp.player.app.ui.anim.LocalNavAnimatedVisibilityScope provides this,
                                                    ) {
                                                        screen.Content()
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                // 封面飞行器：必须压在所有页面与 MiniPlayer 之上（最后绘制）。
                                CoverFlightHost()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全局 MiniPlayer 宿主（根 Navigator 上的路由页通用底栏；MainScreen 自己有一份）。
 *
 * 结构是「**页面内容进宿主**」：[pageContent]（根 Navigator 渲染的整页路由）与
 * 小播放器同被 `LocalMiniPlayerTailSpace` 的 provider 罩住 —— 尾留白必须同时管住
 * 两端：各页滚动容器读它给列表末尾让位，浮层与预留量出自**同一个**状态源。
 * 页面若在 provider 外面（曾经的写法），读到的永远是默认 0，整页路由的列表末尾
 * 就会被小播放器压住。
 *
 * ⚠️ 完整播放状态的订阅隔离在最内层的 [GlobalMiniPlayerBar]：播放中
 * `playback.state` 每 200ms 换一个新对象（位置轮询），collect 在宿主这一层会连带
 * [pageContent]（整棵页面树）每 200ms 重组 —— 与 [PlaybackMediaControlsBridge]
 * 的 KDoc 同一理由。宿主自己只订一个**起/停歌才翻转**的布尔（尾留白的判据）。
 *
 * ⚠️ `show` 翻转必须走 [AnimatedContent] 的 target，**不能**用外层 `if` 摘除整个块：
 * 从 MiniPlayer 打开播放页（push `PlayerScreen`）时，sharedBounds 需要退场方
 * （MiniPlayer）在转场期间保持组合才能和进场方配对 —— 外层 `if` 会把它连同
 * scope 一起瞬时移除，配对失败，展开动画随之消失。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.GlobalMiniPlayerHost(
    show: Boolean,
    onClick: () -> Unit,
    pageContent: @Composable () -> Unit,
) {
    // 尾留白的判据只依赖「有没有当前曲目」—— 起/停歌才翻转。派生成独立布尔流，
    // 不订阅 200ms 的完整状态（理由见 KDoc）。remember 以 flow 实例为 key：
    // playbackController 是 `by lazy` 单例，key 平时稳定；换后端时自动重派生。
    val hasTrack by remember(AppModel.playback.state) {
        AppModel.playback.state
            .map { it.currentTrack != null }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    // 内容要让位的量：实测高度 + 12dp 呼吸缝，与 `MainScreen` 那一份同源同算法
    // （见 LocalMiniPlayerTailSpace）。路由页（歌单详情 / 专辑 / 歌手 / 一起听 …）
    // 的列表末尾靠它留白，小播放器浮在内容之上而不是坐进一条空白带里。
    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    val tailSpace by animateDpAsState(
        targetValue = if (show && hasTrack && barHeightPx > 0) {
            with(density) { barHeightPx.toDp() } + 12.dp
        } else 0.dp,
        animationSpec = cp.player.app.ui.theme.CpMotion.spatial(),
        label = "globalMiniPlayerTail",
    )
    CompositionLocalProvider(
        // ⚠️ 必须钳到非负：tailSpace 由 spring 动画驱动（CpMotion.spatial 是回弹
        // spring），小播放器消失时从高值回落到 0 会**下冲穿负**，负 Dp 进
        // PaddingValues 直接抛「Padding must be non-negative」—— Android 实锤过
        // 的概率崩溃。钳在这里（提供方）+ DesktopScrollbars（消费方）双保险。
        cp.player.app.ui.component.LocalMiniPlayerTailSpace provides tailSpace.coerceAtLeast(0.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            pageContent()
            GlobalMiniPlayerBar(
                show = show,
                onClick = onClick,
                onBarHeight = { barHeightPx = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 小播放器浮层本体 —— **完整播放状态的唯一订阅点**（见 [GlobalMiniPlayerHost]）。
 *
 * 播放中它随位置轮询每 200ms 重组一次，但重组范围只有这一小块：
 * 页面（[GlobalMiniPlayerHost] 的 `pageContent`）与宿主 Box 都不在它的作用域内。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.GlobalMiniPlayerBar(
    show: Boolean,
    onClick: () -> Unit,
    onBarHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackState by AppModel.playback.state.collectAsState()
    AnimatedContent(
        targetState = show && playbackState.currentTrack != null,
        transitionSpec = {
            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        },
        label = "GlobalMiniPlayer",
        modifier = modifier,
    ) { visible ->
        if (visible) {
            // this（AnimatedContentScope）要在 with(this@…) 进到 SharedTransitionScope
            // 之前捕获 —— with 块里 `this` 已经换人了。
            val animScope = this
            with(this@GlobalMiniPlayerBar) {
                MiniPlayer(
                    state = playbackState,
                    animatedVisibilityScope = animScope,
                    onClick = onClick,
                    onTogglePlay = AppModel.playback::togglePlayPause,
                    onSkipPrev = AppModel.playback::skipPrevious,
                    onSkipNext = AppModel.playback::skipNext,
                    modifier = Modifier
                        .navigationBarsPadding()
                        // 实测高度回传给宿主的尾留白（只量浮层自身，不含底部 inset）。
                        .onSizeChanged { onBarHeight(it.height) },
                )
            }
        }
    }
}

/**
 * 系统媒体控制（SMTC / MPRIS）的接线层。
 *
 * **刻意不把这段留在 [App] 里。** 播放中 `AppModel.playback.state` 每 200 ms
 * （位置轮询）就会换一个新对象，若 [App] 直接 `collectAsState()` 它，[App] 自身
 * 会每秒重组 5 次；由于该状态是不稳定类型，其 lambda 无法被 Compose 跳过，会连带
 * Navigator / SlideTransition / SharedTransitionLayout 一起重算，与出帧抢 CPU。
 * 隔离到这一层后，跟随播放状态重组的只剩这个极小的 composable。
 */
@Composable
private fun PlaybackMediaControlsBridge() {
    val playbackState by AppModel.playback.state.collectAsState()
    PlatformMediaControlsEffect(
        controller = AppModel.playback,
        state = playbackState,
    )
}

/**
 * 主题宿主。
 *
 * **刻意把主题状态的订阅收在这一层**，理由与 [PlaybackMediaControlsBridge] 完全相同：
 * `coverSeedFlow` 每换一首歌就变，若 [App] 自己 `collectAsState` 它，[App] 会跟着重组，
 * 而 [App] 的函数体会连带 Navigator / SlideTransition / SharedTransitionLayout 一起重算。
 * 隔到这一层后，跟随换色重组的只剩这个极小的 composable；
 * 真正需要换色的那些 composable 由 `CompositionLocal` 的读取失效单独驱动，不受影响。
 */
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val themeMode by AppModel.themeModeFlow.collectAsState()
    val colorSource by AppModel.colorSourceFlow.collectAsState()
    val pureBlack by AppModel.pureBlackFlow.collectAsState()
    val coverSeed by AppModel.coverSeedFlow.collectAsState()
    val wallpaperSeed by AppModel.wallpaperSeedFlow.collectAsState()
    val fontRoundness by AppModel.fontRoundnessFlow.collectAsState()
    // 语言必须在这里订阅一次：`ProvideCpStrings` 要覆盖整棵树（含 Navigator 与
    // 桌面的自绘标题栏），而 AppTheme 是全树唯一的主题宿主 —— 挂在这里才能保证
    // 「切换语言后立即生效」不需要重启，也不会漏掉任何分支。
    val appLanguage by AppModel.appLanguageFlow.collectAsState()
    cp.player.app.ui.theme.CpTheme(
        themeMode = themeMode,
        colorSource = colorSource,
        pureBlack = pureBlack,
        coverSeed = coverSeed,
        wallpaperSeed = wallpaperSeed,
        fontRoundness = fontRoundness,
        // 触觉执行器必须在内容**之前**提供：它依赖 LocalView，而 LocalView 要在
        // setContent 的组合树里才拿得到宿主 View。放在主题内层可以保证
        // Navigator / 各 Screen 都在作用域内。
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                cp.player.app.ui.feedback.LocalCpHaptics provides
                    cp.player.app.ui.feedback.rememberPlatformHaptics()
            ) {
                cp.player.app.i18n.ProvideCpStrings(appLanguage) {
                    content()
                }
            }
        },
    )
}
