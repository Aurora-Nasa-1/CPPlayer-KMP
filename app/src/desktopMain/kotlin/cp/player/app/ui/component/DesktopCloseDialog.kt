package cp.player.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings

/**
 * 桌面端「点关闭按钮」时的去向确认。
 *
 * ## 为什么必须有这个框（而不是直接退或直接藏）
 *
 * 消息通知要求**进程活着**。桌面上关掉窗口 = 进程退出 = 收不到 —— 但用户按 X 时
 * 想的多半是「先关掉，一会儿再看」。不问他一句，要么他丢了通知、要么应用赖着不走，
 * 两种都算 bug。所以第一次按 X 时问一次，并把选择记下来（勾了「不再提示」之后不再问）。
 *
 * ## 为什么「退出」放在左边
 *
 * M3 的 `AlertDialog` 只有 confirm / dismiss 两个槽，语义是「确认 / 取消」。
 * 这里两个动作**都不是取消**（一个是隐藏、一个是退出），所以塞进同一个
 * `confirmButton` 槽里自己排 —— 左「退出」（破坏性，靠左、不抢焦点），
 * 右「最小化到托盘」（推荐项，落在用户习惯的确认位置）。
 *
 * 点框外 / 按 Esc = 什么都不做（窗口保持原样），这是最不容易后悔的行为。
 */
@Composable
fun DesktopCloseDialog(
    dontAskAgain: Boolean,
    onDontAskAgainChange: (Boolean) -> Unit,
    onMinimizeToTray: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = cpStrings().messageNotify
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.closeDialogTitle) },
        text = {
            Column {
                Text(strings.closeDialogMessage)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = onDontAskAgainChange)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        strings.closeDialogDontAsk,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onExit) {
                    Text(strings.closeDialogExit, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onMinimizeToTray) { Text(strings.closeDialogMinimize) }
            }
        },
    )
}
