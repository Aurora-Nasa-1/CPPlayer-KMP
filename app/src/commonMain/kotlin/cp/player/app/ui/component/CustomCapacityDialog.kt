package cp.player.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.util.formatBytes

/**
 * 自定义歌曲缓存上限对话框。
 *
 * 允许用户直接输入任意数值（支持切换 GB / MB 单位），
 * 并校验在 128 MB 到 1024 GB 的合理范围内。
 */
@Composable
fun CustomCapacityDialog(
    currentCapacityBytes: Long,
    onConfirm: (bytes: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = cpStrings()
    val isGbDefault = currentCapacityBytes >= 1024L * 1024L * 1024L
    var isGb by remember { mutableStateOf(isGbDefault) }
    var textValue by remember {
        val initialNum = if (isGbDefault) {
            val gb = currentCapacityBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            if (gb == gb.toLong().toDouble()) gb.toLong().toString() else gb.toString()
        } else {
            val mb = currentCapacityBytes.toDouble() / (1024.0 * 1024.0)
            if (mb == mb.toLong().toDouble()) mb.toLong().toString() else mb.toString()
        }
        mutableStateOf(initialNum)
    }

    val parsedBytes: Long? = textValue.trim().toDoubleOrNull()?.let { num ->
        if (num <= 0.0) null
        else {
            val multiplier = if (isGb) 1024L * 1024L * 1024L else 1024L * 1024L
            (num * multiplier).toLong()
        }
    }
    val isValid = parsedBytes != null && parsedBytes in (128L * 1024L * 1024L)..(1024L * 1024L * 1024L * 1024L)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = {
            CpText(
                s.storage.customCapacityDialogTitle,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CpText(
                    s.storage.customCapacityDialogNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.large,
                        placeholder = { CpText(if (isGb) "例如 10" else "例如 2048") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CpToggleChip(
                            checked = !isGb,
                            onCheckedChange = { isGb = false },
                            label = s.storage.customCapacityUnitMb,
                        )
                        CpToggleChip(
                            checked = isGb,
                            onCheckedChange = { isGb = true },
                            label = s.storage.customCapacityUnitGb,
                        )
                    }
                }
                if (parsedBytes != null && isValid) {
                    CpText(
                        "设定容量：${formatBytes(parsedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (textValue.isNotBlank()) {
                    CpText(
                        "请输入有效容量（128 MB ~ 1024 GB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                shape = MaterialTheme.shapes.large,
                onClick = {
                    parsedBytes?.let { onConfirm(it) }
                    onDismiss()
                },
            ) {
                CpText("确定")
            }
        },
        dismissButton = {
            TextButton(
                shape = MaterialTheme.shapes.large,
                onClick = onDismiss,
            ) {
                CpText("取消")
            }
        },
    )
}
