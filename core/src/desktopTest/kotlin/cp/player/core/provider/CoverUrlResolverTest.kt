package cp.player.core.provider

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverUrlResolverTest {

    @AfterTest
    fun resetPolicy() {
        HostTrafficPolicy.setInBackgroundTray(false)
        HostTrafficPolicy.setDataSaver(false)
        HostTrafficPolicy.setOfflineMode(false)
        HostTrafficPolicy.setActiveProviderImagePolicy(null, null)
    }

    @Test
    fun `appendNeteaseParam replaces stale param instead of keeping mismatched size`() {
        val fromList = CoverUrlResolver.resizeCoverUrl(
            "https://p1.music.126.net/abc/123.jpg?param=140y140",
            requestedPx = 240,
        )
        val fromDetail = CoverUrlResolver.resizeCoverUrl(
            "https://p1.music.126.net/abc/123.jpg?param=300y300",
            requestedPx = 240,
        )
        assertEquals("https://p1.music.126.net/abc/123.jpg?param=256y256", fromList)
        assertEquals(fromList, fromDetail)
    }

    @Test
    fun `appendNeteaseParam preserves non-param query parameters and fragments`() {
        val resized = CoverUrlResolver.resizeCoverUrl(
            "https://p2.music.126.net/x.jpg?v=2&param=100y100#top",
            requestedPx = 500,
        )
        assertEquals("https://p2.music.126.net/x.jpg?v=2&param=512y512#top", resized)
    }

    @Test
    fun `canonicalCoverKey strips param and signature parameters`() {
        val a = CoverUrlResolver.canonicalCoverKey(
            "https://p1.music.126.net/abc/123.jpg?param=256y256&sign=xyz&ts=999",
        )
        val b = CoverUrlResolver.canonicalCoverKey(
            "https://p1.music.126.net/abc/123.jpg?param=1024y1024&token=other",
        )
        assertEquals("https://p1.music.126.net/abc/123.jpg", a)
        assertEquals(a, b)
    }

    @Test
    fun `resizeCoverUrl respects dataSaver and background tray caps`() {
        HostTrafficPolicy.setDataSaver(true)
        assertEquals(
            "https://p1.music.126.net/abc/123.jpg?param=256y256",
            CoverUrlResolver.resizeCoverUrl("https://p1.music.126.net/abc/123.jpg", 1024),
        )
        HostTrafficPolicy.setInBackgroundTray(true)
        assertEquals(
            "https://p1.music.126.net/abc/123.jpg?param=128y128",
            CoverUrlResolver.resizeCoverUrl("https://p1.music.126.net/abc/123.jpg", 1024),
        )
    }
}
