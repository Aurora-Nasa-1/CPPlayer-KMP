package cp.player.core.lyrics

import cp.player.core.music.TrackLyrics
import cp.player.core.playback.LyricsParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LyricsParserTest {

    @Test
    fun `ProviderLyricsSource - 格式识别判定正确`() {
        val provider = ProviderLyricsSource()
        val lrcLines = LyricsParser.parseTrackLyrics(TrackLyrics(lrc = "[00:01.00]A"))
        val typedLrcInfo = provider.infoOf(TrackLyrics(lrc = "[00:01.00]A"), lrcLines)
        assertEquals("LRC", typedLrcInfo.format)

        val ttmlInfo = provider.infoOf(TrackLyrics(ttml = "<tt><body/></tt>"), emptyList())
        assertEquals("TTML", ttmlInfo.format)
    }

    @Test
    fun `parseTrackLyrics - 直接解析强类型 TrackLyrics`() {
        val trackLyrics = TrackLyrics(
            lrc = "[00:01.00]Line 1\n[00:02.00]Line 2",
            translation = "[00:01.00]翻译 1\n[00:02.00]翻译 2",
        )
        val lines = LyricsParser.parseTrackLyrics(trackLyrics)
        assertEquals(2, lines.size)
        assertEquals("Line 1", lines[0].text)
        assertEquals("翻译 1", lines[0].translation)
    }

    @Test
    fun `ProviderLyricsSource - 支持从 UnifiedMusicSource 读取`() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val source = java.lang.reflect.Proxy.newProxyInstance(
            cp.player.core.music.UnifiedMusicSource::class.java.classLoader,
            arrayOf(cp.player.core.music.UnifiedMusicSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getLyrics" -> cp.player.core.BackendResult.Success(
                    TrackLyrics(lrc = "[00:01.00]Hello world")
                )
                else -> cp.player.core.BackendResult.Unsupported("unused")
            }
        } as cp.player.core.music.UnifiedMusicSource
        val provider = ProviderLyricsSource(unifiedSource = source)
        val result = provider.fetch(
            LyricsRequest(
                mediaId = cp.player.core.music.CPMediaId("netease", "song", "123"),
                title = "Hello",
                artist = "World",
                album = "Album",
            )
        )
        assertNotNull(result)
        assertEquals(1, result.lines.size)
        assertEquals("Hello world", result.lines[0].text)
    }
}
