package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openUrl
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.update.AppUpdateChecker
import cp.player.app.version.AppVersion

/**
 * 软件更新弹窗。
 *
 * 针对大屏桌面（Windows / Linux / macOS）与紧凑移动端（Android）进行自适应排版：
 * - 明确的版本迭代指示（当前版本 ➔ 目标新版本）与发布徽章；
 * - 更新日志支持层级解析（小节标题、特性条目、粗体高亮），正文字号加大，提供舒适的行距与可滚动视图；
 * - 平台安装包适配指示与清晰的操作入口（直接下载、在浏览器查看完整发布、稍后再说）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialog(
    result: AppUpdateChecker.UpdateResult,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    val isExpanded = LocalIsExpanded.current

    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val changelogContainerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 320.dp, max = if (isExpanded) 600.dp else 420.dp)
            .fillMaxWidth(if (isExpanded) 0.65f else 0.94f),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = containerColor,
        icon = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = Icons.Default.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // 版本迭代指示器：v当前版本 ➔ v目标版本
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    VersionBadge(
                        text = "v${AppVersion.versionName}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    VersionBadge(
                        text = "v${result.versionName}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        isBold = true,
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 属性标签行：发布时间、安装包匹配类型
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    result.publishedAt?.take(10)?.let { date ->
                        InfoTag(
                            icon = Icons.Default.CalendarToday,
                            text = "发布于 $date",
                        )
                    }
                    val targetPackageDesc = resolveTargetPackageDescription(result.assetName)
                    InfoTag(
                        icon = Icons.Default.Terminal,
                        text = targetPackageDesc,
                    )
                }

                // 更新日志区域
                Text(
                    text = "更新内容与说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = changelogContainerColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = if (isExpanded) 360.dp else 260.dp),
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val parsedEntries = parseChangelogContent(result.changelog)
                        if (parsedEntries.isEmpty()) {
                            Text(
                                text = "此版本包含常规体验优化、性能改进与问题修复。\n建议保持更新以获得最佳使用体验。",
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            for (entry in parsedEntries) {
                                when (entry) {
                                    is ChangelogEntry.Header -> {
                                        Text(
                                            text = entry.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                                        )
                                    }
                                    is ChangelogEntry.Bullet -> {
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 8.dp, end = 8.dp)
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary),
                                            )
                                            FormattedChangelogText(
                                                raw = entry.text,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                    is ChangelogEntry.SubBullet -> {
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 16.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 8.dp, end = 8.dp)
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
                                            )
                                            FormattedChangelogText(
                                                raw = entry.text,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                    is ChangelogEntry.Paragraph -> {
                                        Text(
                                            text = entry.text,
                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    is ChangelogEntry.CompareLink -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { openUrl(entry.url) }
                                                .padding(vertical = 4.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "在 GitHub 上查看完整代码差异对比",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.height(42.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(CpIconSize.inline),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "立即下载更新",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = { openUrl(result.releaseUrl) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(CpIconSize.inline),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "发布页",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(42.dp),
                ) {
                    Text(
                        text = "稍后再说",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
    )
}

@Composable
private fun VersionBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    isBold: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InfoTag(
    icon: ImageVector,
    text: String,
) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 格式化渲染 Markdown 风格行（支持 **粗体** 解析）。
 */
@Composable
private fun FormattedChangelogText(
    raw: String,
    modifier: Modifier = Modifier,
) {
    val annotated = buildAnnotatedString {
        var cursor = 0
        val regex = Regex("""\*\*(.*?)\*\*""")
        val matches = regex.findAll(raw)

        for (match in matches) {
            if (match.range.first > cursor) {
                append(raw.substring(cursor, match.range.first))
            }
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                append(match.groupValues[1])
            }
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            append(raw.substring(cursor))
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = 22.sp,
            fontSize = 14.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        modifier = modifier,
    )
}

/**
 * 解析更新说明，归纳为层级实体。
 */
private sealed class ChangelogEntry {
    data class Header(val title: String) : ChangelogEntry()
    data class Bullet(val text: String) : ChangelogEntry()
    data class SubBullet(val text: String) : ChangelogEntry()
    data class Paragraph(val text: String) : ChangelogEntry()
    data class CompareLink(val url: String) : ChangelogEntry()
}

private fun parseChangelogContent(changelog: String?): List<ChangelogEntry> {
    if (changelog.isNullOrBlank()) return emptyList()

    val entries = mutableListOf<ChangelogEntry>()
    val lines = changelog.lines()

    for (rawLine in lines) {
        val trimmed = rawLine.trim()
        if (trimmed.isBlank() || trimmed == "---") continue

        when {
            trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                val title = trimmed.replace(Regex("""^#+\s*"""), "")
                // 如果标题纯粹是比较链接则作为外链处理
                if (title.contains("Full Changelog") || title.contains("完整变更")) {
                    extractUrl(title)?.let { entries.add(ChangelogEntry.CompareLink(it)) }
                } else {
                    entries.add(ChangelogEntry.Header(title))
                }
            }
            trimmed.contains("compare/") && (trimmed.contains("Full Changelog") || trimmed.contains("完整变更")) -> {
                val url = extractUrl(trimmed)
                if (url != null) {
                    entries.add(ChangelogEntry.CompareLink(url))
                }
            }
            rawLine.startsWith("    - ") || rawLine.startsWith("    * ") || rawLine.startsWith("  - ") || rawLine.startsWith("  * ") -> {
                val text = trimmed.removePrefix("- ").removePrefix("* ")
                entries.add(ChangelogEntry.SubBullet(text))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                val text = trimmed.removePrefix("- ").removePrefix("* ")
                entries.add(ChangelogEntry.Bullet(text))
            }
            else -> {
                entries.add(ChangelogEntry.Paragraph(trimmed))
            }
        }
    }

    return entries
}

private fun extractUrl(text: String): String? {
    val regex = Regex("""https?://[^\s)\]]+""")
    return regex.find(text)?.value
}

private fun resolveTargetPackageDescription(assetName: String?): String {
    if (assetName != null) {
        if (assetName.endsWith(".apk", ignoreCase = true)) return "Android 安装包 (.apk)"
        if (assetName.endsWith("-Setup.exe", ignoreCase = true)) return "Windows 安装程序 (.exe)"
        if (assetName.endsWith(".deb", ignoreCase = true)) return "Linux Debian/Ubuntu (.deb)"
        if (assetName.endsWith(".tar.gz", ignoreCase = true)) return "Linux 通用包 (.tar.gz)"
        return assetName
    }
    return when {
        isAndroidPlatform() -> "Android 安装包 (.apk)"
        desktopPlatform() == "windows" -> "Windows 安装程序 (.exe)"
        desktopPlatform() == "linux" -> "Linux 软件包"
        else -> "跨平台软件包"
    }
}
