package cp.player.app.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cp.player.app.platform.downloadUpdate
import cp.player.app.platform.openUrl
import cp.player.app.ui.component.AppUpdateDialog
import kotlinx.coroutines.launch

/**
 * Checks once per application session and owns the update dialog.
 *
 * Keeping this above the Navigator means the prompt also appears on the first
 * screen (including onboarding), rather than only after the user opens About.
 */
@Composable
fun AppUpdateHost() {
    val scope = rememberCoroutineScope()
    var updateResult by remember { mutableStateOf<AppUpdateChecker.UpdateResult?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result = AppUpdateChecker.checkUpdate()
        updateResult = result
        showDialog = result != null
    }

    val result = updateResult
    if (showDialog && result != null) {
        AppUpdateDialog(
            result = result,
            onDismiss = { if (!downloading) showDialog = false },
            onDownload = {
                if (!downloading) {
                    val url = result.downloadUrl
                    if (url == null) {
                        openUrl(result.releaseUrl)
                    } else {
                        downloading = true
                        scope.launch {
                            val completed = downloadUpdate(
                                url = url,
                                fileName = result.assetName ?: "CPPlayer-${result.versionName}",
                            )
                            downloading = false
                            if (completed) showDialog = false
                        }
                    }
                }
            },
        )
    }
}
