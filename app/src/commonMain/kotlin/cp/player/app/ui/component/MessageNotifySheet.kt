package cp.player.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel
import cp.player.app.i18n.cpStrings

/**
 * 单个联系人的「新消息通知」开关弹层（**安卓长按**用；桌面走右键菜单，不需要它）。
 *
 * 为什么不用 `CpConfirmDialog`：那是个「确认 / 取消」的二选一，而这里要的是一个
 * 可保持打开状态的开关 + 一句解释。硬套会让「取消」和「关闭」语义打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageNotifySheet(
    contactName: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = cpStrings().messageNotify
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = contactName.ifBlank { strings.sheetTitle },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                strings.sheetBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(cpStrings().common.dismiss)
            }
        }
    }
}

/**
 * 首次进入消息页的一次性引导。
 *
 * 为什么不做成全局新手引导的一页：那是「装音源 / 登录」级别的门槛，在用户**第一次点进消息页**
 * 之前根本不该提；本功能是消息页的上下文说明，就地讲最自然。
 *
 * @param hasContacts 列表里已经有联系人 —— 空列表 / 错误态时弹引导会叠在空态上，很难看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageNotifyGuideSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val strings = cpStrings().messageNotify
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = strings.guideTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                strings.guideBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(strings.guideConfirm)
            }
        }
    }
}

/**
 * 引导的**触发与记忆**（消息页与桌面双栏左栏共用）。
 *
 * ⚠️ 幂等性靠落盘，不靠内存：Voyager 的路由页 pop 回来会**重新进入组合、
 * `LaunchedEffect` 全部重跑**（本仓库反复踩过）。所以判据读
 * [AppModel.messageNotifyGuideDone]，关闭时立刻写回。
 */
@Composable
internal fun rememberMessageNotifyGuide(eligible: Boolean): Pair<Boolean, () -> Unit> {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(eligible) {
        if (eligible && !AppModel.messageNotifyGuideDone()) visible = true
    }
    val dismiss: () -> Unit = {
        visible = false
        AppModel.markMessageNotifyGuideDone()
    }
    return visible to dismiss
}

/**
 * 联系人行的通知开关状态：给行内小铃铛与右键菜单共用一份判据。
 *
 * ⚠️ 必须**订阅 `AppModel` 的可变来源**才会在切换后重组 —— 这里用 `contacts` 里
 * 那一行的 `userId` 做 key 触发一次读取即可（订阅表本身不是 Flow）。
 * 实际的重组由调用方在切换后更新自己的 `state`（见 `MessagesModel.setNotifySubscribed`）。
 */
@Composable
internal fun rememberNotifySubscribed(userId: Long, revision: Int): Boolean =
    remember(userId, revision) { AppModel.isMessageNotifySubscribed(userId) }

/** 供列表行复用的一行小铃铛（已开启推送时显示）。 */
@Composable
internal fun NotifyBellIndicator(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Notifications,
        contentDescription = cpStrings().messageNotify.menuDisable,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
