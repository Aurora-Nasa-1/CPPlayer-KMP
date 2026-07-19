package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.PageHeader

class SettingsScreen : Screen {
    @Composable
    override fun Content() { SettingsScreenContent() }
}

@Composable
private fun SettingsScreenContent() {
    val navigator = LocalNavigator.currentOrThrow
    val active = AppModel.activeProvider()

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = CpSpacing.pageHorizontal,
                end = CpSpacing.pageHorizontal,
                top = CpSpacing.pageTop,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.section),
    ) {
        SettingsSection(
            title = "音源与账号",
            entries = listOf(
                SettingsEntry(
                    Icons.Filled.Dns,
                    "音源管理",
                    "导入、切换或移除 Provider",
                ) { navigator.push(ProviderManagementScreen()) },
                SettingsEntry(
                    Icons.AutoMirrored.Filled.Login,
                    "登录",
                    "管理当前音源的账号",
                ) { navigator.push(LoginScreen()) },
                SettingsEntry(
                    Icons.Filled.ManageAccounts,
                    "偏好设置",
                    "主题、播放音质、缓存清理",
                ) { navigator.push(SettingsDetailScreen()) },
            ),
        )
        SettingsSection(
            title = "诊断",
            entries = listOf(
                SettingsEntry(
                    Icons.Filled.BugReport,
                    "API 健康监控",
                    "查看调用状态、日志与回退信息",
                ) { navigator.push(HealthScreen()) },
            ),
        )
        SettingsSection(
            title = "关于",
            entries = listOf(
                SettingsEntry(
                    Icons.Filled.Info,
                    "关于 CP Player",
                    "版本、更新与项目维护者",
                ) { navigator.push(AboutScreen()) },
            ),
        )
    }
}

private data class SettingsEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsSection(title: String, entries: List<SettingsEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        entries.forEachIndexed { index, entry ->
            SettingsRow(entry, index, entries.size)
        }
    }
}

@Composable
private fun SettingsRow(entry: SettingsEntry, index: Int, total: Int) {
    LegacyListItem(
        index = index,
        total = total,
        onClick = entry.onClick,
        leadingContent = {
            Box(
                Modifier.size(44.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        headlineContent = {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
             Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
             )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
