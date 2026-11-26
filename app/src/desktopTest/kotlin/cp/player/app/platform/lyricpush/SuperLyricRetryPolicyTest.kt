package cp.player.app.platform.lyricpush

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuperLyricRetryPolicyTest {
    // =======================================================================
    // 4. SuperLyric 退避
    // =======================================================================

    @Test
    fun `super lyric retry backs off exponentially and caps at five minutes`() {
        // 曲线：30s → 30s → 60s → 120s → 240s → 300s（封顶）→ 300s …
        assertEquals(30_000L, superLyricRetryDelayMs(0), "非法输入按第一次失败处理")
        assertEquals(30_000L, superLyricRetryDelayMs(1))
        assertEquals(60_000L, superLyricRetryDelayMs(2))
        assertEquals(120_000L, superLyricRetryDelayMs(3))
        assertEquals(240_000L, superLyricRetryDelayMs(4))
        // 封顶：再失败也不超过 5 分钟。否则用户「刚装上 SuperLyric」要等太久才生效。
        assertEquals(SUPER_LYRIC_MAX_RETRY_MS, superLyricRetryDelayMs(5))
        assertEquals(SUPER_LYRIC_MAX_RETRY_MS, superLyricRetryDelayMs(500))
    }

    @Test
    fun `super lyric retry never goes below the initial delay`() {
        (0..40).forEach { failures ->
            assertTrue(
                superLyricRetryDelayMs(failures) >= SUPER_LYRIC_INITIAL_RETRY_MS,
                "第 $failures 次失败的等待时间短于起点 —— 会退化成广播风暴",
            )
        }
    }
}
