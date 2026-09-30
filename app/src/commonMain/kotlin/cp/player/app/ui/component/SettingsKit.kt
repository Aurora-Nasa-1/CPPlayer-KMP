package cp.player.app.ui.component

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置项组件的**唯一实现层**（旧版 `SettingsSection` / `Expressive*Item` 的移植 + 重构）。
 *
 * 旧版所有选项行都由同一条链路拼出来：
 * `SettingsSection`（分组标题）→ `Expressive*Item`（Click/Switch/Dropdown/Button）→
 * `UnifiedListItem`（分段圆角）。度量沿用旧版，**不要随手改数字**：
 *
 * - 分组标题：`labelLarge` + Medium + primary，`start=16 / top=12 / bottom=8`，字距 0.5sp
 * - 组内行距：2dp（行靠 `legacySegmentShape(index, total)` 拼成一张分段卡片）
 * - 页面：水平 16dp / 垂直 8dp，组间距 12dp，页尾留白 32dp
 * - 行：`min height 68dp`，标题 16sp Medium，副标题 `bodyMedium / onSurfaceVariant`
 * - 行容器色：深色 `surfaceContainerHighest`，浅色 `surface`
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
 */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, bottom = 8.dp, top = 12.dp)
                .semantics { heading() },
            letterSpacing = 0.5.sp,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
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
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
                modifier = Modifier.size(18.dp),
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
 * 旧版在**每一行**里内联 `if (isSystemInDarkTheme()) surfaceContainerHighest else surface`，
 * 这里收敛成一个函数，保证新旧页面不会各写各的。
 */
@Composable
fun settingsRowContainer(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerHighest
    else MaterialTheme.colorScheme.surface

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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
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
        modifier = Modifier.size(24.dp),
    )
}

// ============================================================ 可点击行

/**
 * 可点击的选项行（旧版 `ExpressiveClickItem`）。
 *
 * @param index/total 分段圆角位置；同一组内必须连续且从 0 开始
 * @param containerColor 不传则按明暗主题取 [settingsRowContainer]
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
    containerColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    LegacyListItem(
        index = index,
        total = total,
        onClick = if (enabled) onClick else null,
        // mergeDescendants：把「图标 / 标题 / 副标题 / 尾部图标」合成一个语义节点，
        // 读屏念一遍而不是四遍。
        modifier = modifier.semantics(mergeDescendants = true) {},
        containerColor = resolveContainer(containerColor),
        leadingContent = leadingContent ?: icon?.let { img ->
            { SettingsLeadingIcon(img, MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        headlineContent = { RowTitle(title, MaterialTheme.colorScheme.onSurface) },
        supportingContent = subtitle?.let { sub ->
            { RowSubtitle(sub, MaterialTheme.colorScheme.onSurfaceVariant) }
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
    val shape = legacySegmentShape(index, total)
    Surface(
        shape = shape,
        color = resolveContainer(containerColor),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
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
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { optionIndex, label ->
            val selected = optionIndex == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            val optionShape = RoundedCornerShape(9.dp)
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
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp).selectableGroup(),
            ) {
                options.forEachIndexed { optionIndex, option ->
                    val isSelected = optionIndex == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val optionShape = RoundedCornerShape(16.dp)
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
        modifier = modifier.fillMaxWidth().heightIn(min = 68.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
 */
@Composable
fun SettingsFieldGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * 设置页正文列：旧版的页面容器（水平 16 / 垂直 8，组间距 12，页尾 32dp 留白）。
 *
 * ⚠️ **内容列宽收敛到 [CpSpacing.formMaxWidth]**。旧版详情面板直接 `fillMaxWidth()`，
 * 2K/4K 屏上一行横跨整屏，标签和控件离得老远。设置页是表单而不是卡片栅格，
 * 不该用 `pageMaxWidth`（1400dp）。
 *
 * ⚠️ `widthIn(...)` 必须写在 `fillMaxWidth()` **之前** —— 反了就是空操作（本仓库踩过）。
 *
 * @param pageModifier 由 scaffold 传下来的 `fillMaxSize()`/`fillMaxWidth()`
 */
@Composable
fun SettingsPage(pageModifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    ScrollColumn(
        modifier = pageModifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CpSpacing.formMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}
