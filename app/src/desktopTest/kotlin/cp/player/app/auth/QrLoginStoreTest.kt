package cp.player.app.auth

import cp.player.core.util.SettingsStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 扫码登录现场（[QrLoginStore]）的落盘 / 恢复。
 *
 * 这里只测**纯逻辑**：`QrLoginStore` 的 `SettingsStorage` 参数就是为它留的 ——
 * 生产代码走默认的 `AppModel.settings`，测试塞一个内存实现，因此不必起整个 AppModel。
 */
class QrLoginStoreTest {

    private class MapSettings : SettingsStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() {
            map.clear()
        }
    }

    /** 默认「刚建出来」的现场（`createdAtMs = 0`），配套的读取一律用 `nowMs` 在其 TTL 内。 */
    private fun session(createdAtMs: Long = 0L, image: String? = "IMG") = QrLoginSession(
        key = "unikey-1",
        url = "https://example.com/qr",
        imageBase64 = image,
        createdAtMs = createdAtMs,
    )

    // —— 有效期判据 ——

    @Test
    fun `isFresh accepts the whole ttl window and rejects anything older`() {
        val created = 10_000L
        val ttl = QrLoginStore.QR_SESSION_TTL_MS
        val s = session(createdAtMs = created)

        assertTrue(s.isFresh(created, ttl), "刚建的现场必须可用")
        assertTrue(s.isFresh(created + ttl, ttl), "边界值（正好 5 分钟）仍算可用")
        assertFalse(s.isFresh(created + ttl + 1, ttl), "超出有效期一毫秒就该丢掉")
    }

    @Test
    fun `isFresh rejects a negative age so a clock rollback cannot reopen the window`() {
        val s = session(createdAtMs = 10_000L)
        assertFalse(s.isFresh(9_000L), "时钟回拨（负年龄）不恢复，宁可重新取一张二维码")
    }

    // —— 落盘 / 恢复 ——

    @Test
    fun `a saved session survives a fresh read`() {
        val store = MapSettings()
        QrLoginStore.save("ncm", session(), store)

        val loaded = QrLoginStore.load("ncm", store, nowMs = 1_000L)
        assertEquals("unikey-1", loaded?.key)
        assertEquals("https://example.com/qr", loaded?.url)
        assertEquals("IMG", loaded?.imageBase64)
        // 键带 providerId ⇒ 音源之间不串台（另一音源读不到）。
        assertNull(QrLoginStore.load("migu", store, nowMs = 1_000L))
    }

    @Test
    fun `an expired session is read as absent and is cleaned up`() {
        val store = MapSettings()
        QrLoginStore.save("ncm", session(createdAtMs = 0L), store)

        val loaded = QrLoginStore.load("ncm", store, nowMs = QrLoginStore.QR_SESSION_TTL_MS + 1)
        assertNull(loaded, "过期现场恢复出来必然是 800，就当没有")
        assertFalse(store.contains("qr_login_ncm"), "过期条目要顺手清掉，别每次开机白解析一遍")
    }

    @Test
    fun `a corrupted entry reads as absent instead of throwing`() {
        val store = MapSettings()
        store.putString("qr_login_ncm", "{ 这不是 json")
        assertNull(QrLoginStore.load("ncm", store, nowMs = 1L))
        assertFalse(store.contains("qr_login_ncm"), "脏数据同样清掉")
    }

    @Test
    fun `clear drops the session`() {
        val store = MapSettings()
        QrLoginStore.save("ncm", session(), store)
        QrLoginStore.clear("ncm", store)
        assertNull(QrLoginStore.load("ncm", store, nowMs = 1_000L))
    }

    @Test
    fun `an oversized qr image is dropped but the session is still saved`() {
        val store = MapSettings()
        val huge = "A".repeat(QrLoginSession.MAX_IMAGE_CHARS + 1)
        QrLoginStore.save("ncm", session(image = huge), store)

        val loaded = QrLoginStore.load("ncm", store, nowMs = 1_000L)
        assertEquals("unikey-1", loaded?.key, "图太大只丢图，恢复登录靠的是 key")
        assertNull(loaded?.imageBase64)
    }

    /**
     * 启动提示的判据（`MainScreen` 用它决定要不要提示「接着扫上次那张」）。
     *
     * 「一个进程只提示一次」不在这里测 —— 那是 MainScreen 的进程级标记，与存储无关；
     * 塞进存储层反而会让单测之间互相消耗掉机会（这就是它被拆出来的原因）。
     */
    @Test
    fun `hasPending reflects only a live session`() {
        val store = MapSettings()

        assertFalse(QrLoginStore.hasPending("ncm", store, nowMs = 1_000L), "没有现场不该提示")
        assertFalse(QrLoginStore.hasPending("", store, nowMs = 1_000L), "音源未激活时不该提示")

        QrLoginStore.save("ncm", session(createdAtMs = 1_000L), store)
        assertTrue(QrLoginStore.hasPending("ncm", store, nowMs = 2_000L), "有还能用的现场就该提示")
        // 提示的判据不该把现场吃掉 —— 登录页还要靠它恢复二维码。
        assertTrue(store.contains("qr_login_ncm"), "判断「该不该提示」不能顺手删掉现场")

        assertFalse(
            QrLoginStore.hasPending("ncm", store, nowMs = QrLoginStore.QR_SESSION_TTL_MS + 2_000L),
            "现场过期后不该再提示（load 会把它清掉）",
        )
    }
}
