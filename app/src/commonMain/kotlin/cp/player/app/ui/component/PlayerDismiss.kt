package cp.player.app.ui.component

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * 独立播放页覆盖层的下拉/退场协调契约（不依赖任何导航框架）。
 */
@Stable
interface PlayerDismissHandler {
    val isDragging: Boolean
    fun onDragDelta(dy: Float)
    fun onDragEnd(velocityY: Float = 0f)
    fun onDragCancel()
    fun requestDismiss()
}

val LocalPlayerDismissHandler = staticCompositionLocalOf<PlayerDismissHandler?> { null }

/**
 * 播放页进入时与 MiniPlayer 走 sharedBounds；下拉、预测性返回或退场动画期间关闭 sharedBounds，
 * 让整页带着屏幕圆角顺滑向下退场，不被共享元素扯回底栏。
 */
val LocalPlayerAllowSharedBounds = compositionLocalOf { true }

/**
 * 把纵向拖拽收起手势绑定到 [handler]。
 *
 * 仅在向下拖动或已处于拖出态时消费事件，静止态上滑继续透传给内部组件。
 */
fun Modifier.playerDismissDragGesture(
    handler: PlayerDismissHandler,
): Modifier = pointerInput(handler) {
    val tracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { tracker.resetTracking() },
        onVerticalDrag = { change, dragAmount ->
            if (dragAmount > 0f || handler.isDragging) {
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                handler.onDragDelta(dragAmount)
            }
        },
        onDragEnd = {
            val velocityY = runCatching { tracker.calculateVelocity().y }.getOrDefault(0f)
            handler.onDragEnd(velocityY)
        },
        onDragCancel = { handler.onDragCancel() },
    )
}
