package cp.player.core.insights

import cp.player.core.util.localDateTimeOf
import kotlinx.serialization.Serializable

/**
 * 听歌习惯的领域模型与**纯聚合函数**。
 *
 * ### 为什么这一层必须无副作用
 * 「今天听了多久」「连续了几天」这类数字会被至少三处消费：习惯页（本地聚合）、
 * 未来的同步面（跨设备合并）、以及单测。三处各算一遍必然漂移 —— 而这类漂移
 * 用户一眼就能看出来（同一屏上两个数字对不上）。所以口径全部收敛到这个文件，
 * 且不碰 IO、不读时钟（时间一律由调用方以参数传入，测试才能固定「今天」）。
 *
 * ### 日期运算刻意不用 `kotlinx.datetime`
 * 它在 desktop 运行时会解析到 0.7.x（编译期 0.6.x）⇒ `NoClassDefFoundError`，
 * 包在 `runCatching` 里会静默退化成空值。本文件只用 [localDateTimeOf]（expect/actual，
 * 走 `java.time`）取本地日期分量，再用纯算术推导「周几」与「相隔天数」——
 * 见 [weekdayIndexOf] 与 [dayNumberOf]。
 */

/** 一次收听的原始记录（= 同步协议里 `track.played` 的 payload 形状，向前兼容）。 */
@Serializable
data class PlayRecord(
    val id: String,
    /** 形如 `provider://song/123`。 */
    val mediaId: String,
    val name: String,
    val artist: String,
    val provider: String,
    /** 开始收听的时刻（epoch ms）。**按它归日**，不按结束时刻 —— 跨零点的长播只算开始那天。 */
    val startedAt: Long,
    /** 实际收听的毫秒数（暂停 / 缓冲不计入）。 */
    val playedMs: Long,
    val durationMs: Long,
    /** 是否算「完整听完」（>= 90% 或无跳过地播到尾部）。 */
    val completed: Boolean = false,
    /** 是否算「跳过」（曲目被切走且收听不足 30%）。 */
    val skipped: Boolean = false,
    /** 产生这条记录的设备；本地单机阶段为空串，接入同步后填真实 deviceId。 */
    val deviceId: String = "",
)

/**
 * 某一天的聚合结果。
 *
 * 这是**唯一的持久化主体**（见 `InsightsStore`）—— 原始记录是有界的环形缓冲，
 * 而日聚合是长久的：日历墙、连续天数、周/月汇总全部只依赖它。
 * 单条 ~120 字节，一年 365 条也就 40KB 出头，全量重写毫无压力。
 */
@Serializable
data class DailyAgg(
    /** 本地日期，`yyyy-MM-dd`。 */
    val date: String,
    val playedMs: Long,
    val playCount: Int,
    val uniqueTracks: Int,
    /** 该日首次出现的曲目数（「新歌发现」）。 */
    val newTracks: Int,
)

/** 排行项。按 `playedMs` 排序 —— 不按次数，次数会让 30 秒跳过的小曲刷榜。 */
data class RankItem(
    val key: String,
    val label: String,
    val playedMs: Long,
    val count: Int,
)

/**
 * 日历墙的配色分档边界（3 个阈值 ⇒ 5 档：0 / 1 / 2 / 3 / 4）。
 *
 * ### 为什么是分位数而不是固定阈值
 * 固定阈值（比如 1h/2h/4h）下，每天听 20 分钟的人整片落在最浅档、每天听 6 小时的人
 * 整片落在最深档 —— 日历墙失去分辨力，等于白画。取**最近 90 天非零日**的
 * P25/P50/P75 作为边界，每个用户看到的都是「和自己比」的相对强度。
 */
data class HeatScale(val thresholds: List<Long>) {

    /**
     * 把某天的收听时长映射到 0–4 档。
     *
     * ⚠️ `0` 是**独立的一档**（「这天没听」），不参与分位计算 ——
     * 否则一个「偶尔听」的用户会把自己的「有听但很少」也算进最低档的分母里。
     */
    fun levelOf(playedMs: Long): Int {
        if (playedMs <= 0L) return 0
        var level = 1
        for (t in thresholds) if (playedMs >= t) level++
        return level.coerceAtMost(4)
    }

    companion object {
        /** 数据不足以分位时的兜底：仍然单调，只是分辨率低。 */
        val FALLBACK = HeatScale(listOf(5 * 60_000L, 30 * 60_000L, 90 * 60_000L))
    }
}

/** 习惯页「概览 / 习惯」两个 tab 需要的全部派生指标。 */
data class InsightsSummary(
    val totalMs: Long = 0L,
    val todayMs: Long = 0L,
    val weekMs: Long = 0L,
    val monthMs: Long = 0L,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val playCount: Int = 0,
    val completedCount: Int = 0,
    val skippedCount: Int = 0,
    val uniqueTrackCount: Int = 0,
    val newTrackCount: Int = 0,
    /** 24 个桶，索引 = 本地小时。 */
    val hourBuckets: List<Long> = List(HOURS) { 0L },
    /** 7 个桶，**索引 0 = 周一**（中文习惯，与日历墙首列一致）。 */
    val weekdayBuckets: List<Long> = List(DAYS_PER_WEEK) { 0L },
    val topArtists: List<RankItem> = emptyList(),
    val topTracks: List<RankItem> = emptyList(),
)

const val HOURS = 24
const val DAYS_PER_WEEK = 7

/** 排行的默认条数。 */
const val DEFAULT_TOP_N = 5

/** 「有听」的判定门槛：低于 5 分钟不算这一天听过（避免一首歌没播完就切走也算打卡）。 */
const val STREAK_MIN_MS = 5 * 60_000L

/** 跳过判定阈值：收听不足曲长的 30%。 */
const val SKIP_RATIO = 0.3

/** 完整收听的判定阈值：收听达到曲长的 90%。 */
const val COMPLETE_RATIO = 0.9

object Insights {

    // ============ 日期算术（纯函数，不依赖任何日期库） ============

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** epoch 毫秒 → 本地日期键 `yyyy-MM-dd`。 */
    fun dateKeyOf(epochMillis: Long): String {
        val p = localDateTimeOf(epochMillis)
        return "${p.year}-${pad2(p.month)}-${pad2(p.day)}"
    }

    /** epoch 毫秒 → 本地小时（0–23）。 */
    fun hourOf(epochMillis: Long): Int = localDateTimeOf(epochMillis).hour

    /**
     * 日期键 → 距 1970-01-01 的天数（Howard Hinnant 的 `days_from_civil`）。
     *
     * 用它算「相隔几天」比解析成时间戳再除 86400000 更安全：
     * 后者在夏令时切换日会因 23/25 小时而错一天。
     */
    fun dayNumberOf(dateKey: String): Long? {
        val p = parseDateKey(dateKey) ?: return null
        return dayNumberOf(p.year, p.month, p.day)
    }

    fun dayNumberOf(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /**
     * 日期键 → 周几，**0 = 周一**。
     *
     * 用 Sakamoto 算法从年月日直接推出，不需要时区信息、也不需要日期库。
     */
    fun weekdayIndexOf(dateKey: String): Int {
        val p = parseDateKey(dateKey) ?: return 0
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (p.month < 3) p.year - 1 else p.year
        // Sakamoto 给出 0 = 周日；转成 0 = 周一。
        val sundayBased = (y + y / 4 - y / 100 + y / 400 + t[p.month - 1] + p.day) % 7
        return (sundayBased + 6) % 7
    }

    private data class Ymd(val year: Int, val month: Int, val day: Int)

    /**
     * 天数 → 日期键（[dayNumberOf] 的逆运算，同样是 Howard Hinnant 的 `civil_from_days`）。
     *
     * 日历墙要「按格子反推日期」——从「今天所在的周一」往前铺 52 周，
     * 每一个格子都要变成 `yyyy-MM-dd` 才能去查当天的时长。没有这个反查就只能
     * 用 `epochMillis` 反复加减再取本地分量，跨月跨年时极易错一天。
     */
    fun dateKeyOfDayNumber(days: Long): String {
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return "${year}-${pad2(m.toInt())}-${pad2(d.toInt())}"
    }

    /** 由年月日拼日期键。月/日会做合法性钳制，调用方不必先校验。 */
    fun dateKey(year: Int, month: Int, day: Int): String =
        "${year}-${pad2(month.coerceIn(1, 12))}-${pad2(day.coerceIn(1, 31))}"

    /** 某年某月的天数。 */
    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 30
    }

    fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun parseDateKey(dateKey: String): Ymd? {
        val parts = dateKey.split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return Ymd(y, m, d)
    }

    // ============ 聚合 ============

    /**
     * 原始记录 → 按日聚合（按日期键升序）。
     *
     * `newTracks` 的判据是「该 `mediaId` 在**整个传入集合**中首次出现」——
     * 所以传入的必须是全量历史，截断过的子集会把它算错。
     */
    fun aggregate(records: List<PlayRecord>): List<DailyAgg> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedBy { it.startedAt }
        val firstSeen = HashMap<String, String>(sorted.size)
        val byDate = LinkedHashMap<String, MutableDay>()

        for (r in sorted) {
            val date = dateKeyOf(r.startedAt)
            val day = byDate.getOrPut(date) { MutableDay() }
            day.playedMs += r.playedMs
            day.playCount += 1
            day.tracks.add(r.mediaId)
            // putIfAbsent 返回 null ⇒ 这是该曲目在全量历史中的首次出现（按 startedAt 升序遍历）。
            if (firstSeen.putIfAbsent(r.mediaId, date) == null) {
                day.newTracks += 1
            }
        }
        return byDate.entries
            .sortedBy { it.key }
            .map { (date, d) ->
                DailyAgg(
                    date = date,
                    playedMs = d.playedMs,
                    playCount = d.playCount,
                    uniqueTracks = d.tracks.size,
                    newTracks = d.newTracks,
                )
            }
    }

    private class MutableDay {
        var playedMs: Long = 0
        var playCount: Int = 0
        var newTracks: Int = 0
        val tracks = HashSet<String>()
    }

    /**
     * 由**日聚合**推算连续天数。
     *
     * 连续的定义：当天与随后每天都 `playedMs >= [STREAK_MIN_MS]`。
     * 当前连续从 [todayKey] 往前数；今天还没听不算断（以昨天为起点），
     * 这样「早上打开应用」不会看到连续数从 30 掉到 0。
     */
    fun streaks(daily: List<DailyAgg>, todayKey: String): Pair<Int, Int> {
        val hitDays = daily.filter { it.playedMs >= STREAK_MIN_MS }
            .mapNotNull { dayNumberOf(it.date) }
            .toSortedSet()
        if (hitDays.isEmpty()) return 0 to 0

        val today = dayNumberOf(todayKey) ?: return 0 to 0

        var longest = 0
        var run = 0
        var prev: Long? = null
        for (d in hitDays) {
            run = if (prev != null && d == prev + 1) run + 1 else 1
            if (run > longest) longest = run
            prev = d
        }

        // 当前连续：从今天或昨天起锚，往前逐日检查。
        val anchor = if (today in hitDays) today else today - 1
        var current = 0
        var cursor = anchor
        while (cursor in hitDays) {
            current++
            cursor--
        }
        return current to longest
    }

    /**
     * 计算日历墙的分档边界。
     *
     * @param windowDays 只取最近这些天的非零样本；太长会让「最近变多了/变少了」反映不出来。
     */
    fun heatScaleOf(daily: List<DailyAgg>, todayKey: String, windowDays: Int = 90): HeatScale {
        val today = dayNumberOf(todayKey) ?: return HeatScale.FALLBACK
        val samples = daily.asSequence()
            .filter { it.playedMs > 0L }
            .filter { (dayNumberOf(it.date) ?: return@filter false) >= today - windowDays }
            .map { it.playedMs }
            .sorted()
            .toList()
        // 样本太少时任何分位都是噪声，直接用兜底阈值。
        if (samples.size < 8) return HeatScale.FALLBACK
        fun quantile(q: Double): Long {
            val idx = ((samples.size - 1) * q).toInt().coerceIn(0, samples.size - 1)
            return samples[idx]
        }
        val t1 = quantile(0.25).coerceAtLeast(1L)
        val t2 = quantile(0.50).coerceAtLeast(t1)
        val t3 = quantile(0.75).coerceAtLeast(t2 + 1)
        return HeatScale(listOf(t1, t2, t3))
    }

    /** 由原始记录一次性算出全部派生指标。 */
    fun summarize(
        records: List<PlayRecord>,
        todayKey: String,
        topN: Int = DEFAULT_TOP_N,
    ): InsightsSummary {
        if (records.isEmpty()) return InsightsSummary()

        val daily = aggregate(records)
        val (current, longest) = streaks(daily, todayKey)
        val today = dayNumberOf(todayKey) ?: 0L

        var total = 0L
        var todayMs = 0L
        var weekMs = 0L
        var monthMs = 0L
        for (d in daily) {
            total += d.playedMs
            if (d.date == todayKey) todayMs += d.playedMs
            val dn = dayNumberOf(d.date) ?: continue
            if (dn > today - 7) weekMs += d.playedMs
            if (dn > today - 30) monthMs += d.playedMs
        }

        val hourBuckets = LongArray(HOURS)
        val weekdayBuckets = LongArray(DAYS_PER_WEEK)
        val artistMs = HashMap<String, Long>()
        val artistCount = HashMap<String, Int>()
        val trackMs = HashMap<String, Long>()
        val trackLabel = HashMap<String, String>()
        val trackCount = HashMap<String, Int>()
        val uniqueTracks = HashSet<String>()
        var completed = 0
        var skipped = 0
        var newTracks = 0

        for (r in records) {
            hourBuckets[hourOf(r.startedAt).coerceIn(0, HOURS - 1)] += r.playedMs
            weekdayBuckets[weekdayIndexOf(dateKeyOf(r.startedAt))] += r.playedMs
            if (r.completed) completed++
            if (r.skipped) skipped++
            uniqueTracks.add(r.mediaId)
            if (r.artist.isNotBlank()) {
                artistMs[r.artist] = (artistMs[r.artist] ?: 0L) + r.playedMs
                artistCount[r.artist] = (artistCount[r.artist] ?: 0) + 1
            }
            trackMs[r.mediaId] = (trackMs[r.mediaId] ?: 0L) + r.playedMs
            trackLabel[r.mediaId] = r.name
            trackCount[r.mediaId] = (trackCount[r.mediaId] ?: 0) + 1
        }
        for (d in daily) newTracks += d.newTracks

        return InsightsSummary(
            totalMs = total,
            todayMs = todayMs,
            weekMs = weekMs,
            monthMs = monthMs,
            currentStreak = current,
            longestStreak = longest,
            playCount = records.size,
            completedCount = completed,
            skippedCount = skipped,
            uniqueTrackCount = uniqueTracks.size,
            newTrackCount = newTracks,
            hourBuckets = hourBuckets.toList(),
            weekdayBuckets = weekdayBuckets.toList(),
            topArtists = artistMs.entries
                .sortedByDescending { it.value }
                .take(topN)
                .map { RankItem(it.key, it.key, it.value, artistCount[it.key] ?: 0) },
            topTracks = trackMs.entries
                .sortedByDescending { it.value }
                .take(topN)
                .map { RankItem(it.key, trackLabel[it.key] ?: it.key, it.value, trackCount[it.key] ?: 0) },
        )
    }

    /**
     * 判定一次会话的收尾结论。
     *
     * 抽成函数是因为它有**三条调用路径**（切歌 / 暂停 / 进程退出），
     * 三处各写一遍必然漂移成「暂停关掉的那次不算跳过、切歌关掉的那次算」。
     *
     * @param playedMs 实际收听毫秒
     * @param durationMs 曲目时长；未知（<=0）时一律不算完成、也不算跳过
     * @param reachedTail 是否播到了曲目尾部（引擎报告的自然结束）
     */
    fun classify(
        playedMs: Long,
        durationMs: Long,
        reachedTail: Boolean,
    ): Pair<Boolean, Boolean> {
        if (durationMs <= 0L) return false to false
        val completed = reachedTail || playedMs >= (durationMs * COMPLETE_RATIO).toLong()
        // 播完的当然不算跳过；只有「没播完就断了」才可能是跳过。
        val skipped = !completed && playedMs < (durationMs * SKIP_RATIO).toLong()
        return completed to skipped
    }

    // ============ 日历墙网格（纯函数，可单测） ============

    /** 日历墙的一格。 */
    data class CalendarCell(
        val dateKey: String,
        val playedMs: Long,
        /** 0–4，由 [HeatScale] 映射而来。 */
        val level: Int,
    )

    /**
     * 年视图网格：`weeks` 列 × 7 行，**最后一列是包含 [todayKey] 的那一周**。
     *
     * 返回 `grid[行][列]`，行索引 0 = 周一（与 [weekdayIndexOf] 一致）。
     * 网格里会有「未来」的格子（今天所在的那一周还没过完），它们的 `playedMs` 恒为 0 ——
     * 渲染层据此把它画成「尚未到来」而不是「这天没听」。
     */
    fun yearGrid(
        daily: List<DailyAgg>,
        scale: HeatScale,
        todayKey: String,
        weeks: Int = 53,
    ): List<List<CalendarCell>> {
        val byDate = daily.associateBy { it.date }
        val todayDays = dayNumberOf(todayKey) ?: return emptyList()
        val lastMonday = todayDays - weekdayIndexOf(todayKey)
        val startDays = lastMonday - (weeks - 1) * 7L
        return List(DAYS_PER_WEEK) { row ->
            List(weeks) { col ->
                val dayNumber = startDays + col * 7L + row
                val key = dateKeyOfDayNumber(dayNumber)
                val ms = if (dayNumber > todayDays) 0L else byDate[key]?.playedMs ?: 0L
                CalendarCell(key, ms, scale.levelOf(ms))
            }
        }
    }

    /**
     * 月视图网格：行 = 周，列 = 周一…周日。
     *
     * 不在当月的格子为 `null`（渲染成空白，不是「没听」）——
     * 两者混在一起会让用户以为上个月末那几天没听过歌。
     */
    fun monthGrid(
        daily: List<DailyAgg>,
        scale: HeatScale,
        year: Int,
        month: Int,
    ): List<List<CalendarCell?>> {
        val byDate = daily.associateBy { it.date }
        val firstWeekday = weekdayIndexOf(dateKey(year, month, 1))
        val total = daysInMonth(year, month)
        val rows = (firstWeekday + total + 6) / 7
        return List(rows) { r ->
            List(DAYS_PER_WEEK) { c ->
                val dayOfMonth = r * 7 + c - firstWeekday + 1
                if (dayOfMonth < 1 || dayOfMonth > total) {
                    null
                } else {
                    val key = dateKey(year, month, dayOfMonth)
                    val ms = byDate[key]?.playedMs ?: 0L
                    CalendarCell(key, ms, scale.levelOf(ms))
                }
            }
        }
    }
}
