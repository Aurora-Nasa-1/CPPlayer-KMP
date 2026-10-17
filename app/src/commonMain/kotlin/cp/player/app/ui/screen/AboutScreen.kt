package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cp.player.app.platform.downloadUpdate
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.AppUpdateDialog
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.update.AppUpdateChecker
import cp.player.app.version.AppVersion
import kotlinx.coroutines.launch

/**
 * 关于与支持页面。
 */
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

        CpRouteScaffold(
            title = "关于与支持",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                AboutHero()
                SettingsSection("版本信息") {
                    SettingsClickItem(
                        index = 0,
                        total = 2,
                        icon = Icons.Default.Code,
                        title = "提交哈希",
                        subtitle = AppVersion.shortSha,
                    )
                    SettingsClickItem(
                        index = 1,
                        total = 2,
                        icon = if (isChecking) null else Icons.Default.SystemUpdate,
                        title = "检查更新",
                        subtitle = when {
                            isChecking -> "正在检查..."
                            updateResult != null -> "发现新版本: v${updateResult!!.versionName}"
                            else -> "已是最新版本"
                        },
                        enabled = !isChecking,
                        trailingContent = if (isChecking) {
                            {
                                // 统一走 Expressive 变形加载器：全应用只剩这一种「等待中」的样子。
                                // 混用转圈的 CircularProgressIndicator 会让「这块是后补的」一眼可见。
                                CpLoadingIndicator(modifier = Modifier.size(CpIconSize.action))
                            }
                        } else {
                            null
                        },
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
                }

                SettingsSection("项目信息") {
                    SettingsClickItem(
                        index = 0,
                        total = 2,
                        icon = Icons.Default.Link,
                        title = "GitHub 项目",
                        subtitle = "${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}",
                        onClick = { openUrl("https://github.com/${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}") },
                    )
                    SettingsClickItem(
                        index = 1,
                        total = 2,
                        icon = Icons.Default.Info,
                        title = "开源许可与致谢",
                        subtitle = "Apache-2.0 · 核心致谢 moss-apps/Flick 等开源项目",
                        onClick = { openUrl("https://github.com/${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}/blob/main/THIRD_PARTY_LICENSES.md") },
                    )
                }

                SettingsSection("维护者") {
                    SettingsClickItem(
                        index = 0,
                        total = 1,
                        icon = Icons.Default.Info,
                        title = "Aurora-Nasa-1",
                        subtitle = "创建者 & 主要维护者",
                        onClick = { openUrl("https://github.com/Aurora-Nasa-1") },
                    )
                }

                SettingsSection("支持项目") {
                    SettingsClickItem(
                        index = 0,
                        total = 1,
                        icon = Icons.Default.Link,
                        title = "支持本项目",
                        subtitle = "在项目主页查看说明与支持方式",
                        onClick = { openUrl("https://github.com/Aurora-Nasa-1/CPPlayer-KMP") },
                    )
                }

                SettingsNote("CPPlayer · Compose Multiplatform")
            }
        }

        if (showUpdateDialog && updateResult != null) {
            AppUpdateDialog(
                result = updateResult!!,
                onDismiss = { showUpdateDialog = false },
                onDownload = {
                    updateResult?.let { result ->
                        downloadUpdate(result.downloadUrl ?: result.releaseUrl, result.assetName ?: "CPPlayer-${result.versionName}")
                    }
                    showUpdateDialog = false
                },
            )
        }
    }
}

/**
 * 「关于」页顶部 Hero 区域：展示应用标识、版本号与更新日志入口。
 */
@Composable
private fun AboutHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = CpSpacing.formRowHorizontal,
                end = CpSpacing.formRowHorizontal,
                top = CpSpacing.formVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "CPPlayer",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = AppVersion.fullVersion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Compose Multiplatform 音乐播放器",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        // 内边距清零，让文字左边缘与上面的标题对齐 —— TextButton 默认有 12dp 横向内边距，
        // 直接用会让这一行比标题右缩 12dp，一眼看出没对齐。
        TextButton(
            onClick = { openUrl(AppVersion.RELEASES_PAGE) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        ) {
            Text("更新日志")
        }
    }
}
