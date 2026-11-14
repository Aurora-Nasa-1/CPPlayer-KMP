package cp.player.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.tao.LocalTaoWindow
import cp.player.app.platform.AppTray
import cp.player.app.platform.DesktopBackDispatcher
import cp.player.app.platform.DesktopCloseBehavior
import cp.player.app.platform.DesktopRenderTuning
import cp.player.app.platform.DesktopTrayState
import cp.player.app.platform.desktopTraySupported
import cp.player.app.platform.TaoPrimaryDisplay
import cp.player.app.platform.WindowDecorChoice
import cp.player.app.platform.WindowsWindowCorners
import cp.player.app.platform.WindowsSmtcControls
import cp.player.app.platform.installDesktopImageCacheLimit
import cp.player.app.monitoring.DesktopMonitoring
import cp.player.app.ui.component.DesktopTitleBar
import cp.player.app.ui.component.DesktopCloseDialog
import cp.player.app.ui.component.DesktopMiniPlayer
import cp.player.app.ui.util.DesktopShell
import cp.player.app.ui.util.next
import cp.player.app.ui.util.popToMainShell
import cp.player.app.version.AppVersion
import cp.player.core.MusicBackend
import cp.player.core.music.TrackSummary
import cp.player.core.playback.DesktopHiResEngine
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.core.provider.typed.QuickJsWarmup
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

/**
 * 窗口最小尺寸。
 *
 * 再窄下去 Expanded（≥840dp）布局会退化成手机布局，而且播放页的「封面 + 队列」双栏
 * 会被挤到互相压住——桌面窗口可以拖小，所以下限必须由程序兜住。
 */
private val MinWindowDpSize = DpSize(900.dp, 640.dp)

/** 手机预览模式窗口最小尺寸：允许开发者自由拉伸测试小屏与紧凑布局。 */
private val MobilePreviewMinWindowDpSize = DpSize(360.dp, 520.dp)

/** 迷你小窗模式的尺寸与下限 */
private val MiniWindowDpSize = DpSize(380.dp, 140.dp)

/** 初始窗口尺寸：一次到位展开侧边栏 + 主内容，避免用户第一眼看到手机布局。 */
private val DefaultWindowSize = DpSize(1320.dp, 860.dp)

/** 手机版界面预览默认尺寸：现代主流手机屏幕比例（约 20:9，如 412x892dp），处于 Compact 紧凑宽度。 */
private val MobilePreviewDefaultWindowSize = DpSize(412.dp, 892.dp)

/** 方向键快进/快退步长。 */
private const val SeekStepMs = 5_000L

/** 窗口尺寸落盘前的静默期：拖动过程中尺寸每帧都在变，停稳了再写。 */
private const val WindowSizeSaveDelayMs = 400L

/**
 * DWM 圆角的重试次数与间隔。
 *
 * 窗口从创建到真正 map 出来之间有一小段窗口期：那之前 `IsWindowVisible` 为假，
 * `WindowsWindowCorners` 按进程 ID 枚举顶层窗口时找不到它。50 × 100ms 覆盖冷启动；
 * 只有**主窗口**真的被读回成 ROUND 才算成功（Tao 的 16×16 事件窗口不算），
 * 所以试满之前不会提前退出。
 */
private const val WindowCornerRetryAttempts = 50
private const val WindowCornerRetryDelayMs = 100L

/**
 * 窗口发丝线的墨色：中性灰 10%。
 *
 * **刻意不按明暗主题换色**：这一层在 `AppTheme` 之外（主题宿主在 `App` 内部），
 * 读 `LocalIsDarkTheme` 会落到 CompositionLocal 的默认值 `false` —— 深色窗口上
 * 会画出一条 10% 黑线（实测就是一整圈 3 个亮度级的暗线，几乎看不见）。
 * 中性灰在深浅底上都各偏 ~13 个亮度级，两种主题下都是「看得见但不抢眼」。
 */
private val WindowEdgeHairlineInk = Color(0x1A808080)

/**
 * 窗口最外圈的 1px 半透明发丝线。
 *
 * Tao 的无边框窗口把非客户区整个吃掉，Win11 本来随边框一起画的那条 1px 窗口描边
 * 也就没了 —— 圆角有了，但窗口和背景之间是「糊」的，没有普通 Win11 窗口那种
 * 一圈极细的轮廓。这里自己补一条：半透明而不是实色，深色底上不会变成一条硬亮的线。
 *
 * 半径取 8dp：DWM `DWMWCP_ROUND` 的圆角在 96dpi 下就是 8px、随系统缩放走，
 * 而 Compose 的 dp 与系统缩放同一把尺，所以两层圆弧能对得上。
 * 画在内容的绘制末尾，纯绘制、不参与布局，不会挤动任何页面。
 */
private fun Modifier.windowEdgeHairline(): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = WindowEdgeHairlineInk,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(8.dp.toPx() - stroke / 2f),
        style = Stroke(width = stroke),
    )
}

/**
 * 窗口装饰固定为 **Tao 自绘标题栏路**，且必须在建窗之前定下来：
 *
 * - 窗口由 Nucleus Tao 创建并固定 `undecorated = true`，内容从客户区 (0,0) 开始；
 * - 标题栏、拖拽、双击最大化与三颗窗口钮只由 `DesktopTitleBar` 提供；
 * - 缩放边框由 Tao 的 `ResizeFrameDecoration` 负责，全程没有 `java.awt.Window`。
 *
 * ⚠️ 不能改回 `undecorated = false`：Tao 会在窗口顶部再画一套 CSD 窗口钮，
 * 与自绘标题栏叠在同一位置（2026-11-14「点 X 不进托盘」就是两套关闭入口）。
 *
 * [WindowDecorChoice] 的三态仍保留，用于日志与历史配置兼容；JBR 路依赖 AWT，
 * Tao 后端下不再提供。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String> = emptyArray()) {
    // ⚠️ 必须是第一句，且在任何 UI 之前：Windows 安装包由 Velopack 打，安装/升级/
    // 卸载时它会用 `--veloapp-*` 参数把本 exe 当钩子拉起并要求 15~30 秒内退出，
    // 超时直接杀掉 ⇒ 安装器报「安装未完成」。见 VelopackHooks.kt。
    exitIfVelopackHook(args)

    // JVM 启动后即刻派发多核并行加载：
    // 1. JNI 原生音频库提前完成动态库查找与 dlopen 加载
    DesktopHiResEngine.preloadAsync()
    // 2. JS QuickJS 原生运行库与环境预热
    QuickJsWarmup.warmupAsync()
    // 3. Provider 模块与后端曲库异步预热
    startBackendAsyncPrewarm()

    runDesktopApp(args)
}

/**
 * 桌面端统一应用入口。
 *
 * @param args 命令行参数（支持 `--mobile` 或 `-m` 进入手机版界面预览）
 * @param isMobilePreview 显式指定是否开启手机版界面预览模式
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun runDesktopApp(args: Array<String> = emptyArray(), isMobilePreview: Boolean = false) {
    val mobilePreview = isMobilePreview ||
        System.getProperty("cp.player.mobilePreview") == "true" ||
        args.contains("--mobile") ||
        args.contains("-m")

    // 必须最先执行：Skiko 在创建渲染器时首次读取 skiko.* 属性并固化，
    // 晚于这一步再写就不生效了。见 DesktopRenderTuning 的时序约束说明。
    DesktopRenderTuning.applyBeforeSkikoInit()

    // Coil 内存缓存上限必须在任何图片请求之前装上（SingletonImageLoader 首次
    // get() 后 factory 固化，晚了 setSafe 直接抛），见 DesktopImageCacheTuning 的说明。
    installDesktopImageCacheLimit()

    // Tao 窗口没有 AWT 对象，JBR 的 WindowDecorations 只能装在 `java.awt.Window` 上，
    // 所以「JBR 路」在 Tao 后端上不可用。这里只保留 [WindowDecorChoice] 里用户真正
    // 关心的那一层意图：UNDECORATED ⇒ Tao 完全无边框；其余 ⇒ Tao 自绘标题栏
    // （Windows/Linux 附带 CSD 边框与 DWM/GTK 阴影）。
    val decorMode = WindowDecorChoice.resolve()
    if (mobilePreview) {
        DesktopShell.isMobileMode = true
        println("[CPPlayer] 启动模式 = 手机版界面预览 (Mobile Preview，412×892 Compact 布局)")
    } else {
        println(
            "[CPPlayer] 窗口装饰 = $decorMode（来源：${WindowDecorChoice.lastSource}）→ " +
                "Tao 无边框路（窗控全部自绘，避免 CSD 与自绘标题栏叠出两套关闭钮）",
        )
        if (decorMode == WindowDecorChoice.Mode.JBR) {
            println("[CPPlayer] JBR 路依赖 AWT 窗口，Tao 后端无法启用；已改用 Tao 自绘标题栏")
        }
    }

    // Nucleus 3.0 只剩 Tao 一个窗口后端（AWT 后端已移除），窗口操作一律走
    // `NucleusWindow` / `TaoWindow`。必须把 `args` 传给 `nucleusApplication`：
    // 单实例转发、deep link 与 Windows AUMID 都靠原始命令行，空数组会静默丢掉它们。
    nucleusApplication(args) {
        ensureBackendInitialized()
        // 放在其它初始化之后立字据：这样音频原生库加载失败之类的无关崩溃
        // 不会被算到渲染后端头上，导致下次启动无端回退用户的设置。
        DesktopRenderTuning.beginStartupProbe()

        val windowState = rememberWindowState(
            size = remember { if (mobilePreview) loadMobileWindowSize() else loadWindowSize() },
            position = WindowPosition(Alignment.Center),
        )

        // 关窗行为：ASK（每次问）/ TRAY（最小化到托盘）/ EXIT（直接退出）。
        // 用户勾过「不再提示」的选择会被记住（见 DesktopCloseBehavior）。
        var closeBehavior by remember { mutableStateOf(DesktopCloseBehavior.load()) }
        var showCloseDialog by remember { mutableStateOf(false) }
        var dontAskAgain by remember { mutableStateOf(false) }
        // 关窗回调定义在 `DecoratedWindow(...)` 的参数位置上，那里拿不到窗口句柄，
        // 所以先把 `NucleusWindow` 存起来（Tao 窗口没有 AWT 句柄可借）。
        val windowRef = remember { mutableStateOf<NucleusWindow?>(null) }

        // 把窗口捞回来。托盘左键、托盘菜单「显示主界面」、迷你模式切换都走这一条 ——
        // 三处各写一遍的话，「隐藏后唤不回」这类问题会变成要找三个地方才开始查。
        val showMainWindow: () -> Unit = {
            windowState.isMinimized = false
            windowRef.value?.let { w ->
                runCatching { w.show() }
                runCatching { w.toFront() }
            }
        }

        // 隐藏到托盘。⚠️ 没有托盘实现时必须**退回真退出** —— 否则窗口藏了、又没有图标能
        // 把它叫回来，应用就变成一个人间蒸发的僵尸进程（任务栏里也找不到，只能去杀进程）。
        val hideToTray: () -> Unit = {
            if (DesktopTrayState.ensureInstalled()) {
                windowRef.value?.let { w -> runCatching { w.hide() } }
            } else {
                println("[CPPlayer] 本机没有系统托盘，无法最小化到托盘，改为退出")
                exitApplication()
            }
        }

        // 关闭入口只有这一条：原生 Alt+F4 / 任务栏关闭（`onCloseRequest`）与自绘的
        // 标题栏 / 迷你窗 / 手机预览关闭钮都走它。三处各写一套会让「关闭进托盘」在
        // 某一个入口上失效 —— 2026-11-14 自绘关闭钮直接 exitApplication 就是这么漏的。
        val requestClose: () -> Unit = {
            when (closeBehavior) {
                DesktopCloseBehavior.EXIT -> exitApplication()
                DesktopCloseBehavior.TRAY -> hideToTray()
                DesktopCloseBehavior.ASK -> {
                    // 每次都从「未勾选」开始：上一次勾没勾不该影响这一次的默认值。
                    dontAskAgain = false
                    showCloseDialog = true
                }
            }
        }

        // 托盘必须在窗口内容之外：窗口 hide() 之后它仍是唯一入口。菜单动作与文案都在
        // composable 内部维护，不再需要往一个全局 AWT 对象里注入回调。
        // 选择「最小化到托盘」的用户启动时就把图标装上：否则第一次关窗要等托盘
        // 首次渲染，窗口已经藏了、图标还没出来，中间会有一段「没有入口」的空窗期。
        LaunchedEffect(closeBehavior) {
            if (closeBehavior == DesktopCloseBehavior.TRAY) DesktopTrayState.ensureInstalled()
        }
        if (desktopTraySupported && DesktopTrayState.isVisible) {
            AppTray(
                onShowWindow = showMainWindow,
                onExit = { exitApplication() },
            )
        }

        val isMiniMode = DesktopShell.isMiniMode
        val isMiniPinned = DesktopShell.isMiniPinned
        val isMobileMode = DesktopShell.isMobileMode

        // 标题跟随当前曲目。只收敛成标题字符串再进组合，避免播放进度每 ~200ms
        // 刷新一次就把整个窗口内容重组一遍。
        var windowTitle by remember {
            mutableStateOf(windowTitleOf(AppModel.playback.state.value.currentTrack, isMobileMode))
        }
        LaunchedEffect(isMobileMode) {
            AppModel.playback.state
                .map { windowTitleOf(it.currentTrack, isMobileMode) }
                .distinctUntilChanged()
                .collect { windowTitle = it }
        }

        DecoratedWindow(
            onCloseRequest = requestClose,
            state = windowState,
            title = windowTitle,
            icon = AppWindowIcon.painter,
            alwaysOnTop = isMiniMode && isMiniPinned,
            resizable = !isMiniMode,
            onKeyEvent = ::handleDesktopShortcut,
            // ⚠️ 固定 `undecorated = true`：Tao 后端下 `false` 时 Nucleus 会在窗口
            // 顶部再画一套 CSD 标题栏（最小化 / 最大化 / 关闭），与 DesktopTitleBar
            // 的三颗窗口钮**叠在同一位置** —— 点击落到哪一层取决于绘制顺序。
            // 2026-11-14 的「点 X 不进托盘」就是两套关闭入口并存导致的。
            // 缩放边框由 Tao 的 ResizeFrameDecoration 负责；标题栏、拖拽、双击最大化
            // 与窗口钮只由本组件提供，是唯一的窗口 chrome。
            undecorated = true,
            // 创建期的初始下限；运行期模式切换由内容里的 LaunchedEffect 显式下发，
            // 因为 Tao 只在建窗时读取这个参数。
            minimumSize = when {
                isMiniMode -> MiniWindowDpSize
                isMobileMode -> MobilePreviewMinWindowDpSize
                else -> MinWindowDpSize
            },
        ) {
            val taoWindow = checkNotNull(LocalTaoWindow.current) {
                "CPPlayer desktop integrations require a Tao window"
            }
            LaunchedEffect(Unit) { AppModel.startCoverColorTracking() }

            // Win11 圆角：Tao 的窗口虽然有 WS_CAPTION | WS_THICKFRAME，但它的窗口壳
            // 会主动把 DWM 的 DWMWA_WINDOW_CORNER_PREFERENCE 写成 DONOTROUND（自己画 chrome），
            // 于是系统圆角被显式关掉了 —— 必须等窗口真正 map 出来后再改回 ROUND。
            // 原来的 AWT 路有一模一样的调用，Tao 迁移时被连带删掉了，才出现「圆角没了」。
            // 窗口 map 出来之前按 PID 找不到句柄，所以带重试；试满就放弃（圆角只是外观）。
            LaunchedEffect(Unit) {
                repeat(WindowCornerRetryAttempts) {
                    if (WindowsWindowCorners.applyRoundCorners()) return@LaunchedEffect
                    delay(WindowCornerRetryDelayMs)
                }
            }

            // 记录进入 Mini / Mobile 模式前的常规窗口尺寸与状态，以便还原。
            // 两套模式各记各的：手机预览里又切迷你时，退出迷你不能把手机尺寸覆盖掉。
            var mobilePreviousSize by remember { mutableStateOf<DpSize?>(null) }
            var mobilePreviousPlacement by remember { mutableStateOf<WindowPlacement?>(null) }
            var miniPreviousSize by remember { mutableStateOf<DpSize?>(null) }
            var miniPreviousPlacement by remember { mutableStateOf<WindowPlacement?>(null) }

            // 监听手机版预览模式动态切换
            LaunchedEffect(isMobileMode) {
                if (DesktopShell.isMiniMode) return@LaunchedEffect
                if (isMobileMode) {
                    mobilePreviousPlacement = windowState.placement
                    mobilePreviousSize = windowState.size

                    if (windowState.placement != WindowPlacement.Floating) {
                        windowState.placement = WindowPlacement.Floating
                    }
                    windowState.size = loadMobileWindowSize()
                } else {
                    mobilePreviousSize?.let { windowState.size = it }
                    mobilePreviousPlacement?.let { windowState.placement = it }
                }
            }

            // 监听 Mini 模式切换：切尺寸、退出最大化；resizable 与 alwaysOnTop
            // 由 `DecoratedWindow` 的参数响应式下发，不再命令式操作窗口。
            LaunchedEffect(isMiniMode) {
                if (isMiniMode) {
                    miniPreviousPlacement = windowState.placement
                    miniPreviousSize = windowState.size

                    if (windowState.placement != WindowPlacement.Floating) {
                        windowState.placement = WindowPlacement.Floating
                    }
                    windowState.size = MiniWindowDpSize
                } else {
                    miniPreviousSize?.let { windowState.size = it }
                    miniPreviousPlacement?.let { windowState.placement = it }
                }
            }

            // Tao 只在建窗时读 `minimumSize`，模式切换必须显式再下发一次。
            LaunchedEffect(isMiniMode, isMobileMode) {
                nucleusWindow.setMinimumSize(
                    when {
                        isMiniMode -> MiniWindowDpSize
                        isMobileMode -> MobilePreviewMinWindowDpSize
                        else -> MinWindowDpSize
                    },
                )
            }

            // 守护：在 Mini 模式期间，绝不允许 placement 变为 Maximized 或 Fullscreen
            LaunchedEffect(isMiniMode, windowState.placement) {
                if (isMiniMode && windowState.placement != WindowPlacement.Floating) {
                    windowState.placement = WindowPlacement.Floating
                    windowState.size = MiniWindowDpSize
                }
            }

            // 记住窗口尺寸：桌面端换一次显示器/改一次分辨率就丢布局，是很容易被抱怨的细节。
            // ⚠️ 处于 Mini 模式时禁止落盘，避免覆盖正常的完整窗口尺寸。
            // 手机预览模式独立落盘到专属 key，不覆盖常规桌面端尺寸。
            LaunchedEffect(Unit) {
                snapshotFlow { windowState.size }
                    .drop(1) // 首帧就是刚恢复出来的值，不必回写
                    .distinctUntilChanged()
                    .collectLatest { size ->
                        if (!DesktopShell.isMiniMode) {
                            delay(WindowSizeSaveDelayMs)
                            if (DesktopShell.isMobileMode) {
                                saveMobileWindowSize(size)
                            } else {
                                saveWindowSize(size)
                            }
                        }
                    }
            }
            StartupHealthProbe()
            // `windowRef` 要在这里补上 —— 关窗回调在 `DecoratedWindow` 参数里，
            // 那里拿不到窗口句柄。
            LaunchedEffect(nucleusWindow) {
                windowRef.value = nucleusWindow
                WindowsSmtcControls.primaryWindowHandle = taoWindow.nativeHandle
            }

            if (isMiniMode) {
                AppTheme {
                    DesktopMiniPlayer(
                        taoWindow = taoWindow,
                        onRestore = { DesktopShell.isMiniMode = false },
                        onMinimize = {
                            if (DesktopTrayState.ensureInstalled()) {
                                hideToTray()
                            } else {
                                windowState.isMinimized = true
                            }
                        },
                        onClose = requestClose,
                    )
                }
            } else {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().windowEdgeHairline(),
                ) {
                    if (isMobileMode) {
                        cp.player.app.ui.component.MobilePreviewTitleBar(
                            taoWindow = taoWindow,
                            onRestoreDesktop = { DesktopShell.isMobileMode = false },
                            onMinimize = { windowState.isMinimized = true },
                            onClose = requestClose,
                        )
                    }
                    App(
                        // 手机版预览模式下不注入桌面自绘标题栏，由顶部抓手栏提供拖拽与切回桌面支持，
                        // 内部自动激活 LocalWindowChromeActive = false，呈现原汁原味的手机版界面与顶栏
                        titleBar = if (isMobileMode) null else { navigator ->
                            DesktopTitleBar(
                                taoWindow = taoWindow,
                                windowState = windowState,
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
                                // Tao 后端没有原生窗口钮，自绘关闭钮与 `onCloseRequest`
                                // 共用同一条 [requestClose] 链路（托盘 / 确认框都生效）。
                                onClose = requestClose,
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

            // 关窗确认框（只可能出现在 `onCloseRequest` 走到 ASK 分支时）。
            // 它是独立窗口（M3 AlertDialog 在桌面的实现方式），所以与 App 平级摆放即可。
            if (showCloseDialog) {
                DesktopCloseDialog(
                    dontAskAgain = dontAskAgain,
                    onDontAskAgainChange = { dontAskAgain = it },
                    onMinimizeToTray = {
                        if (dontAskAgain) {
                            closeBehavior = DesktopCloseBehavior.TRAY
                            DesktopCloseBehavior.save(closeBehavior)
                        }
                        showCloseDialog = false
                        hideToTray()
                    },
                    onExit = {
                        if (dontAskAgain) {
                            closeBehavior = DesktopCloseBehavior.EXIT
                            DesktopCloseBehavior.save(closeBehavior)
                        }
                        showCloseDialog = false
                        exitApplication()
                    },
                    onDismiss = { showCloseDialog = false },
                )
            }
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
    if (width < MinWindowDpSize.width.value || height < MinWindowDpSize.height.value) return DefaultWindowSize
    // ⚠️ 上限取**工作区**，不是整屏（任务栏也算进来就会铺满输出）。
    // 窗口一旦铺满输出，Windows 的 Fullscreen Optimizations 可能把它提升为全屏呈现，
    // HDR 显示器上退出时桌面可能停在坏的色彩状态。这里用 Tao 原生显示器查询收口，
    // 不依赖 AWT Toolkit。
    val work = runCatching { TaoPrimaryDisplay.workAreaDp() }.getOrNull()
        ?: return DpSize(width.dp, height.dp)
    return DpSize(
        width.coerceAtMost(work.width.value).dp,
        height.coerceAtMost(work.height.value).dp,
    )
}

private fun saveWindowSize(size: DpSize) {
    runCatching {
        windowSettings().putString(WINDOW_SIZE_KEY, "${size.width.value}x${size.height.value}")
    }
}

private const val WINDOW_SIZE_KEY = "desktop.window.size"
private const val MOBILE_WINDOW_SIZE_KEY = "desktop.mobile_window.size"

/**
 * 恢复上次手机预览模式的窗口尺寸。
 */
private fun loadMobileWindowSize(): DpSize {
    val raw = runCatching { windowSettings().getString(MOBILE_WINDOW_SIZE_KEY) }.getOrNull()
        ?: return MobilePreviewDefaultWindowSize
    val parts = raw.split('x')
    if (parts.size != 2) return MobilePreviewDefaultWindowSize
    val width = parts[0].toFloatOrNull() ?: return MobilePreviewDefaultWindowSize
    val height = parts[1].toFloatOrNull() ?: return MobilePreviewDefaultWindowSize
    if (width < 320 || height < 400) return MobilePreviewDefaultWindowSize
    val work = runCatching { TaoPrimaryDisplay.workAreaDp() }.getOrNull()
        ?: return DpSize(width.dp, height.dp)
    return DpSize(
        width.coerceAtMost(work.width.value).dp,
        height.coerceAtMost(work.height.value).dp,
    )
}

private fun saveMobileWindowSize(size: DpSize) {
    runCatching {
        windowSettings().putString(MOBILE_WINDOW_SIZE_KEY, "${size.width.value}x${size.height.value}")
    }
}

/** 窗口标题：有曲目时显示「曲名 - 歌手 · CPPlayer」，便于在任务栏/Alt+Tab 里辨认。 */
private fun windowTitleOf(track: TrackSummary?, isMobilePreview: Boolean = false): String {
    val suffix = if (isMobilePreview) " (手机版预览)" else ""
    if (track == null) return "CPPlayer$suffix"
    val artist = track.artist.trim()
    return if (artist.isEmpty()) "${track.name} · CPPlayer$suffix" else "${track.name} - $artist · CPPlayer$suffix"
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
    if (AppVersion.isDesktopRunOrDebug && (event.isCtrlPressed || event.isMetaPressed) && event.isShiftPressed && event.key == androidx.compose.ui.input.key.Key.M) {
        DesktopShell.isMobileMode = !DesktopShell.isMobileMode
        return true
    }
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

private val backendInitLock = Any()
@Volatile private var backendReady = false

private fun startBackendAsyncPrewarm() {
    Thread {
        ensureBackendInitialized()
    }.apply {
        isDaemon = true
        name = "DesktopBackend-Prewarm"
    }.start()
}

private fun ensureBackendInitialized() {
    if (backendReady) return
    synchronized(backendInitLock) {
        if (backendReady) return
        MusicBackend.init(
            context = PlatformContext(),
            settings = defaultSettingsStorage(),
            applicationVersionName = BuildInfo.VERSION_NAME,
        )
        AppModel.markInitialized()
        val isDesktopRun = System.getProperty("cp.player.isDesktopRun") == "true" ||
            System.getProperty("cp.player.mobilePreview") == "true" ||
            System.getProperty("idea.active") != null ||
            System.getProperty("idea.version") != null ||
            (System.getProperty("java.class.path", "").let { it.contains("build/classes") || it.contains("build\\classes") })
        AppVersion.init(
            versionName = BuildInfo.VERSION_NAME,
            versionCode = BuildInfo.VERSION_CODE,
            gitSha = BuildInfo.GIT_SHA,
            isDesktop = true,
            releaseChannel = BuildInfo.RELEASE_CHANNEL,
            isDesktopRun = isDesktopRun,
        )
        DesktopMonitoring.init(
            versionName = BuildInfo.VERSION_NAME,
            isDebug = BuildInfo.RELEASE_CHANNEL != "stable",
        )
        backendReady = true
    }
}
