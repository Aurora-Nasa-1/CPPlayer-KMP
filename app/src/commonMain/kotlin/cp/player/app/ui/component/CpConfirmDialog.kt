package cp.player.app.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 一次二次确认请求。
 *
 * 破坏性操作（删除歌单、删除音源、移除账号、清空 …）在**真正执行前**先弹一次确认。
 * 只描述「要问什么」，不关心从哪个入口发起 —— 菜单项、选项弹层、行尾按钮共用同一份。
 *
 * @param destructive true 时确认按钮走 `error` 色（不可逆 / 会丢数据）；
 *   false 时走默认色（只是需要用户再想一下，但后果可恢复）。
 */
class CpConfirmRequest internal constructor(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val destructive: Boolean,
    val onConfirm: () -> Unit,
)

/**
 * 二次确认的挂起态。一个页面一份，用 [rememberConfirmState] 建立。
 *
 * 之所以要有这个持有者而不是「每个入口自己 `var show by remember`」：
 * 同一个页面往往有**多个**删除入口（右键菜单 + 选项弹层 + 宽屏左栏按钮），
 * 各自维护布尔量就会出现三个文案不一致的确认框。挂起态保证同一时刻只有一个。
 */
class CpConfirmState internal constructor() {
    /** 当前待确认的请求；null = 没有弹窗。 */
    var request: CpConfirmRequest? by mutableStateOf(null)
        private set

    /** 发起一次确认。已有一个待确认时丢弃旧的（不可能同时点两个入口）。 */
    fun request(
        title: String,
        message: String,
        confirmLabel: String = "确认",
        dismissLabel: String = "取消",
        destructive: Boolean = true,
        onConfirm: () -> Unit,
    ) {
        request = CpConfirmRequest(title, message, confirmLabel, dismissLabel, destructive, onConfirm)
    }

    /** 关掉弹窗（取消或已确认后）。 */
    fun dismiss() {
        request = null
    }

    /** 确认并关闭：先关窗再执行，避免动作触发的重组期间弹窗还挂在树上。 */
    fun confirm() {
        val action = request?.onConfirm
        request = null
        action?.invoke()
    }
}

@Composable
fun rememberConfirmState(): CpConfirmState = remember { CpConfirmState() }

/**
 * 确认框宿主：放在页面组合的**末尾**（与其余弹层同级）即可。
 *
 * 没有待确认请求时什么也不渲染 —— 因此调用点不需要 `if`。
 */
@Composable
fun CpConfirmHost(state: CpConfirmState) {
    state.request?.let { req ->
        CpConfirmDialog(
            title = req.title,
            message = req.message,
            confirmLabel = req.confirmLabel,
            dismissLabel = req.dismissLabel,
            destructive = req.destructive,
            onConfirm = state::confirm,
            onDismiss = state::dismiss,
        )
    }
}

/**
 * 全应用统一的二次确认对话框。
 *
 * 各页面此前是各写各的 `AlertDialog`：媒体库页有一份、设置页的
 * [SettingsConfirmItem] 里有一份，其余高风险入口（删除音源、移除账号、
 * 清空队列 …）干脆一个都没有 —— 一次误触就生效，且事后不可恢复。
 * 确认框只有一种外观，改一处处处生效。
 */
@Composable
fun CpConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确认",
    dismissLabel: String = "取消",
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    // 破坏性操作的确认键用 error 色 —— 与菜单里的 danger 项同一语义。
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}
