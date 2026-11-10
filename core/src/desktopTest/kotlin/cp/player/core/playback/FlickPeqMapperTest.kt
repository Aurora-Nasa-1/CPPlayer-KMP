package cp.player.core.playback

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FlickPeqMapper] 的纯函数契约。
 *
 * 这一层没有 JNI、没有音频设备，所以可以逐条钉死「下发给引擎的到底是什么」。
 * 重点覆盖三个曾经出错的地方：
 * 1. **0 dB 段必须被剔除**（旧实现把 5 段恒等滤波器全下发）；
 * 2. **Q 由段间距推导**（旧实现恒为 1.0，导致相邻段之间留凹陷）；
 * 3. **FX 的 wet 比例必须清零**（旧实现让「调左右平衡」附带 240 ms 混响）。
 */
class FlickPeqMapperTest {

    // ============================ PEQ ============================

    @Test
    fun `flat default bands produce no plan`() {
        val plan = FlickPeqMapper.peqPlan(EqualizerConfig(enabled = true))
        assertFalse(plan.active, "全 0 dB 时不应下发任何段（恒等滤波器只会白烧算力）")
        assertEquals("off", plan.signature())
    }

    @Test
    fun `disabled equalizer produces no plan even with non-zero gains`() {
        val bands = listOf(EqBand(gainDb = 6f, frequencyHz = 910f))
        val plan = FlickPeqMapper.peqPlan(EqualizerConfig(enabled = false, bands = bands))
        assertFalse(plan.active)
    }

    @Test
    fun `zero gain bands are dropped and non-zero ones kept`() {
        val bands = listOf(
            EqBand(gainDb = 0f, frequencyHz = 60f),
            EqBand(gainDb = 4.5f, frequencyHz = 230f),
            EqBand(gainDb = 0f, frequencyHz = 910f),
            EqBand(gainDb = -3f, frequencyHz = 3600f),
            EqBand(gainDb = 0f, frequencyHz = 14000f),
        )
        val plan = FlickPeqMapper.peqPlan(bands)
        assertTrue(plan.active)
        assertContentEquals(floatArrayOf(230f, 3600f), plan.freqsHz)
        assertContentEquals(floatArrayOf(4.5f, -3f), plan.gainsDb)
        assertEquals(2, plan.qs.size)
    }

    @Test
    fun `sub-threshold gains are treated as flat`() {
        val bands = listOf(EqBand(gainDb = 0.04f, frequencyHz = 910f))
        assertFalse(FlickPeqMapper.peqPlan(bands).active)
    }

    @Test
    fun `bands at the same frequency merge their gains`() {
        val bands = listOf(
            EqBand(gainDb = 6f, frequencyHz = 910f),
            EqBand(gainDb = 3f, frequencyHz = 910f),
        )
        val plan = FlickPeqMapper.peqPlan(bands)
        assertEquals(1, plan.freqsHz.size, "同频点两段应合并成一段，而不是串联两个相同 biquad")
        assertEquals(9f, plan.gainsDb[0], 1e-3f)
    }

    @Test
    fun `frequencies are clamped into the model range`() {
        val bands = listOf(
            EqBand(gainDb = 6f, frequencyHz = 5f),
            EqBand(gainDb = 6f, frequencyHz = 40_000f),
        )
        val plan = FlickPeqMapper.peqPlan(bands)
        assertContentEquals(
            floatArrayOf(EqBand.EQ_FREQ_MIN_HZ, EqBand.EQ_FREQ_MAX_HZ),
            plan.freqsHz,
        )
    }

    @Test
    fun `gains are clamped into the model range`() {
        val bands = listOf(EqBand(gainDb = 40f, frequencyHz = 910f))
        assertEquals(EqBand.EQ_GAIN_MAX_DB, FlickPeqMapper.peqPlan(bands).gainsDb[0], 1e-3f)
    }

    @Test
    fun `default five band layout yields roughly two octave bands`() {
        val layout = EqualizerConfig.DEFAULT_FREQUENCIES_HZ
        val qs = layout.indices.map { FlickPeqMapper.qForBand(layout, it) }
        // 60/230/910/3600/14000 的相邻间距都是 ~1.95 倍频程 ⇒ Q ≈ 0.67~0.69。
        // 恒定 1.0（≈1.41 倍频程）会让每段都比它负责的频段窄，相邻段之间留凹陷。
        qs.forEach { q ->
            assertTrue(q in 0.60f..0.75f, "Q=$q 偏离「铺满一个段间距」的区间")
        }
    }

    @Test
    fun `single band falls back to one octave q`() {
        val q = FlickPeqMapper.qForBand(listOf(910f), 0)
        assertEquals(1.41f, q, 1e-2f, "单段 PEQ 的默认带宽应为 1 倍频程（Q≈1.414）")
    }

    @Test
    fun `wider band spacing yields lower q`() {
        val narrow = FlickPeqMapper.qForBand(listOf(100f, 300f, 900f), 1)
        val wide = FlickPeqMapper.qForBand(listOf(100f, 900f, 8100f), 1)
        assertTrue(wide < narrow, "间距越大，单段应越宽（Q 越小）：narrow=$narrow wide=$wide")
    }

    @Test
    fun `bandwidth to q mapping keeps the rbj identity`() {
        assertEquals(1.41f, FlickPeqMapper.qForBandwidth(1.0f), 1e-2f)
        assertEquals(4.8f, FlickPeqMapper.qForBandwidth(0.3f), 5e-2f)
        assertEquals(0.27f, FlickPeqMapper.qForBandwidth(4.0f), 5e-2f)
    }

    @Test
    fun `signature is stable for identical parameters and changes with gain`() {
        val a = FlickPeqMapper.peqPlan(listOf(EqBand(gainDb = 6f, frequencyHz = 910f)))
        val b = FlickPeqMapper.peqPlan(listOf(EqBand(gainDb = 6f, frequencyHz = 910f)))
        assertEquals(a.signature(), b.signature(), "相同参数必须得到相同指纹，否则去抖失效")

        val c = FlickPeqMapper.peqPlan(listOf(EqBand(gainDb = 6.5f, frequencyHz = 910f)))
        assertTrue(a.signature() != c.signature())
    }

    @Test
    fun `plan equality follows the arrays`() {
        val a = FlickPeqMapper.peqPlan(listOf(EqBand(gainDb = 6f, frequencyHz = 910f)))
        val b = FlickPeqMapper.peqPlan(listOf(EqBand(gainDb = 6f, frequencyHz = 910f)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ============================ FX ============================

    @Test
    fun `neutral mixer disables fx`() {
        val plan = FlickPeqMapper.fxPlan(MixerConfig())
        assertFalse(plan.enabled)
        assertEquals(1f, plan.width)
        assertEquals(0f, plan.balance)
    }

    @Test
    fun `balance enables fx without reverb wet path`() {
        val plan = FlickPeqMapper.fxPlan(MixerConfig(balance = -0.4f))
        assertTrue(plan.enabled, "平衡非 0 时 FX 必须启用，否则平衡不生效")
        assertEquals(-0.4f, plan.balance, 1e-6f)
        assertEquals(1f, plan.width, 1e-6f, "只调平衡时不应顺带加宽立体声")
        // 引擎的 enabled 同时带着 240 ms 混响，wet 比例必须显式清零。
        assertEquals(0f, FlickPeqMapper.CLEAN_FX_MIX, 1e-6f)
        assertEquals(0f, FlickPeqMapper.CLEAN_FX_FEEDBACK, 1e-6f)
        assertEquals(0f, FlickPeqMapper.CLEAN_FX_DAMP, 1e-6f)
    }

    @Test
    fun `virtualizer strength maps to stereo width`() {
        val plan = FlickPeqMapper.fxPlan(MixerConfig(virtualizerStrength = 1f))
        assertTrue(plan.enabled)
        assertEquals(1.5f, plan.width, 1e-6f)
    }

    @Test
    fun `mixer values are clamped`() {
        val plan = FlickPeqMapper.fxPlan(MixerConfig(balance = 3f, virtualizerStrength = 4f))
        assertEquals(MixerConfig.BALANCE_MAX, plan.balance, 1e-6f)
        assertEquals(1.5f, plan.width, 1e-6f)
    }

    @Test
    fun `fx signature distinguishes enabled state`() {
        val off = FlickPeqMapper.fxPlan(MixerConfig())
        val on = FlickPeqMapper.fxPlan(MixerConfig(balance = 0.2f))
        assertTrue(off.signature() != on.signature())
    }

    // ============================ 动态处理 ============================

    @Test
    fun `dynamics plan is off when the whole chain or leveling is disabled`() {
        assertFalse(FlickPeqMapper.dynamicsPlan(LevelingConfig(enabled = true), overallEnabled = false).compressorEnabled)
        assertFalse(FlickPeqMapper.dynamicsPlan(LevelingConfig(enabled = false), overallEnabled = true).compressorEnabled)
        assertFalse(FlickPeqMapper.dynamicsPlan(LevelingConfig(enabled = false), overallEnabled = true).limiterEnabled)
    }

    @Test
    fun `dynamics plan maps target loudness to compressor threshold`() {
        val plan = FlickPeqMapper.dynamicsPlan(
            LevelingConfig(enabled = true, targetLevelDb = -12f, preventClipping = true),
            overallEnabled = true,
        )
        assertTrue(plan.compressorEnabled)
        assertEquals(-12f, plan.thresholdDb, 1e-6f)
        assertEquals(6f, plan.makeupGainDb, 1e-6f, "只补回被压掉的一半，不能全补回去等于没压")
        assertTrue(plan.limiterEnabled)
    }

    @Test
    fun `prevent clipping controls only the limiter`() {
        val plan = FlickPeqMapper.dynamicsPlan(
            LevelingConfig(enabled = true, preventClipping = false),
            overallEnabled = true,
        )
        assertTrue(plan.compressorEnabled, "关掉防爆音不应连带关掉响度均衡")
        assertFalse(plan.limiterEnabled)
    }

    @Test
    fun `dynamics plan clamps target level and makeup gain`() {
        val quiet = FlickPeqMapper.dynamicsPlan(
            LevelingConfig(enabled = true, targetLevelDb = -100f), overallEnabled = true,
        )
        assertEquals(LevelingConfig.TARGET_LEVEL_MIN_DB, quiet.thresholdDb, 1e-6f)
        assertEquals(6f, quiet.makeupGainDb, 1e-6f)

        val loud = FlickPeqMapper.dynamicsPlan(
            LevelingConfig(enabled = true, targetLevelDb = 20f), overallEnabled = true,
        )
        assertEquals(LevelingConfig.TARGET_LEVEL_MAX_DB, loud.thresholdDb, 1e-6f)
        assertEquals(0f, loud.makeupGainDb, 1e-6f)
    }

    @Test
    fun `dynamics signature changes with target level`() {
        val a = FlickPeqMapper.dynamicsPlan(LevelingConfig(enabled = true, targetLevelDb = -6f), true)
        val b = FlickPeqMapper.dynamicsPlan(LevelingConfig(enabled = true, targetLevelDb = -6f), true)
        val c = FlickPeqMapper.dynamicsPlan(LevelingConfig(enabled = true, targetLevelDb = -9f), true)
        assertEquals(a.signature(), b.signature())
        assertTrue(a.signature() != c.signature())
    }
}
