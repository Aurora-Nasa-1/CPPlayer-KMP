package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.SettingsFieldGroup
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.TopBarAction
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ApiCallRecord
import cp.player.app.HealthLevel
import cp.player.core.util.localDateTimeOf

/**
 * 诊断：接口调用记录。
 *
 * ### 与重构前的差异（2026-10-01 统一版式时收敛）
 *
 * 1. **页边距回到表单页标准。** 原先一个页面里混了三种内边距：概览 20/12、筛选条 16/8、
 *    空态 48dp、列表自己拼 `LazyScrollColumn(fillMaxWidth)` 完全没有宽度上限 ——
 *    宽屏下记录列表横跨整屏，和上一屏的设置页完全不同宽。
 * 2. **筛选条钉在列表上方**（`SettingsLazyPage` 的 `header` 槽位），仍不参与滚动 ——
 *    记录一多，翻到一半就改不了筛选条件了。表头与列表正文受同一个宽度上限约束。
 * 3. **行距从 2dp 改到 `CpSpacing.listRowGap`(4dp)。** 2dp 不是刻度上的值，
 *    而且行按下时圆角会从 4dp 撑到 20dp，2dp 的缝太窄、变形后与邻行粘连。
 * 4. **行底色回到 `settingsRowContainer()`。** 原先吃 `LegacyListItem` 的默认色
 *    `surfaceContainerHigh`，浅色主题下比其余设置页的行深一档。
 */
class HealthScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val records by AppModel.health.recordsFlow.collectAsState()
        val overall by AppModel.health.overallLevelFlow.collectAsState()

        var onlyErrors by remember { mutableStateOf(false) }
        // 「清空诊断记录」的二次确认：顶栏按钮离列表很近，一键即生效太容易误触。
        val confirm = cp.player.app.ui.component.rememberConfirmState()

        val filtered = remember(records, onlyErrors) {
            val recent = records.reversed()
            if (onlyErrors) recent.filter { it.level != HealthLevel.OK } else recent.take(300)
        }

        CpRouteScaffold(
            title = "诊断",
            onBack = { navigator.popOrNotify() },
            topBarActions = listOf(
                TopBarAction(
                    icon = { Icon(Icons.Filled.DeleteSweep, "清空") },
                    onClick = {
                        // 空列表不用弹框：没有东西可清，直接让按钮无副作用。
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
                    OverviewCard(overall, records.size)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                },
            ) {
                if (filtered.isEmpty()) {
                    item(key = "__empty__") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("暂无调用记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    itemsIndexed(filtered) { index, record -> RecordRow(record, index, filtered.size) }
                }
            }
        }

        cp.player.app.ui.component.CpConfirmHost(confirm)
    }
}

@Composable
private fun OverviewCard(overall: HealthLevel, total: Int) {
    val (color, label, icon) = when (overall) {
        HealthLevel.OK -> Triple(MaterialTheme.colorScheme.primary, "健康", Icons.Filled.CheckCircle)
        HealthLevel.WARNING -> Triple(MaterialTheme.colorScheme.tertiary, "存在警告", Icons.Filled.Warning)
        HealthLevel.ERROR -> Triple(MaterialTheme.colorScheme.error, "存在错误", Icons.Filled.Error)
    }
    // 只读信息一律用 SettingsFieldGroup：圆角、内边距、底色与其他设置页的只读块一致。
    SettingsFieldGroup {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CpSpacing.formRowGap),
        ) {
            Icon(icon, null, tint = color)
            Column {
                Text("综合状态：$label", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "最近 100 条综合判定 · 共 $total 条记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordRow(record: ApiCallRecord, index: Int, total: Int) {
    val color = when (record.level) {
        HealthLevel.OK -> MaterialTheme.colorScheme.primary
        HealthLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        HealthLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    // 别用 kotlinx-datetime：运行时 0.7.x 无 kotlinx.datetime.Instant 类文件，
    // 一碰就是 NoClassDefFoundError；统一走 expect/actual 的 localDateTimeOf。
    val time = runCatching {
        val t = localDateTimeOf(record.timestamp)
        "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    }.getOrDefault("--:--:--")

    var showRaw by remember { mutableStateOf(false) }

    // 这一行不是「设置项」而是日志条目：正文是两段文本（时间码 + 可能的错误信息），
    // 表达不了 `SettingsClickItem` 的单个 subtitle，所以直接用 LegacyListItem ——
    // 但**底色必须显式给**，不能吃它的默认值（浅色主题下默认值比设置行深一档）。
    LegacyListItem(
        index = index,
        total = total,
        onClick = { if (record.rawResponse != null) showRaw = true },
        containerColor = settingsRowContainer(),
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

    if (showRaw && record.rawResponse != null) {
        AlertDialog(
            onDismissRequest = { showRaw = false },
            title = { Text("API 原始返回内容") },
            text = {
                LazyColumn {
                    item {
                        SelectionContainer {
                            Text(record.rawResponse.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRaw = false }) { Text("关闭") }
            },
        )
    }
}

private fun levelText(level: HealthLevel) = when (level) {
    HealthLevel.OK -> "OK"
    HealthLevel.WARNING -> "WARN"
    HealthLevel.ERROR -> "ERROR"
}
