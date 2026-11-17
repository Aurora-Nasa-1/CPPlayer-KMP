package cp.player.app.ui.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import cp.player.app.ui.theme.CpTheme
import cp.player.app.ui.theme.ThemeMode
import cp.player.core.playback.EqBand
import java.io.File
import cp.player.app.readPng
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PEQ 响应曲线（`EqualizerCurveCard`）的回归测试。
 *
 * 缺陷背景：第一版按**下标均分**横轴、把 5 个增益点用贝塞尔连起来，`frequencyHz`
 * 完全不参与绘制 —— 改频点曲线纹丝不动，用户以为频点调节没生效。
 *
 * 两层锁：
 * 1. 数学层：单段峰值双二阶在中心频点处必须等于设定增益，频点移动则响应随之移动；
 * 2. 像素层：同一组增益、只改频点，渲染出的卡片**必须不同**（旧实现两张图完全相同 ⇒ 这条会红）。
 */
class PeqCurveResponseTest {

    private val flatBands = listOf(60f, 230f, 910f, 3600f, 14000f)

    // ---------------------------------------------------------------- 数学层

    @Test
    fun `单段在中心频点处等于设定增益`() {
        for (gain in listOf(-12f, -6f, 3f, 12f)) {
            val db = peakingBandResponseDb(hz = 1000f, centerHz = 1000f, gainDb = gain)
            assertTrue(
                abs(db - gain) < 0.05f,
                "中心频点处的响应应等于设定增益，期望 $gain 实际 $db",
            )
        }
    }

    @Test
    fun `频点移动后同一频率的响应随之变化`() {
        val at100 = peakingBandResponseDb(hz = 100f, centerHz = 100f, gainDb = 9f)
        val at1k = peakingBandResponseDb(hz = 100f, centerHz = 1000f, gainDb = 9f)
        assertTrue(abs(at100 - 9f) < 0.2f, "100 Hz 段的中心就在 100 Hz，期望 ≈ +9dB，实际 $at100")
        assertTrue(at1k < 1f, "同一段挪到 1 kHz 后，100 Hz 处应几乎不受影响，实际 $at1k")
    }

    @Test
    fun `合成响应是各段之和`() {
        val bands = listOf(
            EqBand(gainDb = 6f, frequencyHz = 1000f),
            EqBand(gainDb = -3f, frequencyHz = 1000f),
        )
        val total = peqResponseDb(bands, 1000f)
        assertTrue(abs(total - 3f) < 0.1f, "两段同频叠加应约等于 +3dB，实际 $total")
    }

    @Test
    fun `平直配置的合成响应处处为零`() {
        val bands = flatBands.map { EqBand(gainDb = 0f, frequencyHz = it) }
        samplePeqResponse(bands, 64).forEach { db ->
            assertTrue(abs(db) < 0.01f, "全平直时响应应为 0dB，实际 $db")
        }
    }

    @Test
    fun `频率轴是对数刻度`() {
        assertEquals(0f, peqFrequencyRatio(20f))
        assertEquals(1f, peqFrequencyRatio(20000f))
        // 20 → 200 是一个十倍频程，占全轴 1/3（20 Hz ~ 20 kHz 共三个十倍频程）
        assertEquals(1f / 3f, peqFrequencyRatio(200f), 0.001f)
        // 越界夹紧，不产生 NaN
        assertEquals(0f, peqFrequencyRatio(5f))
        assertEquals(1f, peqFrequencyRatio(40000f))
    }

    // ---------------------------------------------------------------- 像素层

    private fun renderBands(bands: List<EqBand>, name: String): IntArray {
        val output = File("build/peq-curve").apply { mkdirs() }
        val scene = ImageComposeScene(width = 420, height = 140, density = Density(1f)) {
            CpTheme(themeMode = ThemeMode.LIGHT) {
                EqualizerCurveCard(
                    bands = bands,
                    enabled = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return try {
            val image = scene.render()
            assertEquals(420, image.width)
            assertEquals(140, image.height)
            val bytes = image.encodeToData()?.bytes ?: error("PNG encoding failed")
            File(output, "$name.png").writeBytes(bytes)
            val bitmap = readPng(bytes)
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getRGB(0, 0, bitmap.width, bitmap.height, it, 0, bitmap.width)
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `改频点曲线必须变化`() {
        val left = renderBands(
            flatBands.mapIndexed { i, hz -> EqBand(gainDb = if (i == 2) 12f else 0f, frequencyHz = hz) },
            "peq-mid-910",
        )
        val right = renderBands(
            flatBands.mapIndexed { i, hz -> EqBand(gainDb = if (i == 2) 12f else 0f, frequencyHz = if (i == 2) 4000f else hz) },
            "peq-mid-4000",
        )
        assertFalse(
            left.contentEquals(right),
            "只改中频段的频点，曲线形状必须跟着变（旧实现按下标均分横轴，两者会一模一样）",
        )
    }

    @Test
    fun `平直配置画出的是零增益直线`() {
        val flat = renderBands(flatBands.map { EqBand(gainDb = 0f, frequencyHz = it) }, "peq-flat")
        val boosted = renderBands(
            flatBands.map { EqBand(gainDb = 12f, frequencyHz = it) },
            "peq-all-boost",
        )
        assertFalse(flat.contentEquals(boosted), "全段 +12dB 与全平直的曲线必须不同")
        assertTrue(flat.toSet().size > 8, "曲线卡片必须画出可见内容（背景 + 网格 + 曲线）")
    }
}
