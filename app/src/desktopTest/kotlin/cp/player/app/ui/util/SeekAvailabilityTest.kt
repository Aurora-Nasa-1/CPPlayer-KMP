package cp.player.app.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SeekAvailability] 回归测试：钉住「时长未知时进度条静默失效且不解释」这个缺陷。
 *
 * 核心是一条：时长未知时时长标签**必须**是 `--:--` 而不是 `0:00`。
 * `0:00` 读起来像「这首歌是零长度」，而不是「还不知道」——这是最误导用户的地方。
 */
class SeekAvailabilityTest {

    // ============ 1. 时长未知 ⇒ 不可拖 ============

    @Test
    fun `an unknown duration is not seekable`() {
        assertFalse(
            SeekAvailability.isSeekable(0L),
            "时长未知（引擎与元信息都不知道）时必须禁用滑条——" +
                "此时滑条位置无法换算成绝对时间，拖出来必然是错的",
        )
    }

    @Test
    fun `a known duration is seekable`() {
        assertTrue(SeekAvailability.isSeekable(200_000L))
        assertTrue(SeekAvailability.isSeekable(1L), "哪怕只有 1ms 也算已知")
    }

    @Test
    fun `a negative duration is not seekable`() {
        // 引擎异常上报负值时不该当成「可拖」，否则 valueRange 会直接非法。
        assertFalse(SeekAvailability.isSeekable(-1L))
    }

    // ============ 2. 时长未知的标签不能是 0:00（本文件存在的理由） ============

    @Test
    fun `an unknown duration is labelled as unknown rather than zero`() {
        assertEquals(
            "--:--",
            SeekAvailability.durationLabel(0L),
            "时长未知必须显示 `--:--`：显示 `0:00` 会让用户以为这首歌是零长度，" +
                "而不是「还不知道」",
        )
        assertEquals("--:--", SeekAvailability.durationLabel(-1L))
    }

    @Test
    fun `a known duration keeps the normal m ss format`() {
        assertEquals("3:20", SeekAvailability.durationLabel(200_000L))
        assertEquals("1:05", SeekAvailability.durationLabel(65_000L))
    }

    // ============ 3. 当前位置的格式化语义没被改坏 ============

    @Test
    fun `the position formatter still renders zero as zero`() {
        // 位置为 0 时 `0:00` 是**正确**的（播放确实在开头）。
        // 所以只给时长加了「未知」标签，没有去改共用的 formatTimeMs——
        // 改了会把进度条左侧的当前位置一起变成 `--:--`。
        assertEquals("0:00", formatTimeMs(0L))
        assertEquals("0:00", formatTimeMs(-5L))
        assertEquals("3:20", formatTimeMs(200_000L))
    }

    // ============ 4. 无损曲后台落盘期间同样不可拖 ============

    @Test
    fun `a lossless track that is still caching is not seekable`() {
        assertFalse(
            SeekAvailability.isSeekable(200_000L, isLocalizing = true),
            "落盘期间引擎放的是**不可定位的流**（桌面 rodio 定位不了 FLAC over HTTP），" +
                "放行只会让用户拖了个寂寞",
        )
    }

    @Test
    fun `the same track becomes seekable once caching finishes`() {
        assertTrue(
            SeekAvailability.isSeekable(200_000L, isLocalizing = false),
            "落盘完成后必须放行 —— 此时第一次拖动会触发切换到本地副本",
        )
    }

    // ============ 5. 禁用理由只呈现一条（落盘优先） ============

    @Test
    fun `caching outranks the unknown-duration reason`() {
        assertEquals(
            SeekAvailability.LOSSLESS_CACHING_HINT,
            SeekAvailability.disabledReason(0L, isLocalizing = true),
            "两种原因同时成立时只能呈现一条：挂两条互相矛盾的说法比不解释还糟",
        )
        assertEquals(
            SeekAvailability.UNKNOWN_DURATION_HINT,
            SeekAvailability.disabledReason(0L, isLocalizing = false),
        )
    }

    @Test
    fun `a seekable track has no disabled reason`() {
        assertNull(
            SeekAvailability.disabledReason(200_000L, isLocalizing = false),
            "可拖动时不该显示任何禁用理由",
        )
    }
}
