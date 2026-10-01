package cp.player.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import cp.player.app.platform.WindowsWindowCorners
import cp.player.app.ui.component.DesktopTitleBar
import cp.player.app.ui.screen.AccountScreen
import cp.player.app.ui.util.DesktopShell
import cp.player.app.version.AppVersion
import cp.player.core.MusicBackend
import cp.player.core.music.TrackSummary
import cp.player.core.playback.PlaybackController
import cp.player.core.playback.PlaybackUiState
import cp.player.app.ui.util.SeekAvailability
import cp.player.core.util.PlatformContext
import cp.player.core.util.defaultSettingsStorage
import kotlinx.coroutines.delay
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
 * DWM 圆角的重试次数与间隔。
 *
 * 窗口从「创建」到「真正 map 出来、`IsWindowVisible` 为真」之间有一小段窗口期，
 * 在那之前按进程 ID 找不到任何可见窗口。20 × 100ms 足够覆盖冷启动。
 */
private const val WindowCornerAttempts = 20
private const val WindowCornerRetryDelayMs = 100L

// `WindowDecoration` 目前还是实验 API（要显式 opted-in）。用它的唯一理由是**能指定缩放抓手
// 厚度**：`undecorated = true` 等价于默认的 8dp，那圈抓手会压住贴着窗口边缘的控件。
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
    // 必须最先执行：Skiko 在创建渲染器时首次读取 skiko.* 属性并固化，
    // 晚于这一步再写就不生效了。见 DesktopRenderTuning 的时序约束说明。
    DesktopRenderTuning.applyBeforeSkikoInit()

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
            onKeyEvent = ::handleDesktopShortcut,
            // 无边框：系统标题栏整体交给 DesktopTitleBar 自绘。
            //
            // 拖拽缩放**不需要额外代码**：ComposeWindow 内置 UndecoratedWindowResizer，
            // 在 isUndecorated() && isResizable() 时自动在窗口四周铺一圈透明抓手并切换光标。
            // 注意 resizable 默认就是 true，别为了「无边框」把它关掉，否则缩放会一起消失。
            //
            // ⚠️ 抓手厚度**刻意指定**而不是用 `undecorated = true`（后者等价于默认 8dp）：
            // 那圈抓手压在窗口最外圈、会和贴边的控件抢手势 —— 桌面滚条就贴在右边缘
            // （`Alignment.CenterEnd`），厚 8dp 时「拖滚条」会变成「缩放窗口」。
            // 这里压到 6dp，滚条那边还额外内缩了同样距离（见 DesktopScrollbars.desktop.kt）。
            decoration = WindowDecoration.Undecorated(6.dp),
        ) {
            // 最小尺寸只能命令式设置：WindowState 没有 minSize 字段。
            LaunchedEffect(Unit) { window.minimumSize = MinWindowSize }
            // 无边框窗口在 Win11 上是直角（纯 WS_POPUP 吃不到系统的自动圆角），这里手动 opt-in。
            // 窗口此刻可能还没真正 map 出来（IsWindowVisible 为假就找不到句柄），所以带重试；
            // 试满就放弃，圆角只是外观，绝不能因此挡住启动。
            LaunchedEffect(Unit) {
                repeat(WindowCornerAttempts) {
                    if (WindowsWindowCorners.applyRoundCorners()) return@LaunchedEffect
                    delay(WindowCornerRetryDelayMs)
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
                        // 标题：当前路由页自己声明的优先（`CpRouteScaffold` / `DesktopRouteTitle`），
                        // 没有声明时回落到 `MainScreen` 发布的主壳层标题。`MainScreen` 被 push 出去的
                        // 页面盖住后会离开组合、不再发布，所以没有这一层回落之外的上层来源，
                        // 窗口标题会停在旧值（从标题栏点账号，标题却还写着「首页」）。
                        title = DesktopShell.routeTitle ?: DesktopShell.pageTitle,
                        // 可返回 = 「Navigator 还能出栈」**或**「主壳层开着内嵌面板」。
                        // 只判后者（曾经如此）会让所有 push 出去的路由页在标题栏上没有返回键。
                        canGoBack = navigator.size > 1 || DesktopShell.pageCanGoBack,
                        onBack = {
                            // 与 Esc 走**同一条链路**：先让页面自己的处理器拿到
                            // （播放页展开态、歌单多选），没人处理再退化为出栈 / 收起内嵌面板。
                            // 两处各写一套判据的话，「Esc 能退、点返回键不能退」这类漂移迟早出现。
                            if (!DesktopBackDispatcher.dispatch()) {
                                if (navigator.size > 1) navigator.pop()
                                else DesktopShell.backRequested = true
                            }
                        },
                        onClose = ::exitApplication,
                        onOpenAccount = { navigator.push(AccountScreen()) },
                        // 「设置」在桌面是**右侧内嵌面板**，开关是 MainScreen 的局部状态，
                        // 标题栏隔着 Navigator 够不到 ⇒ 走 DesktopShell 这条单向指令。
                        onOpenSettings = { DesktopShell.settingsRequested = true },
                        // 搜索同样走指令通道：切到搜索 tab 由 MainScreen 做，关键词由
                        // SearchScreen 消费并喂给它自己的 ScreenModel。
                        onSearch = { DesktopShell.pendingSearchQuery = it },
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
 * 只处理**没有被下层消费**的按键：Compose 的 Main 派发阶段是子节点先收，
 * 所以输入框里的空格、滑条/按钮上的方向键都不会被这里抢走。
 */
private fun handleDesktopShortcut(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    // Esc 等价于安卓返回键，由各页面通过 BackHandler 注册处理器
    if (event.key == Key.Escape) return DesktopBackDispatcher.dispatch()

    if (!AppModel.initialized.value) return false
    val controller = AppModel.playback
    val state = controller.state.value

    return when {
        // 切歌要排在 seek 前面：Ctrl+Shift+← 同时满足 Ctrl+←
        event.isCtrlPressed && event.isShiftPressed && event.key == Key.DirectionLeft -> {
            controller.skipPrevious(); true
        }
        event.isCtrlPressed && event.isShiftPressed && event.key == Key.DirectionRight -> {
            controller.skipNext(); true
        }
        event.isCtrlPressed && event.key == Key.DirectionLeft -> seekBy(controller, state, -SeekStepMs)
        event.isCtrlPressed && event.key == Key.DirectionRight -> seekBy(controller, state, SeekStepMs)
        event.key == Key.Spacebar -> { controller.togglePlayPause(); true }
        else -> false
    }
}

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
