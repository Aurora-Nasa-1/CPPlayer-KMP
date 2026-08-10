package cp.player.app.ui.component

import androidx.compose.ui.Modifier

/** Android 端为触屏操作，无需鼠标支持，直接返回原 Modifier。 */
actual fun Modifier.desktopPagerMouseControl(
    onScrollLeft: () -> Unit,
    onScrollRight: () -> Unit,
    pageCount: Int,
): Modifier = this
