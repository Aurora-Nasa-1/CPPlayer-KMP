package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.kmp.monitor.HealthMonitor
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class HealthScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val records by AppModel.health.recordsFlow.collectAsState()
        val overall by AppModel.health.overallLevelFlow.collectAsState()

        var onlyErrors by remember { mutableStateOf(false) }

        val filtered = remember(records, onlyErrors) {
            val recent = records.reversed()
            if (onlyErrors) recent.filter { it.level != HealthMonitor.HealthLevel.OK } else recent.take(300)
        }

        LegacyPageScaffold(
            title = "API 健康监控",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
            actions = {
                IconButton(onClick = { AppModel.health.clearRecords() }) {
                    Icon(Icons.Filled.DeleteSweep, "清空")
                }
            },
        ) { pageModifier ->
            Column(pageModifier) {
                OverviewCard(overall, records.size)

                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !onlyErrors,
                        onClick = { onlyErrors = false },
                        label = { Text("全部 ${records.size}") },
                    )
                    FilterChip(
                        selected = onlyErrors,
                        onClick = { onlyErrors = true },
                        label = { Text("仅异常") },
                    )
                }

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("暂无调用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(filtered) { index, record -> RecordRow(record, index, filtered.size) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(overall: HealthMonitor.HealthLevel, total: Int) {
    val (color, label, icon) = when (overall) {
        HealthMonitor.HealthLevel.OK -> Triple(MaterialTheme.colorScheme.primary, "健康", Icons.Filled.CheckCircle)
        HealthMonitor.HealthLevel.WARNING -> Triple(MaterialTheme.colorScheme.tertiary, "存在警告", Icons.Filled.Warning)
        HealthMonitor.HealthLevel.ERROR -> Triple(MaterialTheme.colorScheme.error, "存在错误", Icons.Filled.Error)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = color)
        Column {
            Text("综合状态：$label", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("最近 100 条综合判定 · 共 $total 条记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordRow(record: HealthMonitor.ApiCallRecord, index: Int, total: Int) {
    val color = when (record.level) {
        HealthMonitor.HealthLevel.OK -> MaterialTheme.colorScheme.primary
        HealthMonitor.HealthLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        HealthMonitor.HealthLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    val time = runCatching {
        Instant.fromEpochMilliseconds(record.timestamp)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .time.toString().substring(0, 8)
    }.getOrDefault("--:--:--")

    LegacyListItem(
        index = index,
        total = total,
        onClick = null,
        leadingContent = { Icon(Icons.Filled.BugReport, null, tint = color) },
        headlineContent = {
            Text("${record.method} · ${record.providerId}", fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Column {
                Text(
                    "$time · ${record.durationMs}ms · ${levelText(record.level)}" +
                        (if (record.wasFallback) " · 回退自 ${record.fallbackFrom}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                record.errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun levelText(level: HealthMonitor.HealthLevel) = when (level) {
    HealthMonitor.HealthLevel.OK -> "OK"
    HealthMonitor.HealthLevel.WARNING -> "WARN"
    HealthMonitor.HealthLevel.ERROR -> "ERROR"
}
