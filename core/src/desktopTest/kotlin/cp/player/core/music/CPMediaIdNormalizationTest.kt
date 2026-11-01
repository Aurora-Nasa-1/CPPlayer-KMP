package cp.player.core.music

import cp.player.core.playback.toQueueItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CPMediaIdNormalizationTest {

    @Test
    fun parseOrNull_handlesValidAndInvalidFormats() {
        val validOnline = CPMediaId.parseOrNull("netease://song/12345")
        assertEquals("netease", validOnline?.providerId)
        assertEquals("song", validOnline?.resourceType)
        assertEquals("12345", validOnline?.resourceId)

        val validLocal = CPMediaId.parseOrNull("local://audio//storage/emulated/0/Music/song.mp3")
        assertEquals("local", validLocal?.providerId)
        assertEquals("audio", validLocal?.resourceType)
        assertEquals("/storage/emulated/0/Music/song.mp3", validLocal?.resourceId)

        assertNull(CPMediaId.parseOrNull("12345"))
        assertNull(CPMediaId.parseOrNull("netease/song/12345"))
        assertNull(CPMediaId.parseOrNull(""))
        assertNull(CPMediaId.parseOrNull(null))
    }

    @Test
    fun toRawResourceId_extractsPureIds() {
        assertEquals("12345", CPMediaId.toRawResourceId("netease://song/12345"))
        assertEquals("12345", CPMediaId.toRawResourceId("12345"))
        assertEquals("12345", CPMediaId.toRawResourceId("  12345  "))
        assertEquals("", CPMediaId.toRawResourceId(""))
        assertEquals("", CPMediaId.toRawResourceId(null))
        assertEquals("/path/to/song.mp3", CPMediaId.toRawResourceId("local://audio//path/to/song.mp3"))
    }

    @Test
    fun toCanonicalMediaId_normalizesBareIdsAndPreservesCanonical() {
        assertEquals("netease://song/12345", CPMediaId.toCanonicalMediaId("12345"))
        assertEquals("kugou://song/abc1234", CPMediaId.toCanonicalMediaId("abc1234", providerId = "kugou"))
        assertEquals("netease://song/12345", CPMediaId.toCanonicalMediaId("netease://song/12345"))
        assertEquals("local://audio//path/song.mp3", CPMediaId.toCanonicalMediaId("local://audio//path/song.mp3"))
    }

    @Test
    fun isLocal_checksPrefix() {
        assertTrue(CPMediaId.isLocal("local://audio/test.mp3"))
        assertTrue(CPMediaId.isLocal("local://video/test.mp4"))
        assertFalse(CPMediaId.isLocal("netease://song/123"))
        assertFalse(CPMediaId.isLocal("123"))
        assertFalse(CPMediaId.isLocal(null))
    }

    @Test
    fun trackSummary_canonicalMediaId_and_toQueueItem() {
        val bareSummary = TrackSummary(
            id = "2058263004",
            name = "Test Song",
            artist = "Artist",
            album = "Album",
            coverUrl = null,
            durationMs = 180000L,
        )
        assertEquals("netease://song/2058263004", bareSummary.canonicalMediaId())
        assertEquals("kugou://song/2058263004", bareSummary.canonicalMediaId("kugou"))

        // toQueueItem must preserve canonical CPMediaId instead of bare ID
        val queueItemDefault = bareSummary.toQueueItem()
        assertEquals("netease://song/2058263004", queueItemDefault.mediaId)

        val queueItemExplicit = bareSummary.toQueueItem(canonicalMediaId = "myprovider://song/999")
        assertEquals("myprovider://song/999", queueItemExplicit.mediaId)

        // If TrackSummary already has canonical id
        val canonicalSummary = TrackSummary(
            id = "custom://song/777",
            name = "Test Song 2",
            artist = "Artist",
            album = null,
            coverUrl = null,
            durationMs = 0L,
        )
        assertEquals("custom://song/777", canonicalSummary.canonicalMediaId())
        assertEquals("custom://song/777", canonicalSummary.toQueueItem().mediaId)
    }
}
