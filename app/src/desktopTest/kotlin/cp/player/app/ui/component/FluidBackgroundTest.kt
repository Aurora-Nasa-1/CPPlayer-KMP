package cp.player.app.ui.component

import androidx.compose.ui.graphics.Color
import com.mikepenz.hypnoticcanvas.NonAndroidRuntimeEffect
import cp.player.app.AppModel
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FluidBackgroundTest {

    @Test
    fun `CpFluidMeshGradient着色器SkSL编译无错误且支持Dither`() {
        val colors = arrayOf(
            Color(0xFF2B1B17),
            Color(0xFF1C2833),
            Color(0xFF1E3F20),
            Color(0xFF121212),
        )
        val shader = CpFluidMeshGradient(
            colors = colors,
            speed = 1f,
            scale = 2f,
        )
        assertTrue(shader.dither, "抗色阶抖动默认应开启")

        val sksl = shader.sksl
        assertTrue(sksl.contains("uniform float uDither;"))
        assertTrue(sksl.contains("TPDF"))
        assertEquals(2, Regex("snoise\\(vec3\\(").findAll(sksl).count(), "每像素只应计算两组 Simplex 噪声场")
        assertTrue(!sksl.contains("base.x + uTime * 0.2"), "未使用的额外 Simplex 噪声不应逐像素计算")

        // 使用 Skia RuntimeEffect 验证 SkSL 语法完全合法
        val effect = RuntimeEffect.makeForShader(sksl)
        assertNotNull(effect, "SkSL 应该成功通过 Skia 编译")

        // 验证 NonAndroidRuntimeEffect 初始化与 applyUniforms 赋值
        val runtimeEffect = NonAndroidRuntimeEffect(shader)
        assertTrue(runtimeEffect.supported)

        shader.applyUniforms(runtimeEffect, 1.0f, 800f, 600f)
    }

    @Test
    fun `流体背景默认开启且帧率默认30并支持切换`() {
        assertTrue(AppModel.fluidBackground(), "流体背景默认应开启")
        assertEquals(30, AppModel.fluidBackgroundFps())

        AppModel.setFluidBackgroundFps(60)
        assertEquals(60, AppModel.fluidBackgroundFps())
        AppModel.setFluidBackgroundFps(24)
        assertEquals(30, AppModel.fluidBackgroundFps(), "不支持的 FPS 值应回落到默认30")
        AppModel.setFluidBackgroundFps(30)
    }

    @Test
    fun `流体帧率按显示器刷新率整数分频且不超过所选上限`() {
        val low = calculateFluidFpsConfig(screenHz = 60, targetFps = 15)
        assertEquals(4, low.divisor)
        assertEquals(15, low.targetFps)

        val balanced = calculateFluidFpsConfig(screenHz = 60, targetFps = 30)
        assertEquals(2, balanced.divisor)
        assertEquals(30, balanced.targetFps)

        val highRefresh = calculateFluidFpsConfig(screenHz = 144, targetFps = 60)
        assertEquals(3, highRefresh.divisor)
        assertEquals(48, highRefresh.targetFps)
        assertTrue(highRefresh.targetFps <= 60)
    }
}
