package cp.player.core.playback

import cp.player.core.util.SettingsStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Flick 引擎逐项特性开关的持久化契约。
 *
 * 开关本身不产生声音，但**判断错默认值**会静默改变播放行为：
 * - 默认关掉直连流 ⇒ 每个网络音源都要先整首落盘，用户以为「软件变慢了」；
 * - 默认关掉无缝队列 ⇒ 切歌有断点，且与升级前的听感不一致；
 * - DSD 模式读到坏值不回落 ⇒ 引擎拿到非法枚举，行为取决于引擎实现的兜底分支。
 *
 * 所以这里钉三件事：默认值、坏值回落、快照与磁盘的一致性。
 */
class FlickEngineSettingsTest {

    @AfterTest
    fun resetToggles() {
        // FlickEngineToggles 是进程级单例：跑完必须复位，否则下一条用例会继承上一条的
        // 写入（真实事故的经典形状：单跑绿、全量红）。
        FlickEngineToggles.directStream = FlickEngineSettings.DEFAULT_DIRECT_STREAM
        FlickEngineToggles.gaplessQueue = FlickEngineSettings.DEFAULT_GAPLESS_QUEUE
        FlickEngineToggles.nativeCrossfade = FlickEngineSettings.DEFAULT_NATIVE_CROSSFADE
        FlickEngineToggles.dynamics = FlickEngineSettings.DEFAULT_DYNAMICS
        FlickEngineToggles.applyDsdMode(FlickEngineSettings.DEFAULT_DSD_MODE)
    }

    @Test
    fun `empty storage falls back to engine defaults`() {
        val storage = FakeSettings()
        assertTrue(FlickEngineSettings.directStream(storage))
        assertTrue(FlickEngineSettings.gaplessQueue(storage))
        assertTrue(FlickEngineSettings.nativeCrossfade(storage))
        assertTrue(FlickEngineSettings.dynamics(storage))
        assertEquals(FlickEngineSettings.DSD_MODE_AUTO, FlickEngineSettings.dsdMode(storage))
    }

    @Test
    fun `explicit false is preserved`() {
        val storage = FakeSettings()
        storage.putString(FlickEngineSettings.KEY_DIRECT_STREAM, "false")
        storage.putString(FlickEngineSettings.KEY_GAPLESS_QUEUE, "false")
        storage.putString(FlickEngineSettings.KEY_NATIVE_CROSSFADE, "false")
        storage.putString(FlickEngineSettings.KEY_DYNAMICS, "false")
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "1")

        assertFalse(FlickEngineSettings.directStream(storage))
        assertFalse(FlickEngineSettings.gaplessQueue(storage))
        assertFalse(FlickEngineSettings.nativeCrossfade(storage))
        assertFalse(FlickEngineSettings.dynamics(storage))
        assertEquals(FlickEngineSettings.DSD_MODE_DOP, FlickEngineSettings.dsdMode(storage))
    }

    @Test
    fun `garbage values fall back instead of leaking into the engine`() {
        val storage = FakeSettings()
        storage.putString(FlickEngineSettings.KEY_DIRECT_STREAM, "yes")
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "not-a-number")
        assertTrue(FlickEngineSettings.directStream(storage))
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
        storage.putString(FlickEngineSettings.KEY_DIRECT_STREAM, "false")
        storage.putString(FlickEngineSettings.KEY_GAPLESS_QUEUE, "true")
        storage.putString(FlickEngineSettings.KEY_NATIVE_CROSSFADE, "false")
        storage.putString(FlickEngineSettings.KEY_DYNAMICS, "true")
        storage.putString(FlickEngineSettings.KEY_DSD_MODE, "2")

        FlickEngineToggles.applyFrom(storage)

        assertFalse(FlickEngineToggles.directStream)
        assertTrue(FlickEngineToggles.gaplessQueue)
        assertFalse(FlickEngineToggles.nativeCrossfade)
        assertTrue(FlickEngineToggles.dynamics)
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
