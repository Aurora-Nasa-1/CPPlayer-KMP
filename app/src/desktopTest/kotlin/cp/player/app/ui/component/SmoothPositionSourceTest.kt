package cp.player.app.ui.component

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [SmoothPositionSource] 的**平滑性**回归守卫（与 [LyricFrameRateTest] 互补：
 * 那个守「每帧有没有重绘」，这个守「位置值本身有没有台阶」）。
 *
 * 背景：位置源每 200 ms 收一次权威采样（引擎在轮询时刻测量，经跨线程分发 +
 * 重组在 4~20 ms 后才生效）。旧实现每次采样都**硬重置**外推锚点 —— 新锚点通常
 * 落后于已外推的值几~十几毫秒，被单调不减约束钳住后表现为每 200 ms 一次的
 * **停顿帧**（速度骤降为 0），观感是逐字高亮「一格一格跳」，帧率再高也没用。
 *
 * 修复后新锚点以「误差渐消」方式接入：输出连续，速率波动 < ±10 %。
 * 下面的 [playingOutputSpeedStaysWithinBand] 在旧实现下**必然失败**
 * （停顿帧速度 = 0），这是防止改回去的判据。
 */
class SmoothPositionSourceTest {

    /** 可推进的假单调时钟（纳秒）。 */
    private class FakeClock {
        var nanos: Long = 0L
        fun now(): Long = nanos
        fun advanceMs(ms: Double) {
            nanos += (ms * 1_000_000L).toLong()
        }
    }

    /**
     * 核心回归：播放中逐帧输出的**速度**必须始终落在 1.0 附近的窄带内。
     *
     * 模型：真实位置 P(t) = t（1 ms/ms）；轮询每 200 ms 采样一次，采样值 = 测量时刻
     * 的真实位置；测量值经链路延迟 D ∈ [4, 20] ms（锯齿漂移，覆盖正负误差注入）
     * 后才在 UI 侧生效。逐帧（60 fps）读 [SmoothPositionSource.currentMs]。
     */
    @Test
    fun playingOutputSpeedStaysWithinBand() {
        val clock = FakeClock()
        val src = SmoothPositionSource(clock::now)

        val frameMs = 1000.0 / 60.0
        val sampleIntervalMs = 200.0
        var nextSampleAt = 200.0 // 第 1 次采样（首个采样在下方手动喂入）
        var samples = 0L

        // 首个采样：硬重置起点。
        src.update(0L, isPlaying = true)

        var prev = src.currentMs()
        var minSpeed = Double.MAX_VALUE
        var maxSpeed = -Double.MAX_VALUE
        var t = 0.0
        while (t < 6000.0) {
            clock.advanceMs(frameMs)
            t += frameMs
            if (t >= nextSampleAt) {
                // 链路延迟锯齿：4→8→12→16→20→4…，使误差注入方向周期性翻转。
                val linkDelayMs = 4.0 + 16.0 * (samples % 5) / 4.0
                // 引擎在「生效时刻 - 延迟」时测量，P(t)=t ⇒ 采样值即该时刻的 t。
                val measured = (nextSampleAt - linkDelayMs).toLong()
                src.update(measured, isPlaying = true)
                nextSampleAt += sampleIntervalMs
                samples++
            }
            val cur = src.currentMs()
            val speed = (cur - prev) / frameMs
            // 跳过收敛期（首个采样周期内补偿从 0 爬到稳态）。
            if (t > 1500.0 && samples >= 5) {
                minSpeed = minOf(minSpeed, speed)
                maxSpeed = maxOf(maxSpeed, speed)
            }
            prev = cur
        }

        assertTrue(
            minSpeed >= 0.75,
            "播放中出现停顿/过慢帧：最低速度 $minSpeed（1.0=实时）。" +
                "掉到 0 附近说明锚点又变成硬重置、被单调约束钳成停顿了。",
        )
        assertTrue(
            maxSpeed <= 1.30,
            "播放中出现前跳帧：最高速度 $maxSpeed（1.0=实时）。",
        )
    }

    /** 渐消不允许发散：稳态下输出与真实位置的差必须有界（物理上 ≥ 链路延迟）。 */
    @Test
    fun steadyStateErrorStaysBounded() {
        val clock = FakeClock()
        val src = SmoothPositionSource(clock::now)

        val frameMs = 1000.0 / 60.0
        var nextSampleAt = 200.0
        var samples = 0L
        src.update(0L, isPlaying = true)

        var maxError = 0.0
        var t = 0.0
        while (t < 6000.0) {
            clock.advanceMs(frameMs)
            t += frameMs
            if (t >= nextSampleAt) {
                val linkDelayMs = 4.0 + 16.0 * (samples % 5) / 4.0
                src.update((nextSampleAt - linkDelayMs).toLong(), isPlaying = true)
                nextSampleAt += 200.0
                samples++
            }
            val cur = src.currentMs()
            if (samples >= 5) maxError = maxOf(maxError, abs(cur - t))
        }

        // 稳态误差 = 链路延迟（≤20ms）+ 渐消残差；给到 60ms 已是很宽的界，
        // 若发散（补偿互相追逐、越滚越大）会远远冲破它。
        assertTrue(
            maxError <= 60.0,
            "输出与真实位置的误差发散：最大 $maxError ms（期望 ≤ 60 ms）。",
        )
    }

    /** seek 级大跳（> 250 ms）必须立即跟随，1~2 帧内到位。 */
    @Test
    fun bigForwardJumpFollowsImmediately() {
        val clock = FakeClock()
        val src = SmoothPositionSource(clock::now)

        src.update(1000L, isPlaying = true)
        // 跑 500ms 的外推。
        repeat(30) {
            clock.advanceMs(1000.0 / 60.0)
            src.currentMs()
        }
        src.update(5000L, isPlaying = true)
        clock.advanceMs(1000.0 / 60.0)
        val afterOneFrame = src.currentMs()
        clock.advanceMs(1000.0 / 60.0)
        val afterTwoFrames = src.currentMs()

        assertTrue(
            afterOneFrame >= 4950L || afterTwoFrames >= 4950L,
            "seek 到 5000 后 2 帧内应到位，实测 1 帧后 $afterOneFrame、2 帧后 $afterTwoFrames。",
        )
    }

    /** 向后 seek（换曲 / 回跳）：输出必须跟随下降，不许被单调约束卡在旧值上。 */
    @Test
    fun backwardSeekDropsOutput() {
        val clock = FakeClock()
        val src = SmoothPositionSource(clock::now)

        src.update(5000L, isPlaying = true)
        repeat(10) {
            clock.advanceMs(1000.0 / 60.0)
            src.currentMs()
        }
        src.update(1000L, isPlaying = true)
        clock.advanceMs(1000.0 / 60.0)
        val after = src.currentMs()

        assertTrue(
            after in 1000L..1100L,
            "向后 seek 后输出应落到 1000 附近，实测 $after。",
        )
    }

    /** 暂停把输出钉住；恢复播放时从停住的地方继续，不回退、也无大跳。 */
    @Test
    fun pausePinsAndResumeIsContinuous() {
        val clock = FakeClock()
        val src = SmoothPositionSource(clock::now)

        src.update(2000L, isPlaying = true)
        repeat(30) {
            clock.advanceMs(1000.0 / 60.0)
            src.currentMs()
        }
        val atPause = src.currentMs()

        src.update(atPause, isPlaying = false)
        var pinned = LongArray(10) {
            clock.advanceMs(1000.0 / 60.0)
            src.currentMs()
        }
        assertTrue(
            pinned.all { it == atPause },
            "暂停中输出必须钉住（暂停前 $atPause，暂停后 ${pinned.toList()}）。",
        )

        src.update(atPause, isPlaying = true)
        clock.advanceMs(1000.0 / 60.0)
        val resumed = src.currentMs()
        assertTrue(
            resumed in atPause..(atPause + 35),
            "恢复播放第一帧应从暂停处（$atPause）连续起跑，实测 $resumed。",
        )
    }
}
