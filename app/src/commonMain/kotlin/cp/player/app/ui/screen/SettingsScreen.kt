package cp.player.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.CpTwoPane
import cp.player.app.ui.component.LocalEmbeddedInPane
import cp.player.app.ui.component.LocalIsExpanded
import cp.player.app.ui.component.MonetIcon
import cp.player.app.ui.component.ScrollColumn
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.component.settingsRowContainer

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
    val expanded = LocalIsExpanded.current
    val navigator = LocalNavigator.current
    val entries = remember { settingsEntries() }

    if (expanded) {
        var selectedId by remember { mutableStateOf(entries.firstOrNull()?.id) }
        CpTwoPane(
            rail = { railModifier ->
                ScrollColumn(
                    modifier = railModifier.padding(top = 8.dp, bottom = 24.dp),
                ) {
                    SettingsGroupedList(
                        entries = entries,
                        selectedId = selectedId,
                        onSelect = { entry -> selectedId = entry.id },
                    )
                }
            },
            detail = {
                // 右栏渲染的是**另一个路由页**（`SettingsEntry.screen()`），它自己分不清
                // 「被直接 push」和「被塞进右栏」—— 两种情况对外壳的要求正好相反。
                // 由容器在这里声明，`CpRouteScaffold` 才不会在右栏里再画一条顶栏（栏中栏）。
                CompositionLocalProvider(LocalEmbeddedInPane provides true) {
                    val entry = entries.firstOrNull { it.id == selectedId } ?: entries.firstOrNull()
                    if (entry != null) {
                        // remember(entry.id)：否则每次重组都新建一个 Screen 实例
                        val screen = remember(entry.id) { entry.screen() }
                        screen.Content()
                    }
                }
            },
        )
        return
    }

    val list: @Composable () -> Unit = {
        SettingsGroupedList(
            entries = entries,
            selectedId = null,
            onSelect = { entry -> navigator?.push(entry.screen()) },
        )
    }

    if (embedded) {
        // 宿主（MainScreen 的桌面面板）已经提供了顶栏，这里只出内容。
        ScrollColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            list()
        }
        return
    }

    CpRouteScaffold(
        title = "设置",
        onBack = { navigator?.pop() },
    ) { _ ->
        ScrollColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            list()
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
        SettingsSection(group.title) {
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
 * [settingsRowContainer]；宽屏左栏里当前选中的行改用 `surfaceContainerHigh` 高亮。
 */
@Composable
private fun SettingsRow(
    entry: SettingsEntry,
    index: Int,
    total: Int,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    SettingsClickItem(
        title = entry.title,
        subtitle = entry.subtitle,
        index = index,
        total = total,
        onClick = onClick,
        containerColor = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            settingsRowContainer()
        },
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
