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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import cp.player.app.i18n.cpStrings
import cp.player.app.AppModel
import cp.player.app.platform.downloadAndInstallUpdate
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.CpIconSize
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpLoadingIndicator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpText
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.AppUpdateDialog
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.SponsorDialog
import cp.player.app.ui.component.SponsorInlineCard
import cp.player.app.ui.component.SponsorPaymentType
import cp.player.app.update.AppUpdateChecker
import cp.player.app.version.AppVersion
import cp.player.core.BackendResult
import cp.player.core.provider.LegacyProviderModule
import kotlinx.coroutines.launch

/**
 * 关于与支持页面。
 */
class AboutScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val s = cpStrings().about
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        var isChecking by remember { mutableStateOf(false) }
        var updateResult by remember { mutableStateOf<AppUpdateChecker.UpdateResult?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var showDestructiveConfirm by remember { mutableStateOf(false) }
        var legacyModules by remember { mutableStateOf<List<LegacyProviderModule>>(emptyList()) }
        var showSponsorDialog by remember { mutableStateOf(false) }
        var sponsorDialogType by remember { mutableStateOf(SponsorPaymentType.WECHAT) }

        CpRouteScaffold(
            title = s.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) {
                AboutHero(s)
                SettingsSection(s.versionSection) {
                    SettingsClickItem(
                        index = 0,
                        total = 2,
                        icon = Icons.Default.Code,
                        title = s.commitSha,
                        subtitle = AppVersion.shortSha,
                    )
                    SettingsClickItem(
                        index = 1,
                        total = 2,
                        icon = if (isChecking) null else Icons.Default.SystemUpdate,
                        title = s.checkUpdate,
                        subtitle = when {
                            isChecking -> s.checkingUpdate
                            updateResult != null -> s.foundUpdate(updateResult!!.versionName)
                            else -> s.alreadyLatest
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

                SettingsSection(s.projectSection) {
                    SettingsClickItem(
                        index = 0,
                        total = 2,
                        icon = Icons.Default.Link,
                        title = s.githubProject,
                        subtitle = "${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}",
                        onClick = { openUrl("https://github.com/${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}") },
                    )
                    SettingsClickItem(
                        index = 1,
                        total = 2,
                        icon = Icons.Default.Info,
                        title = s.licensesAndThanks,
                        subtitle = s.licensesAndThanksSubtitle,
                        onClick = { openUrl("https://github.com/${AppVersion.REPO_OWNER}/${AppVersion.REPO_NAME}/blob/main/THIRD_PARTY_LICENSES.md") },
                    )
                }

                SettingsSection(s.maintainerSection) {
                    SettingsClickItem(
                        index = 0,
                        total = 1,
                        icon = Icons.Default.Info,
                        title = "Aurora-Nasa-1",
                        subtitle = s.maintainerRole,
                        onClick = { openUrl("https://github.com/Aurora-Nasa-1") },
                    )
                }

                SettingsSection(s.supportSection) {
                    SettingsClickItem(
                        index = 0,
                        total = 2,
                        icon = Icons.Default.Favorite,
                        title = s.sponsorDeveloper,
                        subtitle = s.sponsorDeveloperSubtitle,
                        onClick = {
                            sponsorDialogType = SponsorPaymentType.WECHAT
                            showSponsorDialog = true
                        },
                    )
                    SettingsClickItem(
                        index = 1,
                        total = 2,
                        icon = Icons.Default.Link,
                        title = s.supportProject,
                        subtitle = s.supportProjectSubtitle,
                        onClick = { openUrl("https://github.com/Aurora-Nasa-1/CPPlayer-KMP") },
                    )
                }

                SponsorInlineCard(
                    onOpenDialog = { type ->
                        sponsorDialogType = type
                        showSponsorDialog = true
                    },
                )

                SettingsNote(s.footerNote)
            }
        }

        if (showSponsorDialog) {
            SponsorDialog(
                initialType = sponsorDialogType,
                onDismiss = { showSponsorDialog = false },
            )
        }

        if (showUpdateDialog && updateResult != null) {
            AppUpdateDialog(
                result = updateResult!!,
                onDismiss = { showUpdateDialog = false },
                onDownload = {
                    updateResult?.let { result ->
                        if (result.destructivePolicy != null) {
                            legacyModules = if (result.destructivePolicy.removeLegacyProviders) {
                                AppModel.legacyProviderModulesToRemove()
                            } else emptyList()
                            showDestructiveConfirm = true
                        } else {
                            val url = result.downloadUrl
                            if (url == null) openUrl(result.releaseUrl) else scope.launch {
                                downloadAndInstallUpdate(
                                    url = url,
                                    fileName = result.assetName ?: "CPPlayer-${result.versionName}",
                                    expectedSha256 = result.assetSha256,
                                    expectedSize = result.assetSize,
                                    beforeInstall = { true },
                                )
                            }
                        }
                    }
                    showUpdateDialog = false
                },
            )
        }

        if (showDestructiveConfirm && updateResult?.destructivePolicy != null) {
            val result = updateResult!!
            val policy = result.destructivePolicy!!
            AlertDialog(
                onDismissRequest = { showDestructiveConfirm = false },
                title = { CpText(policy.title) },
                text = {
                    Column {
                        CpText(policy.message)
                        Spacer(Modifier.height(10.dp))
                        if (legacyModules.isEmpty()) {
                            CpText("未发现符合条件的旧版 Provider V1/V3 模块目录，不会删除 SDK Provider 或账号数据。")
                        } else {
                            CpText("以下旧模块目录将在确认后排队，并仅在目标更新版本成功启动后删除；如果安装被取消或失败，旧版本会保留这些目录：")
                            legacyModules.forEach { CpText("${it.name} · API V${it.apiVersion} · ${it.id}") }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showDestructiveConfirm = false
                        val ids = legacyModules.map { it.id }.toSet()
                        val url = result.downloadUrl
                        if (url == null) openUrl(result.releaseUrl) else scope.launch {
                            downloadAndInstallUpdate(
                                url = url,
                                fileName = result.assetName ?: "CPPlayer-${result.versionName}",
                                expectedSha256 = result.assetSha256,
                                expectedSize = result.assetSize,
                                beforeInstall = {
                                    if (!policy.removeLegacyProviders || ids.isEmpty()) true
                                    else AppModel.scheduleLegacyProviderCleanup(ids, result.versionName) is BackendResult.Success
                                },
                            )
                        }
                    }) { CpText("确认并下载") }
                },
                dismissButton = {
                    TextButton(onClick = { showDestructiveConfirm = false }) { CpText("取消") }
                },
            )
        }
    }
}

/**
 * 「关于」页顶部 Hero 区域：展示应用标识、版本号与更新日志入口。
 */
@Composable
private fun AboutHero(s: cp.player.app.i18n.AboutStrings) {
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
        CpText(
            text = "CPPlayer",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        CpText(
            text = AppVersion.fullVersion,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        CpText(
            text = s.appSubtitle,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(4.dp))
        // 内边距清零，让文字左边缘与上面的标题对齐 —— TextButton 默认有 12dp 横向内边距，
        // 直接用会让这一行比标题右缩 12dp，一眼看出没对齐。
        TextButton(
            onClick = { openUrl(AppVersion.RELEASES_PAGE) },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        ) {
            CpText(s.changelog)
        }
    }
}
