package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.core.insights.DailyAgg
import cp.player.core.insights.HeatScale
import cp.player.core.insights.Insights

/**
 * 听歌日历墙 —— 一天一格、颜色深浅 = 当天收听时长（对齐 GitHub 贡献图的信息语法）。
 *
 * ### 为什么用它当习惯页的主体
 * 环形图 / 柱状图只能回答「一共多久」，而这面墙天然表达**连续**与**作息** ——
 * 「我最近断了」「周末听得多还是工作日多」正是习惯页要回答的问题。
 *
 * ### 三处刻意不照抄 GitHub
 * 1. **配色不写死绿色**：默认取主题 `primary` 派生（用户换封面取色时日历墙跟着变），
 *    否则它会成为全应用唯一「不跟着主题走」的地方。经典绿作为可选配色保留
 *    （设置项 `insights_heatmap_palette`）。
 * 2. **分档走分位数**（见 [HeatScale]），固定阈值会让「每天 20 分钟」和「每天 6 小时」
 *    的用户一个整片最浅、一个整片最深，日历墙直接失去分辨力。
 * 3. **零值格必须有底色**：`0 分钟`不是留白而是最浅的一档容器色 ——
 *    留白会让「这天没听」和「这天不存在」分不清，大屏上还会散成一片。
 *
 * @param daily 日聚合（来自 `AppModel.dailyInsightsFlow`）
 * @param scale 配色分档（来自 `Insights.heatScaleOf`）
 * @param todayKey 「今天」的日期键；由调用方传入而不是本组件取时钟，
 *   避免与聚合用的「今天」跨零点错位
 * @param classicGreen 是否使用 GitHub 经典绿（否则跟随主题取色）
 * @param onDayClick 点击某一天的单元格（进入那天的明细）
 */
@Composable
fun ListeningCalendar(
    daily: List<DailyAgg>,
    scale: HeatScale,
    todayKey: String,
    modifier: Modifier = Modifier,
    classicGreen: Boolean = false,
    cellSize: Dp = 12.dp,
    gap: Dp = 3.dp,
    weeks: Int = 53,
    onDayClick: (String) -> Unit = {},
) {
    val palette = rememberCalendarPalette(classicGreen)
    val grid = remember(daily, scale, todayKey, weeks) {
        Insights.yearGrid(daily, scale, todayKey, weeks)
    }
    if (grid.isEmpty()) return

    // 年度视图有 371 个格子：逐格挂语义会让读屏用户要划 371 次才能过去。
    // 这里只给整面墙一句概述，逐日信息交给可聚焦的月视图（手机形态）承担。
    val recordedDays = remember(daily) { daily.count { it.playedMs > 0 } }
    Column(
        modifier = modifier.semantics {
            contentDescription = "听歌日历，最近 $weeks 周，共 $recordedDays 天有收听记录"
        },
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        grid.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { cell ->
                    val isToday = cell.dateKey == todayKey
                    Box(
                        Modifier
                            .size(cellSize)
                            .clip(CellShape)
                            .background(palette.colorFor(cell.level))
                            .then(
                                if (isToday) {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CellShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable(enabled = cell.playedMs > 0) { onDayClick(cell.dateKey) },
                    )
                }
            }
        }
    }
}

/**
 * 月视图：一个月一屏，行 = 周、列 = 周一…周日，可左右翻月。
 *
 * **手机形态必须换成它**：年视图是 53 列，`53 × (10dp + 3dp) ≈ 690dp` 远超 360dp 屏宽 ——
 * 硬塞只会得到两类结果：格子小到 6dp 以下（点不中、看不清），或横向滚动
 * （永远只看到局部，失去「一眼看全年」的价值）。两种布局**共用同一份日聚合与同一套分档**，
 * 否则同一个数字在两端会显示成不同颜色。
 */
@Composable
fun MonthListeningCalendar(
    daily: List<DailyAgg>,
    scale: HeatScale,
    year: Int,
    month: Int,
    todayKey: String,
    modifier: Modifier = Modifier,
    classicGreen: Boolean = false,
    onDayClick: (String) -> Unit = {},
) {
    val palette = rememberCalendarPalette(classicGreen)
    val grid = remember(daily, scale, year, month) {
        Insights.monthGrid(daily, scale, year, month)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEKDAY_LABELS.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        grid.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell ->
                    if (cell == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val isToday = cell.dateKey == todayKey
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(CellShape)
                                .background(palette.colorFor(cell.level))
                                .then(
                                    if (isToday) {
                                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CellShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(enabled = cell.playedMs > 0) { onDayClick(cell.dateKey) }
                                .semantics {
                                    contentDescription = "${cell.dateKey}，${formatDurationShort(cell.playedMs)}"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = cell.dateKey.substringAfterLast('-').trimStart('0'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 配色图例：`少 ▢▢▢▢▢ 多`。 */
@Composable
fun ListeningCalendarLegend(
    modifier: Modifier = Modifier,
    classicGreen: Boolean = false,
) {
    val palette = rememberCalendarPalette(classicGreen)
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "少",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (0..4).forEach { level ->
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CellShape)
                    .background(palette.colorFor(level))
                    // 描一道极淡的边：第 0 档取的是 `surfaceContainerHighest`，
                    // 在图例里直接贴在页面背景上时几乎与背景同色 —— 不描边看起来像"少"和
                    // 第一个色块之间断了一格。墙上的格子因为是成片出现，问题不明显。
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CellShape),
            )
        }
        Text(
            "多",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val CellShape = RoundedCornerShape(2.dp)

/** 与 [Insights.weekdayIndexOf] 的「周一 = 0」对齐。 */
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 日历墙的 5 档配色（0–4）。 */
data class CalendarPalette(val levels: List<Color>) {
    fun colorFor(level: Int): Color = levels[level.coerceIn(0, levels.lastIndex)]
}

/**
 * 由主题派生配色。
 *
 * ⚠️ **浅色/深色都要能看清**：浅色下低档很容易与背景糊在一起、深色下高档容易过曝。
 * 用 `lerp(容器色, primary, t)` 而不是给 `primary` 叠 alpha ——
 * alpha 是叠在**未知背景**上的，卡片底色一变（例如列表选中态）档位对比就会漂。
 */
@Composable
fun rememberCalendarPalette(classicGreen: Boolean): CalendarPalette {
    val dark = LocalIsDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    return remember(classicGreen, dark, scheme.primary, scheme.surfaceContainerHighest) {
        if (classicGreen) {
            CalendarPalette(
                listOf(
                    if (dark) Color(0xFF21262D) else Color(0xFFEBEDF0),
                    Color(0xFF9BE9A8),
                    Color(0xFF40C463),
                    Color(0xFF30A14E),
                    Color(0xFF216E39),
                ),
            )
        } else {
            val empty = scheme.surfaceContainerHighest
            CalendarPalette(
                listOf(
                    empty,
                    lerp(empty, scheme.primary, 0.25f),
                    lerp(empty, scheme.primary, 0.5f),
                    lerp(empty, scheme.primary, 0.75f),
                    scheme.primary,
                ),
            )
        }
    }
}

/** 「3 小时 12 分」这类紧凑时长文案。习惯页多处要用，收敛到一处避免各写各的口径。 */
fun formatDurationShort(ms: Long): String {
    if (ms <= 0L) return "没有收听"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
        hours > 0 -> "$hours 小时"
        totalMinutes > 0 -> "$totalMinutes 分钟"
        else -> "不到 1 分钟"
    }
}

/**
 * 指标卡用的**极短**时长文案。
 *
 * 与 [formatDurationShort] 分开是必须的：指标卡是三列并排，窄屏上每张只有 ~110dp，
 * 而「2 小时 41 分」在那宽度下会被硬裁成「2 小时」——**离屏出图实测到了这个裁切**，
 * 而且裁掉的是分钟，看起来像统计少了。
 * 所以这里优先保证「一定放得下」，精度让位于可读性：超过 1 小时就只给一位小数。
 */
fun formatDurationCompact(ms: Long): String {
    if (ms <= 0L) return "0 分"
    val totalMinutes = ms / 60_000L
    return when {
        totalMinutes < 60 -> "$totalMinutes 分"
        totalMinutes < 600 -> {
            // 四舍五入到 0.1 小时，并**手工拼小数位**而不是用 Double.toString：
            // 后者会给出「3.0 小时」这种尾巴，也可能受 locale 影响（小数点变成逗号）。
            val tenths = (totalMinutes * 10 + 30) / 60
            val whole = tenths / 10
            val frac = tenths % 10
            if (frac == 0L) "$whole 小时" else "$whole.$frac 小时"
        }
        else -> "${totalMinutes / 60} 小时"
    }
}
