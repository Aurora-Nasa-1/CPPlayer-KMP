package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.i18n.CpStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.ContentState
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.LazyScrollColumn
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.ListeningCalendar
import cp.player.app.ui.component.ListeningCalendarLegend
import cp.player.app.ui.component.MonthListeningCalendar
import cp.player.app.ui.component.SectionHeader
import cp.player.app.ui.component.SongMenuActions
import cp.player.app.ui.component.StateSurface
import cp.player.app.ui.component.formatDurationCompact
import cp.player.app.ui.component.formatDurationShort
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.songContextMenuItems
import cp.player.app.ui.component.songShareText
import cp.player.app.ui.anim.CoverFlight
import cp.player.app.ui.util.popOrNotify
import cp.player.app.platform.shareText
import cp.player.core.insights.Insights
import cp.player.core.insights.RankItem
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.launch

/**
 * 听歌习惯页（「听歌报告」）。
 *
 * ### 为什么它把「我的」与「最近播放」并进来
 * 三块内容本来分散在三个入口：「我的」的聆听统计只是一行死数字、「最近播放」是独立整页。
 * 而它们回答的其实是同一类问题（我听多久 / 我怎么听 / 我听了什么），
 * 拆成三处会让用户为了「看一眼总时长」先进设置、再回退、再进另一个列表。
 * 现在收敛成一页三 tab：**概览 / 习惯 / 最近**。
 *
 * ### 为什么不用新 tab 代替「我的」
 * `MAIN_TABS` 的三个 Screen 是会话级单例，动 tab 列表要同步牵动 `MainScreen` 标题栏、
 * `App.kt` Esc 兜底、`MainScreen` 消费这三处返回链判据。习惯页是「低频深看」，
 * 走 push 进内容层栈更合适，也让入口保持两个（仪表盘的「聆听统计」与「最近播放」两张卡）。
 */
class InsightsScreen(private val initialTab: Int = TAB_OVERVIEW) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        InsightsContent(
            initialTab = initialTab,
            onBack = { navigator.popOrNotify() },
        )
    }

    companion object {
        const val TAB_OVERVIEW = 0
        const val TAB_HABITS = 1
        const val TAB_RECENT = 2
    }
}

/**
 * tab 标签做成函数而不是顶层 `val`：顶层属性在**类加载时**求值，那一刻还没有语言状态
 * ⇒ 标签会永远停在一种语言上（编译不报错、出图也看不出，见 I18N.md §5.9）。
 */
private fun tabLabels(s: CpStrings) = listOf(
    s.insights.tabOverview,
    s.insights.tabHabits,
    s.insights.tabRecent,
)

@Composable
private fun InsightsContent(initialTab: Int, onBack: () -> Unit) {
    val s = cpStrings()
    val summary by AppModel.insightsSummaryFlow.collectAsState()
    val daily by AppModel.dailyInsightsFlow.collectAsState()
    val records by AppModel.listeningRecordsFlow.collectAsState()
    val writeError by AppModel.insightsWriteErrorFlow.collectAsState()
    val green by AppModel.heatmapGreenFlow.collectAsState()
    // 「最近」tab 的状态在这里收集，而不是在 `recentTab` 内部 —— 后者是
    // `LazyListScope` 扩展（非 @Composable），在里面读 collectAsState 编译不过。
    val recentTracks by AppModel.recentTracksFlow.collectAsState()
    val recentScope = rememberCoroutineScope()
    val recentProvider = AppModel.activeProviderId()
    // 与独立整页同一套口径（HomeScreen 里是 maxWidth >= 900.dp）。
    // 列数在 `recentTab` 里没法自己算（LazyListScope 扩展不是 @Composable），从外面传。
    val recentColumns = if (LocalIsExpanded.current) 2 else 1
    val recentToMediaId: (String) -> String = { id ->
        if (id.contains("://")) id else "$recentProvider://song/$id"
    }

    // 「今天」只取一次：日历墙与聚合必须用同一个判据，否则跨零点时
    // 会出现「墙上有今天这一格、但今日时长显示为 0」这种自相矛盾的画面。
    val todayKey = remember { AppModel.todayKey() }
    val scale = remember(daily, todayKey) { Insights.heatScaleOf(daily, todayKey) }

    val labels = tabLabels(s)
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, labels.lastIndex)) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    var recentSelection by remember { mutableStateOf<cp.player.core.music.TrackSummary?>(null) }
    val confirm = rememberConfirmState()

    CpRouteScaffold(title = s.insights.screenTitle, onBack = onBack) { pageModifier ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyScrollColumn(
                modifier = pageModifier.widthIn(max = CpSpacing.pageMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.pageHorizontal,
                    end = CpSpacing.pageHorizontal,
                    top = CpSpacing.pageTop,
                    // 宽屏的小播放器浮在底部，末尾留一条安全带。
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            ) {
                item {
                    InsightsTabs(selected = tab, labels = labels, onSelect = { tab = it })
                }

                if (writeError != null) {
                    item {
                        StateSurface {
                            ContentState(
                                title = s.insights.writeErrorTitle,
                                // 写入失败的原因原样透出（与诊断页的约定一致，不冒充本地化文案）。
                                message = writeError,
                                error = true,
                            )
                        }
                    }
                }

                when (tab) {
                    InsightsScreen.TAB_OVERVIEW -> overviewTab(
                        strings = s,
                        summary = summary,
                        daily = daily,
                        scale = scale,
                        todayKey = todayKey,
                        green = green,
                        records = records,
                        selectedDay = selectedDay,
                        onSelectDay = { selectedDay = if (selectedDay == it) null else it },
                        onToggleGreen = { AppModel.setHeatmapGreen(!green) },
                        onClear = {
                            confirm.request(
                                title = s.insights.clearTitle,
                                message = s.insights.clearMessage,
                                confirmLabel = s.insights.clearConfirmLabel,
                                destructive = true,
                                onConfirm = { AppModel.clearListeningRecords() },
                            )
                        },
                    )

                    InsightsScreen.TAB_HABITS -> habitsTab(strings = s, summary = summary)
                    else -> recentTab(
                        strings = s,
                        recentTracks = recentTracks,
                        columns = recentColumns,
                        scope = recentScope,
                        toMediaId = recentToMediaId,
                        onSelectionChange = { recentSelection = it },
                    )
                }
            }
        }
    }
    CpConfirmHost(confirm)
    RecentPlaysOptionsHost(
        track = recentSelection,
        onDismiss = { recentSelection = null },
    )
}

/**
 * 「最近」tab。
 *
 * ⚠️ 这里**只铺行、列表由本页的 `LazyScrollColumn` 承载**，绝不能
 * `item { RecentPlaysScreen(embedded = true).Content() }` 那样嵌一整页 ——
 * `LazyColumn` 的 item 高度无界，内层滚动容器拿到 `maxHeight = Infinity` 会直接抛
 * `IllegalStateException`（离屏探针实测）。行本身复用 [recentPlaysRows]，
 * 与独立整页共用同一份渲染逻辑。
 *
 * 状态（列表 / scope / provider）由 [InsightsContent] 这个 `@Composable` 收集后当参数传进来：
 * `LazyListScope` 的扩展函数不是 `@Composable`，在里面读 `collectAsState` 编译不过。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.recentTab(
    strings: CpStrings,
    recentTracks: List<cp.player.core.music.TrackSummary>,
    columns: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    toMediaId: (String) -> String,
    onSelectionChange: (cp.player.core.music.TrackSummary?) -> Unit,
) {
    if (recentTracks.isEmpty()) {
        item {
            StateSurface {
                ContentState(
                    title = strings.insights.recentEmptyTitle,
                    message = strings.insights.recentEmptyNote,
                )
            }
        }
        return
    }

    item {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = strings.library.recentPlays,
                supportingText = strings.insights.recentCount(recentTracks.size),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    recentTracks.firstOrNull()?.let { CoverFlight.play(it.id, it.coverUrl) }
                    scope.launch {
                        AppModel.playback.playQueue(recentTracks.map { toMediaId(it.id) }, startIndex = 0)
                    }
                },
            ) {
                Text(strings.library.playAll)
            }
        }
    }

    recentPlaysRows(
        tracks = recentTracks,
        columns = columns,
        onPlay = { index ->
            val track = recentTracks[index]
            CoverFlight.play(track.id, track.coverUrl)
            AppModel.playTrackClicked(toMediaId(track.id)) {
                AppModel.playback.playQueue(recentTracks.map { toMediaId(it.id) }, startIndex = index)
            }
        },
        onOptions = onSelectionChange,
        contextMenu = { track ->
            songContextMenuItems(
                SongMenuActions(
                    onPlay = {
                        CoverFlight.play(track.id, track.coverUrl)
                        scope.launch {
                            AppModel.playback.playQueue(
                                recentTracks.map { toMediaId(it.id) },
                                startIndex = recentTracks.indexOf(track),
                            )
                        }
                    },
                    onAddToQueue = {
                        scope.launch { AppModel.playback.addToQueue(toMediaId(track.id)) }
                        cp.player.app.ui.util.UiEvents.notify(strings.library.queuedToPlay)
                    },
                    onPlayNext = {
                        scope.launch { AppModel.playback.addNextToQueue(toMediaId(track.id)) }
                        cp.player.app.ui.util.UiEvents.notify(strings.library.playNext)
                    },
                    onShare = { shareText(songShareText(track)) },
                )
            )
        },
    )
}

// ============================================================
// 分段控件
// ============================================================

@Composable
private fun InsightsTabs(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ============================================================
// 概览
// ============================================================

private fun androidx.compose.foundation.lazy.LazyListScope.overviewTab(
    strings: CpStrings,
    summary: cp.player.core.insights.InsightsSummary,
    daily: List<cp.player.core.insights.DailyAgg>,
    scale: cp.player.core.insights.HeatScale,
    todayKey: String,
    green: Boolean,
    records: List<cp.player.core.insights.PlayRecord>,
    selectedDay: String?,
    onSelectDay: (String) -> Unit,
    onToggleGreen: () -> Unit,
    onClear: () -> Unit,
) {
    item {
        MetricRow(
            listOf(
                // 指标卡三列并排、窄屏每张 ~110dp ⇒ 这里用**极短**格式。
                // 用 `formatDurationShort`（「2 小时 41 分」）在本仓离屏出图时被硬裁成
                // 「2 小时」，而裁掉的恰好是分钟 —— 看起来像统计漏了。
                formatDurationCompact(summary.todayMs, strings) to strings.insights.metricToday,
                formatDurationCompact(summary.weekMs, strings) to strings.insights.metricWeek,
                formatDurationCompact(summary.totalMs, strings) to strings.insights.metricTotal,
            ),
        )
    }
    item {
        MetricRow(
            listOf(
                strings.insights.daysLabel(summary.currentStreak) to strings.insights.metricStreak,
                strings.insights.daysLabel(summary.longestStreak) to strings.insights.metricLongestStreak,
                strings.insights.tracksLabel(summary.uniqueTrackCount) to strings.insights.metricUniqueTracks,
            ),
        )
    }

    item {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = strings.insights.calendarTitle,
                supportingText = strings.insights.calendarNote,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (daily.isEmpty()) {
        item {
            StateSurface {
                ContentState(
                    title = strings.insights.calendarEmptyTitle,
                    message = strings.insights.calendarEmptyNote,
                )
            }
        }
    } else {
        item {
            // 年视图 53 列 ≈ 690dp，放不进手机屏宽 ⇒ 窄屏换月历视图。
            // 两者共用同一份 daily 与同一套分档，绝不允许各算一套（否则同一天在两端颜色不同）。
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= CpBreakpoints.medium) {
                    val gap = 3.dp
                    // 单元格宽度按可用宽度反算，但**封顶 16dp**：不封顶时 1400dp 页面上
                    // 单格会到 22dp 以上，整面墙变成一坨方块、失去「密度感」。
                    val cell = ((maxWidth - gap * 52) / 53).coerceIn(5.dp, 16.dp)
                    ListeningCalendar(
                        daily = daily,
                        scale = scale,
                        todayKey = todayKey,
                        classicGreen = green,
                        cellSize = cell,
                        gap = gap,
                        modifier = Modifier.align(Alignment.TopCenter),
                        onDayClick = onSelectDay,
                    )
                } else {
                    val now = remember { localDateTimeOf(cp.player.core.util.currentTimeMillis()) }
                    MonthListeningCalendar(
                        daily = daily,
                        scale = scale,
                        year = now.year,
                        month = now.month,
                        todayKey = todayKey,
                        classicGreen = green,
                        onDayClick = onSelectDay,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ListeningCalendarLegend(classicGreen = green)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggleGreen) {
                    Text(strings.insights.paletteLabel(green))
                }
            }
        }
    }

    selectedDay?.let { day ->
        item {
            SelectedDayCard(dayKey = day, records = records, strings = strings)
        }
    }

        if (summary.topArtists.isNotEmpty()) {
        item {
            SectionHeader(title = strings.insights.topArtistsTitle, supportingText = strings.insights.rankedByPlayTime, modifier = Modifier.padding(top = 12.dp))
        }
        items(summary.topArtists.size, key = { "artist-${summary.topArtists[it].key}" }) { index ->
            RankRow(summary.topArtists[index], summary.topArtists.first().playedMs, strings)
        }
    }

    if (daily.isNotEmpty()) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onClear) { Text(strings.insights.clearTitle) }
            }
        }
    }
}

/** 某一天的明细：当天听过什么。点击日历格后展开，让日历墙成为**导航入口**而不是装饰。 */
@Composable
private fun SelectedDayCard(
    dayKey: String,
    records: List<cp.player.core.insights.PlayRecord>,
    strings: CpStrings,
) {
    // 日期键形如 `yyyy-MM-dd`，直接拆比再走一次日期库便宜，也不会引入时区换算的边界。
    val parts = remember(dayKey) { dayKey.split('-') }
    val dayLabel = remember(parts) {
        val y = parts.getOrNull(0).orEmpty()
        val m = parts.getOrNull(1)?.trimStart('0').orEmpty()
        val d = parts.getOrNull(2)?.trimStart('0').orEmpty()
        "${y} 年 ${m} 月 ${d} 日"
    }
    val dayRecords = remember(records, dayKey) {
        records.filter { Insights.dateKeyOf(it.startedAt) == dayKey }.sortedBy { it.startedAt }
    }
    val totalMs = dayRecords.sumOf { it.playedMs }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                dayLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                strings.insights.daySummary(formatDurationShort(totalMs, strings), dayRecords.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dayRecords.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatDurationShort(r.playedMs, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ============================================================
// 习惯
// ============================================================

private fun androidx.compose.foundation.lazy.LazyListScope.habitsTab(
    summary: cp.player.core.insights.InsightsSummary,
    strings: CpStrings,
) {
    item {
        MetricRow(
            listOf(
                percent(summary.completedCount, summary.playCount) to strings.insights.metricCompletionRate,
                percent(summary.skippedCount, summary.playCount) to strings.insights.metricSkipRate,
                "${summary.newTrackCount} ${strings.insights.tracksLabel(summary.newTrackCount)}" to strings.insights.metricNewTracks,
            ),
        )
    }
    item {
        SectionHeader(title = strings.insights.hourlyTitle, supportingText = strings.insights.hourlyNote, modifier = Modifier.padding(top = 12.dp))
    }
    item {
        BarChart(
            values = summary.hourBuckets,
            labels = (0..23).map { if (it % 6 == 0) it.toString() else "" },
        )
    }
    item {
        SectionHeader(title = strings.insights.weekdayTitle, supportingText = strings.insights.weekdayNote, modifier = Modifier.padding(top = 12.dp))
    }
    item {
        BarChart(
            values = summary.weekdayBuckets,
            labels = listOf("一", "二", "三", "四", "五", "六", "日"),
        )
    }
    if (summary.topTracks.isNotEmpty()) {
        item {
            SectionHeader(title = strings.insights.topTracksTitle, supportingText = strings.insights.rankedByPlayTime, modifier = Modifier.padding(top = 12.dp))
        }
        items(summary.topTracks.size, key = { "track-${summary.topTracks[it].key}" }) { index ->
            RankRow(summary.topTracks[index], summary.topTracks.first().playedMs, strings)
        }
    }
}

private fun percent(part: Int, total: Int): String =
    if (total <= 0) "—" else "${(part * 100f / total).toInt()}%"

// ============================================================
// 小组件
// ============================================================

@Composable
private fun MetricRow(metrics: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.forEach { (value, label) ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        // 兜底省略号：即便文案仍偏长，也要看得出「这里还有内容」，
                        // 而不是无声无息地裁掉半截。
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 排行行：横条长度按最大值归一。 */
@Composable
private fun RankRow(item: RankItem, maxMs: Long, strings: CpStrings) {
    val fraction = if (maxMs <= 0L) 0f else (item.playedMs.toFloat() / maxMs).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDurationShort(item.playedMs, strings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 极简柱状图。
 *
 * 刻意不引图表库：这里只需要「若干等宽柱子」这一种形态，
 * 引一个图表库会为它带来主题适配、版本冲突与包体三份成本。
 */
@Composable
private fun BarChart(values: List<Long>, labels: List<String>) {
    val max = values.maxOrNull() ?: 0L
    Row(
        Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { index, v ->
            val fraction = if (max <= 0L) 0f else (v.toFloat() / max).coerceIn(0.02f, 1f)
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((72 * fraction).dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    labels.getOrElse(index) { "" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
