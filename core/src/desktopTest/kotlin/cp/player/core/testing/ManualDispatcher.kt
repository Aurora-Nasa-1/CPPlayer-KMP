package cp.player.core.testing

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * 手动调度器：`dispatch` 只把任务入队，由测试显式 [drain] 执行。
 *
 * ### 为什么需要它
 * 生产环境的 `MusicBackend.backendScope` 挂在 `Dispatchers.Main`（桌面端是 Swing EDT），
 * 而 **`Dispatchers.Main` 不在 `core` 的 desktopTest 类路径上** ——
 * `kotlinx-coroutines-swing` 只有桌面应用侧才装配。所以测试里不能直接用它。
 *
 * 这个替身复现了 `Dispatchers.Main` 真正要紧的两个性质：
 *
 * 1. **单线程**：任务严格按入队顺序串行执行；
 * 2. **不同步**：`dispatch` 不立刻执行，于是「调用方返回」与「任务真的执行」之间的窗口
 *    可以被测试精确控制。
 *
 * 第 2 点是关键 —— 竞态就藏在那段窗口里。若改用 `Dispatchers.Unconfined`，任务会立刻
 * 同步执行、窗口消失，测试于是**永远绿，什么也证明不了**。
 *
 * 此前这个类在 `LyricsGenerationGuardTest` 与 `PlaybackControllerNavigationTest` 里
 * 各抄了一份；Phase 3 的播控写路径又需要它，所以抽到这里共用。
 */
class ManualDispatcher : CoroutineDispatcher() {

    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    /** 依次执行队列中的任务直到清空（执行中新增的任务同样会被跑到）。 */
    fun drain() {
        while (true) {
            val task = tasks.removeFirstOrNull() ?: return
            task.run()
        }
    }

    /**
     * 待执行任务数。
     *
     * 用来断言「动作**被派发**到控制线程」而不是被同步执行 ——
     * 这正是播控写路径的核心性质，见 `IntegrationPlaybackControlTest`。
     */
    val pending: Int get() = tasks.size
}
