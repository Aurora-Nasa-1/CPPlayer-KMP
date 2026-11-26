package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Flick 引擎硬件选项的持久化契约。
 *
 * 保留真实影响解码与输出通路的参数：DSD 输出模式（PCM / DoP / 原生）。
 */
class FlickEngineSettingsTest {

    @AfterTest
    fun resetToggles() {
        FlickEngineToggles.applyDsdMode(FlickEngineSettings.DEFAULT_DSD_MODE)
    }

    @Test
    fun `empty storage falls back to engine defaults`() {
        val storage = FakeSettings()
        assertEquals(FlickEngineSettings.DSD_MODE_AUTO, FlickEngineSettings.dsdMode(storage))
    }

    @Test
    fun `explicit dsd mode is preserved`() {
        val storage = FakeSettings()
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "1")
        assertEquals(FlickEngineSettings.DSD_MODE_DOP, FlickEngineSettings.dsdMode(storage))
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "2")
        assertEquals(FlickEngineSettings.DSD_MODE_NATIVE, FlickEngineSettings.dsdMode(storage))
    }

    @Test
    fun `garbage values fall back instead of leaking into the engine`() {
        val storage = FakeSettings()
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "not-a-number")
        assertEquals(FlickEngineSettings.DSD_MODE_AUTO, FlickEngineSettings.dsdMode(storage))

        // 越界数字夹到合法区间，不抛也不返回非法枚举。
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "99")
        assertEquals(FlickEngineSettings.DSD_MODE_AUTO, FlickEngineSettings.dsdMode(storage))
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "-3")
        assertEquals(FlickEngineSettings.DSD_MODE_PCM, FlickEngineSettings.dsdMode(storage))
    }

    @Test
    fun `applyFrom mirrors storage into runtime toggles`() {
        val storage = FakeSettings()
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "2")

        FlickEngineToggles.applyFrom(storage)

        assertEquals(FlickEngineSettings.DSD_MODE_NATIVE, FlickEngineToggles.dsdMode)
    }

    @Test
    fun `applyDsdMode notifies the applier with a clamped value`() {
        val applied = mutableListOf<Int>()
        FlickEngineToggles.registerDsdApplier { applied += it }

        FlickEngineToggles.applyDsdMode(FlickEngineSettings.DSD_MODE_DOP)
        FlickEngineToggles.applyDsdMode(42)

        assertEquals(listOf(FlickEngineSettings.DSD_MODE_DOP, FlickEngineSettings.DSD_MODE_AUTO), applied)
        assertEquals(FlickEngineSettings.DSD_MODE_AUTO, FlickEngineToggles.dsdMode)
    }

    private class FakeSettings : SettingsStorage {
        val map = mutableMapOf<String, String>()
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
