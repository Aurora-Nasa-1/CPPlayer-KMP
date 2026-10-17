package cp.player.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedLyricsTest {

    @Test
    fun `parse standard lrc text`() {
        val lrc = """
            [00:01.00]First line
            [00:03.50]Second line
        """.trimIndent()

        val result = EmbeddedLyricLoader.parseLyricsText(lrc)
        assertNotNull(result)
        val (lines, info) = result
        assertEquals(2, lines.size)
        assertEquals("First line", lines[0].text)
        assertEquals(1000L, lines[0].time)
        assertEquals("Second line", lines[1].text)
        assertEquals(3500L, lines[1].time)
        assertEquals(false, info.hasWordLevel)
    }

    @Test
    fun `parse enhanced karaoke lrc text`() {
        val elrc = """
            [00:01.00]<00:01.00>Hel<00:01.50>lo <00:02.00>world
        """.trimIndent()

        val result = EmbeddedLyricLoader.parseLyricsText(elrc)
        assertNotNull(result)
        val (lines, info) = result
        assertEquals(1, lines.size)
        assertTrue(info.hasWordLevel)
        assertTrue(lines[0].words.isNotEmpty())
    }

    @Test
    fun `parse plain text without timestamps`() {
        val plain = """
            Line 1
            Line 2
            Line 3
        """.trimIndent()

        val result = EmbeddedLyricLoader.parseLyricsText(plain)
        assertNotNull(result)
        val (lines, info) = result
        assertEquals(3, lines.size)
        assertEquals("Line 1", lines[0].text)
        assertEquals("Line 2", lines[1].text)
        assertEquals("Line 3", lines[2].text)
        assertEquals(false, info.hasWordLevel)
    }

    @Test
    fun `blank text returns null`() {
        assertNull(EmbeddedLyricLoader.parseLyricsText("   \n\n  "))
    }
}
