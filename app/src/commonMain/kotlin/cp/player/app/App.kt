package cp.player.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransition
import cp.player.app.ui.anim.CoverFlightHost
import cp.player.app.ui.component.MiniPlayer
import cp.player.app.ui.screen.BackendErrorScreen
import cp.player.app.ui.screen.HomeGeneratedPlaylistScreen
import cp.player.app.ui.screen.MainScreen
import cp.player.app.ui.screen.OnboardingScreen
import cp.player.app.ui.screen.PlaylistDetailScreen
import cp.player.app.ui.screen.SetupScreen
import cp.player.app.ui.screen.StartupScreen
import cp.player.app.platform.PlatformMediaControlsEffect
import cp.player.core.MusicBackend

/**
 * 应用根 Composable。
 *
 * 假定平台入口已在调用前完成 [MusicBackend.init]（由 androidMain/desktopMain 执行）。
 * 负责主题应用与 Voyager 根 Navigator 的初始路由判定：
 * - [BackendState.NoProvider]（含未初始化/错误回退）→ [SetupScreen]
 * - [BackendState.Ready] → [MainScreen]
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
        AppModel.refreshUserProfile()
        AppModel.startHistoryRecorder()
        AppModel.startRecentTracksEnrich()
        AppModel.startCoverColorTracking()
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
                    AppStartDestination.Setup -> SetupScreen()
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
                        AppStartDestination.Setup -> SetupScreen()
                        AppStartDestination.Onboarding -> OnboardingScreen()
                        AppStartDestination.Main -> MainScreen()
                        is AppStartDestination.Error -> BackendErrorScreen(startDestination.message)
                    }
                    val current = runCatching { navigator.lastItem }.getOrNull()
                    if (current?.javaClass != target.javaClass) {
                        navigator.replaceAll(target)
                    }
                }

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
                    ) {
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            ScreenTransition(
                                navigator = navigator,
                                transition = {
                                    if (targetState is PlaylistDetailScreen ||
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
                            )

                            // MainScreen already owns this overlay; all other pages get the
                            // same controller here so playback remains accessible globally.
                            val showMiniPlayer = startDestination is AppStartDestination.Main &&
                                navigator.lastItem !is MainScreen &&
                                navigator.lastItem !is cp.player.app.ui.screen.PlayerScreen
                            if (showMiniPlayer) {
                                val playbackState by AppModel.playback.state.collectAsState()
                                val controller = AppModel.playback
                                SharedTransitionLayout(Modifier.fillMaxSize()) {
                                    AnimatedContent(
                                        targetState = playbackState.currentTrack != null,
                                        transitionSpec = {
                                            fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                                        },
                                        label = "GlobalMiniPlayer",
                                        modifier = Modifier.align(Alignment.BottomCenter),
                                    ) { hasTrack ->
                                        if (hasTrack) {
                                            MiniPlayer(
                                                state = playbackState,
                                                animatedVisibilityScope = this@AnimatedContent,
                                                onClick = { navigator.push(cp.player.app.ui.screen.PlayerScreen()) },
                                                onTogglePlay = controller::togglePlayPause,
                                                onSkipPrev = controller::skipPrevious,
                                                onSkipNext = controller::skipNext,
                                                modifier = Modifier.navigationBarsPadding(),
                                            )
                                        }
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
    cp.player.app.ui.theme.CpTheme(
        themeMode = themeMode,
        colorSource = colorSource,
        pureBlack = pureBlack,
        coverSeed = coverSeed,
        wallpaperSeed = wallpaperSeed,
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
