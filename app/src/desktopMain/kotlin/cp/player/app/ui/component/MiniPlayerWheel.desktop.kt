package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.miniPlayerMouseWheel(
    handleConsumed: Boolean,
    onScroll: (deltaY: Float) -> Unit,
): Modifier {
    val latestOnScroll by rememberUpdatedState(onScroll)
    val pass = if (handleConsumed) PointerEventPass.Initial else PointerEventPass.Main
    return onPointerEvent(PointerEventType.Scroll, pass) { event ->
        if (!handleConsumed && event.changes.any { it.isConsumed }) return@onPointerEvent
        val change = event.changes.firstOrNull() ?: return@onPointerEvent
        if (change.scrollDelta.x != 0f) return@onPointerEvent
        val deltaY = change.scrollDelta.y
        if (deltaY == 0f) return@onPointerEvent
        latestOnScroll(deltaY)
        change.consume()
    }
}
