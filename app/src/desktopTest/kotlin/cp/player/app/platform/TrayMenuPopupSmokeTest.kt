package cp.player.app.platform

import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 托盘菜单的**真弹窗**冒烟测试。
 *
 * 存在的理由：托盘右键「没反应」有两种完全不同的病因，从界面上分不出来，靠猜已经
 * 白折腾过两轮：
 *
 * 1. 窗口建不出来（`JWindow` / 透明背景 / `Window.Type.POPUP` 某一环抛异常）；
 * 2. 窗口没问题，但右键事件根本没走到 `TrayMenuPopup.show()`。
 *
 * 这个测试直接调 [TrayMenuPopup.show]，把第 1 种可能性一次性排除。它会在测试机
 * 桌面上真的弹一个窗口（几毫秒后 dismiss），无图形环境（headless）时自动跳过。
 *
 * ⚠️ 它**不**验证托盘图标的事件接线 —— 那需要真的点一次系统托盘。
 */
class TrayMenuPopupSmokeTest {

    @Test
    fun `show then dismiss a real popup window`() {
        if (GraphicsEnvironment.isHeadless()) {
            println("[skip] headless 环境，跳过托盘菜单冒烟测试")
            return
        }
        try {
            TrayMenuPopup.show(Point(420, 320))
            // show() 内部走 invokeLater，这里把队列刷干净再断言。
            EventQueue.invokeAndWait { }
            assertTrue(
                TrayMenuPopup.isShowing,
                "托盘菜单窗口没有显示出来：${TrayMenuPopup.lastFailure ?: "无异常记录"}",
            )
            // ⚠️ 尺寸必须单独断言：窗口曾经因为常量撞名（`ImageObserver.WIDTH/HEIGHT`）
            // 缩成 1×2 像素 —— `isShowing` 依然为 true，而屏幕上什么都看不到。
            // 这里不钉死具体像素（HiDPI 屏上窗口是密度的整数倍），只要求「像一张菜单」。
            val size = TrayMenuPopup.currentSize
            assertNotNull(size)
            assertTrue(
                size.width > 200 && size.height > 200,
                "菜单窗口尺寸异常（屏幕上看不见）：$size",
            )
        } finally {
            TrayMenuPopup.dismiss()
        }

        EventQueue.invokeAndWait { }
        assertFalse(TrayMenuPopup.isShowing, "dismiss() 之后窗口仍然可见")
    }
}
