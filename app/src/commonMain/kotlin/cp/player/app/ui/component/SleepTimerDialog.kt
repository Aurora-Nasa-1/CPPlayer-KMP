package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.app.AppModel

/**
 * 睡眠定时对话框：N 分钟后暂停 / 播完当前后暂停 / 取消已有定时。
 *
 * @param activeRemainingMs 当前生效中的定时剩余毫秒（null = 未设置）。
 * @param afterTrackActive  当前是否为"播完当前"模式。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(
    activeRemainingMs: Long?,
    afterTrackActive: Boolean,
    onSelect: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 90 分钟此前只存在于设置页的下拉里，对话框没有 —— 于是「设置页能选的」和
    // 「播放页能选的」不是同一套。统一到这里，两边共用一份。
    val options = listOf(15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (activeRemainingMs != null || afterTrackActive) {
                    Text(
                        if (afterTrackActive) "当前：播完本曲后暂停"
                        else "当前：${(activeRemainingMs!! / 60_000L) + 1} 分钟后暂停",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("多少分钟后暂停播放？", style = MaterialTheme.typography.bodyMedium)
                // 分块换行：5 个 chip 排一行在 360dp 窄屏上会横向溢出。
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.chunked(3).forEach { chunk ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunk.forEach { minutes ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onSelect(minutes); onDismiss() },
                                    label = { Text("$minutes 分钟") },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = afterTrackActive,
                        onClick = { onSelect(AppModel.sleepAfterTrack); onDismiss() },
                        label = { Text("播完本曲") },
                    )
                }
            }
        },
        confirmButton = {
            if (activeRemainingMs != null || afterTrackActive) {
                TextButton(onClick = { onCancelTimer(); onDismiss() }) {
                    Text("取消定时", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
