package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.ApiCallRecord
import cp.player.app.AppModel
import cp.player.app.HealthLevel
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.util.popOrNotify
import cp.player.core.monitor.HealthMonitor
import cp.player.core.provider.BackendProvider
import cp.player.core.util.localDateTimeOf
import kotlinx.serialization.json.Json

// ⚠️ 本页一律用 material3 的 `Text`，**不要**换成 `CpText`。
//
// `CpText` 的默认档是「单行 + 尾部省略号 + 悬停浮出全文」（见 `CpText.kt`），
// 而诊断页读的就是**原始数据本身**：音源 ID / 操作名 / 错误串 /
// 底层诊断键值都会超过一行。用 `CpText` 的话，用户看到的是一串省略号，
// 只有把鼠标悬上去（触屏根本没有 hover）才读得到 —— 等于把要排查的信息藏起来。
// 所以这一页的每个 `Text` 都**不传** `maxLines` / `overflow`（= 不截断，自然换行），
// 该独占一行的（错误串、原始 JSON）另加 `Modifier.fillMaxWidth()`。
//
// 代价是字体路由：裸 `Text` 拿不到 `CpText` 那层「拉丁 → Google Sans Flex、
// 汉字 → MiSans」的逐 run 切换，中日文落回平台回退字体。这是本页刻意的取舍
// —— 诊断页的首要目标是**读得到**，不是观感统一。

private enum class HealthTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    METHODS("操作统计", Icons.Default.Analytics),
    LOGS("调用日志", Icons.AutoMirrored.Filled.List),
    STATUS("Provider 状态", Icons.Default.Info),
}

private val prettyJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 诊断与健康监控页面：概览、方法统计、调用日志、Provider 状态。
 *
 * 「API 测试」「apiMap」两页签已随 JSON 兼容层一起删除：新的取数链路没有可自由分发的原始端点。
 */
class HealthScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val records by AppModel.health.recordsFlow.collectAsState()
        val overall by AppModel.health.overallLevelFlow.collectAsState()

        var currentTab by remember { mutableStateOf(HealthTab.OVERVIEW) }
        val confirm = rememberConfirmState()

        CpRouteScaffold(
            title = "诊断与健康",
            onBack = { navigator.popOrNotify() },
            topBarActions = listOf(
                TopBarAction(
                    icon = { Icon(Icons.Filled.DeleteSweep, "清空日志") },
                    onClick = {
                        if (records.isNotEmpty()) {
                            confirm.request(
                                title = "清空诊断记录",
                                message = "确定清空全部 ${records.size} 条调用记录吗？",
                                confirmLabel = "清空",
                                onConfirm = { AppModel.health.clearRecords() },
                            )
                        }
                    },
                )
            ),
        ) { pageModifier ->
            SettingsLazyPage(
                pageModifier = pageModifier,
                header = {
                    // M3 水平滚动导航芯片行
                    Surface(
                        color = settingsRowContainer(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HealthTab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                FilterChip(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    label = {
                                        Text(
                                            tab.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(tab.icon, null, Modifier.size(18.dp))
                                    },
                                )
                            }
                        }
                    }
                },
            ) {
                when (currentTab) {
                    HealthTab.OVERVIEW -> {
                        overviewTabContent(records, overall)
                    }
                    HealthTab.METHODS -> {
                        methodsTabContent(records)
                    }
                    HealthTab.LOGS -> {
                        logsTabContent(records)
                    }
                    HealthTab.STATUS -> {
                        statusTabContent()
                    }
                }
            }
        }

        CpConfirmHost(confirm)
    }
}

// ======================== Tab 1: 概览 (Overview) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.overviewTabContent(
    records: List<ApiCallRecord>,
    overall: HealthLevel,
) {
    item(key = "overview_global") {
        GlobalHealthCard(records, overall)
    }

    item(key = "overview_provider_title") {
        Spacer(Modifier.height(4.dp))
        Text(
            "音源健康评分与指标",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    val allStats = HealthMonitor.getAllStats()
    val providers = AppModel.availableProviders()
    val providerMap = providers.associateBy { it.id }
    val providerIds = (allStats.keys + providers.map { it.id }).distinct()

    if (providerIds.isEmpty()) {
        item(key = "overview_no_providers") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无已加载音源", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        items(providerIds, key = { "overview_provider_$it" }) { providerId ->
            val provider = providerMap[providerId]
            val stats = allStats[providerId]
            ProviderHealthCard(
                provider = provider,
                providerId = providerId,
                stats = stats,
            )
        }
    }
}

// internal：离屏渲染预览测试（desktopTest）直接组合这几张卡片核对「不截断」。
private data class GlobalHealthSummary(
    val totalSuccess: Int,
    val totalUnsupported: Int,
    val avgResponse: Long,
)

@Composable
internal fun GlobalHealthCard(records: List<ApiCallRecord>, overall: HealthLevel) {
    val totalCalls = records.size
    val summary = remember(records) {
        var totalSuccess = 0
        var totalUnsupported = 0
        var totalDuration = 0L
        records.forEach { record ->
            if (record.success) totalSuccess++
            if (record.level == HealthLevel.WARNING) totalUnsupported++
            totalDuration += record.durationMs
        }
        GlobalHealthSummary(
            totalSuccess = totalSuccess,
            totalUnsupported = totalUnsupported,
            avgResponse = if (totalCalls > 0) totalDuration / totalCalls else 0L,
        )
    }
    val totalSuccess = summary.totalSuccess
    val totalFail = totalCalls - totalSuccess
    val totalUnsupported = summary.totalUnsupported
    val globalSuccessRate = if (totalCalls > 0) totalSuccess * 100f / totalCalls else 100f
    val avgResponse = summary.avgResponse

    val accentColor = when (overall) {
        HealthLevel.OK -> Color(0xFF4CAF50)
        HealthLevel.WARNING -> Color(0xFFFF9800)
        HealthLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = settingsRowContainer()),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("系统综合运行状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "共 $totalCalls 次调用 · 平均响应 ${avgResponse}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${globalSuccessRate.toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                        )
                        Text(
                            when (overall) {
                                HealthLevel.OK -> "健康"
                                HealthLevel.WARNING -> "警告"
                                HealthLevel.ERROR -> "异常"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GlobalMetricChip("成功", "$totalSuccess", Color(0xFF4CAF50), Icons.Default.CheckCircle)
                GlobalMetricChip("失败", "$totalFail", MaterialTheme.colorScheme.error, Icons.Default.Error)
                GlobalMetricChip("不支持", "$totalUnsupported", Color(0xFFFF9800), Icons.Default.Warning)
            }
        }
    }
}

@Composable
private fun GlobalMetricChip(label: String, value: String, color: Color, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.12f),
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = color)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ProviderHealthCard(
    provider: BackendProvider?,
    providerId: String,
    stats: HealthMonitor.ProviderHealthStats?,
) {
    val active = AppModel.activeProvider()?.id == providerId
    val hasData = stats != null && stats.totalCalls > 0
    val score = stats?.healthScore ?: (if (provider?.loadError == null) 1f else 0f)

    val scoreColor = when {
        provider?.loadError != null -> MaterialTheme.colorScheme.error
        score >= 0.8f -> Color(0xFF4CAF50)
        score >= 0.5f -> Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.error
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = settingsRowContainer()),
        border = CardDefaults.outlinedCardBorder().copy(
            width = if (active) 1.5.dp else 1.dp,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(scoreColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                provider?.name ?: providerId,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        "当前生效",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                        // ID / 类型 / 版本整行显示，长了就换行 —— 不再靠省略号。
                        Text(
                            "ID: $providerId · 类型: ${provider?.type?.name ?: "UNKNOWN"} · 版本: ${provider?.version ?: "v1.0"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = scoreColor.copy(alpha = 0.14f),
                ) {
                    Text(
                        "${(score * 100).toInt()} 分",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                    )
                }
            }

            // 加载错误报警
            provider?.loadError?.let { err ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "加载异常: $err",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (stats == null || stats.totalCalls == 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "暂无调用记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val s = stats
                Spacer(Modifier.height(12.dp))

                val successRate = s.successCount.toFloat() / s.totalCalls
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("成功率", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(successRate * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = scoreColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { successRate },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                        color = scoreColor,
                        trackColor = scoreColor.copy(alpha = 0.15f),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniMetric("调用", "${s.totalCalls}")
                    MiniMetric("成功", "${s.successCount}", Color(0xFF4CAF50))
                    MiniMetric("失败", "${s.failCount}", if (s.failCount > 0) MaterialTheme.colorScheme.error else Color.Unspecified)
                    MiniMetric("警告", "${s.warningCount}", if (s.warningCount > 0) Color(0xFFFF9800) else Color.Unspecified)
                    MiniMetric("平均", "${s.avgResponseMs}ms")
                    MiniMetric("P95", "${s.p95ResponseMs}ms")
                }

                s.lastError?.let { lastErr ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            // 最近错误整串显示：截到 120 字只会在最关键的尾部（堆栈 / 原因）断掉。
                            Text(
                                "最近错误: $lastErr",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // 底层诊断信息 (PID / 端口 / 进程状态)
            val diag = provider?.getDiagnostics() ?: emptyMap()
            if (diag.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("底层诊断参数", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        diag.forEach { (k, v) ->
                            // 左右各占一半：诊断值（PID / 端口 / 路径）长短不定，靠
                            // SpaceBetween 会被长 key 挤到只剩一个字宽 —— 均分后两列都能换行显示全。
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    k,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    v,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (valueColor != Color.Unspecified) valueColor else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================== Tab 2: 方法统计 (Methods) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.methodsTabContent(
    records: List<ApiCallRecord>,
) {
    val operationStats = HealthMonitor.getStatsByOperation()

    if (operationStats.isEmpty()) {
        item(key = "methods_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("暂无操作统计数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        item(key = "methods_header") {
            Text(
                "取数操作调用性能（按 UnifiedMusicSource 操作名）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val sorted = operationStats.values.sortedByDescending { it.totalCalls }
        items(sorted, key = { "op_${it.operation}" }) { stat ->
            MethodStatCard(stat, records)
        }
    }
}

@Composable
internal fun MethodStatCard(
    stat: HealthMonitor.OperationStats,
    allRecords: List<ApiCallRecord>,
    initialExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val successRate = if (stat.totalCalls > 0) stat.successCount * 100f / stat.totalCalls else 0f

    val accentColor = when {
        stat.warningCount > 0 -> Color(0xFFFF9800)
        successRate >= 90f -> Color(0xFF4CAF50)
        successRate >= 50f -> Color(0xFFFFC107)
        else -> MaterialTheme.colorScheme.error
    }

    OutlinedCard(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.outlinedCardColors(containerColor = settingsRowContainer()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stat.operation, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${stat.totalCalls} 次 · 成功率 ${successRate.toInt()}% · 平均 ${stat.avgResponseMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (stat.failCount > 0) {
                    Text("失败 ${stat.failCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                }
                if (stat.warningCount > 0) {
                    Text("警告 ${stat.warningCount}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                    Spacer(Modifier.width(6.dp))
                }

                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

                    val recentCalls = remember(allRecords, stat.operation) {
                        allRecords.filter { it.operation == stat.operation }.takeLast(5).reversed()
                    }
                    if (recentCalls.isNotEmpty()) {
                        Text("最近调用样本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        recentCalls.forEach { record ->
                            CallRecordItem(record, compact = true)
                        }
                    }
                }
            }
        }
    }
}

// ======================== Tab 3: 调用日志 (Logs) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.logsTabContent(
    records: List<ApiCallRecord>,
) {
    item(key = "logs_summary") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("调用历史（最近 ${records.size} 条）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    if (records.isEmpty()) {
        item(key = "logs_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("暂无调用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        val reversed = records.reversed()
        itemsIndexed(reversed, key = { index, r -> "log_${r.timestamp}_$index" }) { index, record ->
            CallRecordItem(record, compact = false)
        }
    }
}

@Composable
internal fun CallRecordItem(
    record: ApiCallRecord,
    compact: Boolean,
    initialExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initialExpanded) }

    val statusIcon = when (record.level) {
        HealthLevel.OK -> "✅"
        HealthLevel.WARNING -> "⚠️"
        HealthLevel.ERROR -> "❌"
    }

    val timeStr = runCatching {
        val t = localDateTimeOf(record.timestamp)
        "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    }.getOrDefault("--:--:--")

    Surface(
        onClick = { if (!compact) expanded = !expanded },
        shape = MaterialTheme.shapes.extraSmall,
        color = settingsRowContainer(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // 概要行：状态图标 / 时间 / 音源 / 操作名 / 耗时。
            // 操作名用 weight(1f) 吃剩余宽度 —— 它**没有** maxLines，放不下就折行，
            // 所以长操作名也是整串可见（这是本页「一律不截断」的落点之一）。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(statusIcon, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(timeStr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(record.providerId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(record.operation, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text("${record.durationMs}ms", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (!compact && expanded) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "结果: " + when (record.level) {
                                HealthLevel.OK -> "成功"
                                HealthLevel.WARNING -> "音源不支持该操作"
                                HealthLevel.ERROR -> "调用失败"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (record.level == HealthLevel.OK)
                                MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        record.errorMessage?.let { msg ->
                            Text(
                                "错误信息: $msg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ======================== Tab 4: API 测试 (Api Test) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.statusTabContent() {
    val providers = AppModel.availableProviders()
    val activeProvider = AppModel.activeProvider()

    item(key = "status_title") {
        Text("已注册与已加载音源清单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }

    if (providers.isEmpty()) {
        item(key = "status_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("暂无已注册音源", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        items(providers, key = { "prov_status_${it.id}" }) { provider ->
            val isActive = provider.id == activeProvider?.id
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.outlinedCardColors(containerColor = settingsRowContainer()),
                border = CardDefaults.outlinedCardBorder().copy(width = if (isActive) 1.5.dp else 1.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 名称 + 徽章吃掉剩余宽度：名字再长也只换行，不会把右侧按钮挤没。
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("当前生效", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }

                        if (!isActive) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { AppModel.switchOrReport(provider) },
                            ) {
                                Text("切换至此音源", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text("ID: ${provider.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("类型: ${provider.type.name}  ·  版本: ${provider.version}  ·  API 版本: v${provider.apiVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 接口代际标签（manifest 的 `apiVersionTag`）：没声明就不占一行，
                    // 老包与以后的内置源诊断页保持原样。
                    provider.apiVersionTag?.let { tag ->
                        Text("接口代际: $tag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    provider.loadError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("启动/加载失败: $err", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }

                    val diag = provider.getDiagnostics()
                    if (diag.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("运行时底层诊断", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                diag.forEach { (k, v) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            k,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            v,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                            fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.End,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================== 辅助函数 ========================

