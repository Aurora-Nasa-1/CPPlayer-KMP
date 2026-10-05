package cp.player.app.i18n

/**
 * 听歌报告 / 诊断 / 关于三页 + 日历组件的文案。
 *
 * `calendar` 单独成组是因为 `ListeningCalendar` 被「听歌报告」以外的页面复用过
 * （组件在 `ui/component`），它的文案不该挂在某个页面的组下。其余成员摊平在 `InsightsStrings`
 * 顶层，调用点统一走 `s.insights.xxx`。
 */
interface InsightStrings {
    // —— 听歌报告页（概览 / 习惯 / 最近三个 tab）——
    val screenTitle: String
    val tabOverview: String
    val tabHabits: String
    val tabRecent: String

    /** 写库失败时那条警告的标题；正文是原始错误串，刻意不译（见 `HealthScreen` 同款约定）。 */
    val writeErrorTitle: String

    val clearTitle: String
    val clearMessage: String
    val clearConfirmLabel: String

    val recentEmptyTitle: String
    val recentEmptyNote: String

    /** @param count 最近播放的曲目数。 */
    fun recentCount(count: Int): String

    val metricToday: String
    val metricWeek: String
    val metricTotal: String
    val metricStreak: String
    val metricLongestStreak: String
    val metricUniqueTracks: String

    fun daysLabel(days: Int): String
    fun tracksLabel(count: Int): String

    val calendarTitle: String
    val calendarNote: String
    val calendarEmptyTitle: String
    val calendarEmptyNote: String

    /** 配色切换按钮的标签。 */
    fun paletteLabel(classicGreen: Boolean): String

    /** @param month 已去掉前导零的月份（1–12），由调用方从日期键拆出。 */
    fun dateTitle(year: String, month: String, day: String): String

    /** @param duration 已格式化的当天总时长；@param count 当天听过的首数。 */
    fun daySummary(duration: String, count: Int): String

    val metricCompletionRate: String
    val metricSkipRate: String
    val metricNewTracks: String

    /** 分母为 0 时比率无处可算，显示这个占位符（中英同形）。 */
    val noData: String

    val hourlyTitle: String
    val hourlyNote: String
    val weekdayTitle: String
    val weekdayNote: String

    val topArtistsTitle: String
    val topTracksTitle: String
    val rankedByPlayTime: String

    // —— 诊断页 ——
    /**
     * ⚠️ **这一组只收「用户读得懂」的标题与说明**：接口名、耗时、等级码
     * （`OK` / `WARN` / `ERROR`）与后端返回的原始串都是技术内容，**刻意不译** ——
     * 诊断页的价值就在于原样透出，翻译它只会让人没法跟日志对上。
     */
    /** 顶栏清空按钮的图标语义 + 确认框的确认词。 */
    val healthClear: String
    val healthClearConfirmTitle: String

    /** @param count 现有调用记录条数。 */
    fun healthClearConfirmMessage(count: Int): String

    fun healthAllCount(count: Int): String
    val healthErrorsOnly: String
    val healthEmptyRecords: String

    val levelOk: String
    val levelWarning: String
    val levelError: String

    /** @param status 已按当前语言取好的等级词。 */
    fun overallStatus(status: String): String

    /** @param total 记录总数（「最近 100 条」是页面既有口径，故写死在文案里）。 */
    fun overviewNote(total: Int): String

    /** 拼在日志行末尾的「· 回退自 X」片段，含前导分隔符。 */
    fun fallbackFrom(source: String): String

    val rawResponseTitle: String
    val close: String

    // —— 关于与支持页 ——
    val sectionVersion: String
    val currentVersion: String
    val commitHash: String
    val checkUpdate: String
    val checking: String

    /** @param version 形如 `1.2.0`（不含前缀 `v`，由文案自己加）。 */
    fun updateFound(version: String): String

    val upToDate: String

    val sectionProject: String
    val githubRepo: String
    val sectionMaintainer: String
    val maintainerRole: String
    val sectionSupport: String
    val supportTitle: String
    val supportNote: String

    val updateDialogTitle: String
    val changelog: String
    val downloadUpdate: String

    // —— 听歌日历墙（`ListeningCalendar` / 月视图 / 图例）与时长格式化 ——
    val calendar: Calendar

    interface Calendar {
        /** 周一到周日的缩写，与 `Insights.weekdayIndexOf` 的「周一 = 0」对齐。 */
        val weekdayLabels: List<String>

        val legendLess: String
        val legendMore: String

        /** 整面墙的读屏概述：逐格挂语义会让读屏用户划几百次。 */
        fun overviewDescription(weeks: Int, recordedDays: Int): String

        // —— 时长格式化（`formatDurationShort` / `formatDurationCompact`）——
        val noPlayback: String
        fun hoursAndMinutes(hours: Long, minutes: Long): String
        fun hoursLabel(hours: Long): String
        fun minutesLabel(minutes: Long): String
        val underOneMinute: String

        val compactZero: String
        fun compactMinutes(minutes: Long): String

        /** @param value 已按「最多一位小数」拼好的小时数文本（如 `3` / `3.5`）。 */
        fun compactHours(value: String): String
    }
}

object InsightStringsZh : InsightStrings {
    override val screenTitle = "听歌报告"
    override val tabOverview = "概览"
    override val tabHabits = "习惯"
    override val tabRecent = "最近"

    override val writeErrorTitle = "统计可能不完整"

    override val clearTitle = "清空听歌记录"
    override val clearMessage = "将删除全部收听历史与统计。此操作无法恢复。"
    override val clearConfirmLabel = "清空"

    override val recentEmptyTitle = "还没有最近播放"
    override val recentEmptyNote = "播放歌曲后会显示在这里"
    override fun recentCount(count: Int) = "完整历史列表 · 共 $count 首"

    override val metricToday = "今日"
    override val metricWeek = "本周"
    override val metricTotal = "累计"
    override val metricStreak = "当前连续"
    override val metricLongestStreak = "最长连续"
    override val metricUniqueTracks = "听过曲目"
    override fun daysLabel(days: Int) = "$days 天"
    override fun tracksLabel(count: Int) = "$count 首"

    override val calendarTitle = "听歌日历"
    override val calendarNote = "一天一格，颜色 = 当天收听时长"
    override val calendarEmptyTitle = "还没有收听记录"
    override val calendarEmptyNote = "播放任意歌曲后，这里会开始记录你的听歌习惯"
    override fun paletteLabel(classicGreen: Boolean) =
        if (classicGreen) "配色：经典绿" else "配色：跟随主题"

    override fun dateTitle(year: String, month: String, day: String) = "$year 年 $month 月 $day 日"
    override fun daySummary(duration: String, count: Int) = "$duration · $count 首"

    override val metricCompletionRate = "完播率"
    override val metricSkipRate = "跳过率"
    override val metricNewTracks = "新歌发现"
    override val noData = "—"

    override val hourlyTitle = "作息分布"
    override val hourlyNote = "按小时累计的收听时长"
    override val weekdayTitle = "一周分布"
    override val weekdayNote = "周一到周日"

    override val topArtistsTitle = "常听歌手"
    override val topTracksTitle = "常听歌曲"
    override val rankedByPlayTime = "按收听时长"

    override val healthClear = "清空"
    override val healthClearConfirmTitle = "清空诊断记录"
    override fun healthClearConfirmMessage(count: Int) = "确定清空全部 $count 条调用记录吗？"
    override fun healthAllCount(count: Int) = "全部 $count"
    override val healthErrorsOnly = "仅异常"
    override val healthEmptyRecords = "暂无调用记录"

    override val levelOk = "健康"
    override val levelWarning = "存在警告"
    override val levelError = "存在错误"
    override fun overallStatus(status: String) = "综合状态：$status"
    override fun overviewNote(total: Int) = "最近 100 条综合判定 · 共 $total 条记录"
    override fun fallbackFrom(source: String) = " · 回退自 $source"

    override val rawResponseTitle = "API 原始返回内容"
    override val close = "关闭"

    override val sectionVersion = "版本信息"
    override val currentVersion = "当前版本"
    override val commitHash = "提交哈希"
    override val checkUpdate = "检查更新"
    override val checking = "正在检查..."
    override fun updateFound(version: String) = "发现新版本: v$version"
    override val upToDate = "已是最新版本"

    override val sectionProject = "项目信息"
    // 「GitHub」是品牌名，刻意保持原文（别当漏译改掉）。
    override val githubRepo = "GitHub 项目"
    override val sectionMaintainer = "维护者"
    override val maintainerRole = "创建者 & 主要维护者"
    override val sectionSupport = "支持项目"
    override val supportTitle = "支持本项目"
    override val supportNote = "在项目主页查看说明与支持方式"

    override val updateDialogTitle = "发现新版本"
    override val changelog = "更新日志"
    override val downloadUpdate = "下载更新"

    override val calendar: InsightStrings.Calendar = object : InsightStrings.Calendar {
        override val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
        override val legendLess = "少"
        override val legendMore = "多"
        override fun overviewDescription(weeks: Int, recordedDays: Int) =
            "听歌日历，最近 $weeks 周，共 $recordedDays 天有收听记录"

        override val noPlayback = "没有收听"
        override fun hoursAndMinutes(hours: Long, minutes: Long) = "$hours 小时 $minutes 分"
        override fun hoursLabel(hours: Long) = "$hours 小时"
        override fun minutesLabel(minutes: Long) = "$minutes 分钟"
        override val underOneMinute = "不到 1 分钟"

        override val compactZero = "0 分"
        override fun compactMinutes(minutes: Long) = "$minutes 分"
        override fun compactHours(value: String) = "$value 小时"
    }
}

object InsightStringsEn : InsightStrings {
    override val screenTitle = "Listening report"
    override val tabOverview = "Overview"
    override val tabHabits = "Habits"
    override val tabRecent = "Recent"

    override val writeErrorTitle = "Stats may be incomplete"

    override val clearTitle = "Clear listening history"
    override val clearMessage = "This deletes all listening history and stats. It cannot be undone."
    override val clearConfirmLabel = "Clear"

    override val recentEmptyTitle = "No recent plays yet"
    override val recentEmptyNote = "Tracks you play will show up here"
    override fun recentCount(count: Int) =
        "Full history · $count ${if (count == 1) "track" else "tracks"}"

    override val metricToday = "Today"
    override val metricWeek = "This week"
    override val metricTotal = "All time"
    override val metricStreak = "Current streak"
    override val metricLongestStreak = "Longest streak"
    override val metricUniqueTracks = "Tracks heard"
    override fun daysLabel(days: Int) = "$days ${if (days == 1) "day" else "days"}"
    override fun tracksLabel(count: Int) = "$count ${if (count == 1) "track" else "tracks"}"

    override val calendarTitle = "Listening calendar"
    override val calendarNote = "One cell per day; color = time listened that day"
    override val calendarEmptyTitle = "No listening records yet"
    override val calendarEmptyNote = "Play any track and your listening habits will start showing up here"
    override fun paletteLabel(classicGreen: Boolean) =
        if (classicGreen) "Palette: classic green" else "Palette: match theme"

    override fun dateTitle(year: String, month: String, day: String): String {
        val name = month.toIntOrNull()?.let { MONTH_ABBR.getOrNull(it - 1) } ?: return "$year-$month-$day"
        return "$name $day, $year"
    }

    override fun daySummary(duration: String, count: Int) =
        "$duration · $count ${if (count == 1) "track" else "tracks"}"

    override val metricCompletionRate = "Completion rate"
    override val metricSkipRate = "Skip rate"
    override val metricNewTracks = "New tracks"
    override val noData = "—"

    override val hourlyTitle = "By hour"
    override val hourlyNote = "Listening time totaled for each hour of the day"
    override val weekdayTitle = "By weekday"
    override val weekdayNote = "Monday through Sunday"

    override val topArtistsTitle = "Top artists"
    override val topTracksTitle = "Top tracks"
    override val rankedByPlayTime = "By listening time"

    override val healthClear = "Clear"
    override val healthClearConfirmTitle = "Clear diagnostics log"
    override fun healthClearConfirmMessage(count: Int) =
        "Clear all $count ${if (count == 1) "record" else "records"}?"
    override fun healthAllCount(count: Int) = "All $count"
    override val healthErrorsOnly = "Errors only"
    override val healthEmptyRecords = "No call records yet"

    override val levelOk = "Healthy"
    override val levelWarning = "Warnings found"
    override val levelError = "Errors found"
    override fun overallStatus(status: String) = "Overall status: $status"
    override fun overviewNote(total: Int) =
        "Last 100 calls · $total ${if (total == 1) "record" else "records"} in all"
    override fun fallbackFrom(source: String) = " · fallback from $source"

    override val rawResponseTitle = "Raw API response"
    override val close = "Close"

    override val sectionVersion = "Version"
    override val currentVersion = "Current version"
    override val commitHash = "Commit hash"
    override val checkUpdate = "Check for updates"
    override val checking = "Checking for updates…"
    override fun updateFound(version: String) = "New version available: v$version"
    override val upToDate = "Up to date"

    override val sectionProject = "Project"
    // “GitHub” is a brand name — kept as-is on purpose (not a missing translation).
    override val githubRepo = "GitHub repository"
    override val sectionMaintainer = "Maintainer"
    override val maintainerRole = "Creator & lead maintainer"
    override val sectionSupport = "Support the project"
    override val supportTitle = "Support this project"
    override val supportNote = "See the project page for ways to support it"

    override val updateDialogTitle = "New version available"
    override val changelog = "Changelog"
    override val downloadUpdate = "Download update"

    override val calendar: InsightStrings.Calendar = object : InsightStrings.Calendar {
        override val weekdayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        override val legendLess = "Less"
        override val legendMore = "More"
        override fun overviewDescription(weeks: Int, recordedDays: Int) =
            "Listening calendar, last $weeks ${if (weeks == 1) "week" else "weeks"}, " +
                "$recordedDays ${if (recordedDays == 1) "day" else "days"} with listening time"

        override val noPlayback = "No listening time"
        override fun hoursAndMinutes(hours: Long, minutes: Long) =
            "$hours ${if (hours == 1L) "hour" else "hours"} " +
                "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        override fun hoursLabel(hours: Long) = "$hours ${if (hours == 1L) "hour" else "hours"}"
        override fun minutesLabel(minutes: Long) = "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
        override val underOneMinute = "Less than a minute"

        override val compactZero = "0 min"
        override fun compactMinutes(minutes: Long) = "$minutes min"
        override fun compactHours(value: String) = "$value hr"
    }
}

private val MONTH_ABBR = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
