package cp.player.app.ui.component

import androidx.compose.runtime.Composable
import com.mikepenz.hypnoticcanvas.RuntimeEffect
import com.mikepenz.hypnoticcanvas.shaders.Shader

/**
 * 流体背景帧率步进与目标帧率配置。
 *
 * @param divisor 垂直同步分频因子（每几帧 VSync 更新一次渲染）。
 * @param targetFps 期望达到的物理帧率（<= 60fps on mobile；桌面端为屏幕刷新率一半）。
 * @param screenHz 显示设备的名义刷新率（Hz）。
 */
data class FluidFpsConfig(
    val divisor: Int,
    val targetFps: Int,
    val screenHz: Int,
)

/**
 * 获取当前平台的流体背景帧率策略。
 * - 桌面端：显示屏刷新率的一半（divisor = 2）。
 * - 手机端：根据屏幕 Hz 在 2..5 范围内取整除因子，目标帧率最大 60fps。
 */
@Composable
expect fun rememberFluidFpsConfig(): FluidFpsConfig

/**
 * 跨平台构建 HypnoticCanvas 的底层 RuntimeEffect（Desktop 走 Skiko，Android 走 RuntimeShader）。
 */
expect fun buildRuntimeEffect(shader: Shader): RuntimeEffect
