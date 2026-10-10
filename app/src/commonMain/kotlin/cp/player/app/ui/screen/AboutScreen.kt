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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.update.AppUpdateChecker
import cp.player.app.version.AppVersion
import kotlinx.coroutines.launch

/**
 * 关于与支持。
 *
 * ### 与重构前的差异（2026-10-01 统一版式时收敛）
 *
 * 1. **删掉了本文件私有的 `SectionHeader` / `ClickEntry`。** 它们是 `SettingsSection` /
 *    `SettingsClickItem` 的**第二份实现**，而且三处不一致：分组标题内边距是
 *    `horizontal = 16, vertical = 8`（标准是 `start=16 / top=12 / bottom=8`）、
 *    页面用 `padding(16.dp)` 四边等距（标准是水平 16 / 垂直 8）、组间距 4dp（标准 12dp）。
 * 2. **行底色回到 `settingsRowContainer()`。** 私有实现直接用了 `LegacyListItem` 的默认色
 *    `surfaceContainerHigh` —— 浅色主题下它比其余设置页的 `surfaceContainerLow` 明显更深，
 *    于是「关于」这一页的行比旁边每一页都"重"。默认值只在没有约定的地方才对。
 * 3. **正文宽度收进 [SettingsPage]**（720dp）。此前它没有上限，宽屏下与其余设置页不同宽。
 * 4. **底部署名改用 [SettingsNote]**，不再手写 `bodySmall + onSurfaceVariant + padding`。
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
                        total = 1,
                        icon = Icons.Default.Link,
                        title = "GitHub 项目",
                        subtitle = "${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}",
                        onClick = { openUrl(AppVersion.RELEASES_PAGE) },
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

                // 原「赞助」是一个独立的一级设置入口，但它的全部内容就是「项目主页 + 维护者主页」
                // 两个链接 —— 与本页已有的两个条目完全重合。合并进来，设置根页少一个入口。
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
            UpdateDialog(
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
 * 「关于」页的门面：大号主色应用名 + 版本 + 一句话定位 + 更新日志入口。
 *
 * **为什么值得单独做一个 hero**：这一页此前是一串 `SettingsClickItem`，与「播放设置」
 * 「音源管理」长得完全一样 —— 用户读不出这是「这个软件的门面」。参考 Kazumi 的
 * `about_page.dart`：它把应用名做成巨型主色标题，下面是版本号与一句话定位，
 * 再下面是药丸按钮组。这是**零依赖、零成本**的「这是个正经项目」信号。
 *
 * 版本号刻意只在这里出现一次 —— 下面「版本信息」组不再重复列「当前版本」。
 *
 * ⚠️ 左右内边距用 [CpSpacing.formRowHorizontal]，与 [SettingsSection] 的标题、
 * 分组行的内边距同源 —— 三者左边缘必须落在同一条竖线上，否则整页会歪。
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
                        shape = MaterialTheme.shapes.medium,
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
                Icon(Icons.Default.Download, null, modifier = Modifier.size(CpIconSize.inline))
                Spacer(Modifier.width(6.dp))
                Text("下载更新")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
