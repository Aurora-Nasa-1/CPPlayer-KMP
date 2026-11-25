package cp.player.app.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import cp.player.app.AppModel
import cp.player.app.PngImage
import cp.player.app.readPng
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.app.ui.theme.solidImage
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
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
        assertTrue(shouldPauseFluidBackgroundDuringNavigation(transitionIsRunning = true))
        assertFalse(shouldPauseFluidBackgroundDuringNavigation(transitionIsRunning = false))
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
            assertEquals(0, frames.recompositions, "Fluid motion should redraw without recomposing its host")
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
        val recompositions: Int,
        val scene: ImageComposeScene,
        val lastFrameTimeNanos: Long,
    ) {
        fun close() = scene.close()
    }

    @Test
    fun `wide fluid background preserves the gradient at different viewport sizes`() {
        val palette = listOf(
            Color(0xffb61d56), Color(0xff274fa3), Color(0xff6945b0), Color(0xff171023),
        )
        fun render(width: Int, height: Int, preview: File? = null): PngImage {
            val scene = ImageComposeScene(width, height, density = Density(1f)) {
                CpTheme(themeMode = ThemeMode.DARK) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize().cpFluidBackground(
                            fallback = SolidColor(Color.Black),
                            pauseShaderAnimation = true,
                            palette = palette,
                            playing = false,
                        ),
                    )
                }
            }
            try {
                val bytes = scene.render(0L).use { image ->
                    image.encodeToData()!!.use { it.bytes }
                }
                preview?.let { it.parentFile.mkdirs(); it.writeBytes(bytes) }
                return readPng(bytes)
            } finally {
                scene.close()
            }
        }

        val regular = render(1280, 720)
        val wide = render(2560, 1440, File("build/visual/fluid-wide.png"))
        for (y in 32 until regular.height step 47) {
            for (x in 32 until regular.width step 59) {
                val expected = regular.getRGB(x, y)
                val actual = wide.getRGB(x * 2, y * 2)
                for (shift in listOf(16, 8, 0)) {
                    val difference = abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255))
                    assertTrue(difference <= 6, "Color changed by $difference at ($x, $y)")
                }
            }
        }
    }

    private fun renderFrames(pauseShaderAnimation: Boolean): RenderedFrames {
        var compositions = 0
        val scene = ImageComposeScene(width = 240, height = 240, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                SideEffect { compositions++ }
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().cpFluidBackground(
                        enabled = true,
                        fallback = SolidColor(Color.Black),
                        pauseShaderAnimation = pauseShaderAnimation,
                        playing = false,
                    ),
                )
            }
        }
        var t = 0L
        val step = 16_666_667L
        repeat(8) { scene.render(t); t += step }
        val first = hashRendered(scene)
        val initialCompositions = compositions
        repeat(40) { scene.render(t); t += step }
        val later = hashRendered(scene)
        return RenderedFrames(first, later, compositions - initialCompositions, scene, t)
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
    fun `流体背景抖动层在暗色上产生微噪声`() {
        val scene = ImageComposeScene(width = 64, height = 64, density = Density(1f)) {
            val dither = remember { createFluidDitherBrush() }
            Canvas(Modifier.fillMaxSize()) {
                // 用暗灰而不是中灰：乘性抖动在暗部会被压到不足 1 LSB，
                // 这个底色能保证回归用例真的覆盖「暗部色带」这个场景。
                drawRect(Color(0xFF101010))
                drawRect(brush = dither, blendMode = BlendMode.Plus)
            }
        }
        try {
            val encoded = scene.render().encodeToData() ?: error("ImageComposeScene PNG encode failed")
            val image = readPng(encoded.bytes)
            val colors = buildSet {
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) add(image.getRGB(x, y))
                }
            }
            assertTrue(colors.size > 1, "纯色叠加抖动层后必须出现多个相邻像素值，否则色带不会被打破")
        } finally {
            scene.close()
        }
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

    @Test
    fun `AppleMusic流体色盘提取具备深邃暗底与鲜活多色团`() {
        val coverArgb = 0xFF3B82F6.toInt()
        val image = solidImage(coverArgb)
        // HCT 的 chroma 上限随色相与明度变化：蓝色在 tone 50 附近最多只能到 ~43，
        // 超过上限会被色域裁剪。这里改为「贴近该色相/明度下的色域边界」，
        // 既能验证色团够鲜艳，又不会要求几何上达不到的彩度。
        val darkPalette = cp.player.app.ui.theme.extractCoverFluidPalette(image, dark = true)
        assertNotNull(darkPalette, "彩色封面必须能提取出深色流体色盘")
        assertEquals(4, darkPalette.size, "流体色盘必须包含 3 个流体光团色与 1 个底色（共 4 色）")

        // 验证末位底色具有深邃氛围（纯正暗夜底 Tone 8~18，彻底告别 Monet 泥灰色）
        val baseHct = com.materialkolor.hct.Hct.fromInt(darkPalette.last().toArgb())
        assertTrue(baseHct.tone in 8.0..18.0, "深色流体底色必须为幽邃暗夜调（Tone 8~18），当前 Tone=${baseHct.tone}")

        // 验证前 3 个色团具备鲜活的彩度与通透的明度
        for (i in 0 until 3) {
            val hct = com.materialkolor.hct.Hct.fromInt(darkPalette[i].toArgb())
            assertTrue(hct.tone in 40.0..62.0, "流体高光色团明度需通透且文字可读（Tone 40~62），当前 Tone=${hct.tone}")
            val maxChromaAtTone = com.materialkolor.hct.Hct.from(hct.hue, 200.0, hct.tone).chroma
            assertTrue(
                hct.chroma >= maxChromaAtTone * 0.9,
                "流体高光色团应贴近色域边界（Hue=${hct.hue} Tone=${hct.tone} " +
                    "上限 Chroma=$maxChromaAtTone），当前 Chroma=${hct.chroma}",
            )
        }

        // 浅色模式流体色盘验证
        val lightPalette = cp.player.app.ui.theme.extractCoverFluidPalette(image, dark = false)
        assertNotNull(lightPalette, "彩色封面必须能提取出浅色流体色盘")
        assertEquals(4, lightPalette.size)
        val lightBaseHct = com.materialkolor.hct.Hct.fromInt(lightPalette.last().toArgb())
        assertTrue(lightBaseHct.tone >= 95.0, "浅色流体底色必须为纯净明底（Tone>=95），当前 Tone=${lightBaseHct.tone}")
    }

    /**
     * 切歌过渡的像素级回归守卫。
     *
     * 旧实现里 `previousPalette = currentPalette` 之后，插值的终点仍然取
     * `currentPalette`（旧值）⇒ 750ms 内 from == to，画面一动不动，动画结束
     * 才换目标 ⇒ 观感是延迟后硬切。这个用例会抓住「单帧跳变过大」和
     * 「过渡帧数不足」两种退化。
     */
    @Test
    fun `切歌色板在过渡期间连续插值而不是硬切`() {
        // 一暖一冷，且底色也拉开：多色相混合后平均色才不会又变成同一种中性灰，
        // 否则「总差异」小到与自然漂移同量级，跳变阈值就无法区分硬切。
        val paletteA = listOf(
            Color(0xFFD02010), Color(0xFFFF7010), Color(0xFFB01050), Color(0xFF200604),
        )
        val paletteB = listOf(
            Color(0xFF1040D0), Color(0xFF00B0C0), Color(0xFF5010C0), Color(0xFF040820),
        )
        val palette = mutableStateOf(paletteA)
        val scene = ImageComposeScene(width = 160, height = 160, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.DARK) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().cpFluidBackground(
                        enabled = true,
                        fallback = SolidColor(Color.Black),
                        palette = palette.value,
                        playing = false,
                    ),
                )
            }
        }
        try {
            var now = 0L
            fun advance(millis: Long): IntArray {
                now += millis * 1_000_000L
                return averageCenterColor(readPng(scene.render(now).encodeToData()!!.bytes))
            }

            repeat(20) { advance(16) }
            val before = advance(0)

            palette.value = paletteB

            // 220ms 防抖 + 750ms 过渡 + 余量；25ms 一帧，避免自然漂移本身
            // 在单帧里贡献过大差异，淹没「切歌硬切」的信号。
            val samples = List(52) { advance(25) }
            val after = samples.last()
            val totalDelta = colorDistance(before, after)
            assertTrue(totalDelta > 12, "两套色板必须产生可见的整体差异，当前 Δ=$totalDelta")

            val movedFrames = samples.count { colorDistance(it, before) > 3 }
            assertTrue(movedFrames >= 5, "过渡应覆盖多帧；只有 $movedFrames 帧离开起始色，说明是硬切")

            val maxJump = (listOf(before) + samples).zipWithNext().maxOf { (a, b) ->
                colorDistance(a, b)
            }
            assertTrue(
                maxJump * 2 < totalDelta,
                "单帧跳变 $maxJump 超过总差异 $totalDelta 的一半；这是硬切而不是过渡",
            )
        } finally {
            scene.close()
        }
    }

    private fun averageCenterColor(image: PngImage): IntArray {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (y in image.height / 4 until image.height * 3 / 4 step 4) {
            for (x in image.width / 4 until image.width * 3 / 4 step 4) {
                val argb = image.getRGB(x, y)
                r += (argb ushr 16) and 0xFF
                g += (argb ushr 8) and 0xFF
                b += argb and 0xFF
                n++
            }
        }
        return intArrayOf(r / n, g / n, b / n)
    }

    private fun colorDistance(a: IntArray, b: IntArray): Int =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])
}
