package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.ApiCallRecord
import cp.player.app.AppModel
import cp.player.app.HealthLevel
import cp.player.core.monitor.HealthMonitor.ResponseWarning
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.popOrNotify
import cp.player.core.monitor.HealthMonitor
import cp.player.core.provider.BackendProvider
import cp.player.core.util.currentTimeMillis
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private enum class HealthTab(val label: String, val icon: ImageVector) {
    OVERVIEW("概览", Icons.Default.Dashboard),
    METHODS("方法统计", Icons.Default.Analytics),
    LOGS("调用日志", Icons.AutoMirrored.Filled.List),
    TEST("API 测试", Icons.AutoMirrored.Filled.Send),
    API_MAP("apiMap", Icons.Default.Map),
    STATUS("Provider 状态", Icons.Default.Info),
}

private val prettyJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
}

private val allKnownMethods = listOf(
    "login/qr/key", "login/qr/create", "login/qr/check",
    "login", "login/cellphone", "captcha/sent", "logout",
    "register/anonimous", "login/status",
    "user/playlist", "user/playlist/create", "user/playlist/collect",
    "user/detail", "user/cloud", "likelist", "like",
    "recommend/songs", "recommend/resource", "recommend/songs/dislike",
    "playlist/detail", "playlist/track/all", "playlist/tracks",
    "playlist/create", "playlist/delete", "playlist/subscribe",
    "album", "artist/detail", "artist/songs", "artist/album",
    "cloudsearch", "search/hot/detail", "search/suggest",
    "song/url/v1/302", "song/url/v1", "song/download/url/v1",
    "song/detail", "personal_fm", "playmode/intelligence/list",
    "lyric/new",
    "comment/music", "comment/playlist", "comment/album",
    "comment/floor", "comment/like", "comment",
    "pl/count", "msg/recentcontact", "msg/private",
    "msg/private/history", "msg/private/mark/read", "send/text",
)

/**
 * 诊断与健康监控页面：提供 6 大功能模块（概览、方法统计、调用日志、API 测试、apiMap、Provider 状态）。
 */
class HealthScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val records by AppModel.health.recordsFlow.collectAsState()
        val overall by AppModel.health.overallLevelFlow.collectAsState()

        var currentTab by remember { mutableStateOf(HealthTab.OVERVIEW) }
        var rawJsonDialogContent by remember { mutableStateOf<String?>(null) }
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
                        logsTabContent(records, onShowRawJson = { rawJsonDialogContent = it })
                    }
                    HealthTab.TEST -> {
                        testTabContent()
                    }
                    HealthTab.API_MAP -> {
                        apiMapTabContent()
                    }
                    HealthTab.STATUS -> {
                        statusTabContent()
                    }
                }
            }
        }

        // 查看原始 JSON 弹窗
        rawJsonDialogContent?.let { raw ->
            AlertDialog(
                onDismissRequest = { rawJsonDialogContent = null },
                title = { Text("API 原始返回内容") },
                text = {
                    Box(modifier = Modifier.heightIn(max = 420.dp)) {
                        SelectionContainer {
                            Text(
                                text = formatJsonString(raw),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { rawJsonDialogContent = null }) { Text("关闭") }
                },
            )
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

@Composable
private fun GlobalHealthCard(records: List<ApiCallRecord>, overall: HealthLevel) {
    val totalCalls = records.size
    val totalSuccess = records.count { it.success }
    val totalFail = totalCalls - totalSuccess
    val totalFallbacks = records.count { it.wasFallback }
    val totalWarnings = records.count { it.responseWarnings.isNotEmpty() }
    val globalSuccessRate = if (totalCalls > 0) totalSuccess * 100f / totalCalls else 100f
    val avgResponse = if (totalCalls > 0) records.sumOf { it.durationMs } / totalCalls else 0L

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
                Column {
                    Text("系统综合运行状态", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "共 $totalCalls 次调用 · 平均响应 ${avgResponse}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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
                GlobalMetricChip("警告", "$totalWarnings", Color(0xFFFF9800), Icons.Default.Warning)
                GlobalMetricChip("回退", "$totalFallbacks", Color(0xFFFF9800), Icons.Default.SwapHoriz)
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
private fun ProviderHealthCard(
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(scoreColor),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
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
                        Text(
                            "ID: $providerId · 类型: ${provider?.type?.name ?: "UNKNOWN"} · 版本: ${provider?.version ?: "v1.0"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

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
                            Text(
                                "最近错误: ${lastErr.take(120)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2,
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Medium)
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
    val methodStats = HealthMonitor.getStatsByMethod()

    if (methodStats.isEmpty()) {
        item(key = "methods_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("暂无方法统计数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        item(key = "methods_header") {
            Text(
                "API 方法调用性能与警告分布",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val sorted = methodStats.values.sortedByDescending { it.totalCalls }
        items(sorted, key = { "method_${it.method}" }) { stat ->
            MethodStatCard(stat, records)
        }
    }
}

@Composable
private fun MethodStatCard(stat: HealthMonitor.MethodStats, allRecords: List<ApiCallRecord>) {
    var expanded by remember { mutableStateOf(false) }
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
                    Text(stat.method, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
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
                if (stat.fallbackCount > 0) {
                    Text("回退 ${stat.fallbackCount}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
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
                    if (stat.warningTypes.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Color(0xFFFF9800).copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text("警告类型分布", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                                Spacer(Modifier.height(4.dp))
                                stat.warningTypes.forEach { (warning, count) ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(formatWarning(warning), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
                                        Text("×$count", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                                    }
                                }
                            }
                        }
                    }

                    val recentCalls = remember(allRecords, stat.method) {
                        allRecords.filter { it.method == stat.method }.takeLast(5).reversed()
                    }
                    if (recentCalls.isNotEmpty()) {
                        Text("最近调用样本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        recentCalls.forEach { record ->
                            CallRecordItem(record, compact = true, onShowRawJson = {})
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
    onShowRawJson: (String) -> Unit,
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
            CallRecordItem(record, compact = false, onShowRawJson = onShowRawJson)
        }
    }
}

@Composable
private fun CallRecordItem(
    record: ApiCallRecord,
    compact: Boolean,
    onShowRawJson: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val statusIcon = when {
        record.responseWarnings.isNotEmpty() && record.success -> "⚠️"
        record.success && !record.wasFallback -> "✅"
        record.success && record.wasFallback -> "↩️"
        !record.success && record.wasFallback -> "⚠️"
        else -> "❌"
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(statusIcon, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Text(timeStr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(record.providerId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(record.method, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                if (record.responseWarnings.isNotEmpty()) {
                    Text("${record.responseWarnings.size}⚠", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                    Spacer(Modifier.width(6.dp))
                }
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
                        record.responseCode?.let { code ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("HTTP 状态码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$code", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Bold)
                            }
                        }
                        record.errorCode?.let { errCode ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("业务错误码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                Text("$errCode", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                        record.errorMessage?.let { msg ->
                            Text(
                                "错误信息: $msg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (record.responseWarnings.isNotEmpty()) {
                            Text("兼容性警告:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                            record.responseWarnings.forEach { w ->
                                Text("• ${formatWarning(w)}" + (if (record.expectedField != null) " (缺少字段: ${record.expectedField})" else ""), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
                            }
                        }
                        if (record.wasFallback && record.fallbackFrom != null) {
                            Text("回退来源: ${record.fallbackFrom}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9800))
                        }

                        val raw = record.rawResponse
                        if (raw != null) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { onShowRawJson(raw) },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("查看原始 JSON", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================== Tab 4: API 测试 (Api Test) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.testTabContent() {
    item(key = "api_test_panel") {
        ApiTestRunner()
    }
}

@Composable
private fun ApiTestRunner() {
    val coroutineScope = rememberCoroutineScope()
    var selectedMethod by remember { mutableStateOf(allKnownMethods[0]) }
    var paramsText by remember { mutableStateOf("{\n  \"id\": \"\"\n}") }
    var responseText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var responseTime by remember { mutableStateOf(0L) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择要测试的 API 方法", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)

        // 常用方法快速芯片
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val quickMethods = listOf("user/playlist", "song/url/v1", "song/detail", "playlist/detail", "cloudsearch", "lyric/new", "login/status")
            quickMethods.forEach { method ->
                FilterChip(
                    selected = selectedMethod == method,
                    onClick = { selectedMethod = method },
                    label = { Text(method, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        OutlinedTextField(
            value = selectedMethod,
            onValueChange = { selectedMethod = it },
            label = { Text("API 路径") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )

        Text("请求参数 (JSON 字典)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = paramsText,
            onValueChange = { paramsText = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        Button(
            onClick = {
                isLoading = true
                errorMessage = null
                responseText = ""
                coroutineScope.launch {
                    val start = currentTimeMillis()
                    try {
                        val params = parseJsonParams(paramsText)
                        val result = AppModel.backend.musicApi.callApi(selectedMethod, params)
                        responseTime = currentTimeMillis() - start
                        responseText = prettyJson.encodeToString(JsonElement.serializer(), result)
                    } catch (e: Throwable) {
                        responseTime = currentTimeMillis() - start
                        errorMessage = e.message ?: e.toString()
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            shape = MaterialTheme.shapes.small,
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("请求执行中...")
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("发送测试请求")
            }
        }

        if (errorMessage != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("调用失败 (${responseTime}ms):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(errorMessage ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (responseText.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("返回结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${responseTime}ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(containerColor = settingsRowContainer()),
            ) {
                SelectionContainer {
                    Text(
                        responseText,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

// ======================== Tab 5: apiMap ========================

private fun androidx.compose.foundation.lazy.LazyListScope.apiMapTabContent() {
    val activeProvider = AppModel.activeProvider()

    if (activeProvider == null) {
        item(key = "apimap_no_provider") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("当前无生效的 Provider", color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    val apiMap = activeProvider.apiMap ?: emptyMap()
    val mappedCount = allKnownMethods.count { apiMap.containsKey(it) }
    val unsupportedCount = allKnownMethods.count { apiMap[it].equals("unsupported", ignoreCase = true) }
    val passthroughCount = allKnownMethods.size - mappedCount

    item(key = "apimap_header_card") {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.outlinedCardColors(containerColor = settingsRowContainer()),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("当前生效音源: ${activeProvider.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("ID: ${activeProvider.id}  ·  类型: ${activeProvider.type}  ·  版本: ${activeProvider.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("apiMap 映射条目数: ${apiMap.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("已映射: $mappedCount", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.errorContainer) {
                        Text("不支持: $unsupportedCount", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("透传: $passthroughCount", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    item(key = "apimap_list_title") {
        Spacer(Modifier.height(6.dp))
        Text("全量标准 API 映射路由表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }

    items(allKnownMethods, key = { "map_method_$it" }) { method ->
        val mapped = apiMap[method]
        val isUnsupported = mapped.equals("unsupported", ignoreCase = true)
        val isMapped = mapped != null && !isUnsupported

        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = settingsRowContainer(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(method, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    if (isMapped) {
                        Text("→ $mapped", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = when {
                        isUnsupported -> MaterialTheme.colorScheme.errorContainer
                        isMapped -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = when {
                            isUnsupported -> "不支持"
                            isMapped -> "已映射"
                            else -> "透传"
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            isUnsupported -> MaterialTheme.colorScheme.onErrorContainer
                            isMapped -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// ======================== Tab 6: Provider 状态 (Provider Status) ========================

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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("当前生效", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }

                        if (!isActive) {
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
                    Text("apiMap 映射: ${provider.apiMap?.size ?: 0} 条规则", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
                                Text("启动/加载失败: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
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
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Medium)
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

private fun formatWarning(warning: ResponseWarning): String = when (warning) {
    ResponseWarning.MISSING_CODE -> "缺少 code 字段"
    ResponseWarning.UNEXPECTED_CODE -> "异常响应码"
    ResponseWarning.MISSING_DATA_FIELD -> "缺少数据字段"
    ResponseWarning.EMPTY_DATA_ARRAY -> "空数据数组"
    ResponseWarning.EMPTY_DATA_OBJECT -> "空数据对象"
    ResponseWarning.MALFORMED_RESPONSE -> "响应格式异常"
    ResponseWarning.UNSUPPORTED_BY_PROVIDER -> "Provider 不支持"
    ResponseWarning.SLOW_RESPONSE -> "响应过慢(>5s)"
}

private fun parseJsonParams(json: String): Map<String, String> {
    return try {
        val element = prettyJson.parseToJsonElement(json)
        if (element is JsonObject) {
            element.mapValues { (_, v) ->
                if (v is JsonPrimitive) v.content else v.toString()
            }
        } else {
            emptyMap()
        }
    } catch (_: Throwable) {
        emptyMap()
    }
}

private fun formatJsonString(raw: String): String {
    return try {
        val element = prettyJson.parseToJsonElement(raw)
        prettyJson.encodeToString(JsonElement.serializer(), element)
    } catch (_: Throwable) {
        raw
    }
}
