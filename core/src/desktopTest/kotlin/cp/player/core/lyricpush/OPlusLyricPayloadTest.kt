package cp.player.core.lyricpush

import cp.player.core.playback.SyncedLyricLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ColorOS 锁屏岛载荷（[OPlusLyricPayload]）与发布策略（[OPlusLyricPublishPolicy]）的回归守卫。
 *
 * 这一层的特征是「**错了也编译得过、界面也正常**」：时间戳格式差一位、字段名拼错、
 * 前奏 credit 没滤干净，本机上都看不出异常，只有真机上拿 ColorOS 看锁屏才发现。
 * 因此这里逐字符断言 LRC 与 JSON，而不是只断言「非空」。
 */
class OPlusLyricPayloadTest {

    private val track = LyricPushTrack(
        id = "12345",
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 269_000,
        sourceId = "netease",
    )

    private fun line(
        time: Long,
        text: String,
        endTime: Long? = null,
        translation: String? = null,
        romanization: String? = null,
        words: List<SyncedLyricLine.SyncedWord> = emptyList(),
    ) = SyncedLyricLine(
        time = time,
        text = text,
        endTime = endTime,
        translation = translation,
        romanization = romanization,
        words = words,
    )

    // =======================================================================
    // 1. 系统模式：整首行级 LRC
    // =======================================================================

    @Test
    fun `system payload carries line level lrc with centisecond stamps`() {
        val lyrics = listOf(
            line(0, "故事的小黄花"),
            line(3_500, "从出生那年就飘着"),
            line(62_340, "Re So So Si Do Si La"),
        )
        val json = assertNotNull(
            OPlusLyricPayload.buildSystemPayload(track, lyrics),
            "有歌词行时必须产出载荷",
        )

        // 厘秒精度（两位小数）—— ColorOS 的解析器就是这么读的，毫秒会解析失败。
        assertEquals("[00:00.00]故事的小黄花", lrcOf(json).lines()[0])
        assertEquals("[00:03.50]从出生那年就飘着", lrcOf(json).lines()[1])
        assertEquals("[01:02.34]Re So So Si Do Si La", lrcOf(json).lines()[2])

        assertEquals("晴天", LyricPushJson.stringField(json, "songName"))
        assertEquals("周杰伦", LyricPushJson.stringField(json, "artist"))
        // songId 用「音源:音源内 id」——接收端靠它判断「是不是同一首歌」。
        assertEquals("netease:12345", LyricPushJson.stringField(json, "songId"))
        // 系统模式**不该**出现逐字/翻译字段：出现了就说明模式判断错了。
        assertNull(OPlusLyricPayload.rawLyric(json), "系统模式不得带 rawLyric")
    }

    @Test
    fun `payload is null when nothing usable remains`() {
        assertNull(OPlusLyricPayload.buildSystemPayload(track, emptyList()))
        // 全是空文本的行同样视为「没有歌词」，返回 null 而不是一个空 LRC。
        assertNull(OPlusLyricPayload.buildSystemPayload(track, listOf(line(0, "   "))))
    }

    // =======================================================================
    // 2. 模块模式：逐字 + 翻译
    // =======================================================================

    @Test
    fun `module payload carries word level lrc and translation`() {
        val lyrics = listOf(
            line(
                time = 1_000,
                text = "Hello world",
                endTime = 1_500,
                translation = "你好世界",
                words = listOf(
                    SyncedLyricLine.SyncedWord("Hello", 0, 500),
                    SyncedLyricLine.SyncedWord("world", 500, 1_000),
                ),
            ),
        )
        val json = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))
        val raw = assertNotNull(OPlusLyricPayload.rawLyric(json), "模块模式必须带 rawLyric")

        // 逐字用毫秒（三位小数）；词尾的结束时间戳也要给，否则接收端无法知道最后一个词唱完没有。
        // 「Hello」后面那个空格是 withLineSpacing 补回来的 —— 少了它接收端会显示 "Helloworld"。
        assertEquals("[00:00.000]Hello [00:00.500]world[00:01.500]", raw)
        // 行级 LRC 用的是「行时间戳」（1000ms），厘秒精度。
        assertEquals("[00:01.00]Hello world", lrcOf(json).lines()[0])
        assertTrue(OPlusLyricPayload.hasTranslation(json), "同时给了翻译")
        // 翻译行的时间戳取**第一个词**的起点（整行时间戳常常比第一个词早一点点）。
        assertEquals(
            "[00:00.000]你好世界",
            LyricPushJson.stringField(json, OPlusLyricPayload.TRANSLATION_LYRIC_INFO_KEY),
        )
    }

    @Test
    fun `module payload falls back to line level when words are missing`() {
        val lyrics = listOf(line(2_000, "没有逐字时间轴", endTime = null))
        val json = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))
        val raw = assertNotNull(OPlusLyricPayload.rawLyric(json))
        // 没有逐字时退化成行级（毫秒），而不是给一个空字符串。
        assertEquals("[00:02.000]没有逐字时间轴", raw)
        // 翻译与原文相同（都是 null）时不该多出一个空字段。
        assertNull(LyricPushJson.stringField(json, OPlusLyricPayload.TRANSLATION_LYRIC_INFO_KEY))
    }

    @Test
    fun `translation identical to the original line is dropped`() {
        val lyrics = listOf(line(0, "同じ", translation = "同じ"))
        val json = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))
        assertFalse(OPlusLyricPayload.hasTranslation(json), "翻译与原文相同就是没有翻译")
    }

    // =======================================================================
    // 3. 前奏 credit 过滤
    // =======================================================================

    @Test
    fun `credit lines in the intro are filtered out`() {
        val lyrics = listOf(
            line(500, "作词 : 张三"),
            line(1_200, "作曲 : 李四"),
            line(2_000, "版权所有 未经许可不得翻唱"),
            line(3_000, "晴天 - 周杰伦"),
            line(5_000, "故事的小黄花"),
            line(8_000, "从出生那年就飘着"),
        )
        val json = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))
        val lrc = lrcOf(json)
        assertFalse(lrc.contains("作词"), "作词行必须被滤掉")
        assertFalse(lrc.contains("作曲"), "作曲行必须被滤掉")
        assertFalse(lrc.contains("版权所有"), "版权行必须被滤掉")
        assertFalse(lrc.contains("晴天 - 周杰伦"), "「歌名 - 歌手」形式的标题行必须被滤掉")
        assertTrue(lrc.contains("故事的小黄花"))
    }

    @Test
    fun `credit looking text late in the song is kept`() {
        // 15 秒之后出现的「作词：」是歌词正文（例如对白），不能误删。
        val lyrics = listOf(
            line(30_000, "作词 : 张三"),
            line(34_000, "正文一句"),
        )
        val json = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))
        assertTrue(lrcOf(json).contains("作词 : 张三"), "超出前奏窗口的行必须保留")
    }

    @Test
    fun `all credit lyrics fall back to the original list instead of empty`() {
        // 极端情况：整首只有 credit。返回空会让调用方以为「这首歌没歌词」而清除已发布的，
        // 比多显示几行 credit 更糟。
        val lyrics = listOf(line(100, "作词 : 张三"), line(200, "作曲 : 李四"))
        val json = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))
        assertTrue(lrcOf(json).contains("作词"), "全是 credit 时退回原列表，不能清成空")
    }

    // =======================================================================
    // 4. 模式 / 曲目匹配（决定「要不要重发」）
    // =======================================================================

    @Test
    fun `matchesMode distinguishes system from module payloads`() {
        val lyrics = listOf(line(0, "一句", translation = "a line"))
        val system = assertNotNull(OPlusLyricPayload.buildSystemPayload(track, lyrics))
        val module = assertNotNull(OPlusLyricPayload.buildModulePayload(track, lyrics))

        assertTrue(OPlusLyricPayload.matchesMode(system, OPlusLyricMode.SYSTEM))
        assertFalse(OPlusLyricPayload.matchesMode(system, OPlusLyricMode.MODULE))
        assertTrue(OPlusLyricPayload.matchesMode(module, OPlusLyricMode.MODULE))
        // 空载荷不算匹配任何模式 —— 否则「已经发过了」的判断会让首帧被跳过。
        assertFalse(OPlusLyricPayload.matchesMode(null, OPlusLyricMode.SYSTEM))
    }

    @Test
    fun `matchesSong prefers songId and falls back to title plus artist`() {
        val json = assertNotNull(OPlusLyricPayload.buildSystemPayload(track, listOf(line(0, "x"))))
        assertTrue(OPlusLyricPayload.matchesSong(json, track))
        assertFalse(OPlusLyricPayload.matchesSong(json, track.copy(id = "999")))

        // 只有歌名 + 歌手时（本地曲目没有音源 id）走回退比较。
        val local = track.copy(id = "", sourceId = null)
        val localJson = assertNotNull(OPlusLyricPayload.buildSystemPayload(local, listOf(line(0, "x"))))
        assertTrue(OPlusLyricPayload.matchesSong(localJson, local))
        assertFalse(OPlusLyricPayload.matchesSong(localJson, local.copy(title = "另一首")))
    }

    // =======================================================================
    // 5. JSON 转义（歌词里出现引号 / 换行是最常见的一类破坏）
    // =======================================================================

    @Test
    fun `quotes and newlines in lyrics survive the json round trip`() {
        val nasty = "他说\"你好\"\n然后走了"
        val json = assertNotNull(OPlusLyricPayload.buildSystemPayload(track, listOf(line(0, nasty))))

        // 原始 JSON 里不能出现裸换行 / 裸引号（接收端按 JSON 解析，会直接失败）。
        assertFalse(json.contains("\n"), "JSON 文本里不得有裸换行")
        assertEquals("晴天", LyricPushJson.stringField(json, "songName"))
        // 取回来的 LRC 里，文本部分要与原文一致（换行被归一化成空格）。
        assertTrue(lrcOf(json).contains("他说\"你好\" 然后走了"), "转义必须可逆：${lrcOf(json)}")
    }

    // =======================================================================
    // 6. 发布策略
    // =======================================================================

    @Test
    fun `publish policy skips identical payloads`() {
        assertEquals(
            OPlusLyricPublishAction.None,
            OPlusLyricPublishPolicy.actionFor("a", "b", "a", "b"),
            "内容没变就不该重写 MediaMetadata（每次重写都会重建通知）",
        )
        assertEquals(
            OPlusLyricPublishAction.Write,
            OPlusLyricPublishPolicy.actionFor("a", "b", "a", "b", force = true),
            "force 必须能穿透去重（ColorOS 会用旧快照覆盖，需要补发）",
        )
    }

    @Test
    fun `publish policy clears only when something was published before`() {
        assertEquals(
            OPlusLyricPublishAction.None,
            OPlusLyricPublishPolicy.actionFor(null, null, null, null),
            "从没发过就没必要清",
        )
        assertEquals(
            OPlusLyricPublishAction.Clear,
            OPlusLyricPublishPolicy.actionFor("old", "oldRaw", null, null),
            "发过之后变空必须清除，否则锁屏上留着上一首的歌词",
        )
    }

    @Test
    fun `publish policy treats a blank target as clear`() {
        assertEquals(
            OPlusLyricPublishAction.Clear,
            OPlusLyricPublishPolicy.actionFor("old", null, "   ", null),
        )
    }

    // =======================================================================

    /** 从载荷 JSON 里取出 `lyric` 字段（行级 LRC）。 */
    private fun lrcOf(json: String): String =
        assertNotNull(LyricPushJson.stringField(json, "lyric"), "载荷里必须有 lyric 字段")
}
