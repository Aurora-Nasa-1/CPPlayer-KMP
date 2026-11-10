package cp.player.app.ui.component

import androidx.compose.ui.graphics.Color
import com.mikepenz.hypnoticcanvas.NonAndroidRuntimeEffect
import cp.player.app.AppModel
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
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
            dither = true,
        )

        val sksl = shader.sksl
        assertTrue(sksl.contains("uniform float uDither;"))
        assertTrue(sksl.contains("TPDF"))

        // 使用 Skia RuntimeEffect 验证 SkSL 语法完全合法
        val effect = RuntimeEffect.makeForShader(sksl)
        assertNotNull(effect, "SkSL 应该成功通过 Skia 编译")

        // 验证 NonAndroidRuntimeEffect 初始化与 applyUniforms 赋值
        val runtimeEffect = NonAndroidRuntimeEffect(shader)
        assertTrue(runtimeEffect.supported)

        // 开启抖动
        shader.dither = true
        shader.applyUniforms(runtimeEffect, 1.0f, 800f, 600f)

        // 关闭抖动
        shader.dither = false
        shader.applyUniforms(runtimeEffect, 2.0f, 800f, 600f)
    }

    @Test
    fun `AppModel抖动设置默认开启并支持切换`() {
        val initial = AppModel.fluidBackgroundDither()
        assertTrue(initial, "流体背景抗色阶断层默认应开启")

        AppModel.setFluidBackgroundDither(false)
        assertTrue(!AppModel.fluidBackgroundDither())

        AppModel.setFluidBackgroundDither(true)
        assertTrue(AppModel.fluidBackgroundDither())
    }
}
