package cp.player.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.ui.navigation.RoutePage
import cp.player.app.ApiCallRecord
import cp.player.app.AppModel
import cp.player.app.HealthLevel
import cp.player.app.diagnostics.AppDiagnostics
import cp.player.app.diagnostics.DiagnosticCategory
import cp.player.app.diagnostics.DiagnosticItem
import cp.player.app.diagnostics.DiagnosticReport
import cp.player.app.diagnostics.DiagnosticStatus
import cp.player.app.diagnostics.ProbeOperation
import cp.player.app.diagnostics.ProbeResult
import cp.player.app.i18n.HealthStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpConfirmHost
import cp.player.app.ui.component.CpConnectedChoice
import cp.player.app.ui.component.CpLinearProgress
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpToggleChip
import cp.player.app.ui.component.CpWavyProgressIndeterminate
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.rememberConfirmState
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.navigation.LocalAppNavigator
import cp.player.app.ui.theme.cpNestedSurface
import cp.player.app.ui.theme.cpSemanticColors
import cp.player.app.ui.util.UiEvents
import cp.player.core.monitor.HealthMonitor
import cp.player.core.provider.BackendProvider
import cp.player.core.util.localDateTimeOf
import kotlinx.coroutines.launch

// ⚠️ 本页一律用 material3 的 `Text`，**不要**换成 `CpText`。
//
// `CpText` 的默认档是「单行 + 尾部省略号 + 悬停浮出全文」（见 `CpText.kt`），
// 而诊断页读的就是**原始数据本身**：音源 ID / 操作名 / 错误串 /
// 底层诊断键值都会超过一行。用 `CpText` 的话，用户看到的是一串省略号，
// 只有把鼠标悬上去（触屏根本没有 hover）才读得到 —— 等于把要排查的信息藏起来。
// 所以这一页的每个 `Text` 都**不传** `maxLines` / `overflow`（= 不截断，自然换行），
// 该独占一行的（错误串、原始 JSON、诊断详情）另加 `Modifier.fillMaxWidth()`。

private enum class HealthTab(val icon: ImageVector) {
    OVERVIEW(Icons.Default.Dashboard),
    PROBE(Icons.Default.BugReport),
    METHODS(Icons.Default.Analytics),
    LOGS(Icons.AutoMirrored.Filled.List),
    STATUS(Icons.Default.Info),
    ;

    fun label(s: HealthStrings): String = when (this) {
        OVERVIEW -> s.tabOverview
        PROBE -> s.tabProbe
        METHODS -> s.tabMethods
        LOGS -> s.tabLogs
        STATUS -> s.tabStatus
    }
}

/**
 * 诊断与健康监控页面：系统体检、主动接口测试、排障工具箱、概览指标、方法统计、调用日志、Provider 状态。
 */
class HealthScreen : RoutePage {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalAppNavigator.current
        val records by AppModel.health.recordsFlow.collectAsState()
        val overall by AppModel.health.overallLevelFlow.collectAsState()
        val s = cpStrings().health

        var currentTab by remember { mutableStateOf(HealthTab.OVERVIEW) }
        val confirm = rememberConfirmState()

        CpRouteScaffold(
            title = s.screenTitle,
            onBack = { navigator.pop() },
            topBarActions = listOf(
                TopBarAction(
                    icon = { Icon(Icons.Filled.DeleteSweep, s.clearLogAction) },
                    onClick = {
                        if (records.isNotEmpty()) {
                            confirm.request(
                                title = s.clearTitle,
                                message = s.clearMessage(records.size),
                                confirmLabel = s.clearConfirm,
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
                    // M3E 连通切换导航栏（支持窄屏横向滚动）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        CpConnectedChoice(
                            selectedIndex = currentTab.ordinal,
                            labels = HealthTab.entries.map { it.label(s) },
                            icons = HealthTab.entries.map { it.icon },
                            onSelect = { idx -> currentTab = HealthTab.entries[idx] },
                        )
                    }
                },
            ) {
                when (currentTab) {
                    HealthTab.OVERVIEW -> {
                        overviewTabContent(records, overall, s, confirm)
                    }
                    HealthTab.PROBE -> {
                        probeTabContent(s)
                    }
                    HealthTab.METHODS -> {
                        methodsTabContent(records, s)
                    }
                    HealthTab.LOGS -> {
                        logsTabContent(records, s)
                    }
                    HealthTab.STATUS -> {
                        statusTabContent(s)
                    }
                }
            }
        }

        CpConfirmHost(confirm)
    }
}

// ======================== Tab 1: 概览与自检 (Overview & Diagnostics) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.overviewTabContent(
    records: List<ApiCallRecord>,
    overall: HealthLevel,
    s: HealthStrings,
    confirm: cp.player.app.ui.component.CpConfirmState,
) {
    // 1. 系统主动诊断与全面体检卡片
    item(key = "overview_diagnostics") {
        SystemDiagnosticSection(s)
    }

    // 2. 排障工具箱
    item(key = "overview_toolkit") {
        Spacer(Modifier.height(8.dp))
        TroubleshootingToolkitCard(s, confirm)
    }

    // 3. 全局调用历史统计指标
    item(key = "overview_global") {
        Spacer(Modifier.height(8.dp))
        GlobalHealthCard(records, overall, s)
    }

    // 4. 音源健康评分列表标题
    item(key = "overview_provider_title") {
        Spacer(Modifier.height(6.dp))
        Text(
            s.providerHealthSection,
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
                Text(s.noLoadedProviders, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                s = s,
            )
        }
    }
}

@Composable
private fun SystemDiagnosticSection(s: HealthStrings) {
    val report by AppDiagnostics.report.collectAsState()
    val isRunning by AppDiagnostics.isRunning.collectAsState()
    val progressMessage by AppDiagnostics.progressMessage.collectAsState()
    val scope = rememberCoroutineScope()

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
                    Text(
                        s.diagnosticTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        s.diagnosticSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            AppDiagnostics.runFullDiagnostics()
                        }
                    },
                    enabled = !isRunning,
                ) {
                    if (isRunning) {
                        CpLoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(s.runningDiagnostics)
                    } else {
                        Icon(Icons.Filled.Speed, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (report != null) s.reRunDiagnostics else s.runDiagnostics)
                    }
                }
            }

            if (isRunning) {
                Spacer(Modifier.height(14.dp))
                CpWavyProgressIndeterminate(
                    modifier = Modifier.fillMaxWidth(),
                )
                progressMessage?.let { msg ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(14.dp))

            val currentReport = report
            if (currentReport == null && !isRunning) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = cpNestedSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                s.diagnosticNotRun,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                s.diagnosticNotRunHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (currentReport != null) {
                // 评分与状态指示
                val semanticColors = cpSemanticColors()
                val scoreColor = when (currentReport.overallStatus) {
                    DiagnosticStatus.OK -> semanticColors.success
                    DiagnosticStatus.WARNING -> semanticColors.warning
                    DiagnosticStatus.ERROR -> MaterialTheme.colorScheme.error
                    DiagnosticStatus.SKIPPED -> MaterialTheme.colorScheme.outline
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(scoreColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${currentReport.score}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when (currentReport.overallStatus) {
                                DiagnosticStatus.OK -> s.diagnosticHealthy
                                DiagnosticStatus.WARNING -> s.diagnosticWarning
                                DiagnosticStatus.ERROR -> s.diagnosticError
                                DiagnosticStatus.SKIPPED -> s.diagnosticNotRun
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            currentReport.systemSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 明细项列表
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    currentReport.items.forEach { item ->
                        DiagnosticItemCard(item, s)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticItemCard(item: DiagnosticItem, s: HealthStrings) {
    var expanded by remember { mutableStateOf(false) }

    val semanticColors = cpSemanticColors()
    val statusColor = when (item.status) {
        DiagnosticStatus.OK -> semanticColors.success
        DiagnosticStatus.WARNING -> semanticColors.warning
        DiagnosticStatus.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticStatus.SKIPPED -> MaterialTheme.colorScheme.outline
    }

    val statusIcon = when (item.status) {
        DiagnosticStatus.OK -> Icons.Filled.CheckCircle
        DiagnosticStatus.WARNING -> Icons.Filled.Warning
        DiagnosticStatus.ERROR -> Icons.Filled.Error
        DiagnosticStatus.SKIPPED -> Icons.Filled.Info
    }

    OutlinedCard(
        onClick = {
            if (item.details != null || item.suggestion != null) {
                expanded = !expanded
            }
        },
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.outlinedCardColors(containerColor = cpNestedSurface()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        item.latencyMs?.let { latency ->
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = statusColor.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    "${latency}ms",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = statusColor,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (item.details != null || item.suggestion != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.details?.let { detail ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = cpNestedSurface(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    s.diagnosticDetailsLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(2.dp))
                                SelectionContainer {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    item.suggestion?.let { sugg ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    s.diagnosticSuggestionLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    sugg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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
private fun TroubleshootingToolkitCard(
    s: HealthStrings,
    confirm: cp.player.app.ui.component.CpConfirmState,
) {
    val clipboardManager = LocalClipboardManager.current
    val report by AppDiagnostics.report.collectAsState()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = settingsRowContainer()),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                s.toolkitTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 1. 复制完整诊断报告
                OutlinedButton(
                    onClick = {
                        val currentReport = report
                        if (currentReport != null) {
                            val md = AppDiagnostics.formatDiagnosticReportMarkdown(currentReport)
                            clipboardManager.setText(AnnotatedString(md))
                            UiEvents.notify(s.copyReportDone)
                        } else {
                            UiEvents.notify(s.diagnosticNotRunHint)
                        }
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.copyReport, style = MaterialTheme.typography.labelSmall)
                }

                // 2. 重新初始化生效音源
                OutlinedButton(
                    onClick = {
                        val ok = AppDiagnostics.reloadActiveProvider()
                        if (ok) {
                            UiEvents.notify(s.reloadProviderDone)
                        } else {
                            UiEvents.notify(s.noLoadedProviders)
                        }
                    },
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.reloadActiveProvider, style = MaterialTheme.typography.labelSmall)
                }

                // 3. 清理流缓存
                OutlinedButton(
                    onClick = {
                        confirm.request(
                            title = s.clearStreamCacheTitle,
                            message = s.clearStreamCacheMessage,
                            confirmLabel = s.clearStreamCacheAction,
                            onConfirm = {
                                val count = AppDiagnostics.clearStreamCache()
                                UiEvents.notify("${s.clearStreamCacheDone} ($count)")
                            },
                        )
                    },
                ) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.clearStreamCacheAction, style = MaterialTheme.typography.labelSmall)
                }

                // 4. 清理图片封面缓存
                OutlinedButton(
                    onClick = {
                        confirm.request(
                            title = s.clearImageCacheTitle,
                            message = s.clearImageCacheMessage,
                            confirmLabel = s.clearImageCacheAction,
                            onConfirm = {
                                val ok = AppDiagnostics.clearCoverImageCache()
                                if (ok) {
                                    UiEvents.notify(s.clearImageCacheDone)
                                }
                            },
                        )
                    },
                ) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.clearImageCacheAction, style = MaterialTheme.typography.labelSmall)
                }

                // 5. 重置播放引擎
                OutlinedButton(
                    onClick = {
                        AppDiagnostics.resetAudioPlaybackGate()
                        UiEvents.notify(s.resetAudioEngineDone)
                    },
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.resetAudioEngineAction, style = MaterialTheme.typography.labelSmall)
                }
            }
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
internal fun GlobalHealthCard(records: List<ApiCallRecord>, overall: HealthLevel, s: HealthStrings) {
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

    val semanticColors = cpSemanticColors()
    val accentColor = when (overall) {
        HealthLevel.OK -> semanticColors.success
        HealthLevel.WARNING -> semanticColors.warning
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
                    Text(s.systemStatus, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s.callsSummary(totalCalls, avgResponse),
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
                                HealthLevel.OK -> s.levelOk
                                HealthLevel.WARNING -> s.levelWarning
                                HealthLevel.ERROR -> s.levelError
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
                GlobalMetricChip(s.success, "$totalSuccess", semanticColors.success, Icons.Default.CheckCircle)
                GlobalMetricChip(s.failure, "$totalFail", MaterialTheme.colorScheme.error, Icons.Default.Error)
                GlobalMetricChip(s.unsupported, "$totalUnsupported", semanticColors.warning, Icons.Default.Warning)
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
    s: HealthStrings,
) {
    val active = AppModel.activeProvider()?.id == providerId
    val score = stats?.healthScore ?: (if (provider?.loadError == null) 1f else 0f)

    val semanticColors = cpSemanticColors()
    val scoreColor = when {
        provider?.loadError != null -> MaterialTheme.colorScheme.error
        score >= 0.8f -> semanticColors.success
        score >= 0.5f -> semanticColors.warning
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
                                        s.activeNow,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                        Text(
                            s.providerMeta(
                                id = providerId,
                                type = provider?.type?.name ?: "UNKNOWN",
                                version = provider?.version ?: "v1.0",
                            ),
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
                        s.score((score * 100).toInt()),
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
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.loadError(err),
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
                    s.noCalls,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val stat = stats
                Spacer(Modifier.height(12.dp))
                val successRate = stat.successCount.toFloat() / stat.totalCalls
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.successRate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(successRate * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = scoreColor)
                    }
                    Spacer(Modifier.height(4.dp))
                    CpLinearProgress(
                        progress = successRate,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                        color = scoreColor,
                        trackColor = scoreColor.copy(alpha = 0.15f),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniMetric(s.metricCalls, "${stat.totalCalls}")
                    MiniMetric(s.success, "${stat.successCount}", semanticColors.success)
                    MiniMetric(s.failure, "${stat.failCount}", if (stat.failCount > 0) MaterialTheme.colorScheme.error else Color.Unspecified)
                    MiniMetric(s.metricWarning, "${stat.warningCount}", if (stat.warningCount > 0) semanticColors.warning else Color.Unspecified)
                    MiniMetric(s.metricAverage, "${stat.avgResponseMs}ms")
                    MiniMetric("P95", "${stat.p95ResponseMs}ms")
                }

                stat.lastError?.let { lastErr ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                s.recentError(lastErr),
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
                    color = cpNestedSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(s.diagnostics, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
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

// ======================== Tab 2: 接口测试 (API Probe Workbench) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.probeTabContent(
    s: HealthStrings,
) {
    item(key = "probe_workbench") {
        ProbeWorkbenchCard(s)
    }
}

@Composable
private fun ProbeWorkbenchCard(s: HealthStrings) {
    val providers = AppModel.availableProviders()
    val activeProvider = AppModel.activeProvider()
    var selectedOp by remember { mutableStateOf(ProbeOperation.SEARCH) }
    var paramText by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("standard") }

    var isRunning by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.elevatedCardColors(containerColor = settingsRowContainer()),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                s.probeTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                s.probeSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(14.dp))

            // 1. 目标音源与即时切换
            if (providers.isNotEmpty()) {
                Text(
                    s.probeTargetProvider,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { prov ->
                        val isCurrent = prov.id == activeProvider?.id
                        CpToggleChip(
                            checked = isCurrent,
                            onCheckedChange = {
                                if (!isCurrent) {
                                    val ok = AppModel.switchOrReport(prov)
                                    if (ok) {
                                        UiEvents.notify("${s.probeSwitchDone}: ${prov.name}")
                                    }
                                }
                            },
                            label = prov.name,
                            icon = if (isCurrent) Icons.Filled.CheckCircle else null,
                            containerColor = cpNestedSurface(),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // 2. 测试接口选择
            Text(
                s.probeOperation,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProbeOperation.entries.forEach { op ->
                    val label = when (op) {
                        ProbeOperation.SEARCH -> s.probeOpSearch
                        ProbeOperation.TRACK_DETAIL -> s.probeOpDetail
                        ProbeOperation.STREAM_URL -> s.probeOpStream
                        ProbeOperation.LYRIC -> s.probeOpLyric
                        ProbeOperation.RANKINGS -> s.probeOpRankings
                        ProbeOperation.PLAYLISTS -> s.probeOpPlaylists
                    }
                    val selected = selectedOp == op
                    CpToggleChip(
                        checked = selected,
                        onCheckedChange = {
                            selectedOp = op
                            if (op == ProbeOperation.SEARCH && paramText.isEmpty()) {
                                paramText = "周杰伦"
                            }
                        },
                        label = label,
                        containerColor = cpNestedSurface(),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 3. 参数输入
            when (selectedOp) {
                ProbeOperation.SEARCH -> {
                    OutlinedTextField(
                        value = paramText,
                        onValueChange = { paramText = it },
                        label = { Text(s.probeInputKeywordLabel) },
                        placeholder = { Text("周杰伦") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                ProbeOperation.TRACK_DETAIL, ProbeOperation.STREAM_URL, ProbeOperation.LYRIC -> {
                    OutlinedTextField(
                        value = paramText,
                        onValueChange = { paramText = it },
                        label = { Text(s.probeInputMediaIdLabel) },
                        placeholder = { Text("186016 或 netease://song/186016") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                ProbeOperation.RANKINGS, ProbeOperation.PLAYLISTS -> {
                    // 无需必填参数
                }
            }

            // 音质档位选择 (仅流直链测试有效)
            if (selectedOp == ProbeOperation.STREAM_URL) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(s.probeQualityLabel, style = MaterialTheme.typography.labelSmall)
                    listOf("standard", "exhigh", "lossless").forEach { q ->
                        CpToggleChip(
                            checked = quality == q,
                            onCheckedChange = { quality = q },
                            label = q,
                            containerColor = cpNestedSurface(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 4. 执行按钮
            Button(
                onClick = {
                    isRunning = true
                    probeResult = null
                    scope.launch {
                        val res = AppDiagnostics.probeOperation(selectedOp, paramText, quality)
                        probeResult = res
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRunning) {
                    CpLoadingIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(s.probeExecuting)
                } else {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.probeExecute)
                }
            }

            Spacer(Modifier.height(14.dp))

            // 5. 响应结果展示
            val res = probeResult
            if (res != null) {
                val semanticColors = cpSemanticColors()
                val resStatusColor = when (res) {
                    is ProbeResult.Success -> semanticColors.success
                    is ProbeResult.Unsupported -> semanticColors.warning
                    is ProbeResult.Error -> MaterialTheme.colorScheme.error
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = cpNestedSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
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
                                        .background(resStatusColor),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (res) {
                                        is ProbeResult.Success -> s.probeSuccess
                                        is ProbeResult.Unsupported -> s.probeUnsupported
                                        is ProbeResult.Error -> s.probeFailed
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = resStatusColor,
                                )
                            }

                            val duration = when (res) {
                                is ProbeResult.Success -> res.durationMs
                                is ProbeResult.Unsupported -> res.durationMs
                                is ProbeResult.Error -> res.durationMs
                            }
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = resStatusColor.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    "${duration}ms",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = resStatusColor,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        val summary = when (res) {
                            is ProbeResult.Success -> res.summary
                            is ProbeResult.Unsupported -> res.message
                            is ProbeResult.Error -> res.error
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        // 搜索结果快捷联动测试
                        if (res is ProbeResult.Success && res.songs.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "采样歌曲快捷接口探测联动:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                res.songs.take(4).forEach { song ->
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = cpNestedSurface(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    song.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    "${song.artist} [${song.id}]",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        selectedOp = ProbeOperation.STREAM_URL
                                                        paramText = song.id
                                                        isRunning = true
                                                        scope.launch {
                                                            probeResult = AppDiagnostics.probeOperation(
                                                                ProbeOperation.STREAM_URL,
                                                                song.id,
                                                                quality,
                                                            )
                                                            isRunning = false
                                                        }
                                                    },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Text(s.probeSearchQuickStream, style = MaterialTheme.typography.labelSmall)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        selectedOp = ProbeOperation.LYRIC
                                                        paramText = song.id
                                                        isRunning = true
                                                        scope.launch {
                                                            probeResult = AppDiagnostics.probeOperation(
                                                                ProbeOperation.LYRIC,
                                                                song.id,
                                                            )
                                                            isRunning = false
                                                        }
                                                    },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Text(s.probeSearchQuickLyric, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val details = when (res) {
                            is ProbeResult.Success -> res.details
                            is ProbeResult.Unsupported -> null
                            is ProbeResult.Error -> null
                        }

                        if (!details.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    s.diagnosticDetailsLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(details))
                                        UiEvents.notify(s.probeResultCopied)
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s.probeCopyResult, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            SelectionContainer {
                                Text(
                                    details,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            } else if (!isRunning) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = cpNestedSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            s.probeEmptyPrompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ======================== Tab 3: 方法统计 (Methods) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.methodsTabContent(
    records: List<ApiCallRecord>,
    s: HealthStrings,
) {
    val operationStats = HealthMonitor.getStatsByOperation()

    if (operationStats.isEmpty()) {
        item(key = "methods_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text(s.noOperationStats, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        item(key = "methods_header") {
            Text(
                s.operationStatsHeader,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val sorted = operationStats.values.sortedByDescending { it.totalCalls }
        items(sorted, key = { "op_${it.operation}" }) { stat ->
            MethodStatCard(stat, records, s)
        }
    }
}

@Composable
internal fun MethodStatCard(
    stat: HealthMonitor.OperationStats,
    allRecords: List<ApiCallRecord>,
    s: HealthStrings,
    initialExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val successRate = if (stat.totalCalls > 0) stat.successCount * 100f / stat.totalCalls else 0f

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
                        s.operationSummary(stat.totalCalls, successRate.toInt(), stat.avgResponseMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (stat.failCount > 0) {
                    Text(s.failCount(stat.failCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                }
                if (stat.warningCount > 0) {
                    Text(s.warningCount(stat.warningCount), style = MaterialTheme.typography.labelSmall, color = cpSemanticColors().warning)
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
                        Text(s.recentSamples, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        recentCalls.forEach { record ->
                            CallRecordItem(record, compact = true, s = s)
                        }
                    }
                }
            }
        }
    }
}

// ======================== Tab 4: 调用日志 (Logs) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.logsTabContent(
    records: List<ApiCallRecord>,
    s: HealthStrings,
) {
    item(key = "logs_filter_chips") {
        LogsFilterHeader(records, s)
    }
}

private enum class LogFilter { ALL, ERRORS, WARNINGS, SUCCESS }

@Composable
private fun LogsFilterHeader(records: List<ApiCallRecord>, s: HealthStrings) {
    var activeFilter by remember { mutableStateOf(LogFilter.ALL) }

    val filteredRecords = remember(records, activeFilter) {
        val base = records.reversed()
        when (activeFilter) {
            LogFilter.ALL -> base
            LogFilter.ERRORS -> base.filter { it.level == HealthLevel.ERROR }
            LogFilter.WARNINGS -> base.filter { it.level == HealthLevel.WARNING }
            LogFilter.SUCCESS -> base.filter { it.level == HealthLevel.OK }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.history(filteredRecords.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CpToggleChip(
                checked = activeFilter == LogFilter.ALL,
                onCheckedChange = { activeFilter = LogFilter.ALL },
                label = "${s.filterAll} (${records.size})",
            )
            val errCount = records.count { it.level == HealthLevel.ERROR }
            CpToggleChip(
                checked = activeFilter == LogFilter.ERRORS,
                onCheckedChange = { activeFilter = LogFilter.ERRORS },
                label = "${s.filterErrors} ($errCount)",
            )
            val warnCount = records.count { it.level == HealthLevel.WARNING }
            CpToggleChip(
                checked = activeFilter == LogFilter.WARNINGS,
                onCheckedChange = { activeFilter = LogFilter.WARNINGS },
                label = "${s.filterWarnings} ($warnCount)",
            )
            val okCount = records.count { it.level == HealthLevel.OK }
            CpToggleChip(
                checked = activeFilter == LogFilter.SUCCESS,
                onCheckedChange = { activeFilter = LogFilter.SUCCESS },
                label = "${s.filterSuccess} ($okCount)",
            )
        }

        if (filteredRecords.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text(s.noCalls, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            filteredRecords.forEachIndexed { index, record ->
                CallRecordItem(record, compact = false, s = s)
            }
        }
    }
}

@Composable
internal fun CallRecordItem(
    record: ApiCallRecord,
    compact: Boolean,
    s: HealthStrings,
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
                    color = cpNestedSurface(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            s.resultPrefix + when (record.level) {
                                HealthLevel.OK -> s.resultSuccess
                                HealthLevel.WARNING -> s.resultUnsupported
                                HealthLevel.ERROR -> s.resultFailed
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (record.level == HealthLevel.OK)
                                MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        record.errorMessage?.let { msg ->
                            SelectionContainer {
                                Text(
                                    s.errorMessage(msg),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================== Tab 5: Provider 状态 (Provider Status) ========================

private fun androidx.compose.foundation.lazy.LazyListScope.statusTabContent(
    s: HealthStrings,
) {
    val providers = AppModel.availableProviders()
    val activeProvider = AppModel.activeProvider()

    item(key = "status_title") {
        Text(s.providerListSection, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }

    if (providers.isEmpty()) {
        item(key = "status_empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text(s.noRegisteredProviders, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(s.activeNow, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }

                        if (!isActive) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { AppModel.switchOrReport(provider) },
                            ) {
                                Text(s.switchToProvider, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text("ID: ${provider.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        s.providerTypeVersion(provider.type.name, provider.version, provider.apiVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    provider.apiVersionTag?.let { tag ->
                        Text(s.apiGeneration(tag), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    provider.loadError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(s.startupFailed(err), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }

                    val diag = provider.getDiagnostics()
                    if (diag.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = cpNestedSurface(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(s.runtimeDiagnostics, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
