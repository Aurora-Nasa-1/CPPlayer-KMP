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
import cp.player.core.MusicBackend

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

    // 启动：应用持久化音质到播放控制器 + 拉取用户资料/收藏 + 启动播放历史记录 + 补齐最近播放缺失字段
    // + 启动封面取色（「跟随封面」主题用）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        AppModel.syncPlaybackQuality()
        AppModel.restoreLocalServer()
        AppModel.restoreAggressiveStandby()
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
                            Box(Modifier.fillMaxSize().weight(1f)) {
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

                                        // MainScreen already owns this overlay; all other pages get the
                                        // same controller here so playback remains accessible globally.
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
                                            modifier = Modifier.align(Alignment.BottomCenter),
                                        )
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
 * ⚠️ 播放状态的订阅必须隔离在这个小 composable 里：播放中 `playback.state` 每 200ms
 * 换一个新对象（位置轮询），collect 在 [App] 外层会连带 Navigator / ScreenTransition
 * 一起重组 —— 与 [PlaybackMediaControlsBridge] 的 KDoc 同一理由。
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
    modifier: Modifier = Modifier,
) {
    val playbackState by AppModel.playback.state.collectAsState()
    // 内容要让位的量：实测高度 + 12dp 呼吸缝，与 `MainScreen` 那一份同源同算法
    // （见 LocalMiniPlayerTailSpace）。路由页（歌单详情 / 专辑 / 歌手 / 消息 …）
    // 的列表末尾靠它留白，小播放器浮在内容之上而不是坐进一条空白带里。
    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    val tailSpace by animateDpAsState(
        targetValue = if (show && playbackState.currentTrack != null && barHeightPx > 0) {
            with(density) { barHeightPx.toDp() } + 12.dp
        } else 0.dp,
        animationSpec = cp.player.app.ui.theme.CpMotion.spatial(),
        label = "globalMiniPlayerTail",
    )
    CompositionLocalProvider(
        cp.player.app.ui.component.LocalMiniPlayerTailSpace provides tailSpace,
    ) {
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
                with(this@GlobalMiniPlayerHost) {
                    MiniPlayer(
                        state = playbackState,
                        animatedVisibilityScope = animScope,
                        onClick = onClick,
                        onTogglePlay = AppModel.playback::togglePlayPause,
                        onSkipPrev = AppModel.playback::skipPrevious,
                        onSkipNext = AppModel.playback::skipNext,
                        modifier = Modifier
                            .navigationBarsPadding()
                            // 实测高度回传给上面的尾留白（只量浮层自身，不含底部 inset）。
                            .onSizeChanged { barHeightPx = it.height },
                    )
                }
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
                content()
            }
        },
    )
}
