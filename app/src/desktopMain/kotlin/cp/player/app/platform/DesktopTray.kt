package cp.player.app.platform

import cp.player.app.AppModel
import cp.player.app.i18n.AppLanguage
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.MessageNotifyStrings
import java.awt.Image
import java.awt.MenuItem
import java.awt.MouseInfo
import java.awt.Point
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
 * ## 右键菜单：自绘接管，AWT 菜单只做兜底
 *
 * AWT 的 `PopupMenu` 是**原生菜单**，样式、图标、状态一无所能（连勾选态都只能是
 * 系统那套灰蓝高亮）。所以「右键」这条链路由上层自绘接管：
 *
 * - 有人注入了 [onContextMenu] ⇒ 图标**不装**原生菜单，右键改为回调上层
 *   （`Main.kt` 在光标处弹一个 AWT 自绘小窗，见 `TrayMenuPopup`）；
 * - 没人接管（例如只用了通知、还没接线）⇒ 退回 [buildFallbackMenu]，
 *   保证「至少能退出」，不会出现右键毫无反应的死局。
 *
 * ⚠️ **两者不能并存**：设了原生 `popupMenu` 的话，右键会同时弹出原生菜单和自绘菜单。
 * 所以 [applyMenu] 每次都按 [onContextMenu] 是否为空重新决定 —— 这也是注入之后
 * 必须调一次 [refreshMenu] 的原因。
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
     * 右键菜单请求：参数是**光标在屏幕上的位置**（AWT 用户坐标，与 Compose 的 px 同口径）。
     *
     * 非空即代表「右键交给自绘」，此时不会再给图标装原生 `PopupMenu`。
     * 注入后请调一次 [refreshMenu]：图标可能早就装好了（通知订阅会先把它装上）。
     */
    var onContextMenu: ((x: Int, y: Int) -> Unit)? = null

    /**
     * 最近一条气泡对应的会话。
     *
     * ⚠️ AWT 的气泡点击**不带任何 payload**（`ActionListener` 没有参数），
     * 所以只能记住「刚弹的是谁」。多条通知连续弹出时点击会跳到**最新那条** ——
     * 这是 AWT 的硬限制，接受它。
     */
    private var lastNotification: Triple<String, Long, String>? = null

    /**
     * 上一次派发右键的时间。
     *
     * Windows 在 **release** 时才让 `isPopupTrigger` 为真，部分 X11 在 **press** 就为真；
     * 两个回调都接才跨平台，代价是同一手势可能进来两次 —— 用时间窗去抖掉第二次。
     */
    private var lastContextMenuAt = 0L

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
            applyMenu(existing)
            return true
        }
        return runCatching {
            val icon = TrayIcon(trayImage(), "CPPlayer").apply {
                isImageAutoSize = true
                applyMenu(this)
                addActionListener { dispatchClick() }
                addMouseListener(object : MouseAdapter() {
                    // 右键＝自绘菜单入口。两个回调都挂，理由见 [lastContextMenuAt]。
                    override fun mousePressed(e: MouseEvent) = dispatchContextMenu(e)
                    override fun mouseReleased(e: MouseEvent) = dispatchContextMenu(e)
                })
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false)
    }

    /**
     * 按当前的 [onContextMenu] / 语言重算托盘菜单。
     *
     * 注入自绘右键之后必须调一次：图标可能已经被通知订阅先装上了，
     * 那时装的还是原生菜单，不刷新的话右键会同时弹两个。
     */
    fun refreshMenu() {
        trayIcon?.let(::applyMenu)
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

    /**
     * 右键的分发。**只认右键**：左键仍由 `ActionListener` 负责唤回窗口。
     *
     * 判定同时看 `isPopupTrigger`（Windows 在 release 置位）、`button`（多数平台会给
     * `BUTTON3`）与 `BUTTON3_DOWN_MASK`（按下时的修饰位）—— 三者任一成立即算右键，
     * 单看任何一个都会在某个平台上漏掉。
     */
    private fun dispatchContextMenu(e: MouseEvent) {
        val handler = onContextMenu ?: return
        val isRight = e.isPopupTrigger ||
            e.button == MouseEvent.BUTTON3 ||
            (e.modifiersEx and InputEvent.BUTTON3_DOWN_MASK) != 0
        if (!isRight) return
        val now = System.currentTimeMillis()
        if (now - lastContextMenuAt < CONTEXT_MENU_DEBOUNCE_MS) return
        lastContextMenuAt = now
        // 事件自带的 x/y 是「图标内的相对坐标」，对定位菜单毫无用处；要的是屏幕光标。
        val at = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: Point(0, 0)
        // 留一行日志：托盘右键「没反应」时，先确认事件到底有没有走到这里。
        // 这条链路一旦断在前半段（事件没派发），后面的窗口代码再对也看不出问题。
        println("[CPPlayer] 托盘右键 at ${at.x},${at.y}")
        handler(at.x, at.y)
    }

    private fun applyMenu(icon: TrayIcon) {
        icon.popupMenu = if (onContextMenu == null) buildFallbackMenu() else null
    }

    /**
     * AWT 原生兜底菜单。
     *
     * 只在**没人接管**右键时使用 —— 它的价值是「保证托盘图标始终有退出入口」，
     * 不是视觉效果。正常路径下用户看到的是 `TrayMenuPopup` 那套自绘菜单。
     */
    private fun buildFallbackMenu(): PopupMenu {
        val strings = stringsForTray()
        return PopupMenu().apply {
            add(MenuItem(strings.trayShowWindow).apply { addActionListener { onShowWindow?.invoke() } })
            add(MenuItem(strings.trayMiniMode).apply {
                addActionListener {
                    cp.player.app.ui.util.DesktopShell.isMiniMode = !cp.player.app.ui.util.DesktopShell.isMiniMode
                    onShowWindow?.invoke()
                }
            })
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
     *
     * 对 `TrayMenuPopup` 开放（internal）：自绘菜单和兜底菜单必须用同一套文案，
     * 各写一份的话，改文案时漏掉一处就会出现「中文界面里混一句英文」。
     */
    internal fun stringsForTray(): MessageNotifyStrings = when (AppModel.appLanguageFlow.value) {
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

    /** 右键去抖窗口：够盖住同一次手势的 press + release，又不至于吃掉连按。 */
    private const val CONTEXT_MENU_DEBOUNCE_MS = 350L
}

