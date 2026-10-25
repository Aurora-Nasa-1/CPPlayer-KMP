package cp.player.app.ui.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.component.CpBreakpoints
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpSpacing
import cp.player.app.ui.component.CpTwoPane
import cp.player.app.ui.component.LocalEmbeddedInPane
import cp.player.app.ui.component.MonetIcon
import cp.player.app.ui.component.ScrollColumn
import androidx.compose.foundation.layout.Arrangement
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsPage
import cp.player.app.ui.component.SettingsSearchBar
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.settingsRowContainer
import cp.player.app.ui.util.popOrNotify
import cp.player.app.ui.component.CpText

/**
 * 设置根页面。
 */
class SettingsScreen(private val embedded: Boolean = false) : Screen {
    @Composable
    override fun Content() {
        SettingsScreenContent(embedded = embedded)
    }
}

@Composable
private fun SettingsScreenContent(embedded: Boolean = false) {
    val navigator = LocalNavigator.current
    val strings = cpStrings()
    val entries = remember { settingsEntries() }
    var searchQuery by remember { mutableStateOf("") }

    val searchResults = remember(searchQuery, entries, strings) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else {
            val queryTokens = q.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val results = mutableListOf<SettingsSearchResult>()

            // 1. 优先搜索所有具体设置子项 (SubItemMatch)
            entries.forEach { entry ->
                entry.visibleSubItems().forEach { subItem ->
                    val title = subItem.titleOf(strings).lowercase()
                    val subtitle = subItem.subtitleOf?.invoke(strings)?.lowercase().orEmpty()
                    val section = subItem.sectionOf?.invoke(strings)?.lowercase().orEmpty()
                    val allKeywords = subItem.keywords.map { it.lowercase() }

                    val matches = queryTokens.all { token ->
                        title.contains(token) ||
                            subtitle.contains(token) ||
                            section.contains(token) ||
                            allKeywords.any { it.contains(token) }
                    }
                    if (matches) {
                        results.add(SettingsSearchResult.SubItemMatch(entry, subItem))
                    }
                }
            }

            // 2. 搜索一级分类入口 (EntryMatch)
            entries.forEach { entry ->
                val title = entry.titleOf(strings).lowercase()
                val subtitle = entry.subtitleOf(strings).lowercase()
                val allKeywords = entry.keywords.map { it.lowercase() }

                val matches = queryTokens.all { token ->
                    title.contains(token) ||
                        subtitle.contains(token) ||
                        allKeywords.any { it.contains(token) }
                }
                if (matches) {
                    results.add(SettingsSearchResult.EntryMatch(entry))
                }
            }

            results
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = CpBreakpoints.isExpanded(maxWidth)

        if (expanded) {
            var selectedId by remember { mutableStateOf(entries.firstOrNull()?.id) }
            CpTwoPane(
                rail = { railModifier ->
                    ScrollColumn(
                        modifier = railModifier.padding(
                            start = CpSpacing.formHorizontal,
                            end = CpSpacing.formHorizontal,
                            top = CpSpacing.formVertical,
                            bottom = CpSpacing.formBottomInset,
                        ),
                        verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                    ) {
                        SettingsSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "搜索设置项与功能...",
                        )
                        if (searchQuery.isNotBlank()) {
                            SettingsSearchResults(
                                results = searchResults,
                                selectedId = selectedId,
                                onSelectEntry = { entry -> selectedId = entry.id },
                                query = searchQuery,
                            )
                        } else {
                            SettingsGroupedList(
                                entries = entries,
                                selectedId = selectedId,
                                onSelect = { entry -> selectedId = entry.id },
                            )
                        }
                    }
                },
                detail = {
                    CompositionLocalProvider(LocalEmbeddedInPane provides true) {
                        val entry = entries.firstOrNull { it.id == selectedId } ?: entries.firstOrNull()
                        if (entry != null) {
                            val screen = remember(entry.id) { entry.screen() }
                            screen.Content()
                        }
                    }
                },
            )
            return@BoxWithConstraints
        }

        // 窄屏：push 到子页
        val list: @Composable () -> Unit = {
            SettingsSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "搜索设置项与功能...",
            )
            if (searchQuery.isNotBlank()) {
                SettingsSearchResults(
                    results = searchResults,
                    selectedId = null,
                    onSelectEntry = { entry -> navigator?.push(entry.screen()) },
                    query = searchQuery,
                )
            } else {
                SettingsGroupedList(
                    entries = entries,
                    selectedId = null,
                    onSelect = { entry -> navigator?.push(entry.screen()) },
                )
            }
        }

        if (embedded) {
            SettingsPage(Modifier.fillMaxSize()) { list() }
            return@BoxWithConstraints
        }

        CpRouteScaffold(
            title = strings.settings.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) { list() }
        }
    }
}

@Composable
private fun SettingsSearchResults(
    results: List<SettingsSearchResult>,
    selectedId: String?,
    onSelectEntry: (SettingsEntry) -> Unit,
    query: String,
) {
    if (results.isEmpty()) {
        SettingsNote("未找到与「$query」相匹配的设置项，请尝试其他关键词。")
        return
    }

    val subItemMatches = remember(results) {
        results.filterIsInstance<SettingsSearchResult.SubItemMatch>()
    }
    val entryMatches = remember(results) {
        results.filterIsInstance<SettingsSearchResult.EntryMatch>()
    }

    if (subItemMatches.isNotEmpty()) {
        SettingsSection("具体设置项 (${subItemMatches.size})") {
            subItemMatches.forEachIndexed { index, match ->
                SettingsSubItemRow(
                    entry = match.entry,
                    subItem = match.subItem,
                    index = index,
                    total = subItemMatches.size,
                    isSelected = match.entry.id == selectedId,
                    onClick = { onSelectEntry(match.entry) },
                )
            }
        }
    }

    if (entryMatches.isNotEmpty()) {
        SettingsSection("设置分类 (${entryMatches.size})") {
            entryMatches.forEachIndexed { index, match ->
                SettingsRow(
                    entry = match.entry,
                    index = index,
                    total = entryMatches.size,
                    isSelected = match.entry.id == selectedId,
                    onClick = { onSelectEntry(match.entry) },
                )
            }
        }
    }
}

@Composable
private fun SettingsSubItemRow(
    entry: SettingsEntry,
    subItem: SettingsSubItem,
    index: Int,
    total: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val strings = cpStrings()
    val breadcrumb = remember(entry, subItem, strings) {
        val entryTitle = entry.titleOf(strings)
        val sectionTitle = subItem.sectionOf?.invoke(strings)
        if (sectionTitle.isNullOrBlank() || sectionTitle == entryTitle) {
            entryTitle
        } else {
            "$entryTitle › $sectionTitle"
        }
    }

    SettingsClickItem(
        title = subItem.titleOf(strings),
        subtitle = subItem.subtitleOf?.invoke(strings),
        index = index,
        total = total,
        onClick = onClick,
        selected = isSelected,
        leadingContent = {
            MonetIcon(
                icon = entry.icon,
                containerColor = entry.accent.container(),
                contentColor = entry.accent.content(),
            )
        },
        trailingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                CpText(
                    text = breadcrumb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        },
    )
}

/** 按 [SettingsGroup] 分组的入口列表；[selectedId] 非空时高亮对应行（宽屏左栏用）。 */
@Composable
private fun SettingsGroupedList(
    entries: List<SettingsEntry>,
    selectedId: String?,
    onSelect: (SettingsEntry) -> Unit,
) {
    SettingsGroup.entries.forEach { group ->
        val groupEntries = entries.filter { it.group == group }
        if (groupEntries.isEmpty()) return@forEach
        // 组标题在这里才求值：Registry 里存的是取值函数而不是现成的字符串
        // （那里既读不到 CompositionLocal，也不该依赖组合作用域）。
        SettingsSection(cpStrings().let(group.titleOf)) {
            groupEntries.forEachIndexed { index, entry ->
                SettingsRow(
                    entry = entry,
                    index = index,
                    total = groupEntries.size,
                    isSelected = entry.id == selectedId,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
}

/**
 * 设置首页的分类行：`SettingsClickItem` + `MonetIcon` 的组合。
 *
 * 整组是一张分段卡片（`index`/`total` 连续），行高下限 68dp，容器色按明暗取
 * [settingsRowContainer]；宽屏左栏里当前选中的行由 `SettingsClickItem(selected = true)`
 * 换成统一的「当前项」配色 —— 不要在这里自己传 `containerColor`，
 * 那正是「左栏选中 / 当前账号 / 当前音源」三个地方三种颜色的来源。
 */
@Composable
private fun SettingsRow(
    entry: SettingsEntry,
    index: Int,
    total: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    val strings = cpStrings()
    SettingsClickItem(
        title = entry.titleOf(strings),
        subtitle = entry.subtitleOf(strings),
        index = index,
        total = total,
        onClick = onClick,
        selected = isSelected,
        leadingContent = {
            MonetIcon(
                icon = entry.icon,
                containerColor = entry.accent.container(),
                contentColor = entry.accent.content(),
            )
        },
    )
}

@Composable
private fun SettingsAccent.container(): Color = when (this) {
    SettingsAccent.PRIMARY -> MaterialTheme.colorScheme.primaryFixed
    SettingsAccent.SECONDARY -> MaterialTheme.colorScheme.secondaryFixed
    SettingsAccent.TERTIARY -> MaterialTheme.colorScheme.tertiaryFixed
}

@Composable
private fun SettingsAccent.content(): Color = when (this) {
    SettingsAccent.PRIMARY -> MaterialTheme.colorScheme.onPrimaryFixed
    SettingsAccent.SECONDARY -> MaterialTheme.colorScheme.onSecondaryFixed
    SettingsAccent.TERTIARY -> MaterialTheme.colorScheme.onTertiaryFixed
}
