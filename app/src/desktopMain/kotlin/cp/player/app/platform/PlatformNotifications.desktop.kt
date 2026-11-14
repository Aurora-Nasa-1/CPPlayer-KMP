package cp.player.app.platform

import cp.player.app.notify.MessageNotification
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.notification.linux.LinuxNotificationCenter
import dev.nucleusframework.notification.linux.LinuxNotificationListener
import dev.nucleusframework.notification.linux.NotificationAction as LinuxNotificationAction
import dev.nucleusframework.notification.linux.NotificationHints
import dev.nucleusframework.notification.linux.Notification as LinuxNotification
import dev.nucleusframework.notification.linux.Urgency
import dev.nucleusframework.notification.windows.ToastNotificationListener
import dev.nucleusframework.notification.windows.WindowsNotificationCenter
import java.util.concurrent.ConcurrentHashMap

/**
 * 私信通知的桌面实现 —— 走**各平台原生通知中心**，不再依赖 AWT 托盘气泡。
 *
 * | 平台 | 实现 | 形态 |
 * |---|---|---|
 * | Windows | Nucleus `notification-windows` | WinRT Toast（进操作中心） |
 * | Linux | Nucleus `notification-linux` | freedesktop D-Bus |
 *
 * 两条链路都是 JNI / 原生 API，不创建 `java.awt.SystemTray`，也不经过 EDT。
 * 旧实现的两个硬限制随之消失：
 *
 * 1. **点击带 payload**。每条通知都有稳定的 tag / identifier（[MessageNotification.key]），
 *    点击时按 key 找回对应的 providerId + peerUid，不会「总是跳到最新那条」。
 * 2. **可按会话撤销**。`cancelMessageNotification(key)` 能精确移除该会话的通知
 *    （Windows `remove(tag)` / Linux `closeNotification(id)`），不再只是
 *    「忘掉最近一条」。
 *
 * 进程仍然必须活着才能收新消息 —— 关窗进托盘 / 退出由 `Main.kt` 的关窗策略决定，
 * 这与通知实现无关。
 */

/** 应用级：按 [MessageNotification.key] 记住最近一条通知的跳转目标。 */
private val targets = ConcurrentHashMap<String, MessageNotification>()

/** 由 `App` 注册（通知点击后要跳到哪个会话）。 */
@Volatile
private var clickHandler: ((providerId: String, peerUid: Long, title: String) -> Unit)? = null

actual fun messageNotificationsSupported(): Boolean = when (Platform.Current) {
    Platform.Windows -> WindowsNotificationCenter.isAvailable
    Platform.Linux -> linuxServerAvailable
    else -> false
}

/**
 * 桌面没有 Android 那种「通知权限开关」：只要原生模块加载成功就能发。
 *
 * ⚠️ 刻意不在这里做 `initialize()` / 申请授权 —— 那些是有副作用的动作
 * （Windows 可能创建开始菜单快捷方式），塞进一个会被 UI
 * 反复调用的「查询」函数里不合适。真正的初始化发生在第一次
 * [postMessageNotification] / [requestMessageNotificationPermission]。
 */
actual fun canPostMessageNotifications(): Boolean = when (Platform.Current) {
    Platform.Windows -> WindowsNotificationCenter.isAvailable
    Platform.Linux -> linuxServerAvailable
    else -> false
}

actual fun requestMessageNotificationPermission() {
    // 桌面端没有独立的系统授权流程；Windows 在首次发送时初始化 Toast。
}

actual fun postMessageNotification(notification: MessageNotification) {
    targets[notification.key] = notification
    when (Platform.Current) {
        Platform.Windows -> postWindows(notification)
        Platform.Linux -> postLinux(notification)
        else -> Unit
    }
}

actual fun cancelMessageNotification(key: String) {
    when (Platform.Current) {
        Platform.Windows -> cancelWindows(key)
        Platform.Linux -> cancelLinux(key)
        else -> Unit
    }
    targets.remove(key)
}

actual fun setOnMessageNotificationClick(handler: ((providerId: String, peerUid: Long, title: String) -> Unit)?) {
    clickHandler = handler
}

private fun dispatchKey(key: String) {
    val target = targets[key] ?: return
    clickHandler?.invoke(target.providerId, target.peerUid, target.title)
}

// ---------------------------------------------------------------------------
// Windows — WinRT Toast
// ---------------------------------------------------------------------------

/** Windows 的 tag 上限是 16 字符，`msg_<provider>_<uid>` 很容易超；用稳定短哈希。 */
private fun windowsTagOf(key: String): String = "m" + Integer.toHexString(key.hashCode())

private const val WINDOWS_GROUP = "cpmsg"

private val windowsTagToKey = ConcurrentHashMap<String, String>()
private val windowsLock = Any()

@Volatile
private var windowsInitialized = false

@Volatile
private var windowsListenerInstalled = false

private val windowsListener = object : ToastNotificationListener {
    override fun onActivated(tag: String, group: String, arguments: String, userInputs: Map<String, String>) {
        windowsTagToKey[tag]?.let(::dispatchKey)
    }

    override fun onDismissed(tag: String, group: String, reason: dev.nucleusframework.notification.windows.DismissalReason) {
        // 不在这里删 tag → key：同会话的新消息会用同一个 tag 原地替换，旧条的
        // Dismissed 若晚到就会把**新条**的跳转目标一起抹掉。映射很小，留着无害。
    }

    override fun onFailed(tag: String, group: String, errorCode: Int) {
        println("[CPPlayer] Windows 通知发送失败：tag=$tag group=$group hresult=$errorCode")
    }
}

private fun ensureWindowsReady(): Boolean {
    if (!WindowsNotificationCenter.isAvailable) return false
    synchronized(windowsLock) {
        if (!windowsInitialized) {
            windowsInitialized = WindowsNotificationCenter.initialize()
            if (!windowsInitialized) {
                println(
                    "[CPPlayer] Windows 通知初始化失败：开发态需要先安装一次应用（创建带 AUMID 的" +
                        "开始菜单快捷方式），安装态请确认原生通知 DLL 可加载",
                )
            }
        }
        if (windowsInitialized && !windowsListenerInstalled) {
            WindowsNotificationCenter.addListener(windowsListener)
            windowsListenerInstalled = true
        }
        return windowsInitialized
    }
}

private fun postWindows(notification: MessageNotification) {
    if (!ensureWindowsReady()) return
    val tag = windowsTagOf(notification.key)
    windowsTagToKey[tag] = notification.key
    WindowsNotificationCenter.showSimple(
        title = notification.title,
        body = notification.body,
        tag = tag,
        group = WINDOWS_GROUP,
    ) { error ->
        if (error != null) println("[CPPlayer] Windows 通知发送失败：$error")
    }
}

private fun cancelWindows(key: String) {
    if (!WindowsNotificationCenter.isAvailable) return
    val tag = windowsTagOf(key)
    windowsTagToKey.remove(tag)
    runCatching { WindowsNotificationCenter.remove(tag = tag, group = WINDOWS_GROUP) }
}

// ---------------------------------------------------------------------------
// Linux — freedesktop Desktop Notifications (D-Bus)
// ---------------------------------------------------------------------------

private val linuxIdToKey = ConcurrentHashMap<Int, String>()
private val linuxKeyToId = ConcurrentHashMap<String, Int>()
private val linuxLock = Any()

/**
 * 原生库加载成功 ≠ 有通知服务：部分 headless / 精简桌面没有实现
 * `org.freedesktop.Notifications`。查一次并缓存 —— 不能每次 UI 重组都走 D-Bus。
 */
private val linuxServerAvailable: Boolean by lazy {
    if (!LinuxNotificationCenter.isAvailable) {
        false
    } else {
        runCatching { LinuxNotificationCenter.getServerInformation() != null }.getOrDefault(false)
    }
}

@Volatile
private var linuxListenerInstalled = false

private val linuxListener = object : LinuxNotificationListener {
    override fun onClosed(notificationId: Int, reason: dev.nucleusframework.notification.linux.CloseReason) {
        // 同会话重复通知会 replacesId 复用/替换，旧 id 的 Closed 不能连带删掉新 id
        // 的映射；这里只保留映射，精确撤销由 cancelLinux 负责。
    }

    override fun onActionInvoked(notificationId: Int, actionKey: String) {
        linuxIdToKey[notificationId]?.let(::dispatchKey)
    }
}

private fun ensureLinuxListener() {
    if (linuxListenerInstalled) return
    synchronized(linuxLock) {
        if (!linuxListenerInstalled) {
            LinuxNotificationCenter.addListener(linuxListener)
            linuxListenerInstalled = true
        }
    }
}

private fun postLinux(notification: MessageNotification) {
    if (!LinuxNotificationCenter.isAvailable) return
    ensureLinuxListener()
    val id = LinuxNotificationCenter.notify(
        LinuxNotification(
            appName = "CPPlayer",
            // 同一会话重复来消息时原地替换，不在通知中心堆一排同名条目。
            replacesId = linuxKeyToId[notification.key] ?: 0,
            summary = notification.title,
            body = notification.body,
            actions = listOf(LinuxNotificationAction(LinuxNotificationAction.DEFAULT_KEY, linuxOpenLabel())),
            hints = NotificationHints(
                urgency = Urgency.NORMAL,
                category = "im.received",
            ),
        ),
    )
    if (id > 0) {
        linuxIdToKey[id] = notification.key
        linuxKeyToId[notification.key] = id
    }
}

/** 通知动作在后台线程组装，读不到 `LocalCpStrings`，按与 `CpStrings.of` 相同的规则判一次。 */
private fun linuxOpenLabel(): String =
    when (cp.player.app.AppModel.appLanguageFlow.value) {
        cp.player.app.i18n.AppLanguage.ENGLISH -> "Open"
        cp.player.app.i18n.AppLanguage.ZH_HANS -> "打开"
        cp.player.app.i18n.AppLanguage.SYSTEM ->
            if (java.util.Locale.getDefault().language.equals("en", ignoreCase = true)) "Open" else "打开"
    }

private fun cancelLinux(key: String) {
    if (!LinuxNotificationCenter.isAvailable) return
    linuxKeyToId.remove(key)?.let { id ->
        linuxIdToKey.remove(id)
        runCatching { LinuxNotificationCenter.closeNotification(id) }
    }
}
