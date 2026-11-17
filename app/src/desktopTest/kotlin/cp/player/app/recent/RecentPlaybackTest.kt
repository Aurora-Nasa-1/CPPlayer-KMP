package cp.player.app.recent

import cp.player.core.music.TrackSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentPlaybackTest {

    private fun track(id: String) = TrackSummary(
        id = id,
        name = id.substringAfterLast('/'),
        artist = "artist",
        album = null,
        coverUrl = null,
        durationMs = 180_000,
    )

    @Test
    fun `recent history keeps up to 300 tracks`() {
        assertEquals(300, RECENT_TRACKS_LIMIT)
    }

    @Test
    fun `merge moves an existing track to the front without growing past the limit`() {
        val current = (1..300).map { track("netease://song/$it") }
        val merged = mergeRecentTrack(current, track("netease://song/150"))

        assertEquals(RECENT_TRACKS_LIMIT, merged.size)
        assertEquals("netease://song/150", merged.first().id)
        assertEquals(1, merged.count { it.id == "netease://song/150" })
        assertEquals("netease://song/300", merged.last().id, "原列表末尾的条目应被挤出")
    }

    @Test
    fun `same numeric id from two providers stays as two recent entries`() {
        val netease = track("netease://song/42")
        val qq = track("qq://song/42")

        val merged = mergeRecentTrack(listOf(netease), qq)

        assertEquals(listOf("qq://song/42", "netease://song/42"), merged.map { it.id })
    }

    @Test
    fun `playback plan only queues active source and local tracks`() {
        val tracks = listOf(
            track("netease://song/1"),
            track("qq://song/2"),
            track("local://audio/C:/Music/3.flac"),
            track("netease://song/4"),
        )

        val plan = recentPlaybackPlan(tracks, activeProviderId = "netease", clickedTrackId = "netease://song/1")

        assertEquals(listOf("netease://song/1", "local://audio/C:/Music/3.flac", "netease://song/4"), plan?.tracks?.map { it.id })
        assertEquals(0, plan?.startIndex)
    }

    @Test
    fun `clicking a foreign source track does not build a queue`() {
        val tracks = listOf(track("netease://song/1"), track("qq://song/2"))

        assertNull(recentPlaybackPlan(tracks, activeProviderId = "netease", clickedTrackId = "qq://song/2"))
        assertFalse(isRecentTrackPlayable(tracks[1], "netease"))
        assertTrue(isRecentTrackPlayable(tracks[0], "netease"))
    }

    @Test
    fun `bare legacy ids follow the active provider`() {
        assertEquals("netease", recentTrackProviderId(track("12345"), "netease"))
        assertEquals("netease://song/12345", recentMediaId(track("12345"), "netease"))
    }
}
