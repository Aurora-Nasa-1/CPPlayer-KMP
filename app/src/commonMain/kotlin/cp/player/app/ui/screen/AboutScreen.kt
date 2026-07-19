package cp.player.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.LegacyListItem
import cp.player.app.ui.component.LegacyPageScaffold
import cp.player.app.update.AppUpdateChecker
import cp.player.app.version.AppVersion
import kotlinx.coroutines.launch

class AboutScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        var isChecking by remember { mutableStateOf(true) }
        var updateResult by remember { mutableStateOf<AppUpdateChecker.UpdateResult?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            scope.launch {
                val result = AppUpdateChecker.checkUpdate()
                updateResult = result
                isChecking = false
                if (result != null) showUpdateDialog = true
            }
        }

        LegacyPageScaffold(
            title = "关于",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
        ) { pageModifier ->
            Column(
                pageModifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionHeader("版本信息")

                ClickEntry(
                    index = 0,
                    total = 3,
                    icon = Icons.Default.Info,
                    title = "当前版本",
                    subtitle = AppVersion.fullVersion,
                )
                ClickEntry(
                    index = 1,
                    total = 3,
                    icon = Icons.Default.Code,
                    title = "提交哈希",
                    subtitle = AppVersion.shortSha,
                )

                ClickEntry(
                    index = 2,
                    total = 3,
                    icon = if (isChecking) null else Icons.Default.SystemUpdate,
                    title = "检查更新",
                    subtitle = when {
                        isChecking -> "正在检查..."
                        updateResult != null -> "发现新版本: v${updateResult!!.versionName}"
                        else -> "已是最新版本"
                    },
                    trailing = if (isChecking) {
                        { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
                    } else null,
                    enabled = !isChecking,
                    onClick = {
                        scope.launch {
                            isChecking = true
                            val result = AppUpdateChecker.checkUpdate()
                            updateResult = result
                            isChecking = false
                            if (result != null) showUpdateDialog = true
                        }
                    },
                )

                Spacer(Modifier.height(8.dp))

                SectionHeader("项目信息")
                ClickEntry(
                    index = 0,
                    total = 1,
                    icon = Icons.Default.Link,
                    title = "GitHub 项目",
                    subtitle = "${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}",
                    onClick = { openUrl(AppVersion.RELEASES_PAGE) },
                )

                Spacer(Modifier.height(8.dp))

                SectionHeader("维护者")
                ClickEntry(
                    index = 0,
                    total = 1,
                    icon = Icons.Default.Info,
                    title = "Aurora-Nasa-1",
                    subtitle = "创建者 & 主要维护者",
                    onClick = { openUrl("https://github.com/Aurora-Nasa-1") },
                )

                Spacer(Modifier.height(32.dp))
                Text(
                    "KMP-PRO · Compose Multiplatform",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        }

        if (showUpdateDialog && updateResult != null) {
            UpdateDialog(
                result = updateResult!!,
                onDismiss = { showUpdateDialog = false },
                onDownload = {
                    updateResult?.downloadUrl?.let { openUrl(it) }
                    showUpdateDialog = false
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ClickEntry(
    index: Int,
    total: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    LegacyListItem(
        index = index,
        total = total,
        onClick = if (enabled) onClick else null,
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, maxLines = 2) },
        leadingContent = icon?.let { img -> @Composable { Icon(img, null) } },
        trailingContent = trailing?.let { content -> { content() } },
    )
}

@Composable
private fun UpdateDialog(
    result: AppUpdateChecker.UpdateResult,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("发现新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "v${result.versionName}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (result.publishedAt != null) {
                    Text(
                        result.publishedAt.take(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!result.changelog.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("更新日志", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(result.changelog, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onDownload) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("下载更新")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
