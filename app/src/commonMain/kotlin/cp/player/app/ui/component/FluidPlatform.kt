package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import com.mikepenz.hypnoticcanvas.RuntimeEffect
import com.mikepenz.hypnoticcanvas.shaders.Shader
import kotlin.math.roundToInt

/**
 * 流体背景帧率步进与目标帧率配置。
 *
 * @param divisor 垂直同步分频因子（每几帧 VSync 更新一次渲染）。
 * @param targetFps 按整数 VSync 分频得到的实际流体背景帧率。
 * @param screenHz 显示设备的名义刷新率（Hz）。
 */
data class FluidFpsConfig(
    val divisor: Int,
    val targetFps: Int,
    val screenHz: Int,
)

/**
 * 将用户选择的最大帧率转换为 VSync 整数分频；实际帧率不会超过用户选择。
 */
fun calculateFluidFpsConfig(screenHz: Int, targetFps: Int): FluidFpsConfig {
    val refreshRate = screenHz.coerceAtLeast(1)
    val fpsCap = targetFps.coerceIn(1, 60)
    val divisor = ((refreshRate + fpsCap - 1) / fpsCap).coerceAtLeast(1)
    val effectiveFps = (refreshRate.toFloat() / divisor).roundToInt().coerceAtLeast(1)
    return FluidFpsConfig(divisor = divisor, targetFps = effectiveFps, screenHz = refreshRate)
}

/** 获取当前平台的流体背景帧率策略。 */
@Composable
expect fun rememberFluidFpsConfig(targetFps: Int): FluidFpsConfig

/**
 * 跨平台构建 HypnoticCanvas 的底层 RuntimeEffect（Desktop 走 Skiko，Android 走 RuntimeShader）。
 */
expect fun buildRuntimeEffect(shader: Shader): RuntimeEffect
