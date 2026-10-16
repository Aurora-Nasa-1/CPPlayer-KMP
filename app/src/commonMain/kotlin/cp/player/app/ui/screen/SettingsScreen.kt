package cp.player.app.ui.screen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * 设置根页。
 *
 * ### 与重构前的差异
 *
 * 1. **按分组渲染，不再是 13 行平铺。** 分组与顺序全部来自 [settingsEntries]，
 *    本文件不再持有任何「哪个入口排第几」的知识。
 * 2. **删掉了三栏布局。** 旧版在宽屏下拼出「分类导航栏 + 列表 + 详情」三栏，而那个
 *    分类导航（`SettingsCategoryRail`）硬编码了 4 个标签、永远高亮 index 0，
 *    映射关系（0→外观 / 1→播放 / 2→交互逻辑 / 其余→音源管理）与实际列表毫无关系。
 *    两栏已经足够，第三栏是纯粹的冗余。
 * 3. **宽屏默认选中第一项。** 旧版右侧是一句「选择中间菜单查看设置内容」的占位 ——
 *    用户点进设置先看到一句让人困惑的空态。
 * 4. **只有一套详情渲染路径。** 旧版 `toScreen()` 与 `DesktopSettingsDetail()` 是两张
 *    平行的映射表，改一处漏一处的风险长期存在；现在两者都走 [SettingsEntry.screen]。
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

    val filteredEntries = remember(searchQuery, entries, strings) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) entries
        else {
            entries.filter { entry ->
                val title = entry.titleOf(strings).lowercase()
                val subtitle = entry.subtitleOf(strings).lowercase()
                title.contains(q) || subtitle.contains(q) || entry.keywords.any { it.lowercase().contains(q) }
            }
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
                                entries = filteredEntries,
                                selectedId = selectedId,
                                onSelect = { entry -> selectedId = entry.id },
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
                    entries = filteredEntries,
                    selectedId = null,
                    onSelect = { entry -> navigator?.push(entry.screen()) },
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
    entries: List<SettingsEntry>,
    selectedId: String?,
    onSelect: (SettingsEntry) -> Unit,
    query: String,
) {
    if (entries.isEmpty()) {
        SettingsNote("未找到与「$query」相匹配的设置项，请尝试其他关键词。")
    } else {
        SettingsSection("搜索结果 (找到 ${entries.size} 项)") {
            entries.forEachIndexed { index, entry ->
                SettingsRow(
                    entry = entry,
                    index = index,
                    total = entries.size,
                    isSelected = entry.id == selectedId,
                    onClick = { onSelect(entry) },
                )
            }
        }
    }
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
