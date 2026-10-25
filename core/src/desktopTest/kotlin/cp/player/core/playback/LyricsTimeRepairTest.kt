package cp.player.core.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LyricsTimeRepair] 的回归测试 —— 钉住「某些 LRC 格式让整首歌都停在前奏」的两条成因。
 *
 * ### 成因 1：`[mm:ss:cc]` 被读成 `h:mm:ss`
 * 歌词库按冒号个数分派：2 个冒号 = 时:分:秒。于是一份「百分秒也用冒号」的 LRC
 * （`[00:12:34]` 本意 12.34 秒）被整体推到 12 分 34 秒 —— 45~61 倍。
 * 4 分钟的歌，歌词从 12 分钟才开始 ⇒ `activeLyricIndex` 恒为 -1、
 * 歌词视图永远停在第一行之前 ⇒ **整首都在前奏里**，一个字都不高亮。
 *
 * ### 成因 2：时间戳逆序
 * 库对逆序会抛 `IllegalArgumentException`，外层 `runCatching` 把整份歌词吞成空 ⇒
 * 上层以为「没有歌词」。
 *
 * 两条都必须**只在有证据时**动手：`[00:12:34]` 语法上两种读法都合法，
 * 真正的 `h:mm:ss` 文件（20 分钟组曲）不能被改坏。
 */
class LyricsTimeRepairTest {

    private fun lrcJson(lyric: String): JsonElement =
        Json.parseToJsonElement("""{"code":0,"data":{"lyric":${JsonPrimitive(lyric)}}}""")

    // ============ 1. 与时长不相容 ⇒ 按百分秒读 ============

    @Test
    fun `a colon-centisecond file is re-read as mm ss cc when h mm ss contradicts the duration`() {
        val json = lrcJson("[00:12:34]第一行\n[00:15:00]第二行\n[00:20:00]第三行")

        // 时长 4 分钟：按 h:mm:ss 读最大到 20 分钟 ⇒ 整首歌词永远落不到歌里。
        val lines = LyricsParser.parse(json, durationMs = 240_000L)

        assertEquals(3, lines.size)
        assertEquals(
            12_340L,
            lines[0].time,
            "`[00:12:34]` 是 12.34 秒（百分秒方言）。读成 12 分 34 秒会让整首歌都停在「前奏」",
        )
        assertEquals(15_000L, lines[1].time)
        assertEquals(20_000L, lines[2].time)
    }

    @Test
    fun `the repair also applies to the translation track`() {
        val json = Json.parseToJsonElement(
            """
            {"code":0,"data":{
              "lyric":"[00:12:34]A\n[00:15:00]B",
              "tlyric":{"lyric":"[00:12:34]甲\n[00:15:00]乙"}
            }}
            """.trimIndent(),
        )

        val lines = LyricsParser.parse(json, durationMs = 240_000L)

        assertEquals(12_340L, lines[0].time)
        assertEquals(
            "甲",
            lines[0].translation,
            "翻译轨与主歌词是同一份文件、同一种方言：只修主歌词会让译文全部对不上（500ms 容差）",
        )
    }

    // ============ 2. 硬证据：秒字段 ≥ 60 ============

    @Test
    fun `a tag whose third group is at least 60 is repaired even without a duration`() {
        val lines = LyricsParser.parseText("[00:12:99]第一行\n[00:20:99]第二行")

        assertEquals(2, lines.size)
        // h:mm:ss 的秒不可能 ≥ 60 ⇒ 只能是百分秒，无需时长即可判定。
        assertEquals(12_990L, lines[0].time)
        assertEquals(20_990L, lines[1].time)
    }

    // ============ 3. AutoParser 不认的「无百分秒」写法 ============

    @Test
    fun `a sidecar file without centiseconds still parses`() {
        // `AutoParser` 的 canParse 要求 `\d{2}\.\d{2,3}`，`[00:12]` 会被判成「不是 LRC」。
        // 边车 .lrc 这么写很常见，退到行级解析器即可救回（这是 parseText 的第二刀）。
        val lines = LyricsParser.parseText("[00:12]第一行\n[00:15]第二行")

        assertEquals(2, lines.size)
        assertEquals(12_000L, lines[0].time)
        assertEquals(15_000L, lines[1].time)
    }

    // ============ 4. 真正的 h:mm:ss 不许被改坏 ============

    @Test
    fun `a genuine h mm ss file stays untouched when it fits inside the track`() {
        val json = lrcJson("[00:12:34]第一行\n[00:19:00]第二行")

        // 20 分钟的组曲：按 h:mm:ss 读恰好落在歌里，说明作者就是这么写的。
        val lines = LyricsParser.parse(json, durationMs = 1_200_000L)

        assertEquals(754_000L, lines[0].time, "12 分 34 秒是合法的，不能因为「看着像方言」就改写")
        assertEquals(1_140_000L, lines[1].time)
    }

    @Test
    fun `without a duration the ambiguous dialect is left alone`() {
        val lines = LyricsParser.parse(lrcJson("[00:12:34]第一行"), durationMs = 0L)

        assertEquals(
            754_000L,
            lines[0].time,
            "两种读法都合法又没有时长可依时，宁可保持原样 —— 猜错会把正常文件改坏",
        )
    }

    @Test
    fun `a standard lrc is not touched at all`() {
        val lines = LyricsParser.parse(lrcJson("[00:12.34]第一行\n[00:15.00]第二行"), durationMs = 240_000L)

        assertEquals(12_340L, lines[0].time)
        assertEquals(15_000L, lines[1].time)
    }

    // ============ 5. BOM 不许吃掉第一行 ============

    @Test
    fun `a byte-order mark does not swallow the first line`() {
        val lines = LyricsParser.parseText("\uFEFF[00:12.34]第一行\n[00:15.00]第二行")

        assertEquals(2, lines.size, "带 BOM 的 .lrc（记事本另存 UTF-8 的默认产物）不能丢第一行")
        assertEquals(12_340L, lines[0].time)
        assertEquals("第一行", lines[0].text)
    }

    // ============ 6. 逆序时间戳不许让整份歌词消失 ============

    @Test
    fun `out-of-order timestamps are recovered instead of dropping the whole lyric`() {
        // 库对逆序会抛 "Failed requirement."，旧实现 runCatching 之后整份歌词变空。
        val lines = LyricsParser.parse(lrcJson("[00:20.00]A\n[00:12.34]B\n[00:30.00]C"))

        assertEquals(3, lines.size, "逆序文件必须按时间戳重排救回来，而不是整份丢弃")
        assertEquals(listOf(12_340L, 20_000L, 30_000L), lines.map { it.time })
        assertEquals(listOf("B", "A", "C"), lines.map { it.text })
    }

    @Test
    fun `an ordered file is parsed exactly as before`() {
        val lines = LyricsParser.parse(lrcJson("[00:12.34]A\n[00:20.00]B"))

        assertEquals(listOf(12_340L, 20_000L), lines.map { it.time })
        assertTrue(lines.all { it.endTime != null }, "末行也要有 endTime（沿用 finalize 的补齐规则）")
    }
}
