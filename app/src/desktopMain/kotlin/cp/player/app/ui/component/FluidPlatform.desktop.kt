package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mikepenz.hypnoticcanvas.NonAndroidRuntimeEffect
import com.mikepenz.hypnoticcanvas.RuntimeEffect
import com.mikepenz.hypnoticcanvas.shaders.Shader
import java.awt.DisplayMode
import java.awt.GraphicsEnvironment

actual fun buildRuntimeEffect(shader: Shader): RuntimeEffect =
    NonAndroidRuntimeEffect(shader)

@Composable
actual fun rememberFluidFpsConfig(targetFps: Int): FluidFpsConfig {
    return remember(targetFps) {
        val hz = try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val gd = ge.defaultScreenDevice
            val rate = gd.displayMode.refreshRate
            if (rate > 0 && rate != DisplayMode.REFRESH_RATE_UNKNOWN) rate else 60
        } catch (_: Throwable) {
            60
        }
        calculateFluidFpsConfig(hz, targetFps)
    }
}
