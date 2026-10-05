package cp.player.app.platform

import cp.player.app.notify.MessageNotification
import java.awt.EventQueue

/**
 * 私信通知的桌面实现 —— 走 AWT 的托盘气泡（[DesktopTray]）。
 *
 * ## 已知限制（写在这里，免得日后当 bug 查）
 *
 * 1. **进程必须活着**。桌面端关掉窗口 = 进程退出 = 收不到。想要「关窗也收」，
 *    得先让用户选「最小化到托盘」（见 `Main.kt` 的关窗处理）。
 * 2. **Windows 10+ 的气泡不一定进「操作中心」**。AWT 走的是传统 balloon，
 *    未注册 AUMID 的应用可能只显示一条短暂气泡。要「真·Toast」得走 WinRT
 *    （`ToastNotification` COM 互操作），不在本期范围。
 * 3. **气泡点击不带 payload**，所以只能跳到「最近一条通知」对应的会话
 *    （见 `DesktopTray.lastNotification`）。
 * 4. **没有托盘的环境**（部分 Linux、headless）[messageNotificationsSupported] 为 false，
 *    UI 必须如实显示「本平台不支持系统通知」，而不是给一个永远不响的开关。
 */
actual fun messageNotificationsSupported(): Boolean = DesktopTray.isSupported

/**
 * 桌面没有「通知权限」这个概念 —— 只要托盘装得上就能弹。
 *
 * ⚠️ 刻意**不在这里**调 `ensureInstalled()`：那是个有副作用的动作（会往托盘里加图标），
 * 塞进一个「查询」函数里会让 UI 每次重组都可能装一次图标。
 * 真正的安装发生在第一次 [postMessageNotification]。
 */
actual fun canPostMessageNotifications(): Boolean = DesktopTray.isSupported

actual fun requestMessageNotificationPermission() {
    // 桌面无需授权，空操作。
}

actual fun postMessageNotification(notification: MessageNotification) {
    if (!DesktopTray.ensureInstalled()) return
    // AWT 的托盘操作约定在 EDT 上做。这里是后台协程调进来的，转一下线程。
    EventQueue.invokeLater {
        DesktopTray.showMessage(
            title = notification.title,
            body = notification.body,
            providerId = notification.providerId,
            peerUid = notification.peerUid,
        )
    }
}

/**
 * 桌面只能「忘掉最近一条」。
 *
 * AWT 的 `TrayIcon` **没有**按 id 撤销气泡的 API（气泡由系统窗口管理器接管，
 * 出了 `displayMessage` 就不受我们控制），所以这里做不到精确撤销 ——
 * 能做的是别再让点击托盘跳回一个已经读过的会话。
 */
actual fun cancelMessageNotification(key: String) {
    EventQueue.invokeLater { DesktopTray.clearLastNotification() }
}

actual fun setOnMessageNotificationClick(handler: ((String, Long, String) -> Unit)?) {
    // 桌面进程始终活着，不存在「点击先于注册」的冷启动场景，无需缓存补投。
    DesktopTray.onNotificationClick = handler
}
