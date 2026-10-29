package cp.player.app.platform

import cp.player.app.AppModel
import cp.player.app.i18n.MessageNotifyStrings
import cp.player.app.ui.component.TrayMenuAction
import cp.player.app.ui.component.TrayMenuLayout
import cp.player.app.ui.util.DesktopShell
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JWindow
import kotlin.math.roundToInt

/** 一次弹出所用的数据快照。菜单是瞬时的，不做实时刷新（原生菜单同样如此）。 */
internal class TrayMenuModel(
    val title: String,
    val subtitle: String?,
    val isPlaying: Boolean,
    val isMiniMode: Boolean,
    val strings: MessageNotifyStrings,
)

/**
 * 托盘右键菜单。
 *
 * ## 结构
 *
 * 一句话：**Compose 离屏渲染成图，AWT 窗口贴出去。**
 *
 * - 视觉在 [cp.player.app.ui.component.TrayMenuContent]（Compose），由 [TrayMenuScene]
 *   渲成 `BufferedImage` —— 字体、图标、圆角、投影全是设计稿那一套；
 * - 显示是 `JWindow` + `JLabel(ImageIcon)` —— `setVisible(true)` 是确定的，
 *   不像 Compose 窗口那样可能「窗口在、内容空」。
 *
 * 前两版分别试过「Compose 独立窗口」（内容空 + 任务栏占位）和「AWT 手绘」
 * （字体拿不到项目字库、图标靠路径硬画，观感差一截），这条路是两者的交集。
 *
 * ## 三个必须做对的点
 *
 * - `Window.Type.POPUP` + `setFocusableWindowState(false)` + `setAutoRequestFocus(false)`：
 *   **不进任务栏、不进 Alt+Tab、不抢焦点**。普通 `JWindow` 仍会在任务栏占一格。
 * - 关闭靠**全局 AWT 事件**（`addAWTEventListener`）而不是窗口失焦：POPUP 窗口
 *   根本不接收焦点，`WindowFocusListener` 这条路走不通。
 * - 命中测试用 [TrayMenuLayout] 的同一份常量：图片不会告诉你哪里能点，
 *   两边各算各的必然漂移。
 */
object TrayMenuPopup {

    private var window: JWindow? = null
    private var scene: TrayMenuScene? = null
    private var globalListener: AWTEventListener? = null

    /**
     * 弹出请求的序号。
     *
     * 窗口是在 `invokeLater` 里建的，而右键可以连点 —— 没有这个序号的话，连点两次
     * 会各建一个窗口，第二个把 [window] 覆盖掉，第一个成了**关不掉也看不见的野窗口**。
     * 规则很简单：只有「最后一次请求」才真正建窗口，其余全部作废。
     */
    private var requestId = 0

    /**
     * 最近一次弹出失败的原因（成功时为 null）。
     *
     * 弹出的整段都包在 `runCatching` 里 —— 托盘菜单失败不该拖垮 EDT，但**不能静默**：
     * 托盘是「主窗口藏起来后唯一的入口」，它坏掉时用户只会看到「右键没反应」，
     * 从界面上完全无法推断发生了什么。冒烟测试和排查都靠它。
     */
    var lastFailure: String? = null
        private set

    val isShowing: Boolean get() = window?.isVisible == true

    /**
     * 已显示窗口的实际尺寸（没有窗口时 null）。
     *
     * 冒烟测试靠它钉住「窗口存在但只有几个像素」这类事故：那时 `isShowing` 仍然是
     * true，只有比对尺寸能抓到。
     */
    internal val currentSize: Dimension? get() = window?.size

    /**
     * 在光标处弹出菜单。
     *
     * @param anchor 光标屏幕坐标（`TrayIcon` 事件里给的就是屏幕坐标）
     */
    fun show(anchor: Point) {
        lastFailure = null
        val model = runCatching { currentModel() }.getOrElse { failure ->
            lastFailure = "model: $failure"
            println("[CPPlayer] 托盘菜单取数据失败：$failure")
            return
        }
        dismiss()
        val id = ++requestId

        EventQueue.invokeLater {
            if (id != requestId) return@invokeLater
            runCatching {
                val win = JWindow().apply {
                    type = Window.Type.POPUP
                    isAlwaysOnTop = true
                    setFocusableWindowState(false)
                    setAutoRequestFocus(false)
                    name = "CPPlayerTrayMenu"
                }
                // ⚠️ 必须在 `pack()`（窗口变成 displayable）**之前**设。
                // 不发这一句的话：图片四角是透明的，但窗口本身是不透明的，
                // 透明区域露出窗口底色 —— 看起来就是「菜单外面套了个正方形大边框」。
                val translucent = runCatching {
                    win.background = java.awt.Color(0, 0, 0, 0)
                }.isSuccess
                if (!translucent) {
                    // 少数环境（远程桌面 / 旧驱动）不支持 per-pixel translucency。
                    // 那时只能接受方角 —— 但要说出来，否则又变成「为什么这里有个框」。
                    println("[CPPlayer] 当前环境不支持窗口透明，托盘菜单将显示为直角矩形")
                }
                val density = runCatching { win.graphicsConfiguration.defaultTransform.scaleX.toFloat() }
                    .getOrDefault(1f)
                    .takeIf { it > 0f } ?: 1f
                val shadowPx = (TrayMenuLayout.Shadow * density).roundToInt()

                val menuScene = TrayMenuScene(density, model)
                val label = JLabel()
                label.isOpaque = false
                label.icon = ImageIcon(menuScene.render(null))

                win.contentPane = label
                win.pack()

                val menuWidth = (TrayMenuLayout.Width * density).roundToInt()
                val menuHeight = (TrayMenuLayout.Height * density).roundToInt()
                win.setLocation(placeNear(anchor, menuWidth, menuHeight, shadowPx))

                var hovered: TrayMenuAction? = null
                fun updateHover(action: TrayMenuAction?) {
                    if (hovered == action) return
                    hovered = action
                    label.icon = ImageIcon(menuScene.render(action))
                    label.repaint()
                }

                fun hit(e: MouseEvent): TrayMenuAction? = TrayMenuLayout.actionAt(
                    x = (e.x - shadowPx) / density,
                    y = (e.y - shadowPx) / density,
                )

                label.addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent) = updateHover(hit(e))
                })
                label.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val action = hit(e) ?: return
                        dismiss()
                        perform(action)
                    }

                    override fun mouseExited(e: MouseEvent) = updateHover(null)
                })

                win.isVisible = true
                win.toFront()
                window = win
                scene = menuScene
                installGlobalListener(win)
                println("[CPPlayer] 托盘菜单已弹出 at ${win.location} size=${win.size} translucent=$translucent")
            }.onFailure { failure ->
                lastFailure = failure.toString()
                println("[CPPlayer] 托盘菜单弹出失败：$failure")
                failure.printStackTrace()
            }
        }
    }

    /** 收起菜单（没显示时是无操作）。同时作废还没执行的那次弹出请求。 */
    fun dismiss() {
        requestId++
        globalListener?.let { runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) } }
        globalListener = null
        scene?.close()
        scene = null
        window?.let { w ->
            runCatching { w.isVisible = false }
            runCatching { w.dispose() }
        }
        window = null
    }

    /**
     * 点击菜单外 / Esc 关闭。
     *
     * 用 `MouseInfo` 而不是事件自带的坐标：`AWTEventListener` 拿到的是**目标组件坐标**，
     * 而菜单本身可能没被点到（点在桌面上时连组件都没有），只有屏幕坐标是通用的。
     */
    private fun installGlobalListener(win: JWindow) {
        val listener = AWTEventListener { event: AWTEvent ->
            when (event) {
                is MouseEvent -> {
                    if (event.id == MouseEvent.MOUSE_PRESSED) {
                        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                        if (pointer != null && !win.bounds.contains(pointer)) dismiss()
                    }
                }

                is KeyEvent -> {
                    if (event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ESCAPE) dismiss()
                }
            }
        }
        globalListener = listener
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                listener,
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
            )
        }
    }

    private fun currentModel(): TrayMenuModel {
        val strings = DesktopTray.stringsForTray()
        val state = runCatching { AppModel.playback.state.value }.getOrNull()
        val track = state?.currentTrack
        return TrayMenuModel(
            title = track?.name ?: strings.trayNothingPlaying,
            subtitle = track?.artist?.takeIf { it.isNotBlank() },
            isPlaying = state?.isPlaying == true,
            isMiniMode = DesktopShell.isMiniMode,
            strings = strings,
        )
    }

    private fun perform(action: TrayMenuAction) {
        when (action) {
            TrayMenuAction.SHOW_WINDOW -> DesktopTray.onShowWindow?.invoke()
            TrayMenuAction.TOGGLE_MINI_MODE -> {
                DesktopShell.isMiniMode = !DesktopShell.isMiniMode
                DesktopTray.onShowWindow?.invoke()
            }
            // 播放控制一律包 runCatching：`AppModel.playback` 在未初始化时会抛，
            // 而这条链路的调用方是 AWT 事件循环 —— 让异常冒出去会直接打断 EDT。
            TrayMenuAction.PREVIOUS -> runCatching { AppModel.playback.skipPrevious() }
            TrayMenuAction.PLAY_PAUSE -> runCatching { AppModel.playback.togglePlayPause() }
            TrayMenuAction.NEXT -> runCatching { AppModel.playback.skipNext() }
            TrayMenuAction.EXIT -> DesktopTray.onExit?.invoke()
        }
    }

    /**
     * 窗口左上角落在哪（**像素**）。
     *
     * 传入的是**菜单本体**尺寸，窗口比它四周各多一圈投影留白 [shadowPx]。
     * 托盘在屏幕底边 ⇒ 优先**向上**展开（菜单底边贴着光标、让开一小格）；
     * 上方放不下才翻到下方，最后按光标所在屏的工作区（已扣任务栏）钳一次。
     */
    private fun placeNear(anchor: Point, menuWidth: Int, menuHeight: Int, shadowPx: Int): Point {
        val gc = runCatching { MouseInfo.getPointerInfo()?.device?.defaultConfiguration }
            .getOrNull()
            ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration
        val bounds = gc.bounds
        val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }
            .getOrNull() ?: java.awt.Insets(0, 0, 0, 0)
        val workLeft = bounds.x + insets.left
        val workTop = bounds.y + insets.top
        val workRight = bounds.x + bounds.width - insets.right
        val workBottom = bounds.y + bounds.height - insets.bottom

        val gap = 6
        val windowWidth = menuWidth + shadowPx * 2
        val windowHeight = menuHeight + shadowPx * 2

        // 先按「菜单本体」算，再整体减去留白得到窗口位置。
        var x = anchor.x - shadowPx
        var y = anchor.y - menuHeight - gap - shadowPx
        if (y < workTop) y = anchor.y + gap - shadowPx
        x = x.coerceIn(workLeft, (workRight - windowWidth).coerceAtLeast(workLeft))
        y = y.coerceIn(workTop, (workBottom - windowHeight).coerceAtLeast(workTop))
        return Point(x, y)
    }
}
