package cp.player.app.platform

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.EventQueue
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.RoundRectangle2D
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.Timer
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 桌面端秒开原生启动提示窗口（Splash Window）。
 *
 * ## 为什么需要它
 * Compose Desktop 应用在启动时需要经历 JVM 预热、类加载、Skiko 原生渲染库解压与初始化、
 * 音频后端加载以及 Compose 第一帧排版，在冷启动或性能一般的设备上可能需要 1~3 秒才出主界面。
 *
 * 通过在 `main()` 的**最首行**（在任何 Compose / Skiko / Coroutines 等重型类加载之前）直接
 * 呼出纯 AWT/Swing 轻量窗口：
 * - 耗时小于 50ms 即可呈现在屏幕中央；
 * - 具备高 DPI 缩放适配、圆角毛玻璃/深色容器、CPPlayer 品牌图标与平滑渐变加载条；
 * - 在 Compose 主窗口完成第一帧组合并显示后，渐变淡出并释放资源。
 */
object DesktopSplashWindow {

    private const val ICON_RESOURCE = "/cpplayer/icon.png"

    @Volatile
    private var window: JWindow? = null

    @Volatile
    private var animTimer: Timer? = null

    @Volatile
    private var currentStatus: String = "正在启动应用..."

    @Volatile
    private var isDismissed = false

    /**
     * 立即呈现秒开启动提示窗口。
     *
     * @param isMobilePreview 是否处于手机版预览模式
     */
    fun show(isMobilePreview: Boolean = false) {
        if (GraphicsEnvironment.isHeadless()) return
        if (window != null) return

        // 如果存在底层 JVM 原生 C 级 splash（-splash 参数），先关掉由本窗口接管动态进度
        runCatching {
            java.awt.SplashScreen.getSplashScreen()?.close()
        }

        EventQueue.invokeLater {
            if (isDismissed || window != null) return@invokeLater
            try {
                val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
                val defaultScreen = ge.defaultScreenDevice
                val gc = defaultScreen.defaultConfiguration
                val scale = runCatching { gc.defaultTransform.scaleX.toFloat() }.getOrDefault(1f).coerceAtLeast(1f)

                val baseWidth = 380
                val baseHeight = 190
                val widthPx = (baseWidth * scale).roundToInt()
                val heightPx = (baseHeight * scale).roundToInt()

                val win = JWindow(null as java.awt.Frame?).apply {
                    type = Window.Type.POPUP
                    isAlwaysOnTop = true
                    setFocusableWindowState(false)
                    setAutoRequestFocus(false)
                    name = "CPPlayerSplashWindow"
                }

                // 尝试启用每像素透明（支持系统下呈现精致圆角与边缘阴影）
                val isTranslucent = runCatching {
                    win.background = Color(0, 0, 0, 0)
                }.isSuccess

                val appIcon: Image? = runCatching {
                    DesktopSplashWindow::class.java.getResourceAsStream(ICON_RESOURCE)?.use {
                        ImageIO.read(it)
                    }
                }.getOrNull()

                val subtitle = if (isMobilePreview) "手机版预览模式 (412×892)" else "Material 3 Expressive 音乐播放器"

                var progressAnim = 0.0

                val panel = object : JPanel() {
                    init {
                        isOpaque = false
                    }

                    override fun paintComponent(g: Graphics) {
                        super.paintComponent(g)
                        val g2 = g as? Graphics2D ?: return
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

                        val cornerRadius = 18f * scale

                        // 1. 背景卡片（Zinc-900: #18181B）
                        g2.color = Color(24, 24, 27)
                        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius))

                        // 2. 细边框（Zinc-800: #27272A）
                        g2.color = Color(45, 45, 52)
                        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width.toFloat() - 1f, height.toFloat() - 1f, cornerRadius, cornerRadius))

                        // 3. 应用图标
                        val iconSize = (44 * scale).roundToInt()
                        val iconX = (24 * scale).roundToInt()
                        val iconY = (24 * scale).roundToInt()
                        if (appIcon != null) {
                            g2.drawImage(appIcon, iconX, iconY, iconSize, iconSize, null)
                        } else {
                            // 兜底几何图标
                            g2.color = Color(99, 102, 241)
                            g2.fillRoundRect(iconX, iconY, iconSize, iconSize, (12 * scale).roundToInt(), (12 * scale).roundToInt())
                        }

                        // 4. 标题与副标题
                        val textX = iconX + iconSize + (14 * scale).roundToInt()
                        g2.color = Color(244, 244, 245)
                        g2.font = Font("SansSerif", Font.BOLD, (18 * scale).roundToInt())
                        g2.drawString("CPPlayer", textX, iconY + (20 * scale).roundToInt())

                        g2.color = Color(140, 140, 150)
                        g2.font = Font("SansSerif", Font.PLAIN, (11 * scale).roundToInt())
                        g2.drawString(subtitle, textX, iconY + (38 * scale).roundToInt())

                        // 5. 当前加载状态文案
                        g2.color = Color(180, 180, 192)
                        g2.font = Font("SansSerif", Font.PLAIN, (12 * scale).roundToInt())
                        val statusY = (118 * scale).roundToInt()
                        g2.drawString(currentStatus, (24 * scale).roundToInt(), statusY)

                        // 6. 动态不确定进度条
                        val trackX = (24 * scale).roundToInt()
                        val trackY = (136 * scale).roundToInt()
                        val trackW = width - (48 * scale).roundToInt()
                        val trackH = (4 * scale).roundToInt()

                        // 进度条轨道
                        g2.color = Color(39, 39, 45)
                        g2.fillRoundRect(trackX, trackY, trackW, trackH, trackH, trackH)

                        // 动画高亮滑块（带 Indigo -> Purple 渐变）
                        val thumbW = (trackW * 0.35f).roundToInt()
                        // 周期性余弦平滑摆动 (0..1)
                        val norm = (1.0 - cos(progressAnim)) / 2.0
                        val thumbX = trackX + ((trackW - thumbW) * norm).roundToInt()

                        val oldClip = g2.clip
                        g2.clip(RoundRectangle2D.Float(trackX.toFloat(), trackY.toFloat(), trackW.toFloat(), trackH.toFloat(), trackH.toFloat(), trackH.toFloat()))
                        g2.paint = GradientPaint(
                            thumbX.toFloat(), trackY.toFloat(), Color(99, 102, 241),
                            (thumbX + thumbW).toFloat(), trackY.toFloat(), Color(168, 85, 247)
                        )
                        g2.fillRoundRect(thumbX, trackY, thumbW, trackH, trackH, trackH)
                        g2.clip = oldClip
                    }
                }

                win.contentPane = panel
                win.setSize(widthPx, heightPx)

                // 居中于默认屏幕
                val screenBounds = gc.bounds
                val posX = screenBounds.x + (screenBounds.width - widthPx) / 2
                val posY = screenBounds.y + (screenBounds.height - heightPx) / 2
                win.setLocation(posX, posY)

                // 启动 30 FPS 平滑动画计时器
                val timer = Timer(33) {
                    progressAnim += 0.08
                    panel.repaint()
                }
                animTimer = timer
                timer.start()

                win.isVisible = true
                win.toFront()
                window = win
            } catch (_: Throwable) {
                // 原生环境异常兜底不影响应用主启动链路
            }
        }
    }

    /**
     * 更新启动状态提示文案。
     */
    fun updateStatus(status: String) {
        currentStatus = status
        EventQueue.invokeLater {
            window?.contentPane?.repaint()
        }
    }

    /**
     * 主窗口就绪后平滑关闭并释放启动提示窗口。
     */
    fun dismiss() {
        if (isDismissed) return
        isDismissed = true

        EventQueue.invokeLater {
            val win = window ?: return@invokeLater
            animTimer?.stop()
            animTimer = null

            // 尝试渐变淡出（100ms 内透明度平滑衰减），不支持时直接释放
            val fadeTimer = Timer(16, null)
            var currentOpacity = 1.0f
            fadeTimer.addActionListener {
                currentOpacity -= 0.18f
                if (currentOpacity <= 0.05f) {
                    fadeTimer.stop()
                    win.isVisible = false
                    win.dispose()
                    window = null
                } else {
                    val ok = runCatching { win.opacity = currentOpacity }.isSuccess
                    if (!ok) {
                        fadeTimer.stop()
                        win.isVisible = false
                        win.dispose()
                        window = null
                    }
                }
            }
            fadeTimer.start()
        }
    }
}
