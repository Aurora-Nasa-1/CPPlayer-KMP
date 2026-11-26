package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbReconnectStateMachineTest {

    @Test
    fun `resumes when reconnected within 3 seconds of playback disconnect`() {
        var now = 10_000L
        val sm = UsbReconnectStateMachine(clockMs = { now })

        sm.onTrackLoaded()
        sm.onPlayRequested()
        sm.onPlaybackStateChanged(PlatformPlaybackState.Playing)

        now = 12_000L
        assertTrue(sm.onUsbAudioDisconnected(autoResumeEnabled = true))
        sm.onPlaybackStateChanged(PlatformPlaybackState.Paused)

        now = 14_999L
        assertTrue(sm.hasPendingReconnectWindow())
        assertTrue(sm.onUsbAudioConnected(autoResumeEnabled = true))
        // Duplicate attach callback from both AudioDeviceCallback and UsbManager fires at most once.
        assertFalse(sm.onUsbAudioConnected(autoResumeEnabled = true))
    }

    @Test
    fun `does not resume after 3 seconds expire`() {
        var now = 1_000L
        val sm = UsbReconnectStateMachine(clockMs = { now })

        sm.onPlaybackStateChanged(PlatformPlaybackState.Playing)
        now = 2_000L
        assertTrue(sm.onUsbAudioDisconnected(autoResumeEnabled = true))
        sm.onPlaybackStateChanged(PlatformPlaybackState.Paused)

        now = 5_001L
        assertFalse(sm.hasPendingReconnectWindow())
        assertFalse(sm.onUsbAudioConnected(autoResumeEnabled = true))
    }

    @Test
    fun `handles becoming noisy pause arriving right before usb detach broadcast`() {
        var now = 20_000L
        val sm = UsbReconnectStateMachine(clockMs = { now })

        sm.onPlaybackStateChanged(PlatformPlaybackState.Playing)
        now = 21_000L
        sm.onBecomingNoisy()
        sm.onPlaybackStateChanged(PlatformPlaybackState.Paused)

        now = 21_250L
        assertFalse(sm.onUsbAudioDisconnected(autoResumeEnabled = true))
        assertTrue(sm.hasPendingReconnectWindow())

        now = 23_000L
        assertTrue(sm.onUsbAudioConnected(autoResumeEnabled = true))
    }

    @Test
    fun `user pause suppresses auto resume across reconnect`() {
        var now = 5_000L
        val sm = UsbReconnectStateMachine(clockMs = { now })

        sm.onPlaybackStateChanged(PlatformPlaybackState.Playing)
        now = 6_000L
        sm.onUserPauseOrStop(stopped = false)
        sm.onPlaybackStateChanged(PlatformPlaybackState.Paused)

        now = 6_200L
        assertFalse(sm.onUsbAudioDisconnected(autoResumeEnabled = true))
        assertFalse(sm.hasPendingReconnectWindow())

        now = 7_000L
        assertFalse(sm.onUsbAudioConnected(autoResumeEnabled = true))
    }

    @Test
    fun `user pause after disconnect cancels pending reconnect window`() {
        var now = 5_000L
        val sm = UsbReconnectStateMachine(clockMs = { now })

        sm.onPlaybackStateChanged(PlatformPlaybackState.Playing)
        now = 6_000L
        assertTrue(sm.onUsbAudioDisconnected(autoResumeEnabled = true))
        assertTrue(sm.hasPendingReconnectWindow())

        now = 6_500L
        sm.onUserPauseOrStop(stopped = false)
        assertFalse(sm.hasPendingReconnectWindow())

        now = 7_000L
        assertFalse(sm.onUsbAudioConnected(autoResumeEnabled = true))
    }

    @Test
    fun `toggle off still pauses on disconnect but never auto resumes`() {
        var now = 1_000L
        val sm = UsbReconnectStateMachine(clockMs = { now })

        sm.onPlaybackStateChanged(PlatformPlaybackState.Playing)
        now = 2_000L
        assertTrue(sm.onUsbAudioDisconnected(autoResumeEnabled = false))
        assertFalse(sm.hasPendingReconnectWindow())

        now = 2_500L
        assertFalse(sm.onUsbAudioConnected(autoResumeEnabled = false))
    }

    @Test
    fun `android usb settings defaults use shared aaudio and enable 3s reconnect`() {
        val storage = FakeSettings()
        assertFalse(PlaybackSessionSettings.androidUsbExclusive(storage))
        assertTrue(PlaybackSessionSettings.androidUsbAutoResume(storage))

        storage.putString(PlaybackSessionSettings.KEY_ANDROID_USB_EXCLUSIVE, "true")
        storage.putString(PlaybackSessionSettings.KEY_ANDROID_USB_AUTO_RESUME, "false")
        assertTrue(PlaybackSessionSettings.androidUsbExclusive(storage))
        assertFalse(PlaybackSessionSettings.androidUsbAutoResume(storage))

        storage.putString(PlaybackSessionSettings.KEY_ANDROID_USB_EXCLUSIVE, "invalid")
        storage.putString(PlaybackSessionSettings.KEY_ANDROID_USB_AUTO_RESUME, "invalid")
        assertFalse(PlaybackSessionSettings.androidUsbExclusive(storage))
        assertEquals(true, PlaybackSessionSettings.androidUsbAutoResume(storage))
    }

    private class FakeSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() = map.clear()
    }
}
