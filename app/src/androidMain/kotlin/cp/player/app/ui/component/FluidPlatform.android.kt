package cp.player.app.ui.component

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

@Composable
actual fun rememberFluidFpsConfig(targetFps: Int): FluidFpsConfig {
    val context = LocalContext.current
    return remember(context, targetFps) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.display
            } catch (_: Throwable) {
                null
            }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
        val rate = display?.refreshRate ?: 60f
        val screenHz = if (rate > 0f) rate.roundToInt().coerceIn(30, 360) else 60

        calculateFluidFpsConfig(screenHz, targetFps)
    }
}
