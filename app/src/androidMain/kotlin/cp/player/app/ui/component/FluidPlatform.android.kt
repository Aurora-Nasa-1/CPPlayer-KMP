package cp.player.app.ui.component

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import com.mikepenz.hypnoticcanvas.RuntimeEffect
import com.mikepenz.hypnoticcanvas.shaders.Shader
import kotlin.math.roundToInt

actual fun buildRuntimeEffect(shader: Shader): RuntimeEffect {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val clazz = Class.forName("com.mikepenz.hypnoticcanvas.AndroidRuntimeEffect")
            val ctor = clazz.getDeclaredConstructor(Shader::class.java)
            ctor.isAccessible = true
            ctor.newInstance(shader) as RuntimeEffect
        } else {
            val clazz = Class.forName("com.mikepenz.hypnoticcanvas.FallbackAndroidRuntimeEffect")
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance() as RuntimeEffect
        }
    } catch (_: Throwable) {
        object : RuntimeEffect {
            override val supported: Boolean get() = false
            override val ready: Boolean get() = false
            override fun build(): Brush = SolidColor(Color.Transparent)
        }
    }
}

@Composable
actual fun rememberFluidFpsConfig(): FluidFpsConfig {
    val context = LocalContext.current
    return remember(context) {
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

        // 手机端流体背景帧率根据屏幕 hz 整除 2-5，最大 60fps
        var chosenDivisor = 2
        var chosenFps = screenHz / 2
        for (k in 2..5) {
            if (screenHz % k == 0 && screenHz / k <= 60) {
                chosenDivisor = k
                chosenFps = screenHz / k
                break
            }
        }
        if (chosenFps > 60) {
            for (k in 2..5) {
                if (screenHz / k <= 60) {
                    chosenDivisor = k
                    chosenFps = screenHz / k
                    break
                }
            }
        }
        FluidFpsConfig(
            divisor = chosenDivisor,
            targetFps = chosenFps.coerceIn(1, 60),
            screenHz = screenHz,
        )
    }
}
