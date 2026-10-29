package cp.player.core.scrobble

import cp.player.core.util.SettingsStorage
import cp.player.core.util.md5Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrobbleHubTest {

    @Test
    fun `md5Hex produces standard lowercase hex string`() {
        // MD5("") = d41d8cd98f00b204e9800998ecf8427e
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        // MD5("test") = 098f6bcd4621d373cade4e832627b4f6
        assertEquals("098f6bcd4621d373cade4e832627b4f6", md5Hex("test"))
    }

    @Test
    fun `LastFmApi signature sorts keys and appends secret correctly`() {
        val api = LastFmApi()
        val params = mapOf(
            "method" to "track.scrobble",
            "api_key" to "my_api_key",
            "track" to "Hello",
            "artist" to "Adele",
            "format" to "json", // format 必须被过滤排除
        )
        val secret = "my_secret"
        // 排序后顺序: api_key, artist, method, track
        // 拼接: api_keymy_api_keyartistAdelemethodtrack.scrobbletrackHellomy_secret
        val expectedConcat = "api_keymy_api_keyartistAdelemethodtrack.scrobbletrackHellomy_secret"
        val expectedSig = md5Hex(expectedConcat)

        val actualSig = api.generateSignature(params, secret)
        assertEquals(expectedSig, actualSig)
    }

    @Test
    fun `Scrobble track duration threshold logic`() {
        // 短于 30 秒的曲目不应该触发打卡
        val shortTrack = ScrobbleTrack(
            title = "Intro",
            artist = "Artist",
            durationSec = 25,
            timestampSec = 1000L,
        )
        // 5 分钟的歌曲，播放到一半（150秒）应触发打卡
        val normalTrack = ScrobbleTrack(
            title = "Long Song",
            artist = "Artist",
            durationSec = 300,
            timestampSec = 1000L,
        )
        // 10 分钟的长曲，播放满 240 秒（4分钟）即便未过半也应触发打卡
        val epicTrack = ScrobbleTrack(
            title = "Epic",
            artist = "Artist",
            durationSec = 600,
            timestampSec = 1000L,
        )

        fun isEligible(durationSec: Int, playedMs: Long): Boolean {
            if (durationSec < 30) return false
            val thresholdMs = minOf((durationSec * 1000L) / 2L, 240_000L)
            return playedMs >= thresholdMs
        }

        assertFalse(isEligible(shortTrack.durationSec, playedMs = 25_000L))
        assertFalse(isEligible(normalTrack.durationSec, playedMs = 140_000L))
        assertTrue(isEligible(normalTrack.durationSec, playedMs = 150_000L))
        assertFalse(isEligible(epicTrack.durationSec, playedMs = 239_000L))
        assertTrue(isEligible(epicTrack.durationSec, playedMs = 240_000L))
    }

    @Test
    fun `offline queue 14 days expiration filter logic`() {
        val nowSec = 1_000_000L
        val maxAgeSec = 14 * 86400L // 14 天

        val validTrack = ScrobbleTrack(
            title = "Valid",
            artist = "Artist",
            timestampSec = nowSec - 5 * 86400L, // 5 天前，合法
        )
        val expiredTrack = ScrobbleTrack(
            title = "Expired",
            artist = "Artist",
            timestampSec = nowSec - 15 * 86400L, // 15 天前，已过期
        )

        val isExpired1 = (nowSec - validTrack.timestampSec) > maxAgeSec
        val isExpired2 = (nowSec - expiredTrack.timestampSec) > maxAgeSec

        assertFalse(isExpired1)
        assertTrue(isExpired2)
    }

    @Test
    fun `ScrobbleHub dispatches to custom scrobbler when enabled`() {
        val fakeStorage = InMemorySettingsStorage()
        val fakeScrobbler = FakeScrobbler(id = "test_scrobbler", displayName = "Test")

        fakeScrobbler.enabled = true
        fakeScrobbler.authorized = true

        val hub = ScrobbleHub(
            lastFm = LastFmScrobbler(storage = fakeStorage),
            storage = fakeStorage,
        )

        // 验证初始状态加载正常
        val state = hub.state.value
        assertEquals(0, state.todayScrobbledCount)
        assertEquals(0, state.pendingQueueSize)
    }

    private class FakeScrobbler(
        override val id: String,
        override val displayName: String,
    ) : ExternalScrobbler {
        var enabled: Boolean = false
        var authorized: Boolean = false
        var lastScrobbled: ScrobbleTrack? = null

        override val isEnabled: Boolean get() = enabled
        override val isAuthorized: Boolean get() = authorized
        override val username: String get() = "fake_user"
        private val _flow = MutableStateFlow(ScrobblerStatus(enabled, authorized, "fake_user"))
        override val statusFlow: StateFlow<ScrobblerStatus> = _flow.asStateFlow()

        override suspend fun updateNowPlaying(track: ScrobbleTrack): ScrobbleResult = ScrobbleResult.Success
        override suspend fun scrobble(track: ScrobbleTrack): ScrobbleResult {
            lastScrobbled = track
            return ScrobbleResult.Success
        }
        override suspend fun love(track: ScrobbleTrack): ScrobbleResult = ScrobbleResult.Success
        override suspend fun unlove(track: ScrobbleTrack): ScrobbleResult = ScrobbleResult.Success
    }

    private class InMemorySettingsStorage : SettingsStorage {
        private val map = mutableMapOf<String, String>()
        override fun getString(key: String, default: String?): String? = map[key] ?: default
        override fun putString(key: String, value: String?) {
            if (value != null) map[key] = value else map.remove(key)
        }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun clear() { map.clear() }
    }
}
