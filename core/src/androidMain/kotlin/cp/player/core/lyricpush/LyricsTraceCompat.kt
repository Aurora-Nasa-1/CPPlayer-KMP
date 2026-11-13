package cp.player.core.lyricpush

import android.os.Build
import android.os.Trace

/**
 * API-safe targets for the diagnostics calls in accompanist lyrics-ui 2.0.0-rc.2.
 * Its Android artifact calls API-29 Trace methods without guards, while this app supports API 24+.
 */
internal object LyricsTraceCompat {
    @JvmStatic
    fun isEnabled(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()

    @JvmStatic
    fun beginSection(name: String) = Trace.beginSection(name)

    @JvmStatic
    fun endSection() = Trace.endSection()

    /** Trace.setCounter(String, long) was added in API 29; diagnostics are a no-op below that. */
    @JvmStatic
    fun setCounter(name: String, value: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.setCounter(name, value)
        }
    }
}
