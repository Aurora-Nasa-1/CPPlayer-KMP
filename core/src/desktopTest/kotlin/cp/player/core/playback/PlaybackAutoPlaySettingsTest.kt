package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackAutoPlaySettingsTest {

    private class MapSettings : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }

    @Test
    fun autoPlayDefaults() {
        val storage = MapSettings()
        assertEquals(AutoPlayMode.OFF, PlaybackSessionSettings.autoPlayMode(storage))
        assertEquals(AutoPlayCustomType.PLAYLIST, PlaybackSessionSettings.autoPlayCustomType(storage))
        assertEquals("", PlaybackSessionSettings.autoPlayCustomId(storage))
        assertEquals("", PlaybackSessionSettings.autoPlayCustomTitle(storage))
    }

    @Test
    fun autoPlayModeRoundTrip() {
        val storage = MapSettings()
        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_MODE, AutoPlayMode.CONTINUE.storageKey)
        assertEquals(AutoPlayMode.CONTINUE, PlaybackSessionSettings.autoPlayMode(storage))

        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_MODE, AutoPlayMode.DAILY.storageKey)
        assertEquals(AutoPlayMode.DAILY, PlaybackSessionSettings.autoPlayMode(storage))

        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_MODE, AutoPlayMode.CUSTOM.storageKey)
        assertEquals(AutoPlayMode.CUSTOM, PlaybackSessionSettings.autoPlayMode(storage))

        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_MODE, AutoPlayMode.OFF.storageKey)
        assertEquals(AutoPlayMode.OFF, PlaybackSessionSettings.autoPlayMode(storage))

        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_MODE, "invalid_value")
        assertEquals(AutoPlayMode.OFF, PlaybackSessionSettings.autoPlayMode(storage))
    }

    @Test
    fun autoPlayCustomTargetRoundTrip() {
        val storage = MapSettings()
        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_CUSTOM_TYPE, AutoPlayCustomType.TRACK.storageKey)
        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_CUSTOM_ID, "123456")
        storage.putString(PlaybackSessionSettings.KEY_AUTO_PLAY_CUSTOM_TITLE, "晴天 - 周杰伦")

        assertEquals(AutoPlayCustomType.TRACK, PlaybackSessionSettings.autoPlayCustomType(storage))
        assertEquals("123456", PlaybackSessionSettings.autoPlayCustomId(storage))
        assertEquals("晴天 - 周杰伦", PlaybackSessionSettings.autoPlayCustomTitle(storage))
    }
}
