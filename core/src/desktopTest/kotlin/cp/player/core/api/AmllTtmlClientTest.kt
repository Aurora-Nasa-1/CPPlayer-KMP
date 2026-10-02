package cp.player.core.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AmllTtmlClient] 的纯本地单元测试：DTO 反序列化（用真实 API 响应片段）、
 * providerId → 平台映射、来源模式键解析。不发网络请求。
 */
class AmllTtmlClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesGetResponse() {
        val raw = """
            {"status":200,"data":{"id":269710089745311,"filename":"1768754400682-250306205-r6IrpmBd.ttml","createdAt":1768754400682,
            "musicNames":["ME!","ME! (feat. Brendon Urie)"],"artistNames":["Brendon Urie","Taylor Swift"],
            "albumNames":["Lover"],"ncmMusicIds":["1361348080"],"qqMusicIds":["0032UZe62rZk9K"],
            "appleMusicIds":["1468058706"],"spotifyIds":["2Rk4JlNc2TPmZe2af99d45"],"isrcs":["USUG11901494"],
            "authorIds":["108002475"],"authorUsernames":["SteamFinder"],
            "lyrics":"<tt><body></body></tt>","format":"ttml"}}
        """.trimIndent()
        val resp = json.decodeFromString<AmllGetResponseDto>(raw)
        assertEquals(200, resp.status)
        val item = resp.data!!
        assertEquals(269710089745311L, item.id)
        assertEquals("1361348080", item.ncmMusicIds.single())
        assertEquals("<tt><body></body></tt>", item.lyrics)
    }

    @Test
    fun decodesSearchResponseWithoutLyrics() {
        val raw = """
            {"status":200,"data":{"items":[{"id":8713122671638320,"filename":"a.ttml","musicNames":["ME!"],
            "artistNames":["Taylor Swift"],"albumNames":["Lover"],"ncmMusicIds":["1361348080"],
            "qqMusicIds":[],"appleMusicIds":[],"spotifyIds":[],"isrcs":[],"authorIds":[],"authorUsernames":[],
            "matchContext":{"snippet":"one of these things"}}],
            "pagination":{"page":1,"pageSize":50,"total":1,"totalPages":1,"hasMore":false}}}
        """.trimIndent()
        val resp = json.decodeFromString<AmllSearchResponseDto>(raw)
        assertEquals(1, resp.data!!.items.size)
        assertNull(resp.data!!.items[0].lyrics)
    }

    @Test
    fun platformMapping() {
        assertEquals(AmllPlatform.NCM, amllPlatformFor("netease"))
        assertEquals(AmllPlatform.QQ, amllPlatformFor("qq"))
        assertEquals(AmllPlatform.APPLE, amllPlatformFor("apple"))
        assertEquals(AmllPlatform.SPOTIFY, amllPlatformFor("spotify"))
        assertNull(amllPlatformFor("local"))
        assertNull(amllPlatformFor(null))
    }

    @Test
    fun sourceModeKeys() {
        assertEquals(LyricsSourceMode.PROVIDER_ONLY, LyricsSourceMode.fromKey("provider_only"))
        assertEquals(LyricsSourceMode.AMLL_FIRST, LyricsSourceMode.fromKey("amll_first"))
        assertEquals(LyricsSourceMode.AMLL_ONLY, LyricsSourceMode.fromKey("amll_only"))
        // 非法 / 旧值回落默认 AMLL 优先
        assertEquals(LyricsSourceMode.AMLL_FIRST, LyricsSourceMode.fromKey(null))
        assertEquals(LyricsSourceMode.AMLL_FIRST, LyricsSourceMode.fromKey("bogus"))
        assertTrue(LyricsSourceMode.entries.all { it.key.isNotBlank() })
    }
}
