package cp.player.kmp.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SeekSettle] 回归测试。
 *
 * 重点锁死「小幅 seek 会被立刻误判为已追上」这个 bug：
 * 旧写法只比 `|引擎位置 - 目标| <= 容差`，当目标离 seek 前的位置不足一个容差时
 * （比如从 10.0s 拖到 10.5s），引擎**还没动**就已经满足条件，
 * 于是乐观值被立刻放弃 → 进度条先回弹到旧位置、下一轮再跳过去。
 */
class SeekSettleTest {

    private fun settled(
        engine: Long,
        target: Long,
        from: Long,
        elapsed: Long = 0L,
        loading: Boolean = false,
    ) = SeekSettle.isSettled(
        enginePositionMs = engine,
        targetMs = target,
        fromPositionMs = from,
        elapsedMs = elapsed,
        engineLoading = loading,
    )

    @Test
    fun `engine has not moved yet so a small forward seek is not settled`() {
        // 从 10.0s 拖到 10.5s，引擎仍在 10.0s：旧写法会误判为已追上。
        assertFalse(
            settled(engine = 10_000L, target = 10_500L, from = 10_000L),
            "引擎还没动，不能判定为已追上",
        )
    }

    @Test
    fun `engine has not moved yet so a small backward seek is not settled`() {
        assertFalse(
            settled(engine = 10_000L, target = 9_500L, from = 10_000L),
            "引擎还没动，不能判定为已追上",
        )
    }

    @Test
    fun `small forward seek settles once the engine arrives`() {
        assertTrue(settled(engine = 10_500L, target = 10_500L, from = 10_000L))
    }

    @Test
    fun `large seek settles once the engine arrives`() {
        assertTrue(settled(engine = 60_000L, target = 60_000L, from = 1_000L))
    }

    @Test
    fun `large seek is not settled while the engine is still mid-flight`() {
        assertFalse(
            settled(engine = 20_000L, target = 60_000L, from = 1_000L),
            "引擎已离开原位但离目标还远，应继续显示乐观值",
        )
    }

    @Test
    fun `keyframe landing within tolerance counts as settled`() {
        // 只能按关键帧定位的流会落点偏差，容差内应接受。
        assertTrue(settled(engine = 60_150L, target = 60_000L, from = 1_000L))
    }

    @Test
    fun `landing outside tolerance is not settled`() {
        assertFalse(settled(engine = 61_000L, target = 60_000L, from = 1_000L))
    }

    @Test
    fun `timeout settles even if the engine never moved`() {
        // 目标就是当前位置：引擎永远不会"离开原位"，只能靠超时收场。
        assertTrue(
            settled(engine = 10_000L, target = 10_000L, from = 10_000L, elapsed = SeekSettle.TIMEOUT_MS + 1),
        )
    }

    @Test
    fun `before timeout an unmoved engine is still not settled`() {
        assertFalse(
            settled(engine = 10_000L, target = 10_000L, from = 10_000L, elapsed = SeekSettle.TIMEOUT_MS - 1),
        )
    }

    // ============ 流媒体装载期的宽限 ============

    @Test
    fun `a buffering engine keeps the optimistic value past the normal timeout`() {
        // 流媒体 seek 到未缓冲区间：引擎要先建连拿首包，位置停在原地。
        // 若按固定 800ms 放弃，进度条会先回弹到旧位置——正是「seek 没生效」的观感。
        assertFalse(
            settled(
                engine = 1_000L,
                target = 90_000L,
                from = 1_000L,
                elapsed = SeekSettle.TIMEOUT_MS + 1,
                loading = true,
            ),
            "引擎仍在装载时，不能按常规超时放弃乐观值",
        )
    }

    @Test
    fun `even a buffering engine gives up at the loading timeout`() {
        // 宽限必须有限：真不可定位的流最终要回落，不能把乐观值永远挂着。
        assertTrue(
            settled(
                engine = 1_000L,
                target = 90_000L,
                from = 1_000L,
                elapsed = SeekSettle.LOADING_TIMEOUT_MS + 1,
                loading = true,
            ),
        )
    }

    @Test
    fun `a loading engine still settles as soon as it arrives`() {
        assertTrue(
            settled(engine = 90_000L, target = 90_000L, from = 1_000L, elapsed = 400L, loading = true),
        )
    }

    @Test
    fun `the default is the normal timeout so existing callers are unaffected`() {
        // 不传 loading 时行为必须与改造前一致（本地文件等非流媒体场景）。
        assertTrue(
            settled(engine = 1_000L, target = 90_000L, from = 1_000L, elapsed = SeekSettle.TIMEOUT_MS + 1),
        )
    }
}
