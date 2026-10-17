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
actual fun rememberFluidFpsConfig(): FluidFpsConfig {
    return remember {
        val hz = try {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val gd = ge.defaultScreenDevice
            val rate = gd.displayMode.refreshRate
            if (rate > 0 && rate != DisplayMode.REFRESH_RATE_UNKNOWN) rate else 60
        } catch (_: Throwable) {
            60
        }
        // 桌面端则是显示屏帧率一半（分频因子固定为 2）
        val divisor = 2
        val targetFps = (hz / divisor).coerceAtLeast(1)
        FluidFpsConfig(divisor = divisor, targetFps = targetFps, screenHz = hz)
    }
}
