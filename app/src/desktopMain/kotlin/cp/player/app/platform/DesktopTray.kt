package cp.player.app.platform

import cp.player.app.AppModel
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.MessageNotifyStrings
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.imageio.ImageIO

/**
 * 桌面托盘图标（**唯一持有者**）。
 *
 * ## 一个图标，两个用途
 *
 * 1. **私信通知**：AWT 的托盘气泡是桌面端唯一可用的系统通知途径
 *    （没有 AWT 之外的现成方案；真·WinRT Toast 需要另起一套 COM 互操作，不在本期）。
 * 2. **常驻**：关窗时「最小化到托盘」需要有一个图标把用户捞回来 ——
 *    没有它的话，隐藏窗口 = 应用人间蒸发。
 *
 * 所以图标**懒创建**：只有「有人被订阅推送」或「用户选了最小化到托盘」时才装，
 * 两种情况都消失时由 [remove] 摘掉 —— 平时不占托盘位，不改变老用户的观感。
 *
 * ## 为什么菜单要能重建
 *
 * 菜单文案要跟随界面语言，而托盘菜单是**非组合**的 AWT 对象（读不到 `cpStrings()`），
 * 所以 [ensureInstalled] 每次都会按当前 [AppModel] 的语言重建菜单。
 * 图标本身只创建一次（重复 `SystemTray.add` 会抛）。
 */
object DesktopTray {

    private var trayIcon: TrayIcon? = null

    /** 托盘菜单「显示主界面」。由 `Main.kt` 注入（要拿到窗口与 WindowState）。 */
    var onShowWindow: (() -> Unit)? = null

    /** 托盘菜单「退出」。由 `Main.kt` 注入（`exitApplication`）。 */
    var onExit: (() -> Unit)? = null

    /** 气泡被点击（或双击托盘）时回跳。由通知层注入。 */
    var onNotificationClick: ((providerId: String, peerUid: Long, title: String) -> Unit)? = null

    /**
     * 最近一条气泡对应的会话。
     *
     * ⚠️ AWT 的气泡点击**不带任何 payload**（`ActionListener` 没有参数），
     * 所以只能记住「刚弹的是谁」。多条通知连续弹出时点击会跳到**最新那条** ——
     * 这是 AWT 的硬限制，接受它。
     */
    private var lastNotification: Triple<String, Long, String>? = null

    val isSupported: Boolean get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    val isInstalled: Boolean get() = trayIcon != null

    /**
     * 确保托盘图标存在，并按当前语言重建菜单。
     *
     * @return 托盘可用（已装上或刚刚装上）；`false` = 本机没有系统托盘
     *   （部分 Linux / headless），调用方应如实告知用户而不是假装成功。
     */
    fun ensureInstalled(): Boolean {
        if (!isSupported) return false
        val existing = trayIcon
        if (existing != null) {
            existing.popupMenu = buildMenu()
            return true
        }
        return runCatching {
            val icon = TrayIcon(trayImage(), "CPPlayer").apply {
                isImageAutoSize = true
                popupMenu = buildMenu()
                addActionListener { dispatchClick() }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false)
    }

    /** 摘掉托盘图标（订阅清零且不再常驻时调用）。 */
    fun remove() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
        lastNotification = null
    }

    /**
     * 弹一条气泡。
     *
     * @param providerId / peerUid 点击时要跳去的会话（见 [lastNotification] 的说明）。
     */
    fun showMessage(title: String, body: String, providerId: String, peerUid: Long) {
        val icon = trayIcon ?: return
        lastNotification = Triple(providerId, peerUid, title)
        runCatching { icon.displayMessage(title, body, TrayIcon.MessageType.NONE) }
    }

    /** 清掉「最近一条通知」——用户已经进会话看过了，之后再点托盘不该再跳过去。 */
    fun clearLastNotification() {
        lastNotification = null
    }

    private fun dispatchClick() {
        val notification = lastNotification
        val routed = notification?.let { (provider, peer, title) ->
            onNotificationClick?.invoke(provider, peer, title)
        }
        // 没有待跳转的通知（或没人处理）⇒ 当成「把窗口叫回来」，这是托盘图标的通用语义。
        if (routed == null) onShowWindow?.invoke()
    }

    private fun buildMenu(): PopupMenu {
        val strings = strings()
        return PopupMenu().apply {
            add(MenuItem(strings.trayShowWindow).apply { addActionListener { onShowWindow?.invoke() } })
            addSeparator()
            add(MenuItem(strings.trayExit).apply { addActionListener { onExit?.invoke() } })
        }
    }

    /**
     * 托盘菜单的文案。
     *
     * 读的是**组合之外**的语言设置（`AppModel.appLanguageFlow`），因为托盘菜单是 AWT 对象、
     * 不在 Compose 树里，读不到 `cpStrings()`。
     *
     * ⚠️ 不能图省事写成 `CpStrings.of(...)`：它在 `SYSTEM` 分支里读 `Locale.current`，
     * 而那是 **CompositionLocal**（`@Composable`），组合外调用会直接抛。
     * 这里对 `SYSTEM` 自己按 JVM 默认 locale 判一次 —— 与 `of()` 的
     * 「命中英文才用英文，其余中文」是同一条规则。
     */
    private fun strings(): MessageNotifyStrings = when (AppModel.appLanguageFlow.value) {
        AppLanguage.ENGLISH -> CpStrings.en
        AppLanguage.ZH_HANS -> CpStrings.zh
        AppLanguage.SYSTEM ->
            if (java.util.Locale.getDefault().language.equals("en", ignoreCase = true)) {
                CpStrings.en
            } else {
                CpStrings.zh
            }
    }.messageNotify

    /**
     * 托盘图标位图。
     *
     * 复用窗口图标（`/cpplayer/icon.png`，与 `AppWindowIcon` 同一个资源），
     * 由 `TrayIcon.isImageAutoSize` 交给系统缩放。取不到就退到 16×16 空白 ——
     * 托盘图标画不出来不该让整个应用起不来。
     */
    private fun trayImage(): Image = runCatching {
        ImageIO.read(DesktopTray::class.java.getResourceAsStream(RESOURCE))
            ?: blankImage()
    }.getOrNull() ?: blankImage()

    private fun blankImage(): Image =
        java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)

    private const val RESOURCE = "/cpplayer/icon.png"
}
