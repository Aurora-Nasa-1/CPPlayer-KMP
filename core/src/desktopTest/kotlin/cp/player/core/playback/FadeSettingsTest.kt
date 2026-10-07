package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 淡入淡出的回归守卫。
 *
 * 覆盖四块：
 * 1. **曲线方向**（[FadeCurve]）—— 最易出的错是淡入淡出写反（起手就把音量清 0）。
 * 2. **斜坡边界**（[FadeRunner]）—— 起点 / 终点必须精确落在 from / to 上。
 * 3. **抢占语义** —— 用户在过渡途中操作时，旧斜坡必须停、且**不能**再回调。
 * 4. **持久化契约**（[FadeSettings]）—— 键拼错 / 坏值不污染其余项。
 *
 * 第 2、3 块是「编译过、UI 正常、出图也看不出」的那一类：
 * 曲线写反时设置页照样渲染、单测若不查数值也照样绿，只有真去听才发现。
 */
class FadeSettingsTest {

    // =======================================================================
    // 1. 曲线方向
    // =======================================================================

    @Test
    fun `fade in starts silent and ends at full`() {
        // 起点必须是 0：淡入的定义就是"从无声开始"。若这里非 0，
        // 说明淡入淡出写反了 —— 切歌会以满音量起手，然后越听越小。
        assertEquals(0f, FadeCurve.fadeIn(0f), ABS_TOL, "淡入起点必须是静音")
        // 终点必须是 1：否则曲子永远比用户设定的音量轻（淡入结束时没归位）。
        assertEquals(1f, FadeCurve.fadeIn(1f), ABS_TOL, "淡入终点必须是满音量")
    }

    @Test
    fun `fade out starts at full and ends silent`() {
        assertEquals(1f, FadeCurve.fadeOut(0f), ABS_TOL, "淡出起点必须是满音量")
        assertEquals(0f, FadeCurve.fadeOut(1f), ABS_TOL, "淡出终点必须是静音")
    }

    @Test
    fun `curves are monotonic`() {
        // 单调性是"渐变"的定义：任何一条非单调的曲线都会让音量先降后升，
        // 听感上是个明显的波动。逐点比对，不只查端点。
        for (curve in FadeCurve.Curve.entries) {
            var prevIn = -1f
            var prevOut = Float.MAX_VALUE
            for (step in 0..100) {
                val p = step / 100f
                val inV = FadeCurve.fadeIn(p, curve)
                val outV = FadeCurve.fadeOut(p, curve)
                assertTrue(
                    inV >= prevIn, "$curve 淡入在 p=$p 处回退了（$prevIn → $inV）",
                )
                assertTrue(
                    outV <= prevOut, "$curve 淡出在 p=$p 处回涨了（$prevOut → $outV）",
                )
                prevIn = inV
                prevOut = outV
            }
        }
    }

    @Test
    fun `curves stay within the normalised range`() {
        // 越界会在 FadeRunner 里被 coerce，但那是兜底；曲线自己就不该产出越界值
        // （一旦依赖兜底，将来兜底被挪走就会静默出界）。
        for (curve in FadeCurve.Curve.entries) {
            for (step in -10..110) {
                val p = step / 100f
                val inV = FadeCurve.fadeIn(p, curve)
                val outV = FadeCurve.fadeOut(p, curve)
                assertTrue(inV in 0f..1f, "$curve 淡入 p=$p 越界：$inV")
                assertTrue(outV in 0f..1f, "$curve 淡出 p=$p 越界：$outV")
            }
        }
    }

    @Test
    fun `out of range progress is clamped rather than extrapolated`() {
        // 负数/超界进度必须钳到端点，不能外推 —— 外推会产出负音量或 >1 的音量。
        assertEquals(0f, FadeCurve.fadeIn(-5f), ABS_TOL)
        assertEquals(1f, FadeCurve.fadeIn(5f), ABS_TOL)
        assertEquals(1f, FadeCurve.fadeOut(-5f), ABS_TOL)
        assertEquals(0f, FadeCurve.fadeOut(5f), ABS_TOL)
    }

    @Test
    fun `default curve is not linear`() {
        // 本仓刻意不选线性（人耳对响度接近对数感知，线性音量中段会"没变化"）。
        // 这条是防"有人图省事把 DEFAULT 改成 LINEAR"的绊线。
        assertEquals(FadeCurve.Curve.EXPONENTIAL_ISH, FadeCurve.DEFAULT)
        val midLinear = 0.5f
        val midActual = FadeCurve.fadeOut(0.5f)
        assertTrue(
            kotlin.math.abs(midActual - midLinear) > 0.05f,
            "默认曲线在 p=0.5 处应与线性有明显差异，实际 $midActual",
        )
    }

    @Test
    fun `equal power curve keeps total power roughly constant`() {
        // 等功率曲线的定义性质（这条曲线是为 crossfade 准备的，将来若真做 crossfade
        // 会用到它）：in² + out² 在整个过渡中应恒为 1。
        for (step in 0..100) {
            val p = step / 100f
            val inV = FadeCurve.fadeIn(p, FadeCurve.Curve.EQUAL_POWER)
            val outV = FadeCurve.fadeOut(p, FadeCurve.Curve.EQUAL_POWER)
            val power = inV * inV + outV * outV
            assertEquals(1f, power, 0.01f, "等功率在 p=$p 处塌陷：$power")
        }
    }

    // =======================================================================
    // 2. 斜坡边界（FadeRunner）
    // =======================================================================

    /** 收集斜坡推出来的音量序列。 */
    private class VolumeSink {
        val values = mutableListOf<Float>()
        fun apply(v: Float) { values.add(v) }
        val last: Float get() = values.last()
    }

    private fun runFade(
        from: Float,
        to: Float,
        durationMs: Int = 40,
    ): VolumeSink {
        val sink = VolumeSink()
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val runner = FadeRunner(scope) { v -> sink.apply(v) }
            withTimeout(10_000) {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                runner.start(from, to, durationMs, onFinished = { done.complete(Unit) })
                done.await()
            }
            runner.cancel()
        }
        return sink
    }

    @Test
    fun `ramp starts near from and lands exactly on to`() {
        // ⚠️ 第一帧不**等于** from，而是"从 from 出发的第一小步" ——
        // 斜坡没有"停在起点"这一帧（那会白等一个 interval）。
        // 所以判据是"第一帧在 from 与 to 之间、且明显更靠近 from"。
        val up = runFade(from = 0.2f, to = 0.8f)
        val upFirst = up.values.first()
        assertTrue(
            upFirst > 0.2f && upFirst < 0.35f,
            "第一帧应刚离开起点 0.2，实际 $upFirst",
        )
        assertEquals(0.8f, up.last, ABS_TOL, "最后一帧必须精确落在 to 上（不能差一个 ulp）")

        val down = runFade(from = 0.8f, to = 0f)
        val downFirst = down.values.first()
        assertTrue(
            downFirst < 0.8f && downFirst > 0.65f,
            "第一帧应刚离开起点 0.8，实际 $downFirst",
        )
        assertEquals(0f, down.last, ABS_TOL, "淡出必须归零")
    }

    @Test
    fun `non unit endpoints do not overshoot on the first frame`() {
        // ⚠️ 这条钉的是一个真实写错过的 bug：曾经拿「音量包络」当「进度比例」插值。
        // 两者只在 `from=1, to=0` 时数值重合，所以那组端点的测试是绿的；
        // 换成 `from=0.8 → 0` 就变成第一帧直接砸到 ~0.02（听感是"啪"地没了）。
        // 判据：第一帧必须落在 from 与 to 之间、且**不超过** from。
        val sink = runFade(from = 0.8f, to = 0f)
        val first = sink.values.first()
        assertTrue(
            first <= 0.8f + ABS_TOL,
            "第一帧不能超过起点 0.8，实际 $first",
        )
        assertTrue(
            first >= 0.6f,
            "第一帧不该一上来就砸到很低（进度曲线错误），实际 $first",
        )
    }

    @Test
    fun `fade in with a low base volume never exceeds it`() {
        // 反向的同一个坑：`from=0 → to=0.3` 时，若拿包络当比例，
        // 第一帧就会直接跳到 0.3 之上（音量先冲高再回落）。
        val sink = runFade(from = 0f, to = 0.3f)
        assertTrue(
            sink.values.all { it <= 0.3f + ABS_TOL },
            "淡入不该越过目标 0.3，峰值 ${sink.values.maxOrNull()}",
        )
        assertEquals(0.3f, sink.last, ABS_TOL)
    }

    @Test
    fun `ramp is monotonic for a plain fade in`() {
        val sink = runFade(from = 0f, to = 1f)
        assertTrue(sink.values.size >= 5, "步数太少，量不出平滑度：${sink.values.size}")
        for (i in 1 until sink.values.size) {
            assertTrue(
                sink.values[i] >= sink.values[i - 1] - ABS_TOL,
                "淡入在第 $i 帧回退了：${sink.values[i - 1]} → ${sink.values[i]}",
            )
        }
    }

    @Test
    fun `ramp is monotonic for a plain fade out`() {
        val sink = runFade(from = 1f, to = 0f)
        for (i in 1 until sink.values.size) {
            assertTrue(
                sink.values[i] <= sink.values[i - 1] + ABS_TOL,
                "淡出在第 $i 帧回涨了：${sink.values[i - 1]} → ${sink.values[i]}",
            )
        }
    }

    @Test
    fun `zero duration applies the target immediately without a ramp`() {
        val sink = VolumeSink()
        runBlocking {
            val runner = FadeRunner(CoroutineScope(Dispatchers.Default)) { v -> sink.apply(v) }
            var finished = false
            runner.start(0f, 0.7f, durationMs = 0, onFinished = { finished = true })
            assertTrue(finished, "0 时长必须同步完成回调")
            runner.cancel()
        }
        assertEquals(listOf(0.7f), sink.values, "0 时长应只写一次目标值，不起斜坡")
    }

    @Test
    fun `target volume is honoured rather than forced to full`() {
        // 这条是防"淡入把音量顶到 100%"的事故：用户音量 30% 时，
        // 淡入终点必须是 0.3 而不是 1.0。
        val sink = runFade(from = 0f, to = 0.3f)
        assertEquals(0.3f, sink.last, ABS_TOL, "淡入必须落在用户音量上，不能顶到满格")
        assertTrue(
            sink.values.none { it > 0.3f + ABS_TOL },
            "淡入过程中不该超过用户音量：${sink.values.maxOrNull()}",
        )
    }

    // =======================================================================
    // 3. 抢占语义
    // =======================================================================

    @Test
    fun `a new ramp preempts the running one`() {
        val sink = VolumeSink()
        runBlocking {
            val runner = FadeRunner(CoroutineScope(Dispatchers.Default)) { v -> sink.apply(v) }
            // 一条很长的斜坡，随即被第二条抢占
            runner.start(1f, 0f, durationMs = 5_000)
            runner.start(0f, 1f, durationMs = 40)
            kotlinx.coroutines.delay(400)
            runner.cancel()
        }
        // 被抢占后，音量必须往第二条斜坡的目标（1.0）走，
        // 而不是继续被第一条推向 0 —— 后者表现为"切了歌但还是越听越小"。
        assertTrue(
            sink.last > 0.5f,
            "被抢占后应朝新目标走，实际停在 ${sink.last}",
        )
    }

    @Test
    fun `a preempted ramp does not fire its completion callback`() {
        // ⚠️ 这条是「连跳两首」的绊线：若被抢占的淡出仍回调 onFinished，
        // 而那个回调正是"推进下一首"，用户手动切歌后就会再被自动跳掉一首。
        runBlocking {
            val runner = FadeRunner(CoroutineScope(Dispatchers.Default)) { }
            var finished = false
            runner.start(1f, 0f, durationMs = 5_000, onFinished = { finished = true })
            runner.start(0f, 1f, durationMs = 5_000)
            kotlinx.coroutines.delay(600)
            assertTrue(!finished, "被抢占的斜坡不该回调完成")
            runner.cancel()
        }
    }

    @Test
    fun `cancel stops the ramp without writing a value`() {
        val sink = VolumeSink()
        runBlocking {
            val runner = FadeRunner(CoroutineScope(Dispatchers.Default)) { v -> sink.apply(v) }
            runner.start(1f, 0f, durationMs = 5_000)
            kotlinx.coroutines.delay(120)
            val before = sink.values.size
            runner.cancel()
            kotlinx.coroutines.delay(200)
            assertEquals(before, sink.values.size, "cancel 之后不该再写音量")
        }
    }

    // =======================================================================
    // 4. 持久化契约（FadeSettings）
    // =======================================================================

    private class FakeSettings : SettingsStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }

    @Test
    fun `config round-trips through storage`() {
        val storage = FakeSettings()
        val config = FadeConfig(enabled = true, durationMs = 4200, fadeIn = false, fadeOut = true)
        FadeSettings.write(storage, config)
        assertEquals(config, FadeSettings.read(storage))
    }

    @Test
    fun `defaults round-trip and stay disabled`() {
        val storage = FakeSettings()
        FadeSettings.write(storage, FadeConfig.OFF)
        val read = FadeSettings.read(storage)
        assertEquals(FadeConfig.OFF, read)
        assertTrue(!read.enabled, "出厂默认必须是关闭")
    }

    @Test
    fun `missing keys fall back to per-item defaults`() {
        // 只写一个键：其余各项必须各自回落，而不是整体作废成 OFF
        // （否则用户手改过配置文件就会丢掉全部设置）。
        val storage = FakeSettings()
        storage.putString(FadeSettings.KEY_ENABLED, "true")
        val read = FadeSettings.read(storage)
        assertTrue(read.enabled, "已写的键要生效")
        assertEquals(FadeConfig.DEFAULT_DURATION_MS, read.durationMs, "未写的键回落默认时长")
        assertTrue(read.fadeIn, "未写的子开关回落默认（开）")
        assertTrue(read.fadeOut, "未写的子开关回落默认（开）")
    }

    @Test
    fun `illegal values fall back to defaults instead of throwing`() {
        val storage = FakeSettings()
        storage.putString(FadeSettings.KEY_DURATION_MS, "三千")
        storage.putString(FadeSettings.KEY_FADE_IN, "yes")
        val read = FadeSettings.read(storage)
        assertEquals(FadeConfig.DEFAULT_DURATION_MS, read.durationMs, "非法时长回落默认")
        assertTrue(read.fadeIn, "非法布尔回落默认")
    }

    @Test
    fun `out of range duration is clamped on read`() {
        val storage = FakeSettings()
        storage.putString(FadeSettings.KEY_DURATION_MS, "999999")
        assertEquals(FadeConfig.MAX_DURATION_MS, FadeSettings.read(storage).durationMs)
        storage.putString(FadeSettings.KEY_DURATION_MS, "1")
        assertEquals(FadeConfig.MIN_DURATION_MS, FadeSettings.read(storage).durationMs)
    }

    @Test
    fun `corrupted settings never produce an enabled config with no effect`() {
        // 「开关开着 + 两个子开关都关」是白挂状态：用户以为开了淡入淡出、
        // 实际什么都没发生。存储层读出的组合必须能被设置页的归一化判据识别出来。
        val storage = FakeSettings()
        storage.putString(FadeSettings.KEY_ENABLED, "true")
        storage.putString(FadeSettings.KEY_FADE_IN, "false")
        storage.putString(FadeSettings.KEY_FADE_OUT, "false")
        val read = FadeSettings.read(storage)
        assertTrue(!read.fadeIn && !read.fadeOut, "两个子开关确实都是关的")
        // 控制器侧据此判定为"不淡"（见 PlaybackControllerImpl.startFadeIn/FadeOut
        // 里各自判 cfg.fadeIn / cfg.fadeOut），所以不会白挂 —— 这里钉住这个前提。
    }

    @Test
    fun `duration step divides the range evenly`() {
        // 滑杆的 steps 是靠这个整除关系算出来的；一旦除不尽，
        // 滑杆会有一格落不到 500ms 的整数倍上，读数出现 3.4999998 这类值。
        val span = FadeConfig.MAX_DURATION_MS - FadeConfig.MIN_DURATION_MS
        assertEquals(0, span % FadeConfig.DURATION_STEP_MS, "区间必须能被步进整除")
    }

    @Test
    fun `default duration sits inside the legal range`() {
        assertNotNull(FadeConfig.DEFAULT_DURATION_MS)
        assertTrue(
            FadeConfig.DEFAULT_DURATION_MS in
                FadeConfig.MIN_DURATION_MS..FadeConfig.MAX_DURATION_MS,
        )
    }

    private companion object {
        const val ABS_TOL = 0.001f
    }
}
