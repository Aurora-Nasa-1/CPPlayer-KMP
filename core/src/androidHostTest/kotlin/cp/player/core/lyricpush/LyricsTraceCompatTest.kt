package cp.player.core.lyricpush

import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LyricsTraceCompatTest {
    @Test
    fun api28SkipsApi29TraceQueriesAndCounters() {
        assertFalse(LyricsTraceCompat.isEnabled())
        LyricsTraceCompat.beginSection("lyrics-compat-test")
        LyricsTraceCompat.setCounter("lyrics-compat-test", 1L)
        LyricsTraceCompat.endSection()
    }
}
