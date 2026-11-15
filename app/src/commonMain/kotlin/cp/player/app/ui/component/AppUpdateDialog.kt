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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.i18n.AppUpdateStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openUrl
import cp.player.app.ui.theme.LocalIsDarkTheme
import cp.player.app.ui.util.formatBytes
import cp.player.app.update.AppUpdateChecker
import cp.player.app.version.AppVersion

/**
 * 软件更新弹窗。
 *
 * 遵循 Material 3 Expressive (M3E) 设计规范与现代工程表现力视觉层级：
 * - 风格严谨、正式、高信息密度，界面与文案完全剔除任何 Emoji；
 * - 规范的头部 Hero 区域：展示当前版本至目标版本的演进、架构格式、包体大小与发布时间；
 * - 智能结构化更新日志渲染：深度适配 Release CI 中由 AI 提炼的结构化更新内容
 *   （功能新增、问题修复、性能优化、界面与交互、其他改进），
 *   并支持优雅回退展示传统日志；
 * - 强表现力操作按钮组，桌面端与移动端自适应排版。
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
    val s = cpStrings().update

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
            .widthIn(min = 320.dp, max = if (isExpanded) 620.dp else 440.dp)
            .fillMaxWidth(if (isExpanded) 0.65f else 0.94f),
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = containerColor,
        icon = {
            // M3E 风格的 Hero 图标容器
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.RocketLaunch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CpText(
                    text = s.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // 版本迭代指示栏：当前版本 ➔ 目标版本
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = changelogContainerColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        CpText(
                            text = "v${AppVersion.versionName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            CpText(
                                text = "v${result.versionName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 远端重要公告 (Banner)
                result.announcement?.let { notice ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                CpText(
                                    text = stripEmojis(notice.title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                CpText(
                                    text = stripEmojis(notice.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    maxLines = Int.MAX_VALUE,
                                )
                            }
                        }
                    }
                }

                // 破坏性变更/关键警示 (Critical Alert Banner)
                result.destructivePolicy?.let { policy ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                CpText(
                                    text = stripEmojis(policy.title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                CpText(
                                    text = stripEmojis(policy.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    maxLines = Int.MAX_VALUE,
                                )
                            }
                        }
                    }
                }

                // 高信息密度元数据胶囊条 (Meta Chips Bar)
                val isPreRelease = result.versionName.contains("-") || result.versionName.contains("debug")
                val pkgInfo = resolveTargetPackageInfo(result.assetName, s)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 发布渠道
                    ExpressiveMetaChip(
                        icon = if (isPreRelease) Icons.Default.NewReleases else Icons.Default.RocketLaunch,
                        text = if (isPreRelease) "预发布版" else "正式版",
                        tint = if (isPreRelease) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        containerColor = if (isPreRelease) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        },
                    )

                    // 目标包格式
                    ExpressiveMetaChip(
                        icon = pkgInfo.icon,
                        text = pkgInfo.label,
                    )

                    // 安装包体积
                    result.assetSize?.takeIf { it > 0 }?.let { bytes ->
                        ExpressiveMetaChip(
                            icon = Icons.Default.Storage,
                            text = formatBytes(bytes),
                        )
                    }

                    // 发布日期
                    result.publishedAt?.take(10)?.let { date ->
                        ExpressiveMetaChip(
                            icon = Icons.Default.CalendarToday,
                            text = s.publishedAt(date),
                        )
                    }
                }

                // 更新说明小标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                ) {
                    CpText(
                        text = s.changelogTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 更新日志滚动容器
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = changelogContainerColor,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = if (isExpanded) 380.dp else 270.dp),
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val parsedChangelog = parseChangelog(result.changelog)

                        if (parsedChangelog.sections.isEmpty() && parsedChangelog.uncategorized.isEmpty()) {
                            CpText(
                                text = s.defaultChangelog,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = Int.MAX_VALUE,
                            )
                        } else {
                            // 渲染结构化分类卡片
                            for (section in parsedChangelog.sections) {
                                ExpressiveCategoryCard(section)
                            }

                            // 渲染未分类或传统段落条目
                            if (parsedChangelog.uncategorized.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (item in parsedChangelog.uncategorized) {
                                        ChangelogItemRow(item, dotColor = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            // 查看完整变更对比链接
                            parsedChangelog.compareUrl?.let { url ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { openUrl(url) }
                                        .padding(top = 4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        CpText(
                                            text = s.viewDiffOnGithub,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
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
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                val downloadButtonText = result.assetSize?.takeIf { it > 0 }?.let { bytes ->
                    "${s.downloadNow} · ${formatBytes(bytes)}"
                } ?: s.downloadNow
                CpText(
                    text = downloadButtonText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = { openUrl(result.releaseUrl) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    CpText(
                        text = s.releasePage,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.height(42.dp),
                ) {
                    CpText(
                        text = s.later,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
    )
}

/**
 * 结构化分类卡片组件。
 */
@Composable
private fun ExpressiveCategoryCard(
    section: ChangelogSection,
    modifier: Modifier = Modifier,
) {
    val meta = section.category.meta()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = meta.containerColor().copy(alpha = 0.28f),
        border = BorderStroke(1.dp, meta.accentColor().copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 分类标题栏（包含矢量图标与条目计数）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = meta.accentColor().copy(alpha = 0.16f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            imageVector = meta.icon,
                            contentDescription = null,
                            tint = meta.accentColor(),
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        CpText(
                            text = section.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = meta.accentColor(),
                        )
                    }
                }

                // 该分类条目数量指示
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ) {
                    CpText(
                        text = "${section.items.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }

            // 分类内各条目列表
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (item in section.items) {
                    ChangelogItemRow(item, dotColor = meta.accentColor())
                }
            }
        }
    }
}

/**
 * 单条更新条目行（带对齐圆点与富文本格式化）。
 */
@Composable
private fun ChangelogItemRow(
    item: ChangelogLine,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (item.isSubItem) 16.dp else 2.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp, end = 8.dp)
                .size(if (item.isSubItem) 4.dp else 6.dp)
                .clip(CircleShape)
                .background(if (item.isSubItem) dotColor.copy(alpha = 0.6f) else dotColor),
        )
        FormattedChangelogText(
            raw = item.text,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 紧凑表现力元数据胶囊。
 */
@Composable
private fun ExpressiveMetaChip(
    icon: ImageVector,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            CpText(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 格式化渲染 Markdown 风格行（支持 **粗体**、`行内代码` 以及 PR/Commit 引用）。
 */
@Composable
private fun FormattedChangelogText(
    raw: String,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary

    val annotated = buildAnnotatedString {
        val tokenRegex = Regex("""(\*\*(.*?)\*\*|`(.*?)`)""")
        var cursor = 0
        val matches = tokenRegex.findAll(raw)

        for (match in matches) {
            if (match.range.first > cursor) {
                append(raw.substring(cursor, match.range.first))
            }

            val boldContent = match.groups[2]?.value
            val codeContent = match.groups[3]?.value

            if (boldContent != null) {
                withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = onSurface,
                    ),
                ) {
                    append(boldContent)
                }
            } else if (codeContent != null) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor,
                        fontSize = 12.5.sp,
                    ),
                ) {
                    append(codeContent)
                }
            }

            cursor = match.range.last + 1
        }

        if (cursor < raw.length) {
            append(raw.substring(cursor))
        }
    }

    CpText(
        text = annotated,
        style = MaterialTheme.typography.bodySmall.copy(
            lineHeight = 20.sp,
            fontSize = 13.5.sp,
        ),
        color = onSurface.copy(alpha = 0.92f),
        maxLines = Int.MAX_VALUE,
        modifier = modifier,
    )
}

/**
 * 结构化分类类型（严谨正式命名，无 Emoji）。
 */
enum class ChangelogCategory(val defaultTitle: String) {
    FEATURE("功能新增"),
    BUGFIX("问题修复"),
    PERFORMANCE("性能优化"),
    UI("界面与交互"),
    OTHER("其他改进"),
    WARNING("重要说明"),
    GENERAL("更新说明");

    @Composable
    fun meta(): CategoryMeta = when (this) {
        FEATURE -> CategoryMeta(
            icon = Icons.Default.AutoAwesome,
            accentColor = { MaterialTheme.colorScheme.primary },
            containerColor = { MaterialTheme.colorScheme.primaryContainer },
        )
        BUGFIX -> CategoryMeta(
            icon = Icons.Default.BugReport,
            accentColor = { MaterialTheme.colorScheme.tertiary },
            containerColor = { MaterialTheme.colorScheme.tertiaryContainer },
        )
        PERFORMANCE -> CategoryMeta(
            icon = Icons.Default.Bolt,
            accentColor = { MaterialTheme.colorScheme.secondary },
            containerColor = { MaterialTheme.colorScheme.secondaryContainer },
        )
        UI -> CategoryMeta(
            icon = Icons.Default.Palette,
            accentColor = { MaterialTheme.colorScheme.primary },
            containerColor = { MaterialTheme.colorScheme.primaryContainer },
        )
        WARNING -> CategoryMeta(
            icon = Icons.Default.Warning,
            accentColor = { MaterialTheme.colorScheme.error },
            containerColor = { MaterialTheme.colorScheme.errorContainer },
        )
        OTHER -> CategoryMeta(
            icon = Icons.Default.Build,
            accentColor = { MaterialTheme.colorScheme.onSurfaceVariant },
            containerColor = { MaterialTheme.colorScheme.surfaceVariant },
        )
        GENERAL -> CategoryMeta(
            icon = Icons.Default.Info,
            accentColor = { MaterialTheme.colorScheme.primary },
            containerColor = { MaterialTheme.colorScheme.primaryContainer },
        )
    }
}

class CategoryMeta(
    val icon: ImageVector,
    val accentColor: @Composable () -> Color,
    val containerColor: @Composable () -> Color,
)

data class ChangelogLine(
    val text: String,
    val isSubItem: Boolean = false,
)

data class ChangelogSection(
    val title: String,
    val category: ChangelogCategory,
    val items: List<ChangelogLine>,
)

data class ParsedChangelog(
    val sections: List<ChangelogSection>,
    val uncategorized: List<ChangelogLine>,
    val compareUrl: String? = null,
)

/**
 * 过滤文本中的 Emoji 表情符号，确保界面干净正式。
 * 使用基于 Char 的 Unicode 码点解析，全平台安全且无正则代理对歧义。
 */
internal fun stripEmojis(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val high = c.code
            val low = text[i + 1].code
            val codePoint = 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
            val isEmoji = codePoint in 0x1F000..0x1FAFF
            if (!isEmoji) {
                sb.append(c).append(text[i + 1])
            }
            i += 2
        } else {
            val code = c.code
            val isEmoji = (code in 0x2600..0x27BF) || (code in 0xFE00..0xFE0F) || code == 0x200D
            if (!isEmoji) {
                sb.append(c)
            }
            i += 1
        }
    }
    return sb.toString().replace(Regex("""[ \t]{2,}"""), " ").trim()
}

/**
 * 解析更新说明，支持识别结构化分类、条目与比较链接。
 */
internal fun parseChangelog(changelog: String?): ParsedChangelog {
    if (changelog.isNullOrBlank()) {
        return ParsedChangelog(emptyList(), emptyList(), null)
    }

    val lines = changelog.lines()
    val sections = mutableListOf<ChangelogSection>()
    val uncategorized = mutableListOf<ChangelogLine>()
    var compareUrl: String? = null

    var currentCategory: ChangelogCategory? = null
    var currentTitle: String? = null
    var currentItems = mutableListOf<ChangelogLine>()

    fun commitCurrentSection() {
        val title = currentTitle
        val category = currentCategory
        if (title != null && category != null && currentItems.isNotEmpty()) {
            sections.add(
                ChangelogSection(
                    title = title,
                    category = category,
                    items = currentItems.toList(),
                ),
            )
        } else if (currentItems.isNotEmpty()) {
            uncategorized.addAll(currentItems)
        }
        currentItems = mutableListOf()
        currentTitle = null
        currentCategory = null
    }

    for (rawLine in lines) {
        val rawTrimmed = rawLine.trim()
        if (rawTrimmed.isBlank() || rawTrimmed == "---") continue

        // 识别比较链接
        if (rawTrimmed.contains("compare/") || rawTrimmed.contains("commits/") || isFullChangelogHeading(rawTrimmed)) {
            val url = extractUrl(rawTrimmed)
            if (url != null) {
                compareUrl = url
                continue
            }
        }

        // 识别分类标题 (# / ## / ###)
        if (rawTrimmed.startsWith("#")) {
            val headingText = rawTrimmed.replace(Regex("""^#+\s*"""), "").trim()
            if (headingText.isBlank() || isFullChangelogHeading(headingText)) {
                extractUrl(headingText)?.let { compareUrl = it }
                continue
            }
            if (headingText.contains("更新内容") || headingText.contains("What's Changed", ignoreCase = true)) {
                continue
            }

            commitCurrentSection()
            val category = classifyCategory(headingText)
            currentCategory = category
            currentTitle = category.defaultTitle
            continue
        }

        // 识别列表项 (- / *)
        val isSubBullet = rawLine.startsWith("    - ") || rawLine.startsWith("    * ") ||
            rawLine.startsWith("  - ") || rawLine.startsWith("  * ") || rawLine.startsWith("\t- ")
        val isBullet = rawTrimmed.startsWith("- ") || rawTrimmed.startsWith("* ")

        if (isBullet) {
            val content = stripEmojis(rawTrimmed.removePrefix("- ").removePrefix("* ").trim())
            if (content.isNotBlank()) {
                currentItems.add(ChangelogLine(text = content, isSubItem = isSubBullet))
            }
        } else {
            // 普通段落文本
            val content = stripEmojis(rawTrimmed)
            if (content.isNotBlank()) {
                if (currentTitle != null) {
                    currentItems.add(ChangelogLine(text = content, isSubItem = false))
                } else {
                    uncategorized.add(ChangelogLine(text = content, isSubItem = false))
                }
            }
        }
    }

    commitCurrentSection()
    return ParsedChangelog(sections, uncategorized, compareUrl)
}

/**
 * 分类判定辅助函数。
 */
internal fun classifyCategory(title: String): ChangelogCategory {
    val clean = title.trim()
    return when {
        clean.contains("新功能") || clean.contains("新特性") || clean.contains("功能新增") ||
            clean.contains("Features", ignoreCase = true) -> ChangelogCategory.FEATURE

        clean.contains("Bug 修复") || clean.contains("问题修复") || clean.contains("缺陷修复") ||
            clean.contains("Fixes", ignoreCase = true) || clean.contains("Bug Fixes", ignoreCase = true) -> ChangelogCategory.BUGFIX

        clean.contains("性能") || clean.contains("优化") ||
            clean.contains("Performance", ignoreCase = true) -> ChangelogCategory.PERFORMANCE

        clean.contains("界面") || clean.contains("交互") ||
            clean.contains("UI", ignoreCase = true) -> ChangelogCategory.UI

        clean.contains("破坏性") || clean.contains("警告") || clean.contains("重要") ||
            clean.contains("Breaking", ignoreCase = true) -> ChangelogCategory.WARNING

        clean.contains("其他") || clean.contains("构建") || clean.contains("调整") ||
            clean.contains("Maintenance", ignoreCase = true) -> ChangelogCategory.OTHER

        else -> ChangelogCategory.GENERAL
    }
}

private fun isFullChangelogHeading(text: String): Boolean =
    text.contains("Full Changelog") || text.contains("完整变更")

private fun extractUrl(text: String): String? {
    val regex = Regex("""https?://[^\s)\]]+""")
    return regex.find(text)?.value
}

private data class TargetPackageInfo(
    val icon: ImageVector,
    val label: String,
)

private fun resolveTargetPackageInfo(assetName: String?, s: AppUpdateStrings): TargetPackageInfo {
    if (assetName != null) {
        if (assetName.endsWith(".apk", ignoreCase = true)) {
            return TargetPackageInfo(Icons.Default.PhoneAndroid, s.packageAndroid)
        }
        if (assetName.endsWith("-Setup.exe", ignoreCase = true) || assetName.endsWith(".exe", ignoreCase = true)) {
            return TargetPackageInfo(Icons.Default.DesktopWindows, s.packageWindows)
        }
        if (assetName.endsWith(".deb", ignoreCase = true)) {
            return TargetPackageInfo(Icons.Default.Terminal, s.packageLinuxDeb)
        }
        if (assetName.endsWith(".tar.gz", ignoreCase = true)) {
            return TargetPackageInfo(Icons.Default.Terminal, s.packageLinuxTar)
        }
        return TargetPackageInfo(Icons.Default.Terminal, assetName)
    }

    return when {
        isAndroidPlatform() -> TargetPackageInfo(Icons.Default.PhoneAndroid, s.packageAndroid)
        desktopPlatform() == "windows" -> TargetPackageInfo(Icons.Default.DesktopWindows, s.packageWindows)
        desktopPlatform() == "linux" -> TargetPackageInfo(Icons.Default.Terminal, s.packageLinux)
        else -> TargetPackageInfo(Icons.Default.Terminal, s.packageUniversal)
    }
}
