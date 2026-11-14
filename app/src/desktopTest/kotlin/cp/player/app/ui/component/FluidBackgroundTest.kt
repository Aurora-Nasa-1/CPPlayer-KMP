package cp.player.app.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Density
import com.mikepenz.hypnoticcanvas.NonAndroidRuntimeEffect
import cp.player.app.AppModel
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import org.jetbrains.skia.RuntimeEffect
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FluidBackgroundTest {

    @Test
    fun `流体背景只在导航转场期间冻结`() {
        // 反过来（转场结束才冻结）会让播放页一亮相就永久定格 —— 别再改回去。
        assertTrue(shouldPauseFluidShaderDuringNavigation(transitionIsRunning = true))
        assertFalse(shouldPauseFluidShaderDuringNavigation(transitionIsRunning = false))
    }

    /**
     * 本 bug 的像素级回归守卫：**时间轴在走，画面就必须变**。
     *
     * 之前 `shouldPauseFluidShaderAfterNavigation` 把「转场结束」接成「暂停」，
     * 播放页进场 400ms 后背景永久停在第一帧；这个用例会在那种实现下直接失败。
     */
    @Test
    fun `流体背景在未暂停时随时间流动`() {
        val frames = renderFrames(pauseShaderAnimation = false)
        try {
            assertNotEquals(frames.sha1, frames.laterSha1, "时间轴前进后流体画面必须变化；完全相同说明又冻住了")
        } finally {
            frames.close()
        }
    }

    /** 冻结必须真的省资源：不再推进时间，后续帧应当与冻结帧逐像素一致。 */
    @Test
    fun `流体背景冻结时不再推进时间也不请求新帧`() {
        val frames = renderFrames(pauseShaderAnimation = true)
        try {
            assertEquals(frames.sha1, frames.laterSha1, "冻结期间不应再推进时间轴")
            repeat(10) { frames.scene.render(frames.lastFrameTimeNanos + it * 16_666_667L) }
            assertFalse(
                frames.scene.hasInvalidations(),
                "冻结期间不应再有帧请求；还有失效说明时间轴仍在跑，转场动画期间白烧一份着色器开销",
            )
        } finally {
            frames.close()
        }
    }

    private class RenderedFrames(
        val sha1: String,
        val laterSha1: String,
        val scene: ImageComposeScene,
        val lastFrameTimeNanos: Long,
    ) {
        fun close() = scene.close()
    }

    private fun renderFrames(pauseShaderAnimation: Boolean): RenderedFrames {
        val scene = ImageComposeScene(width = 240, height = 240, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().cpFluidBackground(
                        enabled = true,
                        fallback = SolidColor(Color.Black),
                        pauseShaderAnimation = pauseShaderAnimation,
                    ),
                )
            }
        }
        var t = 0L
        val step = 16_666_667L
        repeat(8) { scene.render(t); t += step }
        val first = hashRendered(scene)
        repeat(40) { scene.render(t); t += step }
        val later = hashRendered(scene)
        return RenderedFrames(first, later, scene, t)
    }

    private fun hashRendered(scene: ImageComposeScene): String {
        val bytes = scene.render().encodeToData()?.bytes ?: error("ImageComposeScene PNG encode failed")
        val image = readPng(bytes)
        val digest = MessageDigest.getInstance("SHA-1")
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                digest.update((argb ushr 16).toByte())
                digest.update((argb ushr 8).toByte())
                digest.update(argb.toByte())
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

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
