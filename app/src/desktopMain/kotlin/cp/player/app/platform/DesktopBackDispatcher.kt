package cp.player.app.platform

/**
 * 桌面端的「返回」分发器。
 *
 * 安卓有系统返回键/侧滑手势，桌面没有——桌面的事实标准是 **Esc**。
 * 但 [BackHandler] 是各个页面自己调用的（播放页展开态、歌单详情、设置子页……），
 * 需要有人把它们按注册顺序收集起来，再由窗口级按键回调统一派发。
 *
 * 约定：**后注册的优先**。页面越"上层"越晚进入组合，所以后注册即更靠近用户，
 * 与安卓 `OnBackPressedDispatcher` 的行为一致。全部处理器都在 EDT 上操作，无需加锁。
 */
object DesktopBackDispatcher {

    private val stack = ArrayDeque<Entry>()
    private var sequence = 0L

    private class Entry(val id: Long, val handler: () -> Unit)

    /**
     * 当前是否有页面注册了返回处理器。
     *
     * 供窗口标题栏判断「能不能显示返回键」：只有 Navigator 栈深与内嵌面板状态两个判据时，
     * 「播放页展开态」这类**不涉及路由**的返回语义会被漏掉 —— 那时 Esc 能退、标题栏上
     * 却没有返回键，同一个动作两条链路给出不同答案。
     *
     * ⚠️ 它不是 `mutableStateOf`，读它**不会**驱动重组。标题栏的重组由 `navigator` 的
     * 栈深变化与 `DesktopShell.pageCanGoBack`（那是快照状态）触发，两处变化都发生在
     * 处理器注册/注销**之后**的同一帧里，所以这个快照读拿到的值是新的。
     * 若将来出现「注册处理器却完全不引起上层重组」的页面，这里需要改成快照状态。
     */
    val hasHandlers: Boolean get() = stack.isNotEmpty()

    /** 注册一个返回处理器，返回可用于注销的令牌。 */
    fun register(handler: () -> Unit): Long {
        val id = ++sequence
        stack.addLast(Entry(id, handler))
        return id
    }

    fun unregister(token: Long) {
        stack.removeAll { it.id == token }
    }

    /**
     * 派发给最后注册的处理器。
     * @return 是否有人消费；`false` 表示事件应继续冒泡（比如交给系统的其它快捷键）。
     */
    fun dispatch(): Boolean {
        val entry = stack.lastOrNull() ?: return false
        entry.handler()
        return true
    }
}
