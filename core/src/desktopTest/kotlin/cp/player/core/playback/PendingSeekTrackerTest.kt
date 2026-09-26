package cp.player.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PendingSeekTracker] 回归测试：钉住「拖动进度条没反应」的三个真实成因。
 *
 * 前两个缺陷原先**同时存在于桌面与安卓**两份逐行同构的内联实现里，
 * 且表现完全一致（拖完先跳到目标、再弹回原处），所以极难归因：
 *
 * 1. **补发判定用「位置有没有动」反推** —— 最致命的一个。
 *    rodio 的 player 没建好时 `seekTo` 是**静默空操作**；而引擎一旦开始播放，
 *    位置就离开了 seek 前的位置。于是「引擎动了」被误读成「seek 生效了」，
 *    **恰好从引擎可用的那一刻起停止补发**，这次 seek 永久丢失。
 * 2. **基线取错值**：`seekTo` 把「seek 前的位置」记成对外显示的**乐观值**。
 *    连续拖动时第二次 seek 的基线变成上一次的目标，「引擎一步没动」永远不成立。
 * 3. **补发预算早于宽限期耗尽**：固定 3 次（≈600ms）vs 4s 宽限期。
 *
 * 时钟是注入的，所以节流与宽限期都能被精确驱动，不依赖真实等待。
 */
class PendingSeekTrackerTest {

    /**
     * 可编程的假引擎。
     *
     * [ready] 为 false 时 [apply] **静默失败**（模拟 rodio player 未创建），
     * 并且此时 [positionMs] 不会前进——这正是真实引擎的行为。
     */
    private class FakeEngine(initialPositionMs: Long = 0L) {
        var positionMs = initialPositionMs
        var ready = true
        val dispatched = mutableListOf<Long>()

        fun apply(targetMs: Long): Boolean {
            dispatched += targetMs
            if (!ready) return false
            positionMs = targetMs
            return true
        }
    }

    private class TestClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun trackerOf(engine: FakeEngine, clock: TestClock): PendingSeekTracker =
        PendingSeekTracker(
            enginePositionMs = { engine.positionMs },
            dispatchSeek = engine::apply,
            clockMs = { clock.now },
        )

    // ============ 1. 就绪的那一刻必须补发（最致命的一个） ============

    @Test
    fun `a seek issued before the engine is ready is re-issued the moment it becomes ready`() {
        val engine = FakeEngine(initialPositionMs = 0L)
        engine.ready = false
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        // 装载窗口内拖动：引擎还没建好，下发是空操作。
        tracker.request(30_000L, engineReady = false)
        repeat(3) {
            clock.advance(200)
            tracker.tick(engine.positionMs, engineReady = false, engineLoading = true)
        }

        // 引擎建好了，并且**开始从头播放** —— 位置离开了 seek 前的位置（0 → 260）。
        // 旧实现用「位置有没有动」判断，于是从这一刻起永远不再补发。
        engine.ready = true
        engine.positionMs = 260L
        clock.advance(200)
        tracker.tick(engine.positionMs, engineReady = true, engineLoading = true)

        assertEquals(
            30_000L,
            engine.positionMs,
            "引擎就绪的那一刻必须补发 seek：旧实现会因为「位置已经从 0 走开了」而停止补发，seek 永久丢失",
        )
    }

    /**
     * 就绪跃迁必须排在落定判定**之前**。
     *
     * 这是端到端测试（[AudioPlayerImplSeekE2ETest]）才暴露出来的第四个缺陷，单测原本漏了它：
     * 真实 rodio 在首包到达的**同一轮**里既把时长变得可知、又把状态从 BUFFERING 转成 PLAYING。
     * 于是这一轮 `engineLoading` 由 true 翻成 false，宽限期从 4s 缩回 800ms，
     * 而 `elapsed` 仍是从**最初那次拖动**起算的 1.8s ⇒ 先做落定判定就会当场判超时放弃，
     * 本该发出的补发连发都发不出去。位置只能停在低位慢慢爬。
     */
    @Test
    fun `the ready transition is dispatched even after the normal timeout has already elapsed`() {
        val engine = FakeEngine(initialPositionMs = 0L)
        engine.ready = false
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        tracker.request(30_000L, engineReady = false)
        // 装载期：状态 BUFFERING，靠 4s 装载宽限挡着，所以 800ms 常规超时还没生效。
        repeat(8) {
            clock.advance(200)
            tracker.tick(engine.positionMs, engineReady = false, engineLoading = true)
        }

        // 首包到达：同一轮里时长可知 + 转为播放（loading 翻成 false），位置从 0 走到 140。
        engine.ready = true
        engine.positionMs = 140L
        clock.advance(200)
        tracker.tick(engine.positionMs, engineReady = true, engineLoading = false)

        assertEquals(
            30_000L,
            engine.positionMs,
            "就绪跃迁必须先于落定判定：否则「距最初拖动」的 elapsed 会在同一轮里判超时，" +
                "把这次 seek 直接丢掉，位置只能停在低位慢慢爬",
        )
    }

    // ============ 2. 基线必须取引擎真实位置 ============

    @Test
    fun `a second seek still reaches the engine because the baseline is the engine position`() {
        val engine = FakeEngine(initialPositionMs = 10_000L)
        engine.ready = false
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        tracker.request(40_000L, engineReady = false)
        clock.advance(200)
        tracker.tick(engine.positionMs, engineReady = false, engineLoading = true)

        // 用户又拖了一次。旧实现会把基线记成上一次的**乐观值** 40_000，
        // 于是「引擎一步没动」的兜底判定永久不成立。
        tracker.request(45_000L, engineReady = false)

        engine.ready = true
        clock.advance(200)
        tracker.tick(engine.positionMs, engineReady = true, engineLoading = true)

        assertEquals(
            45_000L,
            engine.positionMs,
            "第二次 seek 必须真的发到引擎：基线取乐观值会让兜底补发判定永久失效",
        )
    }

    // ============ 3. 补发与宽限期同生命周期 ============

    @Test
    fun `retries keep going for the whole grace period instead of a fixed attempt budget`() {
        val engine = FakeEngine(initialPositionMs = 0L)
        engine.ready = false
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        tracker.request(30_000L, engineReady = false)

        // 2 秒仍未就绪 —— 远超旧的 3 次预算（3 × 200ms ≈ 600ms）。
        repeat(10) {
            clock.advance(200)
            tracker.tick(engine.positionMs, engineReady = false, engineLoading = true)
        }
        assertTrue(
            engine.dispatched.size > 3,
            "宽限期内应持续补发：固定次数预算会先于宽限期耗尽，引擎后来就绪也没人补发",
        )

        // 引擎终于就绪 → 补发生效 → 下一轮落定。
        engine.ready = true
        clock.advance(200)
        tracker.tick(engine.positionMs, engineReady = true, engineLoading = true)
        clock.advance(200)
        assertEquals(
            PendingSeekTracker.Tick.Reached,
            tracker.tick(engine.positionMs, engineReady = true, engineLoading = true),
            "引擎追上目标后必须判定为落定",
        )
    }

    // ============ 4. 落定后不得回跳 ============

    @Test
    fun `does not re-issue once the engine is already moving`() {
        val engine = FakeEngine(initialPositionMs = 0L)
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        tracker.request(30_000L, engineReady = true)
        val dispatchesAtRequest = engine.dispatched.size

        // 引擎已在播放并越过目标（落定判定用容差，可能还没判到）→ 绝不能把它拽回去。
        engine.positionMs = 30_400L
        clock.advance(200)
        tracker.tick(engine.positionMs, engineReady = true, engineLoading = false)

        assertEquals(
            dispatchesAtRequest,
            engine.dispatched.size,
            "引擎已经在动时不得补发，否则会把已落点的进度条重新拽回目标、造成回跳",
        )
        assertEquals(30_400L, engine.positionMs)
    }

    // ============ 5. 落定与放弃 ============

    @Test
    fun `releases the optimistic value once the engine catches up`() {
        val engine = FakeEngine(initialPositionMs = 1_000L)
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        tracker.request(20_000L, engineReady = true)
        assertEquals(20_000L, tracker.displayMs(), "待定期间对外显示目标值")

        assertEquals(
            PendingSeekTracker.Tick.Reached,
            tracker.tick(engine.positionMs, engineReady = true, engineLoading = false),
        )
        assertNull(tracker.displayMs(), "落定后必须交还给引擎真实位置")
    }

    @Test
    fun `gives up after the grace period instead of pinning the optimistic value forever`() {
        val engine = FakeEngine(initialPositionMs = 5_000L)
        engine.ready = false
        val clock = TestClock()
        val tracker = trackerOf(engine, clock)

        tracker.request(90_000L, engineReady = false)

        // 注意：放弃之后 targetMs 已被清空，后续 tick 返回的是 Idle，
        // 所以必须看「过程中出现过 GaveUp」，而不是看最后一次的返回值。
        val ticks = (0 until 30).map {
            clock.advance(200)
            tracker.tick(engine.positionMs, engineReady = false, engineLoading = false)
        }

        assertTrue(
            ticks.any { it is PendingSeekTracker.Tick.GaveUp },
            "引擎始终不接受时必须放弃并回报失败，实际序列=${ticks.distinct()}",
        )
        assertNull(
            tracker.displayMs(),
            "放弃后必须交还引擎真实位置，绝不能把乐观值永久挂着（否则进度条冻结）",
        )
    }
}
