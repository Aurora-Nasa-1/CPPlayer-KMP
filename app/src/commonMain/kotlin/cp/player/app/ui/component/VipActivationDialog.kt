package cp.player.app.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.core.license.VipLicenseManager

/**
 * VIP 发烧友特权卡密激活弹层。
 */
@Composable
fun VipActivationDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var inputCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "解锁 Hi-Res 发烧友专有特权",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "FlickEngine 提供专有的 WASAPI 硬件独占（Windows）与 USB DAC 免驱直通（Android），绕开操作系统混音器，实现零损耗 Bit-Perfect 源码输出与 DSD 原生解码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = inputCode,
                    onValueChange = {
                        inputCode = it
                        errorMessage = null
                    },
                    label = { Text("请输入特权卡密") },
                    placeholder = { Text("输入 16 位激活卡密") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = VipLicenseManager.activate(inputCode)
                    result.onSuccess {
                        onSuccess()
                        onDismiss()
                    }.onFailure {
                        errorMessage = it.message ?: "激活失败，请检查激活码"
                    }
                }
            ) {
                Text("立即激活")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("暂不激活")
            }
        },
    )
}
