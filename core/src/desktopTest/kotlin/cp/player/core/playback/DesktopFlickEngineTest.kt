package cp.player.core.playback

import cp.player.core.license.VipLicenseManager
import cp.player.core.util.SettingsStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopFlickEngineTest {

    private class FakeStorage(private val map: MutableMap<String, String> = mutableMapOf()) : SettingsStorage {
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value != null) map[key] = value else map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }

    private lateinit var storage: FakeStorage

    @BeforeTest
    fun setUp() {
        storage = FakeStorage()
        VipLicenseManager.deactivate()
    }

    @AfterTest
    fun tearDown() {
        VipLicenseManager.deactivate()
    }

    @Test
    fun `DesktopEngineManager detects flick player engine availability`() {
        // DesktopHiResEngine should detect DLL in app/native or native
        val available = DesktopEngineManager.isHiResEngineSupported
        println("[Test] DesktopHiResEngine isAvailable: $available")
        assertTrue(available, "rust_lib_flick_player DLL should be present and detected on desktop")
    }

    @Test
    fun `DesktopPlayerEngineType defaults to FLICK_STANDARD when not VIP`() {
        storage.putString(PlaybackSessionSettings.KEY_AUDIO_ENGINE, PlaybackSessionSettings.ENGINE_STANDARD)
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = false)
        assertNotNull(player)
        // In standard mode, supportsExclusiveAudio should be false
        assertFalse(player.supportsExclusiveAudio)
        player.release()
    }

    @Test
    fun `exclusive is never implied by the engine key alone`() {
        // 老用户的历史键位（选过「发烧内核」）：独占开关没写过 ⇒ 必须仍是共享。
        storage.putString(PlaybackSessionSettings.KEY_AUDIO_ENGINE, PlaybackSessionSettings.ENGINE_FLICK_HIRES)
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = true)
        assertNotNull(player)
        assertFalse(
            player.supportsExclusiveAudio,
            "选了 FlickEngine 不等于同意独占声卡 —— 独占必须由 KEY_WASAPI_EXCLUSIVE 显式开启",
        )
        player.release()
    }

    @Test
    fun `VIP with explicit switch enables exclusive`() {
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "true")
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = true)
        assertNotNull(player)
        assertTrue(player.supportsExclusiveAudio)
        player.release()
    }

    @Test
    fun `non-VIP cannot get exclusive even with the switch on`() {
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "true")
        val player = DesktopEngineManager.createPlayer(storage = storage, isVipUser = false)
        assertNotNull(player)
        assertFalse(player.supportsExclusiveAudio, "卡密未激活时开关必须无效（独占是 VIP 特权）")
        player.release()
    }

    @Test
    fun `wasapi exclusive preference defaults to off`() {
        assertFalse(
            PlaybackSessionSettings.wasapiExclusive(storage),
            "缺失键时必须回落为「关」—— 这是「禁止默认独占」的最后一道闸",
        )
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "true")
        assertTrue(PlaybackSessionSettings.wasapiExclusive(storage))
        storage.putString(PlaybackSessionSettings.KEY_WASAPI_EXCLUSIVE, "not-a-bool")
        assertFalse(PlaybackSessionSettings.wasapiExclusive(storage))
    }

    @Test
    fun `DesktopHiResEngine setExclusiveMode sets process env and toggles mode`() {
        val ok1 = DesktopHiResEngine.setExclusiveMode(true)
        assertTrue(ok1)
        assertEquals(true, DesktopHiResEngine.isExclusiveMode)

        val ok2 = DesktopHiResEngine.setExclusiveMode(false)
        assertTrue(ok2)
        assertEquals(false, DesktopHiResEngine.isExclusiveMode)
    }

    @Test
    fun `DesktopHiResPlatformPlayer reports correct audio effect capabilities`() {
        val player = DesktopHiResPlatformPlayer(isExclusive = false)
        val caps = player.audioEffectCapabilities
        assertTrue(caps.supportsEqualizer)
        assertTrue(caps.supportsBalance)
        assertTrue(caps.supportsVirtualizer)
        assertFalse(caps.supportsLeveling)
        player.release()
    }
}
