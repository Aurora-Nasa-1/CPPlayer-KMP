package cp.player.core.insights

import cp.player.core.util.PlatformSupport
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 听歌习惯聚合的**口径契约测试**。
 *
 * 这些数字（时长 / 连续天数 / 分档）会同时被习惯页、将来的跨设备同步与用户的直觉三方校验 ——
 * 算错一次用户立刻能看出来（「我昨天明明听了」）。所以口径必须钉住，
 * 且所有涉及「今天」的用例都把 todayKey 显式传进去，不读系统时钟。
 */
class ListeningStatsTest {

    /** 构造本地时区下的时刻，避免用例依赖运行机器的时区设置。 */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun record(
        id: String,
        mediaId: String,
        startedAt: Long,
        playedMs: Long,
        durationMs: Long = 240_000L,
        artist: String = "某个歌手",
        completed: Boolean = false,
        skipped: Boolean = false,
    ) = PlayRecord(
        id = id,
        mediaId = mediaId,
        name = "曲目 $mediaId",
        artist = artist,
        provider = "test",
        startedAt = startedAt,
        playedMs = playedMs,
        durationMs = durationMs,
        completed = completed,
        skipped = skipped,
    )

    // ============ 日期算术 ============

    @Test
    fun `日期键取本地分量`() {
        assertEquals("2026-10-04", Insights.dateKeyOf(at(2026, 10, 4)))
        assertEquals("2026-01-09", Insights.dateKeyOf(at(2026, 1, 9)))
    }

    @Test
    fun `周几以周一为 0`() {
        // 2026-10-04 是周日 ⇒ 6；2026-10-05 是周一 ⇒ 0。
        assertEquals(6, Insights.weekdayIndexOf("2026-10-04"))
        assertEquals(0, Insights.weekdayIndexOf("2026-10-05"))
        assertEquals(5, Insights.weekdayIndexOf("2026-10-03"))
    }

    @Test
    fun `相隔天数按日历日计算`() {
        val a = Insights.dayNumberOf("2026-10-04")!!
        val b = Insights.dayNumberOf("2026-10-05")!!
        assertEquals(1L, b - a)
        // 跨月、跨年、跨闰日都要连续。
        assertEquals(1L, Insights.dayNumberOf("2026-03-01")!! - Insights.dayNumberOf("2026-02-28")!!)
        assertEquals(1L, Insights.dayNumberOf("2027-01-01")!! - Insights.dayNumberOf("2026-12-31")!!)
        assertEquals(1L, Insights.dayNumberOf("2024-03-01")!! - Insights.dayNumberOf("2024-02-29")!!)
    }

    // ============ 会话判定 ============

    @Test
    fun `播到尾部即算完整`() {
        val (completed, skipped) = Insights.classify(playedMs = 10_000, durationMs = 240_000, reachedTail = true)
        assertTrue(completed)
        assertFalse(skipped)
    }

    @Test
    fun `收听到九成算完整`() {
        val (completed, _) = Insights.classify(playedMs = 216_000, durationMs = 240_000, reachedTail = false)
        assertTrue(completed)
    }

    @Test
    fun `不足三成且未播完算跳过`() {
        val (completed, skipped) = Insights.classify(playedMs = 30_000, durationMs = 240_000, reachedTail = false)
        assertFalse(completed)
        assertTrue(skipped)
    }

    @Test
    fun `时长未知时既不判完整也不判跳过`() {
        // 上游没给时长（durationMs = 0）时**不能**猜：猜「跳过」会让新歌全被打上跳过标记。
        val (completed, skipped) = Insights.classify(playedMs = 5_000, durationMs = 0, reachedTail = false)
        assertFalse(completed)
        assertFalse(skipped)
    }

    // ============ 聚合 ============

    @Test
    fun `按本地日期归日并累计时长`() {
        val records = listOf(
            record("1", "a", at(2026, 10, 4, 9), 60_000),
            record("2", "b", at(2026, 10, 4, 21), 120_000),
            record("3", "c", at(2026, 10, 5, 8), 30_000),
        )
        val daily = Insights.aggregate(records)
        assertEquals(2, daily.size)
        assertEquals("2026-10-04", daily[0].date)
        assertEquals(180_000L, daily[0].playedMs)
        assertEquals(2, daily[0].playCount)
        assertEquals("2026-10-05", daily[1].date)
        assertEquals(30_000L, daily[1].playedMs)
    }

    @Test
    fun `新歌发现按全量历史首次出现计`() {
        val records = listOf(
            record("1", "a", at(2026, 10, 4, 9), 60_000),
            record("2", "a", at(2026, 10, 5, 9), 60_000),
            record("3", "b", at(2026, 10, 5, 10), 60_000),
        )
        val daily = Insights.aggregate(records)
        // a 在 10-04 首次出现；b 在 10-05 首次出现；10-05 再听 a 不算新。
        assertEquals(1, daily.first { it.date == "2026-10-04" }.newTracks)
        assertEquals(1, daily.first { it.date == "2026-10-05" }.newTracks)
    }

    @Test
    fun `跨零点的长播算在开始那天`() {
        // 23:50 开始、听到次日 00:10 ⇒ 全部计入 2026-10-04。
        val records = listOf(record("1", "a", at(2026, 10, 4, 23, 50), 1_200_000))
        val daily = Insights.aggregate(records)
        assertEquals(listOf("2026-10-04"), daily.map { it.date })
    }

    // ============ 连续天数 ============

    @Test
    fun `当前连续与最长连续分开计算`() {
        val daily = listOf(
            Insights.aggregate(listOf(record("1", "a", at(2026, 9, 1), 600_000))), // 孤立的一天
            Insights.aggregate(listOf(record("2", "b", at(2026, 10, 1), 600_000))),
            Insights.aggregate(listOf(record("3", "c", at(2026, 10, 2), 600_000))),
            Insights.aggregate(listOf(record("4", "d", at(2026, 10, 3), 600_000))),
            Insights.aggregate(listOf(record("5", "e", at(2026, 10, 4), 600_000))),
        ).flatten()
        val (current, longest) = Insights.streaks(daily, todayKey = "2026-10-04")
        assertEquals(4, current)
        assertEquals(4, longest)
    }

    @Test
    fun `今天还没听不算断`() {
        val daily = Insights.aggregate(
            listOf(
                record("1", "a", at(2026, 10, 2), 600_000),
                record("2", "b", at(2026, 10, 3), 600_000),
            )
        )
        // 「今天」是 10-04 但还没听 ⇒ 连续仍应从昨天起算为 2，而不是 0。
        val (current, _) = Insights.streaks(daily, todayKey = "2026-10-04")
        assertEquals(2, current)
    }

    @Test
    fun `不足五分钟的一天不算听过`() {
        val daily = Insights.aggregate(
            listOf(
                record("1", "a", at(2026, 10, 3), 4 * 60_000L),
                record("2", "b", at(2026, 10, 4), 600_000L),
            )
        )
        val (current, _) = Insights.streaks(daily, todayKey = "2026-10-04")
        assertEquals(1, current, "10-03 只有 4 分钟，不该把连续接起来")
    }

    // ============ 分档 ============

    @Test
    fun `零值单独成档且不参与分位`() {
        val daily = Insights.aggregate(
            listOf(
                record("1", "a", at(2026, 10, 1), 3_600_000),
            )
        ) + listOf(DailyAgg("2026-09-30", 0, 0, 0, 0))
        val scale = Insights.heatScaleOf(daily, "2026-10-01")
        assertEquals(0, scale.levelOf(0))
        assertTrue(scale.levelOf(3_600_000) >= 1)
    }

    @Test
    fun `最近九十天才参与分位且样本不足时用兜底`() {
        // 样本只有 1 天 ⇒ 走兜底阈值，仍然是单调的。
        val few = Insights.aggregate(listOf(record("1", "a", at(2026, 10, 1), 600_000)))
        assertEquals(HeatScale.FALLBACK, Insights.heatScaleOf(few, "2026-10-01"))

        // 构造 12 天递增样本 ⇒ 分位阈值应随样本走，而不是等于兜底值。
        val many = (0 until 12).map {
            record("r$it", "m$it", at(2026, 9, 1 + it), (it + 1) * 600_000L)
        }
        val scale = Insights.heatScaleOf(Insights.aggregate(many), "2026-09-12")
        assertTrue(scale.thresholds != HeatScale.FALLBACK.thresholds)
        // 单调：时长越大档位不会变小。
        assertTrue(scale.levelOf(12 * 600_000L) >= scale.levelOf(600_000L))
    }

    // ============ 汇总 ============

    @Test
    fun `汇总按收听时长而不是次数排行`() {
        val records = listOf(
            // 短曲被听了 3 次但每次 10 秒；长曲听了 1 次 5 分钟。
            record("1", "short", at(2026, 10, 4, 9), 10_000, artist = "甲"),
            record("2", "short", at(2026, 10, 4, 10), 10_000, artist = "甲"),
            record("3", "short", at(2026, 10, 4, 11), 10_000, artist = "甲"),
            record("4", "long", at(2026, 10, 4, 12), 300_000, artist = "乙"),
        )
        val summary = Insights.summarize(records, todayKey = "2026-10-04")
        assertEquals("乙", summary.topArtists.first().label)
        assertEquals("long", summary.topTracks.first().key)
        assertEquals(330_000L, summary.totalMs)
        assertEquals(330_000L, summary.todayMs)
    }

    @Test
    fun `小时与周几分桶按本地时刻`() {
        val records = listOf(
            record("1", "a", at(2026, 10, 4, 9), 60_000),  // 周日
            record("2", "b", at(2026, 10, 5, 9), 120_000), // 周一
        )
        val summary = Insights.summarize(records, todayKey = "2026-10-05")
        assertEquals(180_000L, summary.hourBuckets[9])
        assertEquals(60_000L, summary.weekdayBuckets[6], "周日对应索引 6")
        assertEquals(120_000L, summary.weekdayBuckets[0], "周一对应索引 0")
    }

    @Test
    fun `空数据集不炸且返回零值`() {
        val summary = Insights.summarize(emptyList(), todayKey = "2026-10-04")
        assertEquals(0L, summary.totalMs)
        assertEquals(0, summary.currentStreak)
        assertEquals(24, summary.hourBuckets.size)
        assertEquals(7, summary.weekdayBuckets.size)
    }

    // ============ 存储 ============

    @Test
    fun `追加后能原样读回且按时间升序`() {
        val dir = Files.createTempDirectory("insights").toFile().absolutePath
        try {
            val store = InsightsStore(dir)
            // 先写晚的、再写早的：读回来必须按 startedAt 升序。
            assertTrue(store.append(record("b", "m2", at(2026, 10, 5), 60_000)))
            assertTrue(store.append(record("a", "m1", at(2026, 10, 4), 60_000)))

            val result = store.loadAll()
            assertEquals(0, result.skippedLines)
            assertEquals(listOf("a", "b"), result.records.map { it.id })
        } finally {
            PlatformSupport.deleteRecursively(dir)
        }
    }

    @Test
    fun `跨月写入分片且读取合并`() {
        val dir = Files.createTempDirectory("insights").toFile().absolutePath
        try {
            val store = InsightsStore(dir)
            store.append(record("sep", "m1", at(2026, 9, 30), 60_000))
            store.append(record("oct", "m2", at(2026, 10, 1), 60_000))

            // ⚠️ 按文件名比对而不是按整条路径：`listChildFiles` 在 Windows 上返回的是
            // `C:\...\plays-2026-09.jsonl`。这条断言曾经用 `substringAfterLast('/')`，
            // 于是暴露出 `InsightsStore` 的分片过滤在 Windows 上**恒不命中**
            // （整条反斜杠路径当然不以 `plays-` 开头）—— 记录读出来永远是空。
            val files = PlatformSupport.listChildFiles(dir)
                .map { it.substringAfterLast('/').substringAfterLast('\\') }
                .sorted()
            assertEquals(listOf("plays-2026-09.jsonl", "plays-2026-10.jsonl"), files)

            assertEquals(2, store.loadAll().records.size)
        } finally {
            PlatformSupport.deleteRecursively(dir)
        }
    }

    @Test
    fun `坏行被跳过但好行照常读出`() {
        val dir = Files.createTempDirectory("insights").toFile().absolutePath
        try {
            val store = InsightsStore(dir)
            store.append(record("good", "m1", at(2026, 10, 4), 60_000))
            // 模拟「写一半被杀」：半截 JSON 落在文件末尾。
            val file = "$dir/plays-2026-10.jsonl"
            assertTrue(PlatformSupport.appendTextFile(file, """{"id":"truncated","mediaI"""))

            val result = store.loadAll()
            assertEquals(1, result.records.size)
            assertEquals("good", result.records.single().id)
            assertEquals(1, result.skippedLines)
        } finally {
            PlatformSupport.deleteRecursively(dir)
        }
    }

    @Test
    fun `清空后读回为空`() {
        val dir = Files.createTempDirectory("insights").toFile().absolutePath
        try {
            val store = InsightsStore(dir)
            store.append(record("a", "m1", at(2026, 10, 4), 60_000))
            assertTrue(store.clear())
            assertEquals(0, store.loadAll().records.size)
        } finally {
            PlatformSupport.deleteRecursively(dir)
        }
    }

    @Test
    fun `目录不存在时读取返回空而不是抛异常`() {
        val missing = "${Files.createTempDirectory("insights").toFile().absolutePath}/not-created"
        assertEquals(0, InsightsStore(missing).loadAll().records.size)
    }

    // ============ 日历墙网格 ============

    @Test
    fun `年视图是 53 列 7 行且包含今天`() {
        val daily = Insights.aggregate(
            listOf(record("1", "a", at(2026, 10, 4), 600_000)),
        )
        val grid = Insights.yearGrid(daily, Insights.heatScaleOf(daily, "2026-10-04"), "2026-10-04")
        assertEquals(7, grid.size, "7 行（周一…周日）")
        assertEquals(53, grid.first().size, "53 列（近一年）")
        assertTrue(grid.any { row -> row.any { it.dateKey == "2026-10-04" } }, "网格必须含今天")
        // 今天所在那一列的行索引必须与「今天是周几」一致。
        assertEquals(6, grid.indexOfFirst { it.any { c -> c.dateKey == "2026-10-04" } })
    }

    @Test
    fun `年视图里未来的格子时长为 0`() {
        // 「今天」取 10-05（周一），则同一周里 10-06..10-11 都还没到来。
        val daily = Insights.aggregate(listOf(record("1", "a", at(2026, 10, 5), 600_000)))
        val grid = Insights.yearGrid(daily, Insights.heatScaleOf(daily, "2026-10-05"), "2026-10-05")
        val future = grid.flatten().filter { it.dateKey > "2026-10-05" }
        assertTrue(future.isNotEmpty(), "应存在未来格子")
        assertTrue(future.all { it.playedMs == 0L }, "未来格子必须是 0，渲染层据此区别于「没听」")
    }

    @Test
    fun `月视图覆盖整月且月外为 null`() {
        val daily = Insights.aggregate(listOf(record("1", "a", at(2026, 10, 4), 600_000)))
        val cells = Insights.monthGrid(daily, Insights.heatScaleOf(daily, "2026-10-04"), 2026, 10).flatten()
        assertEquals(31, cells.count { it != null }, "2026 年 10 月共 31 天")
        assertEquals(1, cells.count { it?.dateKey == "2026-10-01" })
        assertEquals(1, cells.count { it?.dateKey == "2026-10-31" })
        // 10-01 是周四 ⇒ 首行前 3 格（周一/二/三）应为空。
        assertEquals(3, cells.take(3).count { it == null })
    }

    @Test
    fun `月视图按闰年的二月给 29 天`() {
        val daily = emptyList<DailyAgg>()
        val scale = HeatScale.FALLBACK
        assertEquals(29, Insights.monthGrid(daily, scale, 2024, 2).flatten().count { it != null })
        assertEquals(28, Insights.monthGrid(daily, scale, 2026, 2).flatten().count { it != null })
    }

    @Test
    fun `天数与日期键互为逆运算`() {
        listOf("1970-01-01", "2000-02-29", "2026-10-04", "2027-01-01").forEach { key ->
            assertEquals(key, Insights.dateKeyOfDayNumber(Insights.dayNumberOf(key)!!))
        }
    }
}
