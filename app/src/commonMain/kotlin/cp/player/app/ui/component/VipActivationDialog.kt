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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cp.player.core.license.VipLicenseManager
import cp.player.app.i18n.cpStrings
import cp.player.app.ui.theme.cpSheetSurface
import kotlinx.coroutines.launch

/**
 * VIP 发烧友特权卡密激活弹层。
 */
@Composable
fun VipActivationDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val s = cpStrings().playback
    var inputCode by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cpSheetSurface(),
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                imageVector = Icons.Filled.WorkspacePremium,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            CpText(
                text = s.vipDialogTitle,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CpText(
                    text = s.vipDialogNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = inputCode,
                    enabled = !activating,
                    onValueChange = {
                        inputCode = it
                        errorMessage = null
                    },
                    label = { CpText(s.vipKeyLabel) },
                    placeholder = { CpText(s.vipKeyPlaceholder) },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { CpText(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !activating,
                onClick = {
                    activating = true
                    scope.launch {
                        try {
                            VipLicenseManager.activate(inputCode).onSuccess {
                                onSuccess()
                                onDismiss()
                            }.onFailure {
                                errorMessage = it.message ?: s.vipActivateFailed
                            }
                        } finally {
                            activating = false
                        }
                    }
                }
            ) {
                CpText(s.vipActivateNow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                CpText(s.vipActivateLater)
            }
        },
    )
}
