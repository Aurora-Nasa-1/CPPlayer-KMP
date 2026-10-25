package cp.player.core.lyrics

import cp.player.core.playback.LyricsParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsParserTest {

    @Test
    fun `Standard v3 - 支持从 data_lyric 解析扁平 LRC`() {
        val json = Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "lyric": "[00:00.00]晴天 - 周杰伦\n[00:29.00]故事的小黄花 从出生那年就飘着\n[00:33.50]童年的荡秋千 随记忆一直晃到现在"
              }
            }
            """
        )
        val lines = LyricsParser.parse(json)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].time)
        assertEquals("晴天 - 周杰伦", lines[0].text)
        assertEquals(29000L, lines[1].time)
        assertEquals("故事的小黄花 从出生那年就飘着", lines[1].text)
        assertEquals(33500L, lines[2].time)
    }

    @Test
    fun `Standard v3 - 支持从 data_ttml 或 XML 文本直接解析逐字歌词`() {
        val ttmlJson = Json.parseToJsonElement(
            """
            {
              "code": 0,
              "data": {
                "ttml": "<tt><body><div><p begin=\"1.000\" end=\"4.000\"><span begin=\"1.000\" end=\"2.500\">测试逐字歌词</span></p></div></body></tt>"
              }
            }
            """
        )
        val lines = LyricsParser.parse(ttmlJson)
        assertEquals(1, lines.size)
        assertEquals(1000L, lines[0].time)
        assertEquals("测试逐字歌词", lines[0].text)
    }

    @Test
    fun `Legacy 与 Standard v3 - 格式识别判定正确`() {
        val provider = ProviderLyricsSource(throwingApi())

        val lrcJson = Json.parseToJsonElement("""{"code":0,"data":{"lyric":"[00:01.00]A"}}""")
        val lrcLines = LyricsParser.parse(lrcJson)
        val lrcInfo = provider.infoOf(lrcJson, lrcLines)
        assertEquals("LRC", lrcInfo.format)

        val ttmlJson = Json.parseToJsonElement("""{"code":0,"data":{"ttml":"<tt><body/></tt>"}}""")
        val ttmlInfo = provider.infoOf(ttmlJson, emptyList())
        assertEquals("TTML", ttmlInfo.format)
    }

    private fun throwingApi(): cp.player.core.api.MusicApiService =
        java.lang.reflect.Proxy.newProxyInstance(
            cp.player.core.api.MusicApiService::class.java.classLoader,
            arrayOf(cp.player.core.api.MusicApiService::class.java),
        ) { _, method, _ ->
            throw UnsupportedOperationException("stub api: ${method.name}")
        } as cp.player.core.api.MusicApiService
}
