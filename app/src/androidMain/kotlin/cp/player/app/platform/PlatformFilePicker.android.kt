package cp.player.app.platform

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

@Composable
actual fun rememberZipSaver(fileName: String, onWriteTo: (destPath: String?) -> Boolean): () -> Unit {
    val context = LocalContext.current
    val currentOnWriteTo = rememberUpdatedState(onWriteTo)
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            currentOnWriteTo.value(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // 先在 cache 里让回调把 zip 写进临时文件（同步、已在 IO 线程），
            // 写成功后拷贝到 SAF 选定的目标 —— zip 写盘只能走文件路径，content:// 不通。
            withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "export_${System.currentTimeMillis()}.zip")
                try {
                    val written = runCatching { currentOnWriteTo.value(temp.absolutePath) }.getOrDefault(false)
                    if (written) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            temp.inputStream().use { it.copyTo(out) }
                        } ?: error("无法写入所选位置")
                    }
                    written
                } finally {
                    temp.delete()
                }
            }
        }
    }

    return { launcher.launch(fileName) }
}

@Composable
actual fun rememberDirectoryPicker(onPicked: (dirPath: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked = rememberUpdatedState(onPicked)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            currentOnPicked.value(null)
            return@rememberLauncherForActivityResult
        }
        // 持久化授权，保证后续访问该树不需再次弹框
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        currentOnPicked.value(uri.toString())
    }

    return { launcher.launch(null) }
}

actual fun sendPlatformToast(message: String) { toast(message) }

private var appContext: Context? = null
fun provideAppContext(context: Context) { if (appContext == null) appContext = context.applicationContext }
internal val ctxOrNull: Context? get() = appContext
private fun toast(msg: String) = appContext?.let {
    android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
} ?: Unit