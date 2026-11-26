package cp.player.core.playback

/**
 * Pure state machine for Android USB audio disconnect -> 3-second reconnect auto-resume.
 *
 * Works identically across all engines (Media3 ExoPlayer and FlickEngine) and both
 * output modes (AAudio shared and UAC2 USB exclusive).
 *
 * ### Why track both [isPlaying] and [lastActivePlaybackAtMs]
 * On Android, unplugging a USB-C DAC or headset often triggers
 * `ACTION_AUDIO_BECOMING_NOISY` (which pauses the player) 50-300 ms *before*
 * `ACTION_USB_DEVICE_DETACHED` or `AudioDeviceCallback.onAudioDevicesRemoved`
 * arrives. By checking whether playback was active either at disconnect time or
 * right before a hardware disconnect pause (within [noisyGraceMs] and without an
 * explicit user pause), we avoid missing the reconnect window.
 */
internal class UsbReconnectStateMachine(
    private val reconnectWindowMs: Long = DEFAULT_RECONNECT_WINDOW_MS,
    private val noisyGraceMs: Long = DEFAULT_NOISY_GRACE_MS,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    companion object {
        const val DEFAULT_RECONNECT_WINDOW_MS = 3_000L
        const val DEFAULT_NOISY_GRACE_MS = 1_500L
    }

    private var isPlaying = false
    private var hasResumableTrack = false
    private var lastActivePlaybackAtMs = Long.MIN_VALUE
    private var suppressedByUserAction = false
    private var pendingResumeWindowStartMs = Long.MIN_VALUE

    /** Reflects the player's current [PlatformPlaybackState]. */
    @Synchronized
    fun onPlaybackStateChanged(state: PlatformPlaybackState) {
        val now = clockMs()
        when (state) {
            PlatformPlaybackState.Playing -> {
                isPlaying = true
                hasResumableTrack = true
                lastActivePlaybackAtMs = now
                suppressedByUserAction = false
                pendingResumeWindowStartMs = Long.MIN_VALUE
            }
            PlatformPlaybackState.Buffering,
            PlatformPlaybackState.Ready -> {
                hasResumableTrack = true
                if (isPlaying) {
                    lastActivePlaybackAtMs = now
                }
            }
            PlatformPlaybackState.Paused -> {
                if (isPlaying) {
                    lastActivePlaybackAtMs = now
                }
                isPlaying = false
            }
            PlatformPlaybackState.Idle,
            PlatformPlaybackState.Ended,
            is PlatformPlaybackState.Error -> {
                isPlaying = false
                hasResumableTrack = false
                pendingResumeWindowStartMs = Long.MIN_VALUE
            }
        }
    }

    /** Called when `load(...)` starts a new track. */
    @Synchronized
    fun onTrackLoaded() {
        hasResumableTrack = true
        suppressedByUserAction = false
        pendingResumeWindowStartMs = Long.MIN_VALUE
    }

    /** Called when playback is explicitly started/resumed by user or controller. */
    @Synchronized
    fun onPlayRequested() {
        suppressedByUserAction = false
        pendingResumeWindowStartMs = Long.MIN_VALUE
        hasResumableTrack = true
    }

    /** Called when the user explicitly pauses or stops playback; suppresses auto-resume. */
    @Synchronized
    fun onUserPauseOrStop(stopped: Boolean = false) {
        suppressedByUserAction = true
        pendingResumeWindowStartMs = Long.MIN_VALUE
        isPlaying = false
        if (stopped) {
            hasResumableTrack = false
        }
    }

    /**
     * Called when `ACTION_AUDIO_BECOMING_NOISY` triggers a hardware pause.
     * Records the active timestamp without setting [suppressedByUserAction].
     */
    @Synchronized
    fun onBecomingNoisy() {
        if (isPlaying) {
            lastActivePlaybackAtMs = clockMs()
            isPlaying = false
        }
    }

    /**
     * Called when a USB audio output device detaches.
     * Returns `true` if the player should be paused immediately.
     */
    @Synchronized
    fun onUsbAudioDisconnected(autoResumeEnabled: Boolean): Boolean {
        val now = clockMs()
        val wasPlayingNow = isPlaying
        val recentHardwareDisconnect = !suppressedByUserAction &&
            hasResumableTrack &&
            lastActivePlaybackAtMs != Long.MIN_VALUE &&
            (now - lastActivePlaybackAtMs) <= noisyGraceMs

        if (wasPlayingNow) {
            lastActivePlaybackAtMs = now
            isPlaying = false
        }

        if (autoResumeEnabled && (wasPlayingNow || recentHardwareDisconnect)) {
            pendingResumeWindowStartMs = now
        } else {
            pendingResumeWindowStartMs = Long.MIN_VALUE
        }

        return wasPlayingNow
    }

    /**
     * Called when a USB audio output device attaches.
     * Returns `true` exactly once if disconnected within [reconnectWindowMs] and
     * auto-resume should execute once audio routing/USB is ready.
     */
    @Synchronized
    fun onUsbAudioConnected(autoResumeEnabled: Boolean): Boolean {
        val windowStart = pendingResumeWindowStartMs
        pendingResumeWindowStartMs = Long.MIN_VALUE
        if (!autoResumeEnabled || suppressedByUserAction || !hasResumableTrack || isPlaying) {
            return false
        }
        if (windowStart == Long.MIN_VALUE) {
            return false
        }
        val elapsed = clockMs() - windowStart
        return elapsed in 0L..reconnectWindowMs
    }

    @Synchronized
    fun hasPendingReconnectWindow(): Boolean {
        val windowStart = pendingResumeWindowStartMs
        if (windowStart == Long.MIN_VALUE) return false
        val elapsed = clockMs() - windowStart
        if (elapsed !in 0L..reconnectWindowMs) {
            pendingResumeWindowStartMs = Long.MIN_VALUE
            return false
        }
        return true
    }

    @Synchronized
    fun reset() {
        isPlaying = false
        hasResumableTrack = false
        lastActivePlaybackAtMs = Long.MIN_VALUE
        suppressedByUserAction = false
        pendingResumeWindowStartMs = Long.MIN_VALUE
    }
}
