package cp.player.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

actual fun isAndroidPlatform(): Boolean = true

actual fun saveQrCodeToGallery(base64Image: String, fileName: String) {
    val ctx = ctxOrNull ?: return
    try {
        val cleanBase64 = base64Image
            .substringAfter("base64,")
            .trim()
        val decoded = Base64.decode(cleanBase64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CPPlayer")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                sendPlatformToast("二维码已保存到相册")
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val cpDir = File(dir, "CPPlayer").apply { mkdirs() }
            val file = File(cpDir, "$fileName.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(file)
            }
            ctx.sendBroadcast(intent)
            sendPlatformToast("二维码已保存到相册")
        }
        bitmap.recycle()
    } catch (e: Exception) {
        sendPlatformToast("保存失败: ${e.message}")
    }
}

actual fun openTargetApp(packageName: String) {
    val ctx = ctxOrNull ?: return
    try {
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } else {
            try {
                val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(marketIntent)
            } catch (_: Exception) {
                sendPlatformToast("未找到目标应用")
            }
        }
    } catch (e: Exception) {
        sendPlatformToast("打开失败: ${e.message}")
    }
}

actual fun isPackageInstalled(packageName: String): Boolean {
    val ctx = ctxOrNull ?: return false
    return try {
        ctx.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

actual fun openUrl(url: String) {
    val ctx = ctxOrNull ?: return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (_: Exception) {}
}

actual fun clearImageCache(): Boolean {
    val ctx = ctxOrNull ?: return false
    return try {
        val loader = coil3.SingletonImageLoader.get(ctx)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        true
    } catch (_: Exception) {
        false
    }
}

// ============ 媒体扫描运行时权限（app 模块不依赖 androidApp，经回调桥接 MainActivity） ============

@Volatile
private var mediaPermissionRequester: (() -> Unit)? = null

@Volatile
private var mediaPermissionGrantedCallback: (() -> Unit)? = null

/** 注册权限申请入口（由 MainActivity 启动时调用，内部调 requestPermissions）。 */
fun setMediaPermissionRequester(requester: (() -> Unit)?) {
    mediaPermissionRequester = requester
}

/** 权限已授予时由 Activity 层调用，转发给已注册的 UI 回调（如自动重试扫描）。 */
fun notifyMediaReadPermissionGranted() {
    mediaPermissionGrantedCallback?.invoke()
}

actual fun requestMediaScanPermission() {
    mediaPermissionRequester?.invoke()
}

actual fun setOnMediaPermissionGranted(callback: (() -> Unit)?) {
    mediaPermissionGrantedCallback = callback
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
