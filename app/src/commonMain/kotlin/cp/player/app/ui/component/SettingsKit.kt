package cp.player.app.ui.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cp.player.app.ui.theme.CpText
import cp.player.app.ui.theme.LocalIsDarkTheme

/**
 * 设置项组件的**唯一实现层**（旧版 `SettingsSection` / `Expressive*Item` 的移植 + 重构）。
 *
 * 旧版所有选项行都由同一条链路拼出来：
 * `SettingsSection`（分组标题）→ `Expressive*Item`（Click/Switch/Dropdown/Button）→
 * `LegacyListItem`（分段圆角）。
 *
 * ## 度量：全部来自 `CpSpacing`，本文件不再出现页面级裸值
 *
 * | 位置 | 值 | 令牌 |
 * |------|-----|------|
 * | 页面宽度上限 | 720dp | `CpSpacing.formMaxWidth` |
 * | 页面水平内边距 | 16dp | `CpSpacing.formHorizontal` |
 * | 页面上下内边距 | 8dp | `CpSpacing.formVertical` |
 * | 区块间距 | 12dp | `CpSpacing.formSectionGap` |
 * | 页尾留白 | 32dp | `CpSpacing.formBottomInset` |
 * | 行最小高度 | 68dp | `CpSpacing.formRowMinHeight` |
 * | 行水平内边距 | 16dp | `CpSpacing.formRowHorizontal` |
 * | 行垂直内边距 | 10dp | `CpSpacing.formRowVertical` |
 * | 行内横向间隔 | 14dp | `CpSpacing.formRowGap` |
 * | 组内行距 | 4dp | `CpSpacing.listRowGap` |
 *
 * ⚠️ **页面容器只认 [SettingsPage]（静态）与 [SettingsLazyPage]（长列表）**，
 * 不要在页面里自己拼 `ScrollColumn { Column(Modifier.widthIn(...).padding(16.dp)) }`
 * —— 那样宽度上限、页边距、区块间距三样都会各写各的，正是重构前 11 个设置页
 * 里 4 个与其余 7 个长得不一样的原因。
 *
 * ⚠️ 组件**内部**的微调（图标与文字之间 4/6/8dp 这类）允许写裸值：它们不跨页面复用。
 * 判据是「这个值会不会在第二个页面出现」——会，就必须进 `CpSpacing`。
 *
 * ## 与旧版的三处刻意差异
 *
 * 1. **每一行都有可访问性语义。** 旧版一行是「图标 + 标题 + 副标题 + 控件」四个独立
 *    语义节点，读屏要念四遍；开关行更糟 —— 整行可点、内层 `Switch` 也可点，
 *    于是**一个设置项有两个可聚焦目标**。现在统一 `mergeDescendants`，
 *    开关行的角色与状态由整行承担（内层 `Switch` 只负责画）。
 * 2. **不再靠 `maxLines` 截断标题。** 旧版标题 `maxLines = 2` + `Ellipsis`，
 *    200% 字号下设置项的含义会被吃掉。行高本来就是 `heightIn(min = 68.dp)`，允许增长。
 * 3. **新增三个组件**，对应三类此前没有正确交互契约的设置项：
 *    [SettingsSegmentedItem]（选项少时别用弹层）、[SettingsTextInputItem]（别边打字边提交）、
 *    [SettingsConfirmItem]（破坏性操作要二次确认）。
 *
 * @see LegacyListItem 分段圆角的载体
 */

// ============================================================ 分组与说明

/**
 * 分组标题。
 *
 * 标题带 `heading()` 语义：设置页有 20+ 个条目，读屏用户需要能**按分组跳转**，
 * 而不是一条条划过去。
 *
 * 内边距的三个值都取令牌（`start=16 / top=12 / bottom=8`）：
 * 左边缘与行内标题、行内控件对齐（都是 16dp），上方留一个区块间距与上一组分开，
 * 下方只留页面垂直内边距 —— 标题因此**贴着**它自己的那一组，而不是悬在两组中间。
 */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            // labelLarge 的字重规范就是 Medium(500)，原先又写了一遍 —— 与 token 同值，纯噪声。
            // 但下面的 `letterSpacing = 0.5.sp` 是**真覆盖**（labelLarge 规范是 0.1sp），保留。
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(
                    start = CpSpacing.formRowHorizontal,
                    top = CpSpacing.formSectionGap,
                    bottom = CpSpacing.formVertical,
                )
                .semantics { heading() },
            letterSpacing = 0.5.sp,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            // 组内行距。**2026-10-01 从 2dp 改到 4dp（= CpSpacing.listRowGap）**，理由：
            // 行现在按下时圆角会从 4dp 撑到 20dp，2dp 的缝太窄 —— 变形后的行会与
            // 邻行视觉粘连，"这一行弹出来了"读不出来。4dp 也是 Kazumi `SplitListRow`
            // 的缝宽（`reference/Kazumi/lib/bean/widget/split_list_row.dart`）。
            // ⚠️ 这不是"随手改数字"：缝宽与形状变形是**一对**参数，改一个要回头看另一个。
            verticalArrangement = Arrangement.spacedBy(CpSpacing.listRowGap),
            content = content,
        )
    }
}

/**
 * 说明文字的三档语义。
 *
 * 旧版所有说明都是同一个 `bodySmall + onSurfaceVariant` —— 于是「访问令牌是 xxx」
 * 「绑定局域网务必保留令牌」这类**关键信息**和「开关即时生效」这类普通提示长得
 * 一模一样，对比度还都贴着下限。[WARNING] / [ERROR] 因此改成带容器的强调条，
 * 并挂上 `liveRegion` 让状态变化能被播报。
 */
enum class SettingsNoteEmphasis { INFO, WARNING, ERROR }

/**
 * 组外的说明 / 状态文字。
 *
 * 必须放在 [SettingsSection] **之外**、页面列的直接子项上 —— 组内是分段卡片，
 * 中间插一行非分段文本会把圆角拼接打断。
 */
@Composable
fun SettingsNote(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: SettingsNoteEmphasis = SettingsNoteEmphasis.INFO,
    color: Color = Color.Unspecified,
) {
    if (emphasis == SettingsNoteEmphasis.INFO) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = CpSpacing.formHorizontal, vertical = 4.dp),
        )
        return
    }

    val container = if (emphasis == SettingsNoteEmphasis.ERROR) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val onContainer = if (emphasis == SettingsNoteEmphasis.ERROR) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        // 14dp 是历史遗留的非标度值；收到 `shapes.medium`(12dp)。
        // 提示条是行内元素，比所在分段卡片（外 20dp）轻一档才对。
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CpSpacing.formHorizontal, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(CpIconSize.inline),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

// ============================================================ 行骨架

/** 旧版 `MonetIcon`：46dp 圆形底 + 26dp 图标，用于设置首页的分类入口。 */
@Composable
fun MonetIcon(icon: ImageVector, containerColor: Color, contentColor: Color) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 选项行的默认容器色。
 *
 * ⚠️ **必须读 [LocalIsDarkTheme]，不要读 `isSystemInDarkTheme()`。** 两者在「跟随系统」时
 * 恰好相等，但本应用允许用户显式指定浅色 / 深色 —— 用户选了深色而系统是浅色时，
 * `isSystemInDarkTheme()` 返回 false，于是这里会去取**浅色**色板的角色色，
 * 与页面背景撞色、分组卡片整块糊住。
 *
 * ⚠️ 浅色分支**不能返回 `surface`**：设置页背景本身就是 `surface`，
 * 两者相同 ⇒ 分组卡片完全不可见（离屏取像素实测：行内与页面都是 #FBF8FF）。
 * 取 `surfaceContainerLow` 才是 M3 里「卡片浮在页面之上」的标准关系。
 */
@Composable
fun settingsRowContainer(): Color =
    if (LocalIsDarkTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surfaceContainerLow

/**
 * 「**当前生效 / 当前选中**」的行容器色。
 *
 * 全应用唯一一处。收敛前同一个语义有三种写法：`AccountScreen` 里当前账号是
 * `primaryContainer.copy(alpha = 0.5f)`、`ProviderManagementScreen` 里当前音源是
 * `primaryContainer.copy(alpha = 0.45f)`、`SettingsScreen` 左栏选中是 `surfaceContainerHigh`
 * —— 两个 alpha 纯属巧合，第三个更是连色相都不一样。
 *
 * 选 `primaryContainer` 而不是 `secondaryContainer`：本应用已经用它表达「正在播放」
 * （`SongItem`），「当前账号 / 当前音源」是同一类概念，沿用同一个角色色。
 *
 * ⚠️ 配 [settingsRowHighlightContent] 使用 —— 底色换成 primaryContainer 之后
 * 标题再写 `onSurface` 会在浅色主题下掉对比度。
 */
@Composable
fun settingsRowHighlight(): Color = MaterialTheme.colorScheme.primaryContainer

/** 与 [settingsRowHighlight] 配套的内容色。 */
@Composable
fun settingsRowHighlightContent(): Color = MaterialTheme.colorScheme.onPrimaryContainer

@Composable
private fun resolveContainer(containerColor: Color): Color =
    if (containerColor == Color.Unspecified) settingsRowContainer() else containerColor

/** 行内标题：16sp Medium，**不设 maxLines** —— 大字号下允许换行撑高行，而不是截断。 */
@Composable
private fun RowTitle(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RowSubtitle(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = FontWeight.Normal,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 行内容布局：`[leading] [title / subtitle / extra] [trailing]`。 */
@Composable
private fun SettingsRowContent(
    leadingContent: (@Composable () -> Unit)?,
    title: String,
    subtitle: String?,
    titleColor: Color,
    subtitleColor: Color,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CpSpacing.formRowHorizontal, vertical = CpSpacing.formRowVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CpSpacing.formRowGap),
    ) {
        leadingContent?.invoke()
        Column(Modifier.weight(1f)) {
            RowTitle(title, titleColor)
            subtitle?.let { RowSubtitle(it, subtitleColor) }
            extra?.invoke(this)
        }
        trailingContent?.invoke(this)
    }
}

@Composable
private fun SettingsLeadingIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(CpIconSize.action),
    )
}

// ============================================================ 可点击行

/**
 * 可点击的选项行（旧版 `ExpressiveClickItem`）。
 *
 * @param index/total 分段圆角位置；同一组内必须连续且从 0 开始
 * @param selected 该行是不是「当前生效 / 当前选中」项（当前账号、当前音源、左栏选中项）。
 *   底色与内容色一起换成 [settingsRowHighlight] / [settingsRowHighlightContent] ——
 *   只换底色不换文字色会在浅色主题下掉对比度，所以这一对由组件内部一起处理，
 *   调用点不要自己传 `containerColor = primaryContainer`。
 * @param containerColor 不传则按明暗主题取 [settingsRowContainer]；传了则压过 [selected]
 * @param mergeSemantics 是否把整行合成一个语义节点。**默认 true**（读屏念一遍而不是四遍）。
 *   仅当 [trailingContent] 里放了**独立可操作**的控件（行尾的「删除」「移除」图标按钮）时
 *   传 `false` —— 合并会把子节点的点击动作并进父节点，读屏用户就再也点不到那个按钮了。
 *   这一条是本仓库 2026-10-01 统一版式时补上的：音源管理与账号页的行尾都有删除按钮。
 */
@Composable
fun SettingsClickItem(
    title: String,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    mergeSemantics: Boolean = true,
    containerColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    val highlight = selected && containerColor == Color.Unspecified
    val resolvedContainer = if (highlight) settingsRowHighlight() else resolveContainer(containerColor)
    val titleColor = if (highlight) settingsRowHighlightContent() else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (highlight) {
        settingsRowHighlightContent().copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val leadingTint = if (highlight) {
        settingsRowHighlightContent()
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    LegacyListItem(
        index = index,
        total = total,
        onClick = if (enabled) onClick else null,
        // mergeDescendants：把「图标 / 标题 / 副标题 / 尾部图标」合成一个语义节点，
        // 读屏念一遍而不是四遍。行尾有独立按钮时由调用点关掉（见 mergeSemantics）。
        modifier = if (mergeSemantics) {
            modifier.semantics(mergeDescendants = true) {}
        } else {
            modifier
        },
        containerColor = resolvedContainer,
        leadingContent = leadingContent ?: icon?.let { img ->
            { SettingsLeadingIcon(img, leadingTint) }
        },
        headlineContent = { RowTitle(title, titleColor) },
        supportingContent = subtitle?.let { sub ->
            { RowSubtitle(sub, subtitleColor) }
        },
        trailingContent = trailingContent,
    )
}

// ============================================================ 开关行

/**
 * 开关行（旧版 `ExpressiveSwitchItem`）：整行可点，点行即切换。
 *
 * ⚠️ **内层 `Switch` 的 `onCheckedChange` 必须是 `null`。**
 * 旧版两边都传了回调，于是同一行上有两个可聚焦目标 —— 读屏要念两遍、
 * 键盘 Tab 要停两次，而它们操作的是同一个布尔值。现在角色与状态由整行承担
 * （`toggleable` + `Role.Switch`），`Switch` 只负责画。
 *
 * 选中时拇指内嵌 `Check` —— 旧版靠它让「开」的状态在小尺寸下也能一眼看出来。
 */
@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 按下时圆角从组内圆角(4dp)撑到容器圆角(20dp) —— 与 `LegacyListItem` 同源。
    // 开关行是设置页出现频率最高的一类行，之前它只有水波纹、圆角是死的；
    // 现在按下时会"长出来"，给出「我抓住了这一行」的确认。
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = rememberSegmentShape(index, total, pressed = pressed && enabled)
    Surface(
        shape = shape,
        color = resolveContainer(containerColor),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = CpSpacing.formRowMinHeight)
            .clip(shape)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {},
    ) {
        SettingsRowContent(
            leadingContent = icon?.let { img ->
                { SettingsLeadingIcon(img, MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            title = title,
            subtitle = subtitle,
            titleColor = MaterialTheme.colorScheme.onSurface,
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    thumbContent = if (checked) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    } else null,
                )
            },
        )
    }
}

// ============================================================ 分段控件

/**
 * 2–4 项的选项行：**就地可见、一次点击**即可切换。
 *
 * 旧版这类设置走的是「下拉行 → 底部弹层 → 选一项」：看当前值要点开，改一次要点三次。
 * 选项少的时候这是纯浪费。选项多（≥5）或文案长的时候仍然用 [SettingsDropdownItem]。
 *
 * 语义上是一个 `selectableGroup`，每项 `Role.RadioButton` —— 读屏会念
 * 「选项组，较高，已选中」。
 */
@Composable
fun SettingsSegmentedItem(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
) {
    Surface(
        shape = legacySegmentShape(index, total),
        color = resolveContainer(containerColor),
        modifier = modifier.fillMaxWidth().heightIn(min = CpSpacing.formRowMinHeight),
    ) {
        SettingsRowContent(
            leadingContent = icon?.let { img ->
                { SettingsLeadingIcon(img, MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            title = title,
            subtitle = subtitle,
            titleColor = MaterialTheme.colorScheme.onSurface,
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
            extra = {
                SegmentedControl(
                    options = options,
                    selectedIndex = selectedIndex,
                    enabled = enabled,
                    onSelect = onSelect,
                )
            },
        )
    }
}

@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    // 圆角同心：**内块圆角 = 外框圆角 − 内边距**。
    //
    // 外框取 `shapes.medium`(12dp)、内边距 3dp ⇒ 内块 9dp。
    // ⚠️ 不要"顺手"把内块改成 `shapes.small`(8dp)：那会破坏同心，内外曲率不平行时
    // 肉眼能看出内块四角"贴不齐"外框 —— 这正是这里用 9dp 而不是某个 token 的原因。
    val trackRadius = 12.dp
    val trackPadding = 3.dp
    val outerShape = MaterialTheme.shapes.medium
    val optionShape = RoundedCornerShape(trackRadius - trackPadding)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(outerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(trackPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(trackPadding),
    ) {
        options.forEachIndexed { optionIndex, label ->
            val selected = optionIndex == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            Surface(
                shape = optionShape,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(optionShape)
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(optionIndex) },
                    ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                )
            }
        }
    }
}

// ============================================================ 滑杆行

/**
 * 连续取值的滑杆行：标题 + 副标题 + 行内滑杆，右上角可带当前值标签。
 *
 * ### 提交节奏：**拖动只改显示，松手才提交**
 *
 * 滑杆一秒能产生几十个中间值。调用方把「写盘」放进 [onValueChangeFinished]，
 * 「改显示」放进 [onValueChange]（本组件只负责把拖动回调转发出去，不内部缓存状态）。
 * 理由与 [SettingsTextInputItem] 相同：桌面端设置存储每次写入都是**全量文件回写**，
 * 边拖边写会把 IO 打爆，而且中间值大多是用户没打算要的值。
 *
 * @param value 当前显示值（外部状态）。拖动中调用 [onValueChange]，由调用方更新它
 * @param steps 滑轨上的离散档数（不含两端）；0 表示连续。想按 5 一格取 0–100 就传 19
 * @param valueLabel 右上角的当前值标签（如「100」或「100 · 最大」），null 则不显示
 */
@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)?,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    subtitle: String? = null,
    valueLabel: String? = null,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
) {
    Surface(
        shape = legacySegmentShape(index, total),
        color = resolveContainer(containerColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CpSpacing.formRowHorizontal,
                // 滑杆行是「标题 + 副标题 + 滑轨」三段，比普通行高一档，
                // 垂直内边距与 [SettingsTextInputItem] 同样放宽到 12dp。
                vertical = 12.dp,
            ),
        ) {
            SettingsRowContent(
                leadingContent = null,
                title = title,
                subtitle = subtitle,
                titleColor = MaterialTheme.colorScheme.onSurface,
                subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingContent = valueLabel?.let { label -> {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } },
            )
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

// ============================================================ 下拉行

/**
 * 下拉行（旧版 `ExpressiveDropdownItem`）：右侧 `ArrowDropDown`，点行弹出单选底部弹层。
 *
 * 适用于**选项 ≥5 或文案较长**的场景；选项少的时候请用 [SettingsSegmentedItem]。
 *
 * 与旧版的差异：尾部图标不再是「无语义的装饰」—— 整行经 [SettingsClickItem] 带上
 * 合并语义，弹层里的选项是 `selectableGroup` + `Role.RadioButton`。
 */
@Composable
fun SettingsDropdownItem(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
) {
    var showSheet by remember { mutableStateOf(false) }
    val currentSubtitle = subtitle ?: options.getOrNull(selectedIndex)

    SettingsClickItem(
        title = title,
        subtitle = currentSubtitle,
        icon = icon,
        index = index,
        total = total,
        enabled = enabled,
        containerColor = containerColor,
        modifier = modifier,
        onClick = { showSheet = true },
        trailingContent = {
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
    )

    if (showSheet) {
        LegacyModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Text(
                text = title,
                // 弹层标题要压过下面的选项，所以取 Emphasized 档而不是裸 titleLarge
                // （titleLarge 规范是 Regular(400)，直接用会显得"标题没立住"）。
                style = CpText.titleLargeEmphasized,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).selectableGroup(),
            ) {
                options.forEachIndexed { optionIndex, option ->
                    val isSelected = optionIndex == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val optionShape = MaterialTheme.shapes.large
                    Surface(
                        shape = optionShape,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(optionShape)
                            .selectable(
                                selected = isSelected,
                                interactionSource = interactionSource,
                                indication = LocalIndication.current,
                                role = Role.RadioButton,
                                onClick = {
                                    onSelect(optionIndex)
                                    showSheet = false
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================ 文本输入行

/**
 * 文本输入行：**草稿 → 校验 → 显式提交**。
 *
 * ### 为什么不能边打字边提交
 *
 * 旧版端口框是 `input.toIntOrNull()?.let(AppModel::setLocalServerStreamPort)` ——
 * 输入 `8080` 的过程中会依次提交 `8 / 80 / 808 / 8080`，每一次都触发
 * **全量文件回写 + 服务器重绑**。这不是风格问题，是缺陷：中间值全是用户没打算用的值，
 * 而且服务器会被反复重启。
 *
 * 现在编辑只改草稿；「应用」按钮在 [validate] 通过时才可用；
 * 未提交时行内显示「未保存」徽标（`liveRegion`，读屏也会播报）。
 *
 * @param value 已持久化的当前值；它变化时会重置草稿（外部重置 / 恢复默认也能跟上）
 * @param validate 返回 `null` 表示合法，否则返回给用户看的错误说明
 */
@Composable
fun SettingsTextInputItem(
    title: String,
    value: String,
    onCommit: (String) -> Unit,
    validate: (String) -> String?,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    containerColor: Color = Color.Unspecified,
) {
    // key = value：外部改了持久值时草稿跟着走，避免显示陈旧值。
    var draft by remember(value) { mutableStateOf(value) }
    val error = validate(draft)
    val dirty = draft != value

    Surface(
        shape = legacySegmentShape(index, total),
        color = resolveContainer(containerColor),
        modifier = modifier.fillMaxWidth().heightIn(min = CpSpacing.formRowMinHeight),
    ) {
        Column(
            // 12dp = `formRowVertical`(10) + 2：这一行是「标题 + 副标题 + 输入框」三段，
            // 比普通行高一档，垂直内边距跟着放宽一点，否则输入框会贴着行底。
            modifier = Modifier.padding(
                horizontal = CpSpacing.formRowHorizontal,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RowTitle(title, MaterialTheme.colorScheme.onSurface)
            subtitle?.let { RowSubtitle(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = enabled,
                    singleLine = true,
                    isError = error != null,
                    placeholder = placeholder?.let { hint -> { Text(hint) } },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    supportingText = error?.let { msg -> { Text(msg) } },
                    modifier = Modifier.weight(1f),
                )
                if (dirty) {
                    FilledTonalButton(
                        onClick = { onCommit(draft) },
                        enabled = enabled && error == null,
                    ) {
                        Text("应用")
                    }
                }
            }
            if (dirty) {
                Text(
                    text = if (error != null) "未保存 · 请先修正上面的问题" else "未保存 · 点「应用」生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

// ============================================================ 动作行

/**
 * 整行按钮（旧版 `ExpressiveButtonItem`）：破坏性操作铺 `errorContainer`，
 * 主操作铺 `primaryContainer`，普通操作保持行容器色。
 */
@Composable
fun SettingsButtonItem(
    text: String,
    onClick: () -> Unit,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    val finalContainer = if (containerColor == Color.Unspecified) settingsRowContainer() else containerColor
    val finalContent = if (contentColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else contentColor
    LegacyListItem(
        index = index,
        total = total,
        onClick = if (enabled) onClick else null,
        modifier = modifier.semantics(mergeDescendants = true) {},
        containerColor = finalContainer,
        leadingContent = icon?.let { img -> { SettingsLeadingIcon(img, finalContent) } },
        headlineContent = { RowTitle(text, finalContent) },
        supportingContent = subtitle?.let { sub -> { RowSubtitle(sub, finalContent.copy(alpha = 0.75f)) } },
    )
}

/**
 * 需要二次确认的动作行。
 *
 * 旧版「重新生成访问令牌」是**一键即生效**的：一次误触就让所有已连接设备立刻失效，
 * 而且没有任何提示。破坏性操作必须先说清后果、再让用户确认。
 *
 * @param destructive 是否用 `errorContainer` 呈现（不可逆操作）
 */
@Composable
fun SettingsConfirmItem(
    title: String,
    confirmTitle: String,
    confirmMessage: String,
    onConfirm: () -> Unit,
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    confirmLabel: String = "确认",
    dismissLabel: String = "取消",
    icon: ImageVector? = null,
    destructive: Boolean = true,
    enabled: Boolean = true,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (destructive) {
        SettingsButtonItem(
            text = title,
            subtitle = subtitle,
            icon = icon,
            index = index,
            total = total,
            modifier = modifier,
            enabled = enabled,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = { showDialog = true },
        )
    } else {
        SettingsClickItem(
            title = title,
            subtitle = subtitle,
            icon = icon,
            index = index,
            total = total,
            modifier = modifier,
            enabled = enabled,
            onClick = { showDialog = true },
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(confirmTitle) },
            text = { Text(confirmMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        onConfirm()
                    },
                ) {
                    Text(
                        text = confirmLabel,
                        color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(dismissLabel) }
            },
        )
    }
}

// ============================================================ 容器

/**
 * 裸信息 / 只读内容的容器（旧版把这些放在分组里，用普通 Surface 承载）。
 *
 * **只给只读信息、说明段落用** —— 选项行一律走 [SettingsSection] + `Settings*Item`，
 * 否则分段圆角会被打断。
 *
 * ⚠️ 这里必须 opt-in：`Shapes.largeIncreased`（20dp）带
 * `@ExperimentalMaterial3ExpressiveApi`，而 `large` / `extraLarge` 这些老槽位不带。
 * 用 20dp 是因为它是 4 档容器里"比行内块大、比卡片小"的那一档 ——
 * 改成 `shapes.large`(16dp) 会让它和行内块看不出层级。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsFieldGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CpSpacing.formRowHorizontal,
                vertical = CpSpacing.formRowGap,
            ),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.formRowVertical - 2.dp),
            content = content,
        )
    }
}

/**
 * 表单页正文列的**唯一**宽度与内边距约束。
 *
 * 需要懒加载的页面（长列表）用它 + [LazyScrollColumn] / [SettingsLazyPage]；
 * 其余页面一律用 [SettingsPage]。
 *
 * ⚠️ `widthIn` 必须写在 `fillMaxWidth()` **之前** —— 反了就是空操作（约束已被钉死，
 * 本仓库踩过）。这条顺序就是「宽屏下设置页正文宽度跳一下」那个缺陷的根因，
 * 所以它被收进这个函数，页面不要再自己拼。
 */
fun Modifier.settingsContentWidth(): Modifier =
    widthIn(max = CpSpacing.formMaxWidth)
        .fillMaxWidth()
        .padding(horizontal = CpSpacing.formHorizontal)

/**
 * 设置页正文列（旧版的页面容器）。
 *
 * 宽度收敛到 [CpSpacing.formMaxWidth]（720dp）、水平 [CpSpacing.formHorizontal]（16dp）、
 * 上下 [CpSpacing.formVertical]（8dp）、区块间距 [CpSpacing.formSectionGap]（12dp）、
 * 页尾 [CpSpacing.formBottomInset]（32dp）。
 *
 * ⚠️ **所有设置 / 表单类页面都必须走这里**（长列表走 [SettingsLazyPage]）。
 * 重构前 11 个设置页里有 4 个自己拼容器：`SettingsScreen` 根页没有宽度上限
 * （宽屏下列表横跨整屏、点进子页又收到 720dp，正文宽度整体跳一下）、
 * `AboutScreen` 用 `padding(16.dp)` + 4dp 行距、`ProviderManagementScreen` 用 8dp 页边距、
 * `HealthScreen` 用 20/12/48 三种混着的内边距 —— 同一套设置，四种边距。
 *
 * @param pageModifier 由 scaffold 传下来的 `fillMaxSize()`/`fillMaxWidth()`
 */
@Composable
fun SettingsPage(pageModifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    ScrollColumn(
        modifier = pageModifier.padding(vertical = CpSpacing.formVertical),
        verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.settingsContentWidth(),
            verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
        ) {
            content()
            Spacer(Modifier.height(CpSpacing.formBottomInset))
        }
    }
}

/**
 * [SettingsPage] 的**懒加载**版本：宽度、页边距完全一致，只是正文由 `LazyListScope` 提供。
 *
 * 给「行数可能到几百条」的表单页用 —— 目前是音源管理（模块列表）与诊断（调用记录，
 * 上限 300 条）。这两页此前各自拼容器，于是页边距是 8dp / 20+12+48dp 混着来的，
 * 和旁边 9 个设置页对不齐。**懒加载不构成"可以另写一套边距"的理由。**
 *
 * ⚠️ 页面里**不要**再套 [SettingsPage]（会叠加两层宽度与内边距）。
 *
 * @param bottomInset 页尾留白。有 FAB 的页面要传得比 FAB 高，否则最后一行会被压住
 *   （FAB 是 56dp + 16dp 边距 ⇒ 至少 88dp）。
 * @param itemGap 相邻 item 的间距。默认取组内行距（4dp）—— 这里装的是**行**，
 *   行要靠 4dp 的细缝拼成一张分段卡片。装分组标题的页面把整个 [SettingsSection]
 *   放进一个 item 即可：标题自带 12dp 顶部间距，组间距因此是 12 + 4。
 * @param header 钉在列表上方的表头（不参与滚动）。用于「筛选条」这类需要一直可见的控件；
 *   它和列表正文受同一个宽度上限约束，不会错开。
 */
@Composable
fun SettingsLazyPage(
    pageModifier: Modifier,
    bottomInset: Dp = CpSpacing.formBottomInset,
    itemGap: Dp = CpSpacing.listRowGap,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(pageModifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (header != null) {
            Column(
                modifier = Modifier
                    .settingsContentWidth()
                    .padding(top = CpSpacing.formVertical),
                verticalArrangement = Arrangement.spacedBy(CpSpacing.formSectionGap),
                content = header,
            )
            Spacer(Modifier.height(CpSpacing.formSectionGap))
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyScrollColumn(
                modifier = Modifier.widthIn(max = CpSpacing.formMaxWidth).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = CpSpacing.formHorizontal,
                    end = CpSpacing.formHorizontal,
                    top = if (header == null) CpSpacing.formVertical else 0.dp,
                    bottom = bottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(itemGap),
                content = content,
            )
        }
    }
}
