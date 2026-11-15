package cp.player.app.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * 框架无关的 UI 状态呈现者规范（UI-Agnostic Presenter）。
 *
 * 核心目标：
 * 1. 彻底与 UI 框架（Compose / Voyager / Android / JavaFX 等）解耦；
 * 2. 状态全部采用强类型 Kotlin 数据模型（[StateFlow]），免除前端解析 JSON 成本；
 * 3. 具备自主生命周期管理，接受外部注入的 [coroutineScope]，在页面退出时可安全取消；
 * 4. 可在非 Compose 前端（如 CLI、WinUI 3、Flutter 桥接、纯 JVM 窗口）直接复用。
 */
interface UiPresenter<TState> {
    /** 状态流：UI 订阅此流即可驱动视图渲染。 */
    val state: StateFlow<TState>

    /** 关联的协程作用域。 */
    val scope: CoroutineScope

    /** 释放或取消该 Presenter 持有的异步任务。 */
    fun dispose() {
        scope.cancel()
    }
}
