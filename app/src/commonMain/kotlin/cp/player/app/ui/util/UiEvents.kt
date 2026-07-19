package cp.player.app.ui.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 轻量全局 UI 事件总线。
 *
 * 各 Screen / ScreenModel 通过 [notify] 发送一次性用户提示（收藏成功、已加入队列等），
 * 由 MainScreen 的全局 SnackbarHost 统一展示，避免每个页面各自维护 Snackbar 状态。
 */
object UiEvents {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 发送一条一次性提示（非挂起；缓冲满时丢弃最旧语义由 tryEmit 失败静默处理）。 */
    fun notify(message: String) {
        _messages.tryEmit(message)
    }
}
