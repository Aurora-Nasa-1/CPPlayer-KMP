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
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsPage
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
    val entries = remember { settingsEntries() }

    // ⚠️ **宽度判据必须用「自己拿到的宽度」，不能用 `LocalIsExpanded`**。
    //
    // `LocalIsExpanded` 是 `MainScreen` 在**窗口层**算的（`BoxWithConstraints(Modifier.fillMaxSize())`
    // 的 `maxWidth`，还减掉了桌面侧栏）。而本页作为桌面内嵌面板时，可用宽度已经比窗口窄了
    // 一截 —— 用窗口的宽度判据决定「要不要摆双栏」，等于拿别人的尺子量自己。
    //
    // 更关键的是：`embedded = true` 时宿主（`MainScreen` 的面板区）**已经画好了顶栏与导航**，
    // 本页只该出「左栏 + 右栏」这一层内容；一旦判错就会在面板里再套一层外壳（栏中栏）。
    //
    // 正确姿势与 `PlaylistDetailScreen` 宽屏分支完全一致：
    // 自己 `BoxWithConstraints` 量自己的可用宽度，判 `CpBreakpoints.isExpanded`。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 走 CpBreakpoints 而不是内联 840：断点只此一份，改一次处处生效。
        val expanded = CpBreakpoints.isExpanded(maxWidth)

        if (expanded) {
            // 宽屏：左栏 = 分类导航，右栏 = 选中项的详情。
            // 这正是设置页的设计意图 —— 左侧导航、右侧界面，一眼看全、不必来回 push。
            var selectedId by remember { mutableStateOf(entries.firstOrNull()?.id) }
            CpTwoPane(
                rail = { railModifier ->
                    ScrollColumn(
                        // 水平内边距取 `formHorizontal`：左栏的行与右栏的行因此**离各自栏边界同远**
                        // （16dp）。此前左栏是 0 —— 行的圆角贴着窗口左边缘，与右栏一比就看出错位。
                        modifier = railModifier.padding(
                            start = CpSpacing.formHorizontal,
                            end = CpSpacing.formHorizontal,
                            top = CpSpacing.formVertical,
                            bottom = CpSpacing.formBottomInset,
                        ),
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
            return@BoxWithConstraints
        }

        // 窄屏：push 到子页（原设计如此，手机 / 平板上的行为）。
        val list: @Composable () -> Unit = {
            SettingsGroupedList(
                entries = entries,
                selectedId = null,
                onSelect = { entry -> navigator?.push(entry.screen()) },
            )
        }

        if (embedded) {
            // 宿主已经提供了顶栏，这里只出内容，让它自己的滚动容器生效。
            SettingsPage(Modifier.fillMaxSize()) { list() }
            return@BoxWithConstraints
        }

        // ⚠️ 根页也走 `SettingsPage`。此前它自己拼容器、**没有宽度上限** ——
        // 宽屏下列表横跨整屏，点进任一子页正文又收到 720dp，切换时宽度整体跳一下。
        // 根页是导航列表不是表单，但它和子页是同一屏的两个状态，必须同宽。
        CpRouteScaffold(
            title = cpStrings().settings.screenTitle,
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            SettingsPage(pageModifier) { list() }
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
