package cp.player.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

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

    /**
     * 当前注册的处理器数量，作为**快照状态**暴露给组合。
     *
     * 用它与 [stack] 保持同步，只是为了给 `hasHandlers` 一个可观察的依赖：
     * 注册/注销会改变它 ⇒ 读到 [hasHandlers] 的重组作用域会被重新计算。
     */
    private var handlerCount by mutableIntStateOf(0)

    private class Entry(val id: Long, val handler: () -> Unit)

    /**
     * 当前是否有页面注册了返回处理器。
     *
     * 供窗口标题栏判断「能不能显示返回键」：只有 Navigator 栈深与内嵌面板状态两个判据时，
     * 「播放页展开态」这类**不涉及路由**的返回语义会被漏掉 —— 那时 Esc 能退、标题栏上
     * 却没有返回键，同一个动作两条链路给出不同答案。
     *
     * ⚠️ 它读的是快照状态（[handlerCount]），注册 / 注销会驱动重组 —— 所以展开播放页
     * （只注册 `BackHandler`、不动 Navigator 栈深与 `pageCanGoBack`）时，标题栏也能跟着
     * 更新出返回键。**不要再把它当普通布尔值**：早先它是裸 `ArrayDeque.isNotEmpty()`，
     * 不驱动重组，`Main.kt` 里据此算出的 `canGoBack` 在那条路径上不会刷新。
     */
    val hasHandlers: Boolean get() = handlerCount > 0

    /** 注册一个返回处理器，返回可用于注销的令牌。 */
    fun register(handler: () -> Unit): Long {
        val id = ++sequence
        stack.addLast(Entry(id, handler))
        handlerCount = stack.size
        return id
    }

    fun unregister(token: Long) {
        stack.removeAll { it.id == token }
        handlerCount = stack.size
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
