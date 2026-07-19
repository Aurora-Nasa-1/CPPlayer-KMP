package cp.player.app.platform

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
actual fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked = rememberUpdatedState(onPicked)
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) { currentOnPicked.value(null); return@rememberLauncherForActivityResult }
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(context.cacheDir, "temp_module_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temp).use { out -> input.copyTo(out) }
                    }
                    temp.absolutePath
                }.getOrNull()
            }
            currentOnPicked.value(path)
        }
    }

    return { launcher.launch("application/zip") }
}

actual fun sendPlatformToast(message: String) { toast(message) }

private var appContext: Context? = null
fun provideAppContext(context: Context) { if (appContext == null) appContext = context.applicationContext }
internal val ctxOrNull: Context? get() = appContext
private fun toast(msg: String) = appContext?.let {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
} ?: Unit