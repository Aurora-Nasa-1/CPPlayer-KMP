@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package cp.player.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.DialogProperties
import cp.player.app.i18n.AppUpdateStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openUrl
import cp.player.app.ui.theme.cpSheetInnerSurface
import cp.player.app.ui.theme.cpSheetSurface
import cp.player.app.ui.util.formatBytes
import cp.player.app.update.AppUpdateChecker

/** 软件更新弹窗：沿用 Kazumi 的信息顺序，用主题色区分安装包与主要操作。 */
@Composable
fun AppUpdateDialog(
    result: AppUpdateChecker.UpdateResult,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDownloadAsset: (AppUpdateChecker.UpdateAsset) -> Unit,
    isAutoCheck: Boolean = false,
    onDisableAutoUpdate: (() -> Unit)? = null,
) {
    val s = cpStrings().update
    val colors = MaterialTheme.colorScheme
    val installTypes = resolveInstallTypes(result, s)
    val parsedChangelog = remember(result.changelog) { parseReleaseChangelog(result.changelog) }

    CpAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.NewReleases, contentDescription = null)
                    }
                }
                CpText(
                    text = s.titleWithVersion(result.versionName),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    maxLines = Int.MAX_VALUE,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChangelogSectionCard(
                    parsed = parsedChangelog,
                    defaultText = s.defaultChangelog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 56.dp),
                )

                result.publishedAt?.takeIf { it.isNotBlank() }?.take(10)?.let { date ->
                    ExpressiveMetaChip(
                        icon = Icons.Default.CalendarToday,
                        text = s.publishedAt(date),
                    )
                }

                if (installTypes.isNotEmpty()) {
                    UpdateInstallPackages(s.installPackage, installTypes, onDownloadAsset)
                }
            }
        },
        confirmButton = {
            AppUpdateActions(
                releaseUrl = result.releaseUrl,
                onDismiss = onDismiss,
                onDownload = onDownload,
                onDisableAutoUpdate = onDisableAutoUpdate.takeIf { isAutoCheck },
            )
        },
    )
}

@Composable
private fun ChangelogSectionCard(
    parsed: ParsedReleaseChangelog,
    defaultText: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = cpSheetInnerSurface(),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f)),
    ) {
        ProvideCpTextFullDisplay {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (parsed.blocks.isEmpty()) {
                    CpText(
                        text = defaultText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                        ),
                        color = colors.onSurface.copy(alpha = 0.9f),
                        maxLines = Int.MAX_VALUE,
                    )
                } else {
                    val showVersionHeaders = parsed.blocks.size > 1 ||
                        parsed.blocks.any { !it.version.isNullOrBlank() }
                    parsed.blocks.forEachIndexed { blockIdx, block ->
                        if (blockIdx > 0) {
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (showVersionHeaders && !block.version.isNullOrBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = colors.primaryContainer,
                                        contentColor = colors.onPrimaryContainer,
                                    ) {
                                        CpText(
                                            text = block.version,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                        )
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = colors.outlineVariant.copy(alpha = 0.3f),
                                    )
                                }
                            }

                            for (section in block.sections) {
                                ExpressiveCategorySection(section)
                            }

                            if (block.uncategorized.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (item in block.uncategorized) {
                                        ChangelogItemRow(
                                            item = item,
                                            dotColor = colors.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateInstallPackages(
    title: String,
    installTypes: List<UpdateInstallType>,
    onDownloadAsset: (AppUpdateChecker.UpdateAsset) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = cpSheetInnerSurface(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CpText(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp),
                color = colors.onSurfaceVariant,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.padding(start = 4.dp),
            )

            for (installType in installTypes) {
                OutlinedButton(
                    onClick = { onDownloadAsset(installType.asset) },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        CpText(
                            text = installType.label,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp),
                            maxLines = Int.MAX_VALUE,
                        )
                        installType.asset.size?.takeIf { it > 0L }?.let { size ->
                            CpText(
                                text = formatBytes(size),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppUpdateActions(
    releaseUrl: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDisableAutoUpdate: (() -> Unit)?,
) {
    val s = cpStrings().update
    val secondaryColors = ButtonDefaults.textButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (onDisableAutoUpdate != null) {
            TextButton(onClick = onDisableAutoUpdate, colors = secondaryColors) {
                CpText(s.disableAutoUpdate, maxLines = Int.MAX_VALUE)
            }
        }
        TextButton(onClick = onDismiss, colors = secondaryColors) {
            CpText(s.later, maxLines = Int.MAX_VALUE)
        }
        if (releaseUrl.isNotBlank()) {
            FilledTonalButton(
                onClick = { openUrl(releaseUrl) },
                shapes = ButtonDefaults.shapes(),
            ) {
                CpText(s.releasePage, maxLines = Int.MAX_VALUE)
            }
        }
        Button(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            CpText(s.downloadNow, maxLines = Int.MAX_VALUE)
        }
    }
}

private data class UpdateInstallType(
    val label: String,
    val asset: AppUpdateChecker.UpdateAsset,
)

/** 按 Kazumi `_detectAvailableInstallationTypes` 的平台分支生成安装类型行。 */
private fun resolveInstallTypes(
    result: AppUpdateChecker.UpdateResult,
    s: AppUpdateStrings,
): List<UpdateInstallType> {
    if (desktopPlatform() == "linux") return emptyList()

    val assets = result.availableAssets.ifEmpty {
        val url = result.downloadUrl ?: return emptyList()
        listOf(
            AppUpdateChecker.UpdateAsset(
                name = result.assetName ?: "CPPlayer-${result.versionName}",
                downloadUrl = url,
                sha256 = result.assetSha256,
                size = result.assetSize,
            ),
        )
    }

    return when {
        isAndroidPlatform() -> assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .map { UpdateInstallType(s.packageAndroid, it) }

        desktopPlatform() == "windows" -> buildList {
            assets.firstOrNull {
                it.name.endsWith("-Setup.exe", ignoreCase = true) || it.name.endsWith(".exe", ignoreCase = true)
            }?.let { add(UpdateInstallType(s.packageWindows, it)) }
            assets.firstOrNull {
                it.name.endsWith("-portable.zip", ignoreCase = true) ||
                    (it.name.endsWith(".zip", ignoreCase = true) && !it.name.contains("nupkg", ignoreCase = true))
            }?.let { add(UpdateInstallType(s.packageWindowsPortable, it)) }
        }

        desktopPlatform() == "macos" -> assets
            .filter { it.name.endsWith(".dmg", ignoreCase = true) }
            .map { UpdateInstallType(s.packageMacos, it) }

        else -> emptyList()
    }
}

/**
 * 分类章节区块：胶囊标题 + 条目数 + 列表项。
 */
@Composable
private fun ExpressiveCategorySection(
    section: ChangelogSection,
    modifier: Modifier = Modifier,
) {
    val meta = section.category.meta()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = meta.accentColor().copy(alpha = 0.14f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = meta.icon,
                        contentDescription = null,
                        tint = meta.accentColor(),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    CpText(
                        text = section.title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = meta.accentColor(),
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = cpSheetSurface(),
            ) {
                CpText(
                    text = "${section.items.size}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            for (item in section.items) {
                ChangelogItemRow(item, dotColor = meta.accentColor())
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
                .padding(top = 8.5.dp, end = 10.dp)
                .size(if (item.isSubItem) 4.dp else 6.dp)
                .clip(CircleShape)
                .background(if (item.isSubItem) dotColor.copy(alpha = 0.6f) else dotColor),
        )
        FormattedChangelogText(
            raw = item.text,
            isSubItem = item.isSubItem,
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
    containerColor: Color = cpSheetInnerSurface(),
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            CpText(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * 格式化渲染 Markdown 风格行（支持 **粗体**、`行内代码`）。
 */
@Composable
private fun FormattedChangelogText(
    raw: String,
    isSubItem: Boolean = false,
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
                        background = primaryColor.copy(alpha = 0.10f),
                        fontSize = if (isSubItem) 12.5.sp else 13.5.sp,
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
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = if (isSubItem) 21.sp else 23.sp,
            fontSize = if (isSubItem) 14.sp else 15.sp,
        ),
        color = onSurface.copy(alpha = if (isSubItem) 0.78f else 0.94f),
        maxLines = Int.MAX_VALUE,
        modifier = modifier,
    )
}

/**
 * 结构化分类类型（严谨正式命名，无 Emoji）。
 */
enum class ChangelogCategory(val defaultTitle: String) {
    FEATURE("功能新增"),
    IMPROVEMENT("优化改进"),
    PERFORMANCE("性能优化"),
    UI("界面与交互"),
    BUGFIX("问题修复"),
    WARNING("重要说明"),
    OTHER("其他改进"),
    GENERAL("更新说明");

    @Composable
    fun meta(): CategoryMeta = when (this) {
        FEATURE -> CategoryMeta(
            icon = Icons.Default.AutoAwesome,
            accentColor = { MaterialTheme.colorScheme.primary },
            containerColor = { MaterialTheme.colorScheme.primaryContainer },
        )
        IMPROVEMENT -> CategoryMeta(
            icon = Icons.Default.Tune,
            accentColor = { MaterialTheme.colorScheme.secondary },
            containerColor = { MaterialTheme.colorScheme.secondaryContainer },
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
            containerColor = { cpSheetInnerSurface() },
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

data class ChangelogVersionBlock(
    val version: String?,
    val sections: List<ChangelogSection>,
    val uncategorized: List<ChangelogLine> = emptyList(),
)

data class ParsedReleaseChangelog(
    val blocks: List<ChangelogVersionBlock>,
    val compareUrl: String? = null,
)

private val versionHeadingRegex = Regex(
    """^(?:debug-)?v?\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$""",
    RegexOption.IGNORE_CASE,
)
private val conventionalCommitRegex = Regex(
    """^(feat|fix|perf|style|ui|refactor|chore|build|ci|test|docs)(?:\(([^)]+)\))?:\s*(.+)$""",
    RegexOption.IGNORE_CASE,
)
private val trailingCommitShaRegex = Regex("""\s+\([0-9a-f]{6,12}\)$""", RegexOption.IGNORE_CASE)
private val markdownLinkRegex = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
private val githubAutoPrAttributionRegex = Regex("""\s+by\s+@[A-Za-z0-9-]+(?:\s+in\s+https?://\S+/pull/(\d+))?$""", RegexOption.IGNORE_CASE)
private val horizontalRuleRegex = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")

private val featureLeadRegex = Regex("""^(?:新增了?|加入了?|支持了?|推出了?|添加了?)""")
private val improvementLeadRegex = Regex("""^(?:优化了?|改进了?|提升了?|完善了?|精简了?|增强了?)""")
private val bugfixLeadRegex = Regex("""^(?:修复了?|解决了?|修正了?|处理了?|消除了?)""")
private val adjustmentLeadRegex = Regex("""^(?:调整了?|移除了?|删除了?|重构了?|变更了?|迁移了?|统一了?|规范了?)""")

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

/** 裁剪单行更新日志中的噪音（Emoji、Markdown 链接 URL、GitHub 自动生成后缀、末尾短 SHA）。 */
internal fun cleanChangelogLine(raw: String): String {
    var cleaned = stripEmojis(raw)
    cleaned = markdownLinkRegex.replace(cleaned) { it.groupValues[1] }
    cleaned = githubAutoPrAttributionRegex.replace(cleaned) { match ->
        val pr = match.groupValues[1]
        if (pr.isNotBlank()) " (#$pr)" else ""
    }
    cleaned = trailingCommitShaRegex.replace(cleaned, "")
    return cleaned.replace(Regex("""[ \t]{2,}"""), " ").trim()
}

/**
 * 解析传统结构化更新说明（保留给既有单测与显式分类调用）。
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
        if (rawTrimmed.isBlank() || horizontalRuleRegex.matches(rawTrimmed)) continue

        if (rawTrimmed.contains("compare/") || rawTrimmed.contains("commits/") || isFullChangelogHeading(rawTrimmed)) {
            val url = extractUrl(rawTrimmed)
            if (url != null) {
                compareUrl = url
                continue
            }
        }

        if (rawTrimmed.startsWith("#")) {
            val headingText = rawTrimmed.replace(Regex("""^#+\s*"""), "").trim()
            if (isTerminalReleaseSectionHeading(headingText)) break
            if (headingText.isBlank() || isFullChangelogHeading(headingText)) {
                extractUrl(headingText)?.let { compareUrl = it }
                continue
            }
            if (isWhatChangedHeading(headingText)) {
                continue
            }

            commitCurrentSection()
            val category = classifyCategory(headingText)
            currentCategory = category
            currentTitle = category.defaultTitle
            continue
        }

        val isSubBullet = rawLine.startsWith("    - ") || rawLine.startsWith("    * ") ||
            rawLine.startsWith("  - ") || rawLine.startsWith("  * ") || rawLine.startsWith("\t- ")
        val isBullet = rawTrimmed.startsWith("- ") || rawTrimmed.startsWith("* ") || rawTrimmed.startsWith("• ")

        if (isBullet) {
            val content = cleanChangelogLine(rawTrimmed.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim())
            if (content.isNotBlank()) {
                currentItems.add(ChangelogLine(text = content, isSubItem = isSubBullet))
            }
        } else {
            val content = cleanChangelogLine(rawTrimmed)
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
 * 解析当前实际 Release 格式：
 * - 多版本聚合 (`### v1.13.0` + `### v1.12.5`)；
 * - 当前 AI 生成扁平列表 (`- 新增了...` / `- 优化了...` / `- 修复了...` / `- 调整了...`) 自动按动词聚类；
 * - 传统显式分类 (`### 功能新增` / `### 问题修复`)；
 * - AI 失败回退 git log (`### feat(scope): ... (sha)`) 去哈希并折叠内部噪音。
 */
internal fun parseReleaseChangelog(changelog: String?): ParsedReleaseChangelog {
    if (changelog.isNullOrBlank()) {
        return ParsedReleaseChangelog(emptyList(), null)
    }

    var compareUrl: String? = null
    val rawBlocks = mutableListOf<Pair<String?, MutableList<String>>>()
    var currentVersion: String? = null
    var currentLines = mutableListOf<String>()

    fun flushBlock() {
        if (currentLines.any { it.isNotBlank() }) {
            rawBlocks += currentVersion to currentLines
        }
        currentLines = mutableListOf()
    }

    for (rawLine in changelog.lines()) {
        val trimmed = rawLine.trim()
        if (trimmed.startsWith("#")) {
            val headingText = stripEmojis(trimmed.replace(Regex("""^#+\s*"""), "").trim())
            if (isTerminalReleaseSectionHeading(headingText)) break
            if (isFullChangelogHeading(headingText)) {
                extractUrl(headingText)?.let { compareUrl = it }
                continue
            }
            if (isWhatChangedHeading(headingText)) continue
            if (versionHeadingRegex.matches(headingText)) {
                flushBlock()
                currentVersion = headingText
                continue
            }
        }
        if (trimmed.contains("compare/") || trimmed.contains("commits/") || isFullChangelogHeading(trimmed)) {
            val url = extractUrl(trimmed)
            if (url != null) {
                compareUrl = url
                continue
            }
        }
        currentLines += rawLine
    }
    flushBlock()

    val blocks = rawBlocks.mapNotNull { (version, lines) ->
        parseVersionBlock(version, lines)
    }
    return ParsedReleaseChangelog(blocks = blocks, compareUrl = compareUrl)
}

private data class CommitEntry(
    val category: ChangelogCategory,
    val title: String,
    val isInternalNoise: Boolean,
    val subItems: List<ChangelogLine>,
)

private fun parseVersionBlock(version: String?, lines: List<String>): ChangelogVersionBlock? {
    val headings = lines.mapNotNull { line ->
        val t = line.trim()
        if (!t.startsWith("#")) null else stripEmojis(t.replace(Regex("""^#+\s*"""), "").trim())
    }
    val hasCommitHeadings = headings.any {
        conventionalCommitRegex.matches(it) || trailingCommitShaRegex.containsMatchIn(it)
    }
    val hasExplicitCategoryHeadings = headings.any {
        it.isNotBlank() &&
            !conventionalCommitRegex.matches(it) &&
            !trailingCommitShaRegex.containsMatchIn(it) &&
            classifyCategory(it) != ChangelogCategory.GENERAL
    }

    if (hasCommitHeadings && !hasExplicitCategoryHeadings) {
        val commits = parseCommitEntries(lines)
        val hasUserFacing = commits.any { !it.isInternalNoise }
        val filtered = if (hasUserFacing) commits.filter { !it.isInternalNoise } else commits
        val grouped = linkedMapOf<ChangelogCategory, MutableList<ChangelogLine>>()
        for (entry in filtered) {
            val list = grouped.getOrPut(entry.category) { mutableListOf() }
            list += ChangelogLine(text = entry.title, isSubItem = false)
            list += entry.subItems
        }
        val sections = buildOrderedSections(grouped)
        return if (sections.isEmpty()) null else ChangelogVersionBlock(version, sections)
    }

    val parsed = parseChangelog(lines.joinToString("\n"))
    if (parsed.sections.isNotEmpty()) {
        return ChangelogVersionBlock(version, parsed.sections, parsed.uncategorized)
    }

    if (parsed.uncategorized.isEmpty()) return null

    // 当前 AI 扁平列表（- 新增了... / - 优化了... / - 修复了... / - 调整了...）自动聚类
    val grouped = linkedMapOf<ChangelogCategory, MutableList<ChangelogLine>>()
    val ungrouped = mutableListOf<ChangelogLine>()
    var lastCategory: ChangelogCategory? = null

    for (item in parsed.uncategorized) {
        val cat = if (item.isSubItem && lastCategory != null) {
            lastCategory
        } else {
            classifyBulletByLead(item.text).also { lastCategory = it }
        }
        if (cat != null) {
            grouped.getOrPut(cat) { mutableListOf() } += item
        } else {
            ungrouped += item
        }
    }

    if (grouped.isEmpty()) {
        return ChangelogVersionBlock(version, emptyList(), ungrouped)
    }

    if (ungrouped.isNotEmpty()) {
        grouped.getOrPut(ChangelogCategory.GENERAL) { mutableListOf() } += ungrouped
    }
    return ChangelogVersionBlock(version, buildOrderedSections(grouped), emptyList())
}

private fun parseCommitEntries(lines: List<String>): List<CommitEntry> {
    val entries = mutableListOf<CommitEntry>()
    var currentHead: String? = null
    var bodyLines = mutableListOf<String>()

    fun flush() {
        val head = currentHead ?: return
        val cleanHead = cleanChangelogLine(head)
        val m = conventionalCommitRegex.matchEntire(cleanHead)
        val (cat, title, isNoise) = if (m != null) {
            val type = m.groupValues[1].lowercase()
            val scope = m.groupValues[2].lowercase()
            val subject = m.groupValues[3].trim()
            val category = when (type) {
                "feat" -> ChangelogCategory.FEATURE
                "fix" -> ChangelogCategory.BUGFIX
                "perf" -> ChangelogCategory.PERFORMANCE
                "style", "ui" -> ChangelogCategory.UI
                else -> ChangelogCategory.OTHER
            }
            val noise = type in setOf("ci", "test", "docs", "build") ||
                (type == "chore" && scope in setOf("release", "deps", "ci", "i18n"))
            Triple(category, subject, noise)
        } else {
            Triple(ChangelogCategory.OTHER, cleanHead, false)
        }

        val subItems = bodyLines.mapNotNull { raw ->
            val t = raw.trim()
            if (t.isBlank() || horizontalRuleRegex.matches(t)) return@mapNotNull null
            val text = cleanChangelogLine(t.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim())
            if (text.isBlank()) null else ChangelogLine(text = text, isSubItem = true)
        }
        if (title.isNotBlank()) {
            entries += CommitEntry(category = cat, title = title, isInternalNoise = isNoise, subItems = subItems)
        }
        bodyLines = mutableListOf()
    }

    for (raw in lines) {
        val t = raw.trim()
        if (t.startsWith("#")) {
            flush()
            currentHead = t.replace(Regex("""^#+\s*"""), "").trim()
        } else {
            bodyLines += raw
        }
    }
    flush()
    return entries
}

private fun buildOrderedSections(
    grouped: Map<ChangelogCategory, List<ChangelogLine>>,
): List<ChangelogSection> {
    val order = listOf(
        ChangelogCategory.FEATURE,
        ChangelogCategory.IMPROVEMENT,
        ChangelogCategory.PERFORMANCE,
        ChangelogCategory.UI,
        ChangelogCategory.BUGFIX,
        ChangelogCategory.WARNING,
        ChangelogCategory.OTHER,
        ChangelogCategory.GENERAL,
    )
    return order.mapNotNull { cat ->
        val items = grouped[cat]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val title = if (cat == ChangelogCategory.OTHER) "调整与变更" else cat.defaultTitle
        ChangelogSection(title = title, category = cat, items = items)
    }
}

private fun classifyBulletByLead(text: String): ChangelogCategory? {
    val clean = text.trim()
    return when {
        featureLeadRegex.containsMatchIn(clean) -> ChangelogCategory.FEATURE
        improvementLeadRegex.containsMatchIn(clean) -> ChangelogCategory.IMPROVEMENT
        bugfixLeadRegex.containsMatchIn(clean) -> ChangelogCategory.BUGFIX
        adjustmentLeadRegex.containsMatchIn(clean) -> ChangelogCategory.OTHER
        else -> null
    }
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

        clean.contains("性能") || clean.contains("Performance", ignoreCase = true) -> ChangelogCategory.PERFORMANCE

        clean.contains("优化改进") || clean.contains("体验优化") || clean.contains("优化") -> ChangelogCategory.IMPROVEMENT

        clean.contains("界面") || clean.contains("交互") ||
            clean.contains("UI", ignoreCase = true) -> ChangelogCategory.UI

        clean.contains("破坏性") || clean.contains("警告") || clean.contains("重要") ||
            clean.contains("Breaking", ignoreCase = true) -> ChangelogCategory.WARNING

        clean.contains("其他") || clean.contains("构建") || clean.contains("调整") ||
            clean.contains("Maintenance", ignoreCase = true) -> ChangelogCategory.OTHER

        else -> ChangelogCategory.GENERAL
    }
}

private fun isWhatChangedHeading(text: String): Boolean =
    text.contains("更新内容") ||
        text.contains("更新日志") ||
        text.contains("What's Changed", ignoreCase = true) ||
        text.equals("Changelog", ignoreCase = true) ||
        text.equals("Release Notes", ignoreCase = true)

private fun isTerminalReleaseSectionHeading(text: String): Boolean =
    text == "下载" ||
        text == "安装" ||
        text.equals("Download", ignoreCase = true) ||
        text.equals("Downloads", ignoreCase = true) ||
        text.equals("New Contributors", ignoreCase = true)

private fun isFullChangelogHeading(text: String): Boolean =
    text.contains("Full Changelog", ignoreCase = true) || text.contains("完整变更")

private fun extractUrl(text: String): String? {
    val regex = Regex("""https?://[^\s)\]]+""")
    return regex.find(text)?.value
}
