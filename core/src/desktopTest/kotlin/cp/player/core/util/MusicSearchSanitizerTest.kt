package cp.player.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicSearchSanitizerTest {

    @Test
    fun `file extensions are stripped`() {
        assertEquals("晴天", MusicSearchSanitizer.cleanTitle("晴天.mp3"))
        assertEquals("晴天", MusicSearchSanitizer.cleanTitle("晴天.flac"))
        assertEquals("晴天", MusicSearchSanitizer.cleanTitle("晴天.wav"))
        assertEquals("晴天", MusicSearchSanitizer.cleanTitle("晴天.m4a"))
    }

    @Test
    fun `track number prefixes are stripped`() {
        assertEquals("七里香", MusicSearchSanitizer.cleanTitle("01. 七里香"))
        assertEquals("七里香", MusicSearchSanitizer.cleanTitle("02 - 七里香"))
        assertEquals("七里香", MusicSearchSanitizer.cleanTitle("3. 七里香"))
        assertEquals("七里香", MusicSearchSanitizer.cleanTitle("04 七里香"))
    }

    @Test
    fun `audio quality and version tags are stripped`() {
        assertEquals("夜曲", MusicSearchSanitizer.cleanTitle("夜曲 [FLAC]"))
        assertEquals("夜曲", MusicSearchSanitizer.cleanTitle("夜曲 (320k)"))
        assertEquals("夜曲", MusicSearchSanitizer.cleanTitle("夜曲 【无损】"))
        assertEquals("夜曲", MusicSearchSanitizer.cleanTitle("夜曲 [Hi-Res 96kHz/24bit]"))
        assertEquals("夜曲", MusicSearchSanitizer.cleanTitle("夜曲 (Live)"))
    }

    @Test
    fun `artist delimiters extract primary artist`() {
        assertEquals("周杰伦", MusicSearchSanitizer.cleanArtist("周杰伦 / 方文山"))
        assertEquals("周杰伦", MusicSearchSanitizer.cleanArtist("周杰伦, 阿信"))
        assertEquals("周杰伦", MusicSearchSanitizer.cleanArtist("周杰伦&费玉清"))
        assertEquals("周杰伦", MusicSearchSanitizer.cleanArtist("周杰伦、潘玮柏"))
        assertEquals("周杰伦", MusicSearchSanitizer.cleanArtist("周杰伦 feat. 阿信"))
    }

    @Test
    fun `sanitize combined test`() {
        val res = MusicSearchSanitizer.sanitize("01. 告白气球 [FLAC].flac", "周杰伦 / 派伟俊")
        assertEquals("告白气球", res.title)
        assertEquals("周杰伦", res.artist)
    }

    @Test
    fun `blank inputs return empty string safely`() {
        val res = MusicSearchSanitizer.sanitize("   ", "   ")
        assertEquals("", res.title)
        assertEquals("", res.artist)
    }
}
