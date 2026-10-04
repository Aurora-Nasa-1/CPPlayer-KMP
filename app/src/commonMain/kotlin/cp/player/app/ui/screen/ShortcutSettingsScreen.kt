package cp.player.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cp.player.app.AppModel
import cp.player.app.shortcut.ShortcutAction
import cp.player.app.shortcut.ShortcutBinding
import cp.player.app.shortcut.ShortcutCategory
import cp.player.app.shortcut.ShortcutKey
import cp.player.app.shortcut.findShortcutConflicts
import cp.player.app.ui.component.CpRouteScaffold
import cp.player.app.ui.component.SettingsClickItem
import cp.player.app.ui.component.SettingsConfirmItem
import cp.player.app.ui.component.SettingsLazyPage
import cp.player.app.ui.component.SettingsNote
import cp.player.app.ui.component.SettingsSection
import cp.player.app.ui.util.popOrNotify

/**
 * 快捷键（**桌面端专属**）。
 *
 * ### 这一页解决什么
 *
 * 重构前快捷键是 `Main.kt` 里的一串硬编码 `when`：5 条、键位写死、看不见全貌，
 * 用户更没法改。现在动作清单声明在 [ShortcutAction]，页面只做两件事：
 * 把「动作 → 当前绑定」列出来（按 [ShortcutCategory] 分组），以及提供一个**录制**入口。
 *
 * ### 三件事刻意分开，不要合并
 *
 * - **保存**：把录到的组合键绑定上去（可与其他动作冲突，冲突只提示不拦截）；
 * - **清除绑定**：把这个动作**关掉**（落盘哨兵值），**不是**恢复默认 ——
 *   用户按下「清除」是想让它消失，再给它塞回默认键位是纯粹的错误行为；
 * - **恢复默认**：回到 `ShortcutAction.defaultBinding`（先前的自定义一并丢弃）。
 *
 * 页脚还有一个「全部恢复默认」，覆盖用户改乱之后一键兜底。
 */
class ShortcutSettingsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val bindings by AppModel.shortcutBindingsFlow.collectAsState()
        var recording by remember { mutableStateOf<ShortcutAction?>(null) }

        val action = recording
        if (action != null) {
            ShortcutRecorderDialog(
                action = action,
                current = bindings[action.id],
                conflictsWith = { candidate ->
                    ShortcutAction.entries.filter { other ->
                        other != action && bindings[other.id] == candidate
                    }
                },
                onResult = { result ->
                    when (result) {
                        is ShortcutRecorderResult.Bind ->
                            AppModel.setShortcutBinding(action, result.binding)
                        ShortcutRecorderResult.Unbind -> AppModel.unbindShortcut(action)
                        ShortcutRecorderResult.ResetToDefault -> AppModel.resetShortcut(action)
                    }
                },
                onDismiss = { recording = null },
            )
        }

        CpRouteScaffold(
            title = "快捷键",
            onBack = { navigator.popOrNotify() },
        ) { pageModifier ->
            ShortcutSettingsBody(
                pageModifier = pageModifier,
                bindings = bindings,
                onEdit = { recording = it },
                onResetAll = AppModel::resetAllShortcuts,
            )
        }
    }
}

/**
 * 页面正文（**只吃参数、不读全局**）。
 *
 * 把状态接线与版式拆开是为了能离屏出图核对：`AppModel` 一旦被碰，整个对象初始化
 * （后端、仓库、磁盘设置）都会在离屏渲染的测试 JVM 里跑一遍。正文只接收一份
 * `bindings` 映射就能渲，测试里喂假数据即可。
 */
@Composable
internal fun ShortcutSettingsBody(
    pageModifier: Modifier,
    bindings: Map<String, ShortcutBinding?>,
    onEdit: (ShortcutAction) -> Unit,
    onResetAll: () -> Unit,
) {
    // 冲突只提示不拦截：派发时取声明顺序靠前的那个，程序不会出错，
    // 但用户会遇到「按下去反应的不是我想的那个」，所以必须可见。
    val conflicts = remember(bindings) { findShortcutConflicts(bindings) }

    SettingsLazyPage(pageModifier) {
        ShortcutCategory.entries.forEach { category ->
            val actions = ShortcutAction.entries.filter { it.category == category }
            if (actions.isEmpty()) return@forEach
            item(key = "shortcut_section_${category.name}") {
                SettingsSection(category.title) {
                    actions.forEachIndexed { index, action ->
                        SettingsClickItem(
                            title = action.label,
                            subtitle = action.hint,
                            index = index,
                            total = actions.size,
                            onClick = { onEdit(action) },
                            trailingContent = {
                                ShortcutKeyChip(
                                    binding = bindings[action.id],
                                    conflicted = action.id in conflicts,
                                )
                            },
                        )
                    }
                }
            }
        }

        item(key = "shortcut_note") {
            SettingsNote(
                "快捷键在任意界面生效；输入框获得焦点时不会触发（字母 / 数字键照常输入）。" +
                    "点击任意一行可以重新录入键位。"
            )
        }
        item(key = "shortcut_reset_all") {
            SettingsConfirmItem(
                title = "全部恢复默认",
                subtitle = "丢弃所有自定义键位，回到出厂设置",
                confirmTitle = "全部恢复默认？",
                confirmMessage = "所有自定义的快捷键都会丢失，恢复为默认键位。",
                confirmLabel = "恢复",
                index = 0,
                total = 1,
                destructive = false,
                onConfirm = onResetAll,
            )
        }
    }
}

/**
 * 行尾的键位徽标。
 *
 * 三种状态各有自己的配色，不能只靠文案区分：`未绑定` / 正常 / **冲突**（错误色）——
 * 冲突必须一眼看得见，否则用户只会觉得「按了没反应」。
 */
@Composable
private fun ShortcutKeyChip(binding: ShortcutBinding?, conflicted: Boolean) {
    // ⚠️ 「未绑定」的底色**不能**用 `surfaceContainerHighest`：深色主题下设置行的底色
    // 就是它（`settingsRowContainer()`），两者相同 ⇒ 徽标只剩文字、没有药丸形状，
    // 和相邻的键位徽标对不齐（离屏出图实测）。`surfaceContainer` 在浅色下比行底深一档、
    // 深色下比行底浅一档，正是「相对行底色反向」的那一档。
    val container = when {
        conflicted -> MaterialTheme.colorScheme.errorContainer
        binding == null -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        conflicted -> MaterialTheme.colorScheme.onErrorContainer
        binding == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = binding?.displayName ?: "未绑定",
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 录制弹窗的三种结果。用密封接口而不是三个回调：三者**互斥**，不该有「同时传两个」的状态。 */
private sealed interface ShortcutRecorderResult {
    /** 绑定到某个组合键。 */
    data class Bind(val binding: ShortcutBinding) : ShortcutRecorderResult

    /** 解绑（关掉这个动作）。 */
    data object Unbind : ShortcutRecorderResult

    /** 恢复默认键位。 */
    data object ResetToDefault : ShortcutRecorderResult
}

/**
 * 键位录制弹窗。
 *
 * ### 为什么在弹窗里录
 *
 * `AlertDialog` 在桌面是**独立窗口**，按键先到它自己的组合，主窗口的
 * `handleDesktopShortcut` 根本收不到 —— 于是「按 S 去录 S」不会边录边把自己的动作触发一遍。
 * 这也是不把录制做成就地展开一行的原因（就地录必然要拦截全局派发，多一层时序假设）。
 *
 * ### 为什么 `Esc` 是取消而不是绑成返回键
 *
 * 录制态下 `Esc` 的通用语义就是「算了」。想给某个动作绑 `Esc`（典型是「返回上一级」），
 * 走「恢复默认」或点上「清除」后重录；不带修饰键的 `Esc` 不参与录制，
 * `Ctrl + Esc` 这类带修饰的组合仍然可以正常录入。
 */
@Composable
private fun ShortcutRecorderDialog(
    action: ShortcutAction,
    current: ShortcutBinding?,
    conflictsWith: (ShortcutBinding) -> List<ShortcutAction>,
    onResult: (ShortcutRecorderResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    var waiting by remember { mutableStateOf(draft == null) }
    val focusRequester = remember { FocusRequester() }

    val conflicts = remember(draft, current) {
        if (draft == null || draft == current) emptyList() else conflictsWith(draft!!)
    }

    /** 捕获一次按键。返回 true 表示已处理（始终消费，避免按键漏到别处）。 */
    fun capture(event: KeyEvent) {
        if (event.type != KeyEventType.KeyDown) return
        // Esc（不带修饰）= 取消录制。
        if (event.key == Key.Escape && !event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed) {
            onDismiss()
            return
        }
        // 只按下修饰键：还不能构成绑定，继续等主键。
        if (event.key == Key.CtrlLeft || event.key == Key.CtrlRight ||
            event.key == Key.ShiftLeft || event.key == Key.ShiftRight ||
            event.key == Key.AltLeft || event.key == Key.AltRight
        ) {
            return
        }
        // 白名单外的键（媒体键、F13+、小键盘…）不接收：落盘需要稳定 token，见 ShortcutKey。
        val key = ShortcutKey.ofKey(event.key) ?: return
        draft = ShortcutBinding(
            key = key,
            ctrl = event.isCtrlPressed,
            shift = event.isShiftPressed,
            alt = event.isAltPressed,
        )
        waiting = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置快捷键") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "「${action.label}」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .focusRequester(focusRequester)
                        .focusable()
                        // 预览阶段捕获：焦点在这块上时，任何按键都先进这里，
                        // 不会被弹窗里的按钮（Enter / 空格激活）先吃掉。
                        .onPreviewKeyEvent { event ->
                            capture(event)
                            true
                        }
                        .clickable { focusRequester.requestFocus() }
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.medium,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = draft?.displayName ?: "按下新的组合键…",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (draft == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    text = if (waiting) "直接按下你想要的组合键，例如 Ctrl + Shift + K" else "再按一下可以换成别的组合键",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conflicts.isNotEmpty()) {
                    Text(
                        text = "注意：这个组合已经给了「" +
                            conflicts.joinToString("、") { it.label } + "」，保存后会同时占用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    draft?.let { onResult(ShortcutRecorderResult.Bind(it)) }
                    onDismiss()
                },
                enabled = draft != null,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        onResult(ShortcutRecorderResult.ResetToDefault)
                        onDismiss()
                    },
                ) {
                    Text("恢复默认")
                }
                TextButton(
                    onClick = {
                        onResult(ShortcutRecorderResult.Unbind)
                        onDismiss()
                    },
                    enabled = current != null,
                ) {
                    Text(
                        text = "清除",
                        color = if (current != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    // 打开就聚焦到按键区：否则用户得先点一下才能开始录。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
