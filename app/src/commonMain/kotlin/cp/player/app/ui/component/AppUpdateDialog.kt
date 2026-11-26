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
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.window.DialogProperties
import cp.player.app.i18n.AppUpdateStrings
import cp.player.app.i18n.cpStrings
import cp.player.app.platform.desktopPlatform
import cp.player.app.platform.isAndroidPlatform
import cp.player.app.platform.openUrl
import cp.player.app.ui.theme.cpSheetInnerSurface
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

    CpAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 540.dp)
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
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CpText(
                    text = stripEmojis(result.changelog?.takeIf { it.isNotBlank() } ?: s.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = Int.MAX_VALUE,
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CpText(
                text = title,
                style = MaterialTheme.typography.labelMedium,
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
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
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
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = Int.MAX_VALUE,
                        )
                        installType.asset.size?.takeIf { it > 0L }?.let { size ->
                            CpText(
                                text = formatBytes(size),
                                style = MaterialTheme.typography.bodySmall,
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
    s: cp.player.app.i18n.AppUpdateStrings,
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
                    color = cpSheetInnerSurface(),
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
    containerColor: Color = cpSheetInnerSurface(),
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
