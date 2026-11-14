package cp.player.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.platform.openUrl
import cp.player.app.platform.sendPlatformToast
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.update.ProviderUpdateInfo
import cp.player.core.provider.BackendProvider

/**
 * 音源独立更新详情弹窗：
 * 展示当前版本与最新版本、更新说明、下载链接获取/复制，以及一键直接下载并更新入口。
 */
@Composable
fun ProviderUpdateDetailsDialog(
    provider: BackendProvider,
    info: ProviderUpdateInfo,
    onDismiss: () -> Unit,
    onDownloadAndUpdate: () -> Unit,
) {
    val p = cpStrings().providerManagement
    val isDark = LocalIsDarkTheme.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val subContainerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = containerColor,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CpText(
                    text = p.updateDialogTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                CpText(
                    text = "${provider.name} (${provider.id})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 版本迭代指示器
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = subContainerColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            CpText(
                                text = p.currentVersion,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            CpText(
                                text = "v${provider.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            CpText(
                                text = p.latestVersion,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            CpText(
                                text = "v${info.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (!info.publishedAt.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    CpText(
                        text = p.publishedAt(info.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 更新说明
                CpText(
                    text = p.changelogTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = subContainerColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        CpText(
                            text = if (info.changelog.isNullOrBlank()) p.changelogMissing else info.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            // 更新说明是**整段多行正文**：容器已经套了滚动，放开行数
                            // 才能真正读完全部条目；默认单行只剩第一行。
                            maxLines = Int.MAX_VALUE,
                            color = if (info.changelog.isNullOrBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 下载链接与操作
                CpText(
                    text = p.downloadUrl,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = subContainerColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CpText(
                            text = info.downloadUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 下载地址是用户核对镜像 / 手动复制的唯一凭据：原来两行截断，
                            // 后面的路径与签名参数直接消失。外层 Column 已可滚动，放开行数。
                            maxLines = Int.MAX_VALUE,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(info.downloadUrl))
                                    sendPlatformToast(p.linkCopied)
                                },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                CpText(p.copyLink, style = MaterialTheme.typography.labelSmall)
                            }

                            OutlinedButton(
                                onClick = { openUrl(info.downloadUrl) },
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.height(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                CpText(p.openInBrowser, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownloadAndUpdate,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                CpText(p.updateNow)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium,
            ) {
                CpText(p.later)
            }
        },
    )
}

/**
 * 自定义更新源设置弹窗：
 * 支持查看 manifest 声明的默认源，输入并覆盖自定义更新检测 URL，或一键恢复默认。
 */
@Composable
fun ProviderUpdateSourceDialog(
    provider: BackendProvider,
    currentCustomUrl: String?,
    manifestDefaultUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResetDefault: () -> Unit,
) {
    val p = cpStrings().providerManagement
    val isDark = LocalIsDarkTheme.current
    var urlText by remember { mutableStateOf(currentCustomUrl ?: manifestDefaultUrl ?: "") }

    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val subContainerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = containerColor,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CpText(
                    text = p.updateSourceTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                CpText(
                    text = "${provider.name} (${provider.id})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = subContainerColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CpText(
                            text = p.updateSourceNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            // 这是几行说明文字，默认单行会被截断。
                            maxLines = Int.MAX_VALUE,
                        )
                        Spacer(Modifier.height(6.dp))
                        CpText(
                            text = p.manifestDefaultSource(manifestDefaultUrl),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // URL 必须完整可读（用户要判断是否与自己的镜像一致）。
                            maxLines = Int.MAX_VALUE,
                        )
                        if (!currentCustomUrl.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            CpText(
                                text = p.customSourceApplied,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { CpText(p.updateCheckUrl) },
                    placeholder = { CpText(manifestDefaultUrl ?: "https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(urlText.trim()) },
                enabled = urlText.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
            ) {
                CpText(p.saveAndCheck)
            }
        },
        dismissButton = {
            Row {
                if (!currentCustomUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = onResetDefault,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        CpText(p.restoreDefault)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    CpText(cpStrings().common.dismiss)
                }
            }
        },
    )
}
