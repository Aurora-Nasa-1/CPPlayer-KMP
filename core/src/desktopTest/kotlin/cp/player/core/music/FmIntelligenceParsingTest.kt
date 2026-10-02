package cp.player.core.music

import cp.player.core.BackendResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 私人 FM / 心动模式解析的回归测试。
 *
 * 钉住的核心 bug：**心动模式永远是「歌单暂无歌曲」**。
 * `playmode/intelligence/list` 返回的是 `data[].songInfo` 包装结构，
 * 旧实现直接把包装对象当曲目解析，每个条目都取不到 `name`，
 * 结果整页恒空 —— 与旧项目 `JsonUtils.parseSong`（先解包 `songInfo`）不一致。
 */
class FmIntelligenceParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(raw: String) = json.parseToJsonElement(raw)

    @Test
    fun `心动模式响应解包 songInfo 后得到曲目`() {
        val payload = """
            {
              "code": 200,
              "data": [
                {
                  "alg": "itembased",
                  "activated": true,
                  "songInfo": {
                    "id": 33894312,
                    "name": "晴天",
                    "ar": [{ "id": 6452, "name": "周杰伦" }],
                    "al": { "id": 18918, "name": "叶惠美", "picUrl": "https://example.com/yhm.jpg" },
                    "dt": 269000
                  }
                },
                {
                  "alg": "artistbased",
                  "activated": false,
                  "songInfo": {
                    "id": 186016,
                    "name": "七里香",
                    "ar": [{ "id": 6452, "name": "周杰伦" }],
                    "al": { "id": 19020, "name": "七里香", "picUrl": "https://example.com/qlx.jpg" },
                    "dt": 296000
                  }
                }
              ]
            }
        """.trimIndent()
        val result = MusicSourceFromApi.parseFmSongs(parse(payload))
        val tracks = (result as BackendResult.Success).data
        assertEquals(2, tracks.size)
        assertEquals("33894312", tracks[0].id)
        assertEquals("晴天", tracks[0].name)
        assertEquals("周杰伦", tracks[0].artist)
        assertEquals("186016", tracks[1].id)
    }

    @Test
    fun `私人FM响应的曲目对象直接解析`() {
        val payload = """
            {
              "code": 200,
              "data": [
                {
                  "id": 5264842,
                  "name": "星空下的独白",
                  "ar": [{ "id": 1, "name": "某歌手" }],
                  "al": { "id": 1, "name": "某专辑", "picUrl": "https://example.com/fm.jpg" },
                  "dt": 240000
                }
              ]
            }
        """.trimIndent()
        val result = MusicSourceFromApi.parseFmSongs(parse(payload))
        val tracks = (result as BackendResult.Success).data
        assertEquals(1, tracks.size)
        assertEquals("5264842", tracks[0].id)
        assertEquals("星空下的独白", tracks[0].name)
    }

    @Test
    fun `包装条目里没有 songInfo 时按曲目本身兜底`() {
        // 顶部有 id 但没有 name 的包装对象不应再被当成有效曲目收进列表。
        val payload = """
            {
              "code": 200,
              "data": [
                { "alg": "x", "id": 999, "songInfo": null },
                { "id": 1001, "name": "直接曲目" }
              ]
            }
        """.trimIndent()
        val result = MusicSourceFromApi.parseFmSongs(parse(payload))
        val tracks = (result as BackendResult.Success).data
        assertTrue(tracks.all { it.id != "999" })
        assertEquals(listOf("1001"), tracks.map { it.id })
    }
}
