package cp.player.app.notify

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通知偏好（订阅表 / 游标 / 引导标志）的读写往返与**跨音源隔离**。
 *
 * 隔离这条最要紧：私信按 `(providerId, uid)` 隔离（cookie 是 `cookie_$providerId`），
 * 订阅表不带 providerId 的话，切到另一个音源会把别人的订阅关系读成自己的 ——
 * 症状是「切了音源之后，一打开应用就收到陌生人的提醒」。
 */
class MessageNotifyPrefsTest {

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

    private val prefs = MessageNotifyPrefs(MapSettings())

    @Test
    fun `默认全关`() {
        assertFalse(prefs.isMasterEnabled(), "总开关默认必须是关的 —— 「默认全不推送/不监听」")
        assertFalse(prefs.isGuideDone())
        assertEquals(0, prefs.subscribedCount("cp_api"))
        assertNull(prefs.cursor("cp_api", 1L))
    }

    @Test
    fun `订阅往返与计数`() {
        assertEquals(1, prefs.setSubscribed("cp_api", 11L, true))
        assertEquals(2, prefs.setSubscribed("cp_api", 22L, true))
        assertTrue(prefs.isSubscribed("cp_api", 11L))
        assertEquals(setOf(11L, 22L), prefs.subscribedUids("cp_api"))

        // 重复订阅同一个人不该把计数加两次（Set 语义）。
        assertEquals(2, prefs.setSubscribed("cp_api", 11L, true))

        assertEquals(1, prefs.setSubscribed("cp_api", 11L, false))
        assertFalse(prefs.isSubscribed("cp_api", 11L))
    }

    @Test
    fun `订阅表按音源隔离`() {
        prefs.setSubscribed("cp_api", 11L, true)
        assertTrue(prefs.isSubscribed("cp_api", 11L))
        // 另一个音源上同 uid 不该被当成已订阅。
        assertFalse(prefs.isSubscribed("migu", 11L))
        assertEquals(0, prefs.subscribedCount("migu"))
    }

    @Test
    fun `游标按音源与会话隔离`() {
        prefs.setCursor("cp_api", 11L, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, prefs.cursor("cp_api", 11L))
        assertNull(prefs.cursor("cp_api", 22L))
        assertNull(prefs.cursor("migu", 11L))
    }

    @Test
    fun `取消订阅不清游标`() {
        // 重新订阅同一个人时，历史消息不该被当成新消息炸一遍 —— 所以游标留着。
        prefs.setCursor("cp_api", 11L, 999L)
        prefs.setSubscribed("cp_api", 11L, true)
        prefs.setSubscribed("cp_api", 11L, false)
        assertEquals(999L, prefs.cursor("cp_api", 11L))
    }

    @Test
    fun `引导标志与总开关往返`() {
        prefs.setGuideDone()
        assertTrue(prefs.isGuideDone())
        prefs.setMasterEnabled(true)
        assertTrue(prefs.isMasterEnabled())
        prefs.setMasterEnabled(false)
        assertFalse(prefs.isMasterEnabled())
    }

    @Test
    fun `索引被写坏时按空处理而不是崩`() {
        // 手工把索引键改成非 JSON —— 旧版本 / 用户手改 prefs 文件都可能造成这种脏数据。
        val settings = MapSettings()
        settings.putString("msg_notify_subs_cp_api", "not json at all")
        val dirty = MessageNotifyPrefs(settings)
        assertEquals(emptySet(), dirty.subscribedUids("cp_api"))
    }
}
