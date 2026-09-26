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
