package cp.player.core.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 边车歌词（同目录同名 .lrc/.ttml/.elrc）的测试。
 *
 * 要点：命中时必须是**本地优先**（拿到就返回），且不能把 `content://` 当成路径去拼。
 */
class SidecarLyricsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File = Files.createTempDirectory("sidecar").toFile().also { tempDirs += it }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun lyricsOf(result: Pair<LyricsState, cp.player.core.model.LyricsInfo>?) =
        (result?.first as? LyricsState.Success)?.lines

    @Test
    fun `picks up an lrc file next to the audio`() {
        val dir = tempDir()
        val audio = File(dir, "Song.flac").apply { writeText("") }
        File(dir, "Song.lrc").writeText("[00:01.00]hello\n[00:02.50]world")

        val lines = lyricsOf(SidecarLyrics.load(audio.absolutePath))
        assertNotNull(lines)
        assertEquals(2, lines.size)
        assertEquals("hello", lines[0].text)
        assertEquals(2500L, lines[1].time)
    }

    @Test
    fun `falls back to ttml when no lrc is present`() {
        val dir = tempDir()
        val audio = File(dir, "Track.mp3").apply { writeText("") }
        File(dir, "Track.ttml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="00:00:01.000" end="00:00:03.000">first line</p>
            </div></body></tt>
            """.trimIndent(),
        )

        val result = SidecarLyrics.load(audio.absolutePath)
        val lines = lyricsOf(result)
        assertNotNull(lines)
        assertEquals("first line", lines[0].text)
        assertEquals("TTML", result!!.second.format)
    }

    @Test
    fun `returns null when no sidecar file exists`() {
        val dir = tempDir()
        val audio = File(dir, "Orphan.flac").apply { writeText("") }
        assertNull(SidecarLyrics.load(audio.absolutePath))
    }

    @Test
    fun `content uri is never treated as a path`() {
        assertNull(SidecarLyrics.load("content://com.android.providers.media.documents/document/1234"))
    }

    @Test
    fun `a dot inside the directory name does not confuse base-name extraction`() {
        val dir = tempDir()
        val nested = File(dir, "Albums.2024").apply { mkdirs() }
        val audio = File(nested, "Song").apply { writeText("") } // 无扩展名
        assertNull(SidecarLyrics.load(audio.absolutePath), "无扩展名时无法推出基名，应返回 null")
    }

    @Test
    fun `empty lyric file yields null rather than an empty result`() {
        val dir = tempDir()
        val audio = File(dir, "Empty.flac").apply { writeText("") }
        File(dir, "Empty.lrc").writeText("   \n  \n")
        assertNull(SidecarLyrics.load(audio.absolutePath))
    }

    @Test
    fun `word level timestamps survive the round trip`() {
        val dir = tempDir()
        val audio = File(dir, "Karaoke.flac").apply { writeText("") }
        // 增强 LRC：逐字时间戳 <mm:ss.xx>
        File(dir, "Karaoke.lrc").writeText("[00:01.00]<00:01.00>你<00:01.50>好<00:02.00>")

        val lines = lyricsOf(SidecarLyrics.load(audio.absolutePath))
        assertNotNull(lines)
        assertTrue(lines[0].words.isNotEmpty(), "逐字时间戳应被解析出来")
    }
}
