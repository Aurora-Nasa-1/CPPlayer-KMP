package cp.player.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * 桌面窗口的「最大化」几何，以及「窗口不该大于工作区」这条不变量。
 *
 * ## 为什么不能用 `WindowState.placement = Maximized`
 *
 * 它最终落到 `setExtendedState(MAXIMIZED_BOTH)`（已 javap 核实 `ComposeWindow.setMaximized`），
 * 而**无边框窗口（WS_POPUP）在 Windows 上会被撑到整个显示器**、连任务栏一起盖住。
 * 实测（屏幕 2560×1440，任务栏 48px）：
 *
 * ```
 * 工作区             = 0,0,2560,1392
 * 无边框最大化后      = 0,0,2560,1440   ← 盖住任务栏 ⇒ 表现就是「独占全屏」
 * 有边框最大化后      = -8,-8,2576,1408 ← 正常（外扩 8px 边框，任务栏仍在上层）
 * ```
 *
 * 还试过「先原生最大化、再把 bounds 纠正回工作区」：**没用** —— 实测 `setBounds` 会把
 * `extendedState` 直接清回 `NORMAL`，等于白做一步。所以这里干脆自己接管最大化状态，
 * 按工作区设 bounds。
 *
 * 代价：`extendedState` 始终是 `NORMAL`，Windows 不认为窗口「已最大化」（Win+↓ 之类
 * 原生快捷键不会认得它）。换来的是任务栏不会被压掉 —— 这笔交易划算。
 *
 * ## 为什么「窗口不能大于工作区」是硬约束
 *
 * 无边框窗口一旦盖住**整个输出**，Windows 的 Fullscreen Optimizations 会把它提升为
 * 全屏呈现（独立翻转 / 独占），**这一步可能连带切换显示模式**；在 HDR 显示器上，
 * 退出时桌面可能停在坏的色彩/模式状态，表现为 SDR 内容闪烁。
 * 应用侧（以及 Skiko）都**没有**任何 Windows 全屏 / 改显示模式的 API
 * （已核实：Skiko 原生库只有 macOS 的 `SetFullscreenNative`，且不导入 `SetFullscreenState`、
 * `ChangeDisplaySettings`）—— 也就是说这个模式切换只能是 Windows 自己发起的，
 * 唯一能触发它的就是「窗口铺满输出」。所以这条约束要在这里收口。
 */
internal object DesktopWindowPlacement {

    /** 窗口所在显示器的工作区（扣掉任务栏 / 停靠栏）。 */
    fun workAreaOf(window: Window): Rectangle = workAreaOf(window.graphicsConfiguration)

    /**
     * 主显示器的工作区。
     *
     * 窗口还没创建时（例如启动前读持久化的窗口尺寸）也要能问「工作区多大」，
     * 所以单独开一个入口，而不是硬塞一个假窗口进去。
     */
    fun primaryWorkArea(): Rectangle = workAreaOf(
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration,
    )

    private fun workAreaOf(gc: GraphicsConfiguration?): Rectangle {
        if (gc == null) {
            val screen = Toolkit.getDefaultToolkit().screenSize
            return Rectangle(0, 0, screen.width, screen.height)
        }
        val screen = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(
            screen.x + insets.left,
            screen.y + insets.top,
            (screen.width - insets.left - insets.right).coerceAtLeast(1),
            (screen.height - insets.top - insets.bottom).coerceAtLeast(1),
        )
    }
}

/**
 * 「最大化到工作区」的控制器。
 *
 * 它替掉了 `WindowState.placement`，所以必须自己记住**还原尺寸**：
 * 最大化前是浮动状态时的 bounds（由 [install] 里的监听持续记录），
 * 这样从最大化还原回去不会跑到屏幕左上角、也不会丢掉用户调好的大小。
 */
internal class WindowMaximizer(private val window: Window) {

    /** 当前是否最大化。Compose 状态：标题栏据此换图标、禁用拖拽、双击取反。 */
    var isMaximized by mutableStateOf(false)
        private set

    /** 浮动状态下的最近 bounds（还原目标）。 */
    private var floatingBounds: Rectangle? = null

    /** 本次最大化前的 bounds。 */
    private var restoreBounds: Rectangle? = null

    /** 诊断去重：只在「从没盖住变成盖住」的那一刻报一次，不刷屏。 */
    private var warnedCoveringOutput = false

    fun toggle() {
        if (isMaximized) restore() else maximize()
    }

    fun maximize() {
        restoreBounds = floatingBounds ?: window.bounds
        // ⚠️ 必须**先**置 isMaximized 再改 bounds：`setBounds` 会同步触发下面那个
        // componentResized 监听，此时若 isMaximized 还是 false，它会把自己刚记下的
        // 「浮动 bounds」覆盖成工作区 —— 第一次最大化看起来正常，**第二次就还原不回去了**。
        isMaximized = true
        window.bounds = DesktopWindowPlacement.workAreaOf(window)
        // 最大化后必须**去掉圆角**。Windows 只在**它认为窗口已最大化**时才自动去圆角，而我们的
        // 最大化是自己按工作区设 bounds 的（`extendedState` 始终是 NORMAL）⇒ 系统不认为它
        // 最大化了，DWM 会继续给圆角，于是**四个角上露出桌面**，看起来就是「最大化之后缺了四个角」。
        // 实测（窗口摆成 2560×1392 的工作区）：
        //   DWMWCP_ROUND      → 四角 = #07011B / #04000E / #0D0125 / #320907（都是桌面）
        //   DWMWCP_DONOTROUND → 四角 = #3060C0（窗口填充色）
        WindowsWindowCorners.setRounded(false)
    }

    fun restore() {
        restoreBounds?.let { window.bounds = it }
        isMaximized = false
        WindowsWindowCorners.setRounded(true)
    }

    /**
     * 装上监听，返回卸载函数。三件事：
     *
     * 1. **持续记录浮动时的 bounds** —— 还原目标不能只记一次，用户可能最大化 / 还原好几轮，
     *    中间还会拖动缩放。
     * 2. **兜住「原生最大化」路径**（Win+↑、任务栏右键、系统菜单）：它同样会把 WS_POPUP
     *    撑到整个显示器，和上面那个坑是同一个。这条路径我们没走 `WindowState`，所以要自己接。
     * 3. **报告「窗口盖住了整个输出」** —— 这是 Windows 把它提升为全屏呈现的唯一触发条件，
     *    而全屏呈现在 HDR 显示器上可能连带切显示模式。这条日志是给「本机复现不了、
     *    但用户报了」准备的：真机上一旦出现，就能立刻确认是不是走了这条路。
     */
    fun install(): () -> Unit {
        val componentListener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = onBoundsChanged()

            override fun componentResized(e: ComponentEvent) = onBoundsChanged()

            private fun onBoundsChanged() {
                if (!isMaximized) floatingBounds = window.bounds
                reportIfCoveringOutput()
            }
        }
        val stateListener = object : WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                val nativeMaximized =
                    (e.newState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
                if (nativeMaximized) {
                    restoreBounds = floatingBounds
                    // 同样先置位再改 bounds，理由见 maximize()。
                    isMaximized = true
                    window.bounds = DesktopWindowPlacement.workAreaOf(window)
                    // 原生最大化在 Windows 那边本来就是「已最大化」，圆角会被自动去掉；
                    // 但我们紧接着把 bounds 改回工作区，会让它又变回普通窗口 —— 这里显式去圆角，
                    // 免得出现「系统去了一次、我们又加回来」的闪烁。
                    WindowsWindowCorners.setRounded(false)
                }
            }
        }
        window.addComponentListener(componentListener)
        window.addWindowStateListener(stateListener)
        floatingBounds = window.bounds
        reportIfCoveringOutput()
        return {
            window.removeComponentListener(componentListener)
            window.removeWindowStateListener(stateListener)
        }
    }

    private fun reportIfCoveringOutput() {
        val gc = window.graphicsConfiguration ?: return
        val screen = gc.bounds
        val bounds = window.bounds
        val covering = bounds.width >= screen.width && bounds.height >= screen.height
        if (covering && !warnedCoveringOutput) {
            warnedCoveringOutput = true
            val mode = runCatching { gc.device.displayMode }.getOrNull()
            println(
                "[CPPlayer] ⚠️ 窗口盖住了整个输出（bounds=$bounds screen=$screen " +
                    "刷新率=${mode?.refreshRate}Hz）。Windows 会据此把它提升为全屏呈现，" +
                    "在 HDR 显示器上可能连带切换显示模式 —— 若桌面随后出现 SDR 内容闪烁，" +
                    "请把这一行连同出现时间一起反馈。",
            )
        } else if (!covering) {
            warnedCoveringOutput = false
        }
    }
}
