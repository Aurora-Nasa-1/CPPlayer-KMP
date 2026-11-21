package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuperLyricPayloadTest {

    private val track = LyricPushTrack(
        id = "song-1",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 3_000L,
    )

    private val lines = listOf(
        SyncedLyricLine(
            time = 1_000L,
            text = "Next line",
            translation = "下一句",
            romanization = "xia yi ju",
        ),
        SyncedLyricLine(
            time = 0L,
            text = "Hello world",
            endTime = 1_000L,
            translation = "你好，世界",
            romanization = "ni hao shi jie",
            words = listOf(
                SyncedLyricLine.SyncedWord("Hello", 0L, 400L),
                SyncedLyricLine.SyncedWord("world", 400L, 900L),
            ),
        ),
    )

    @Test
    fun `payload preserves full song order timing words and translation`() {
        val payload = buildSuperLyricPayload(track, lines, LyricSecondaryMode.TRANSLATION)

        assertEquals("song-1", payload?.id)
        assertEquals("Song", payload?.title)
        assertEquals(3_000L, payload?.durationMs)
        assertEquals(listOf("Hello world", "Next line"), payload?.lines?.map { it.text })
        assertEquals(1_000L, payload?.lines?.first()?.endMs)
        assertEquals("Hello ", payload?.lines?.first()?.words?.first()?.text)
        assertEquals(400L, payload?.lines?.first()?.words?.first()?.endMs)
        assertEquals("你好，世界", payload?.lines?.first()?.translation)
        assertNull(payload?.lines?.first()?.phonetic)
    }

    @Test
    fun `secondary mode maps to translation phonetic or neither`() {
        val translated = buildSuperLyricPayload(track, lines, LyricSecondaryMode.TRANSLATION)
            ?.lines?.first()
        val pronounced = buildSuperLyricPayload(track, lines, LyricSecondaryMode.PRONUNCIATION)
            ?.lines?.first()
        val originalOnly = buildSuperLyricPayload(track, lines, LyricSecondaryMode.OFF)
            ?.lines?.first()

        assertEquals("你好，世界", translated?.translation)
        assertNull(translated?.phonetic)
        assertNull(pronounced?.translation)
        assertEquals("ni hao shi jie", pronounced?.phonetic)
        assertNull(originalOnly?.translation)
        assertNull(originalOnly?.phonetic)
    }

    @Test
    fun `payload rejects missing title or lyrics and normalizes invalid metadata times`() {
        assertNull(buildSuperLyricPayload(track.copy(title = "  "), lines, LyricSecondaryMode.OFF))
        assertNull(buildSuperLyricPayload(track, emptyList(), LyricSecondaryMode.OFF))
        assertNull(
            buildSuperLyricPayload(
                track,
                listOf(SyncedLyricLine(time = 0L, text = " \n ")),
                LyricSecondaryMode.OFF,
            ),
        )

        val normalized = buildSuperLyricPayload(
            track.copy(id = "", title = " Song ", durationMs = -1L),
            listOf(
                SyncedLyricLine(
                    time = -100L,
                    text = "Line",
                    endTime = -50L,
                    words = listOf(SyncedLyricLine.SyncedWord("Line", -80L, -60L)),
                ),
            ),
            LyricSecondaryMode.OFF,
        )

        assertEquals("##Song##Artist##Album", normalized?.id)
        assertEquals(0L, normalized?.durationMs)
        assertEquals(0L, normalized?.lines?.single()?.startMs)
        assertEquals(0L, normalized?.lines?.single()?.endMs)
        assertEquals(0L, normalized?.lines?.single()?.words?.single()?.startMs)
        assertEquals(0L, normalized?.lines?.single()?.words?.single()?.endMs)
    }
}
