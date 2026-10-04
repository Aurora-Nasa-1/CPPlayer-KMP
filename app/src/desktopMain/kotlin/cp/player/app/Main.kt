package cp.player.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cp.player.app.platform.DesktopBackDispatcher
import cp.player.app.platform.DesktopRenderTuning
import cp.player.app.platform.DesktopWindowPlacement
import cp.player.app.platform.JbrWindowChrome
import cp.player.app.platform.WindowDecorChoice
import cp.player.app.platform.WindowsWindowCorners
import cp.player.app.platform.installDesktopImageCacheLimit
import cp.player.app.ui.component.DesktopTitleBar
import cp.player.app.ui.component.TitleBarHeight
import cp.player.app.ui.util.DesktopShell
import cp.player.app.ui.util.next
import cp.player.app.ui.util.popToMainShell
import cp.player.app.version.AppVersion
import cp.player.core.MusicBackend
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.app.ui.util.SeekAvailability
import cp.player.app.shortcut.ShortcutAction
import cp.player.core.util.PlatformContext
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import java.awt.Dimension
import java.awt.Toolkit

/**
 * 窗口最小尺寸。
 *
 * 再窄下去 Expanded（≥840dp）布局会退化成手机布局，而且播放页的「封面 + 队列」双栏
 * 会被挤到互相压住——桌面窗口可以拖小，所以下限必须由程序兜住。
 * 注意单位是**物理像素**：高 DPI 缩放 150% 时约等于 600dp 逻辑宽度，仍能容纳紧凑布局。
 */
private val MinWindowSize = Dimension(900, 640)

/** 初始窗口尺寸：一次到位展开侧边栏 + 主内容，避免用户第一眼看到手机布局。 */
private val DefaultWindowSize = DpSize(1320.dp, 860.dp)

/** 方向键快进/快退步长。 */
private const val SeekStepMs = 5_000L

/** 窗口尺寸落盘前的静默期：拖动过程中尺寸每帧都在变，停稳了再写。 */
private const val WindowSizeSaveDelayMs = 400L

/**
 * DWM 圆角与 JBR 自定义标题栏的重试次数与间隔。
 *
 * 窗口从「创建」到「真正 map 出来、`IsWindowVisible` 为真」之间有一小段窗口期，
 * 在那之前按进程 ID 找不到任何可见窗口（圆角）、peer 也没准备好（JBR 安装）。
 * 20 × 100ms 足够覆盖冷启动。
 */
private const val WindowRetryAttempts = 20
private const val WindowRetryDelayMs = 100L

/**
 * 窗口装饰走哪条路，**必须在建窗之前**决定：`decoration` 参数决定窗口是否带系统边框，
 * AWT 不允许窗口显示之后再改 `undecorated`，这个选择没法事后补。
 *
 * - **JBR 路**（运行在 JBR 且 `JbrWindowChrome.isSupported`，见 `JbrWindowChrome`）：
 *   `SystemDefault` 保持窗口有边框，随后把客户区向上扩展盖过标题栏。原生阴影 /
 *   缩放边框 / Aero Snap / 原生最大化 / Win11 自动圆角全部白拿，**「假全屏」就此根治** ——
 *   原生最大化尊重任务栏，窗口永远不会铺满整个输出，Windows 也就没有理由把窗口
 *   提升为全屏呈现并切换显示模式（HDR 屏上退出后桌面 SDR 闪烁的那个坑）。
 * - **无边框路**（普通 JDK / Linux，或 JBR 太旧）：`Undecorated(6dp)` 纯 `WS_POPUP`，
 *   一切靠手补（`WindowsWindowCorners` / `WindowMaximizer` / 内置缩放抓手）。
 *   `Undecorated` 目前还是实验 API（要显式 opted-in）。用它的唯一理由是**能指定缩放
 *   抓手厚度**：默认等价 8dp，那圈抓手会压住贴着窗口边缘的控件。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
    // 必须最先执行：Skiko 在创建渲染器时首次读取 skiko.* 属性并固化，
    // 晚于这一步再写就不生效了。见 DesktopRenderTuning 的时序约束说明。
    DesktopRenderTuning.applyBeforeSkikoInit()

    // Coil 内存缓存上限必须在任何图片请求之前装上（SingletonImageLoader 首次
    // get() 后 factory 固化，晚了 setSafe 直接抛），见 DesktopImageCacheTuning 的说明。
    installDesktopImageCacheLimit()

    // 探测本身是纯反射 + 类加载，必须在建窗前完成（见上方 KDoc）。
    // ⚠️ 只是「跑在 JBR 上」不会让窗口变原生 —— 这条路必须像这样显式 opt-in，
    // JBR 的 WindowDecorations 完全不碰 `setUndecorated` 的默认行为。
    // 另：`javaHome` 只喂给 jpackage（打包产物）；run 家族的 launcher 由
    // `app/build.gradle.kts` 的 afterEvaluate 指到 `.jbr`（desktopRun 原生指守护进程 JDK）。
    //
    // 三态开关见 [WindowDecorChoice]：auto / jbr / undecorated —— 给想要无边框外观、
    // 或 JBR 标题栏在某台机器上装不上（那时窗口会保留系统标题栏）的用户留自救通道。
    val decorMode = WindowDecorChoice.resolve()
    val useJbrChrome = when (decorMode) {
        WindowDecorChoice.Mode.UNDECORATED -> false
        WindowDecorChoice.Mode.JBR -> JbrWindowChrome.isSupported
        WindowDecorChoice.Mode.AUTO -> JbrWindowChrome.isSupported
    }
    println("[CPPlayer] 窗口装饰 = $decorMode（来源：${WindowDecorChoice.lastSource}）→ " +
        if (useJbrChrome) "JBR 路（系统边框 + 自定义标题栏，原生贴边吸附）" else "无边框自绘路")
    if (decorMode == WindowDecorChoice.Mode.JBR && !JbrWindowChrome.isSupported) {
        println("[CPPlayer] 显式指定了 JBR 路，但当前运行时不支持（需跑在 JBR 上，Windows/macOS），回退无边框方案")
    } else if (!useJbrChrome) {
        println("[CPPlayer] 未走 JBR 自定义标题栏，窗口走自绘无边框方案")
    }

    application {
        ensureBackendInitialized()
        // 放在其它初始化之后立字据：这样音频原生库加载失败之类的无关崩溃
        // 不会被算到渲染后端头上，导致下次启动无端回退用户的设置。
        DesktopRenderTuning.beginStartupProbe()
        val windowState = rememberWindowState(
            size = remember { loadWindowSize() },
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "CPPlayer",
            icon = AppWindowIcon.painter,
            onKeyEvent = ::handleDesktopShortcut,
            // 两条装饰路线的选择，见上方 main() 的 KDoc。
            decoration = if (useJbrChrome) {
                WindowDecoration.SystemDefault
            } else {
                // 无边框：系统标题栏整体交给 DesktopTitleBar 自绘。
                //
                // 拖拽缩放**不需要额外代码**：ComposeWindow 内置 UndecoratedWindowResizer，
                // 在 isUndecorated() && isResizable() 时自动在窗口四周铺一圈透明抓手并切换光标。
                // 注意 resizable 默认就是 true，别为了「无边框」把它关掉，否则缩放会一起消失。
                //
                // ⚠️ 抓手厚度**刻意指定**而不是用默认 8dp：那圈抓手压在窗口最外圈、会和
                // 贴边的控件抢手势 —— 桌面滚条就贴在右边缘（`Alignment.CenterEnd`），
                // 厚 8dp 时「拖滚条」会变成「缩放窗口」。这里压到 6dp，滚条那边还额外
                // 内缩了同样距离（见 DesktopScrollbars.desktop.kt）。
                WindowDecoration.Undecorated(6.dp)
            },
        ) {
            // JBR 自定义标题栏的安装状态。null = 尚未装上 / 不走 JBR 路；
            // DesktopTitleBar 据此在「原生窗口钮」与「自绘窗口钮」两套 chrome 间切换。
            var jbrChrome by remember { mutableStateOf<JbrWindowChrome.Controller?>(null) }
            // 最小尺寸只能命令式设置：WindowState 没有 minSize 字段。
            LaunchedEffect(Unit) { window.minimumSize = MinWindowSize }

            // JBR 路：装自定义标题栏。窗口 peer 可能还没就绪，带重试；试满就放弃，
            // 放弃后的表现是窗口保留系统标题栏（功能完好，只是丑），绝不能因此挡住启动。
            // 高度用 Swing 像素（JBR 的口径），由 Compose density 换算 —— 密度变化
            // （拖去另一块缩放不同的屏幕）会重跑本协程，顺手按新 DPI 重装一次。
            // ⚠️ `LocalDensity.current` 是组合期读取，必须放在协程外面。
            val density = LocalDensity.current
            LaunchedEffect(window, density) {
                if (!useJbrChrome) return@LaunchedEffect
                val titleBarHeightPx = with(density) { TitleBarHeight.toPx() }
                repeat(WindowRetryAttempts) {
                    val installed = JbrWindowChrome.install(window, titleBarHeightPx)
                    if (installed != null) {
                        jbrChrome = installed
                        return@LaunchedEffect
                    }
                    delay(WindowRetryDelayMs)
                }
                println("[CPPlayer] JBR 自定义标题栏安装失败，窗口保留系统标题栏")
            }

            // 无边框路专属：Win11 的 DWM 圆角。纯 WS_POPUP 吃不到系统的自动圆角，
            // 手动 opt-in。窗口此刻可能还没真正 map 出来（IsWindowVisible 为假就找不到
            // 句柄），所以带重试；试满就放弃，圆角只是外观。
            // ⚠️ JBR 路不需要：窗口带边框，Win11 圆角由系统自动给。
            if (!useJbrChrome) {
                LaunchedEffect(Unit) {
                    repeat(WindowRetryAttempts) {
                        if (WindowsWindowCorners.applyRoundCorners()) return@LaunchedEffect
                        delay(WindowRetryDelayMs)
                    }
                }
            }
            // 记住窗口尺寸：桌面端换一次显示器/改一次分辨率就丢布局，是很容易被抱怨的细节。
            LaunchedEffect(Unit) {
                snapshotFlow { windowState.size }
                    .drop(1) // 首帧就是刚恢复出来的值，不必回写
                    .distinctUntilChanged()
                    .collectLatest { size ->
                        delay(WindowSizeSaveDelayMs)
                        saveWindowSize(size)
                    }
            }
            // 标题跟随当前曲目。不依赖 Window(title = ...) 的更新时机，直接写窗口属性。
            //
            // 刻意**不**在组合里 collect 整份 playback.state：它每 ~200ms 就因位置刷新
            // 发射一次，会让这层窗口内容每秒重组 5 次，而标题其实只有换歌时才变。
            // 这里把它彻底移出组合 —— 先收敛成标题字符串，重组次数为 0。
            LaunchedEffect(window) {
                AppModel.playback.state
                    .map { windowTitleOf(it.currentTrack) }
                    .distinctUntilChanged()
                    .collect { window.title = it }
            }
            StartupHealthProbe()
            // WindowDraggableArea 是 WindowScope 的扩展，而 WindowScope 不是 CompositionLocal，
            // 树内深层拿不到；Compose 自带的 LocalWindow 又标了 internal。所以在这里把
            // FrameWindowScope 捕获成 WindowScope 再传下去，标题栏才能在任意深度拖窗口。
            val windowScope: WindowScope = this
            App(
                titleBar = { navigator ->
                    DesktopTitleBar(
                        windowScope = windowScope,
                        windowState = windowState,
                        // 非 null 时 DesktopTitleBar 切到 JBR 模式：不画窗口钮、拖拽 / 双击
                        // 交给原生 hit-test（见 DesktopTitleBar 的「两种窗口模式」一节）。
                        jbr = jbrChrome,
                        // 标题：当前路由页自己声明的优先（`CpRouteScaffold` / `DesktopRouteTitle`），
                        // 没有声明时回落到 `MainScreen` 发布的主壳层标题。`MainScreen` 被 push 出去的
                        // 页面盖住后会离开组合、不再发布，所以没有这一层回落之外的上层来源，
                        // 窗口标题会停在旧值（从标题栏点账号，标题却还写着「首页」）。
                        title = DesktopShell.routeTitle ?: DesktopShell.pageTitle,
                        // 可返回 = 「Navigator 还能出栈」**或**「主壳层开着内嵌面板」。
                        // 只判后者（曾经如此）会让所有 push 出去的路由页在标题栏上没有返回键。
                        //
                        // ⚠️ 只有**真的有事可做**时才为真。三种情况各对应一条真实存在的退路：
                        //   1. `navigator.size > 1` —— 有 push 出去的路由页可以出栈；
                        //   2. `DesktopShell.pageCanGoBack` —— 主壳层开着内嵌面板（设置 / 歌单 /…）；
                        //   3. `DesktopBackDispatcher` —— 有页面注册了处理器（播放页展开态、歌单多选）。
                        // 漏掉第 3 条会让「播放页展开着但 Navigator 只有一页」时标题栏**没有返回键**
                        // —— 那时 Esc 能退、标题栏却不能，正是「同一个动作两条链路」的漂移。
                        // （此处的 `hasHandlers` 是快照读；它是快照状态，注册/注销会触发重组。）
                        canGoBack = navigator.size > 1 ||
                            DesktopShell.pageCanGoBack ||
                            DesktopBackDispatcher.hasHandlers,
                        onBack = {
                            // 与 Esc 走**同一条链路**：先让页面自己的处理器拿到
                            // （播放页展开态、歌单多选），没人处理再退化为出栈 / 收起内嵌面板。
                            // 两处各写一套判据的话，「Esc 能退、点返回键不能退」这类漂移迟早出现。
                            if (!DesktopBackDispatcher.dispatch()) {
                                if (navigator.size > 1) navigator.pop()
                                else if (DesktopShell.pageCanGoBack) DesktopShell.backRequested = true
                                // 走到这里说明上面三条都为假 —— 也就是「没有返回键」的情况。
                                // 理论上按不到（`canGoBack` 已经拦住了），但保留分支是为了将来的
                                // 快捷键 / 手势入口：静默什么都不做才是真正的问题。
                            }
                        },
                        // ⚠️ `onClose` 只有无边框模式的自绘关闭钮在用；JBR 模式的关闭是
                        // 原生窗口钮 → windowClosing → `onCloseRequest`，不走这里。
                        onClose = ::exitApplication,
                        // ⚠️ 下面三个入口**都必须先 `popToMainShell()`**，理由一致，写在
                        // `Navigation.popToMainShell` 的 KDoc 里，这里只说结论：
                        //
                        // 它们都由 `MainScreen` 消费（两个是内嵌面板、一个是内容区路由），
                        // 消费动作靠 `MainScreen` 里的 `LaunchedEffect` 读取 `DesktopShell`
                        // 的单向指令。而 `MainScreen` 是根 Navigator 的起点，一旦 push 到
                        // 别处（播放页 / 引导 …）它就**离开组合了** —— 那时发指令**没人消费**：
                        //   ① 点击完全没有反应（用户报的「在歌单页点设置没弹出」）；
                        //   ② 指令残留 true，等退回主壳层时面板又**自己弹出来**。
                        // 所以先弹回主壳层，再发指令：两步都在同一帧同步发生，
                        // 重组时 MainScreen 既在栈顶、又读得到这条指令。
                        //
                        // 「消息」同样是右侧内嵌面板，与设置走同一条单向指令通道。
                        onOpenMessages = {
                            navigator.popToMainShell()
                            DesktopShell.messagesRequested = true
                        },
                        // 「账号」是**内容区路由页**不是全屏面板：MainScreen 把 AccountScreen
                        // push 进内容区的内嵌 Navigator，左侧导航栏保留、返回还能回到原处。
                        // 账号页内部的二级跳转（我的主页 / 消息 / 切换音源）也落同一条栈。
                        onOpenAccount = {
                            navigator.popToMainShell()
                            DesktopShell.accountRequested = true
                        },
                        // 「设置」在桌面是**右侧内嵌面板**（左导航 + 右界面），开关是
                        // MainScreen 的局部状态，标题栏隔着 Navigator 够不到 ⇒
                        // 走 DesktopShell 这条单向指令 + 先弹回主壳层。
                        onOpenSettings = {
                            navigator.popToMainShell()
                            DesktopShell.settingsRequested = true
                        },
                        // 搜索同样走指令通道：切到搜索 tab 由 MainScreen 做，关键词由
                        // SearchScreen 消费并喂给它自己的 ScreenModel。
                        // 同理必须先回主壳层，否则在别的路由页里搜索也毫无反应。
                        onSearch = {
                            navigator.popToMainShell()
                            DesktopShell.pendingSearchQuery = it
                        },
                    )
                },
            )
        }
    }
}

/**
 * 窗口尺寸的独立命名空间。
 *
 * 刻意和主偏好（`cp_player_prefs`）分开存：`DesktopSettingsStorage` 是
 * 「构造时全量读入 → 每次写入全量回写」的实现，两个实例写同一个文件会互相覆盖。
 */
private fun windowSettings() = defaultSettingsStorage(namespace = "cp_player_window")

/**
 * 恢复上次的窗口尺寸，格式 `宽x高`（dp）。
 *
 * 所有失败路径都回退到 [DefaultWindowSize]：文件被改坏、分辨率变小、
 * 或者上次是在一块更大的显示器上关的窗口 —— 任何一种都不该让窗口开到屏幕外。
 */
private fun loadWindowSize(): DpSize {
    val raw = runCatching { windowSettings().getString(WINDOW_SIZE_KEY) }.getOrNull() ?: return DefaultWindowSize
    val parts = raw.split('x')
    if (parts.size != 2) return DefaultWindowSize
    val width = parts[0].toFloatOrNull() ?: return DefaultWindowSize
    val height = parts[1].toFloatOrNull() ?: return DefaultWindowSize
    if (width < MinWindowSize.width || height < MinWindowSize.height) return DefaultWindowSize
    // ⚠️ 上限取**工作区**，不是 `Toolkit.screenSize`（整屏）。
    // 存过一个「整屏尺寸」的窗口会在下次启动时直接铺满整个输出 —— 而无边框窗口铺满输出会被
    // Windows 提升为全屏呈现（Fullscreen Optimizations），在 HDR 显示器上可能连带切显示模式，
    // 退出后桌面停在坏的色彩状态、SDR 内容闪烁。这类「窗口不该大于工作区」的约束在这里收口，
    // 比事后补救可靠。详见 DesktopWindowPlacement 的说明。
    val work = runCatching { DesktopWindowPlacement.primaryWorkArea() }.getOrNull()
        ?: return DpSize(width.dp, height.dp)
    return DpSize(
        width.coerceAtMost(work.width.toFloat()).dp,
        height.coerceAtMost(work.height.toFloat()).dp,
    )
}

private fun saveWindowSize(size: DpSize) {
    runCatching {
        windowSettings().putString(WINDOW_SIZE_KEY, "${size.width.value}x${size.height.value}")
    }
}

private const val WINDOW_SIZE_KEY = "desktop.window.size"

/** 窗口标题：有曲目时显示「曲名 - 歌手 · CPPlayer」，便于在任务栏/Alt+Tab 里辨认。 */
private fun windowTitleOf(track: TrackSummary?): String {
    if (track == null) return "CPPlayer"
    val artist = track.artist.trim()
    return if (artist.isEmpty()) "${track.name} · CPPlayer" else "${track.name} - $artist · CPPlayer"
}

/**
 * 桌面全局快捷键。
 *
 * 键位**不在这里写死** —— 全部来自 `AppModel`（声明见 `cp.player.app.shortcut.ShortcutAction`，
 * 用户可在「设置 → 快捷键」里改 / 解绑）。这里只负责「事件 → 动作 → 执行」这一层。
 *
 * 只处理**没有被下层消费**的按键：Compose 的主派发阶段是子节点先收，
 * 所以输入框里的字母、滑条 / 按钮上的方向键都不会被这里抢走。
 * 快捷键的「录制」弹窗是独立窗口，它的按键根本到不了这条回调 —— 天然不会自己触发自己。
 */
private fun handleDesktopShortcut(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val action = AppModel.matchShortcut(event) ?: return false

    // 返回键（默认 Esc）等价于安卓返回键，由各页面通过 BackHandler 注册处理器。
    // 它刻意**不**依赖后端是否就绪：启动过程中按 Esc 也应该能退。
    if (action == ShortcutAction.BACK) return DesktopBackDispatcher.dispatch()

    if (!AppModel.initialized.value) return false
    val controller = AppModel.playback
    return performShortcutAction(action, controller, controller.state.value)
}

/**
 * 执行一个快捷键动作。
 *
 * @return 是否消费这次按键。**没有可做的事就返回 false**（例如没在播放「收藏」），
 *   把事件让给系统的其它处理器 —— 静默吞掉会让用户以为快捷键坏了。
 */
private fun performShortcutAction(
    action: ShortcutAction,
    controller: PlaybackController,
    state: PlaybackUiState,
): Boolean = when (action) {
    ShortcutAction.PLAY_PAUSE -> { controller.togglePlayPause(); true }
    ShortcutAction.PREV_TRACK -> { controller.skipPrevious(); true }
    ShortcutAction.NEXT_TRACK -> { controller.skipNext(); true }
    ShortcutAction.SEEK_BACKWARD -> seekBy(controller, state, -SeekStepMs)
    ShortcutAction.SEEK_FORWARD -> seekBy(controller, state, SeekStepMs)
    ShortcutAction.TOGGLE_SHUFFLE -> { controller.toggleShuffle(); true }
    ShortcutAction.CYCLE_REPEAT -> { controller.setRepeatMode(state.repeatMode.next()); true }
    ShortcutAction.TOGGLE_FAVORITE -> if (state.currentTrack == null) {
        false
    } else {
        // `toggleFavorite()` 是 suspend（乐观更新 + 失败回滚），按键回调是同步的，
        // 所以丢给 [shortcutScope]。作用域挂在桌面 UI 线程（`Dispatchers.Main` = Swing EDT），
        // 与 `MusicBackend.backendScope` 同一口径，播放状态的读写天然串行。
        shortcutScope.launch { runCatching { controller.toggleFavorite() } }
        true
    }
    ShortcutAction.OPEN_SETTINGS -> {
        // ⚠️ 不能直接置 `settingsRequested`：`MainScreen` 被 push 出去的路由页盖住后已经
        // 离开组合，指令没人消费、还会残留成 true。走这条「先弹回主壳层」的指令，
        // 由 `App.kt` 的根 Navigator 按顺序执行（理由见 `DesktopShell.openSettingsFromShortcut`）。
        cp.player.app.ui.util.DesktopShell.openSettingsFromShortcut = true
        true
    }
    // BACK 在上面就分流了；补这条只是为了让 `when` 穷尽，将来加动作时编译器能提醒。
    ShortcutAction.BACK -> DesktopBackDispatcher.dispatch()
}

/**
 * 快捷键里 suspend 动作（收藏）用的作用域。
 *
 * 刻意不用 `GlobalScope`：需要一个能被整体取消的父作用域，避免进程退出时还挂着回调。
 * `Dispatchers.Main` 在桌面就是 Swing EDT，与播放状态的写入方同一条线程。
 */
private val shortcutScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 相对当前位置 seek。
 *
 * 时长未知（流媒体元信息未到、直播流）时返回 false 不消费按键——
 * 滑条本身就是禁用的，这里抢走按键只会让用户以为快捷键坏了。
 * 判定复用 [SeekAvailability.isSeekable]，与两处滑条保持同一份规则。
 */
private fun seekBy(controller: PlaybackController, state: PlaybackUiState, deltaMs: Long): Boolean {
    val duration = state.durationMs
    // 无损曲后台落盘期间也一并拦下：引擎放的是不可定位的流，发了也不会动。
    if (!SeekAvailability.isSeekable(duration, state.isLocalizing)) return false
    controller.seekTo((state.positionMs + deltaMs).coerceIn(0L, duration))
    return true
}

/**
 * 渲染后端安全模式的「成功出帧」信号。
 *
 * 能进到这个 composable，就说明 SkiaLayer 已创建、Composition 已跑起来——Skiko 初始化没炸。
 * 再等满 [DesktopRenderTuning.HEALTHY_FRAME_COUNT] 帧，确认渲染循环真的在转
 * （覆盖「后端能初始化但呈现循环卡死」这种更隐蔽的失败）。
 *
 * 只有走到这里才会删掉探测文件。若后端不可用，本协程要么永远等不到帧、
 * 要么整个进程直接崩掉，两种情况探测文件都会留到下次启动 → 自动回退为「自动」。
 */
@Composable
private fun StartupHealthProbe() {
    LaunchedEffect(Unit) {
        // 即使数帧失败也要宣告健康：走到这里已证明 Composition 起来了，
        // 此时若还留着探测文件，会把 Compose 自身的异常误记成「后端不可用」。
        runCatching { repeat(DesktopRenderTuning.HEALTHY_FRAME_COUNT) { withFrameNanos { } } }
        DesktopRenderTuning.markStartupHealthy()
    }
}

@Volatile private var backendReady = false

private fun ensureBackendInitialized() {
    if (backendReady) return
    synchronized(Any()) {
        if (backendReady) return
        MusicBackend.init(
            context = PlatformContext(),
            settings = defaultSettingsStorage(),
        )
        AppModel.markInitialized()
        AppVersion.init(
            versionName = BuildInfo.VERSION_NAME,
            versionCode = BuildInfo.VERSION_CODE,
            gitSha = BuildInfo.GIT_SHA,
            isDesktop = true,
            releaseChannel = BuildInfo.RELEASE_CHANNEL,
        )
        backendReady = true
    }
}
