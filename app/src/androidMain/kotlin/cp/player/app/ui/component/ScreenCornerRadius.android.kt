package cp.player.app.ui.component

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
actual fun rememberScreenCornerRadius(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    return remember(view, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            val radiusPx = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
                ?: insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius
                ?: insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
            if (radiusPx != null && radiusPx > 0) {
                with(density) { radiusPx.toDp() }
            } else {
                28.dp
            }
        } else {
            28.dp
        }
    }
}
