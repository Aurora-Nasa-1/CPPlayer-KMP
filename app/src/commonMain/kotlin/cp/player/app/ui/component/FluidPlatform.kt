package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
 * 流体背景渲染后端引擎梯队：
 * - [MESH_GRADIENT]: Android 14+ (API 34+) 及 Desktop，走原生 Canvas.drawMesh / Skiko；
 * - [RUNTIME_SHADER]: Android 13 (API 33)，走系统原生 AGSL RuntimeShader；
 * - [CANVAS_BLOBS]: Android 12 及以下，走全兼容的高性能多光团径向有机弥散。
 */
enum class FluidRenderTier {
    MESH_GRADIENT,
    RUNTIME_SHADER,
    CANVAS_BLOBS,
}

/** 探测当前平台与系统版本所支持的最佳流体渲染梯队。 */
expect fun resolveFluidRenderTier(): FluidRenderTier

/**
 * 分平台流体渲染器调度实现。
 */
@Composable
internal expect fun Modifier.renderPlatformFluid(
    tier: FluidRenderTier,
    sceneModel: State<FluidSceneModel>,
    fallback: Brush,
): Modifier
