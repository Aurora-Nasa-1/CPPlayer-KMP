package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.miniPlayerMouseWheel(
    handleConsumed: Boolean,
    onScroll: (deltaY: Float) -> Unit,
): Modifier = this
