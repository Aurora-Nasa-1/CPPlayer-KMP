package cp.player.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.delay

actual fun isAndroidPlatform(): Boolean = true
actual fun desktopPlatform(): String = "android"

/** 当前活动网络是否计费（蜂窝 / 计费热点）。读不到系统服务时按非计费处理。 */
actual fun isNetworkMetered(): Boolean = runCatching {
    connectivityManagerOrNull()?.isActiveNetworkMetered ?: false
}.getOrDefault(false)

/**
 * 网络计费状态流：`registerDefaultNetworkCallback` 监听系统网络回调。
 *
 * - `onCapabilitiesChanged` 会**周期性重发**同一状态（系统设计如此），
 *   用 `distinctUntilChanged` 收敛成「变化才发」；
 * - WiFi 断开瞬间 `onCapabilitiesChanged` 可能不发，`onAvailable` / `onLost`
 *   兜底再读一次 `isActiveNetworkMetered`；
 * - 收集开始时先发当前值（订阅方由此拿到初始状态），取消收集即注销回调。
 */
actual fun networkMeteredChanges(): Flow<Boolean> = callbackFlow {
    trySend(isNetworkMetered())
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            networkCapabilities: android.net.NetworkCapabilities,
        ) {
            trySend(!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        override fun onAvailable(network: android.net.Network) {
            trySend(isNetworkMetered())
        }

        override fun onLost(network: android.net.Network) {
            trySend(isNetworkMetered())
        }
    }
    runCatching { connectivityManagerOrNull()?.registerDefaultNetworkCallback(callback) }
    awaitClose { runCatching { connectivityManagerOrNull()?.unregisterNetworkCallback(callback) } }
}.distinctUntilChanged()

private fun connectivityManagerOrNull(): ConnectivityManager? =
    ctxOrNull?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

/**
 * Android 16（API 36，`Build.VERSION_CODES.BAKLAVA`）起系统 UI 把 Google Sans
 * Flexible 的 ROND 轴开到最大。用字面量 36 而不是常量引用：BAKLAVA 要求 compileSdk 36+，
 * 写字面量让这条判据在低 compileSdk 的分支也能一眼读懂。
 */
actual fun defaultFontRoundness(): Int = if (Build.VERSION.SDK_INT >= 36) 100 else 0

/**
 * 把二维码写进系统相册（Pictures/CPPlayer）。
 *
 * ⚠️ 返回值必须是**真的写进去了没有** —— 调用方拿它决定「这张图以后还要不要再试」。
 * 下面两条失败路径（系统拒绝 insert、Q 以下写公共目录抛 SecurityException）都返回 false：
 * - API 29+：`MediaStore.insert` 可能返回 null（媒体库被禁用 / 名额满）；
 * - API 24–28：写 `Pictures` 公共目录**需要 WRITE_EXTERNAL_STORAGE**，本应用清单里没有
 *   （见 app-android 的 AndroidManifest.xml），因此这段在 7/8/9 上必定抛 SecurityException
 *   ⇒ 以前被 catch 成一句「保存失败」toast，现在照样如实返回 false，让自动保存下次再试。
 *   要真正修好它得补权限 + 运行时申请流程，不属于这里。
 */
actual fun saveQrCodeToGallery(base64Image: String, fileName: String): Boolean {
    val ctx = ctxOrNull ?: return false
    var bitmap: Bitmap? = null
    return try {
        val cleanBase64 = base64Image
            .substringAfter("base64,")
            .trim()
        val decoded = Base64.decode(cleanBase64, Base64.DEFAULT)
        bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.size) ?: return false
        val saved = saveBitmapToGallery(ctx, bitmap, fileName)
        if (saved) {
            sendPlatformToast("二维码已保存到相册")
        }
        saved
    } catch (e: Exception) {
        sendPlatformToast("保存失败: ${e.message}")
        false
    } finally {
        // 回收必须放在 finally：上面每个 return 之前都别再各自 recycle 一遍。
        bitmap?.recycle()
    }
}

actual fun saveImageToLocal(
    imageBytes: ByteArray,
    fileName: String,
    extension: String,
    dialogTitle: String,
): ImageSaveResult {
    val ctx = ctxOrNull ?: return ImageSaveResult.Failed
    if (imageBytes.isEmpty()) return ImageSaveResult.Failed
    var bitmap: Bitmap? = null
    return try {
        bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: return ImageSaveResult.Failed
        if (saveBitmapToGallery(ctx, bitmap, fileName)) {
            ImageSaveResult.Saved(location = null)
        } else {
            ImageSaveResult.Failed
        }
    } catch (_: Exception) {
        ImageSaveResult.Failed
    } finally {
        // 回收必须放在 finally：上面每个 return 之前都别再各自 recycle 一遍。
        bitmap?.recycle()
    }
}

private fun saveBitmapToGallery(ctx: Context, bitmap: Bitmap, fileName: String): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveQrViaMediaStore(ctx, bitmap, fileName)
    } else {
        saveQrToPublicPictures(ctx, bitmap, fileName)
    }

/** API 29+：走 MediaStore，不需要任何存储权限。 */
private fun saveQrViaMediaStore(
    ctx: Context,
    bitmap: Bitmap,
    fileName: String,
): Boolean {
    val values = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CPPlayer")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    return try {
        // 只有 `compress` 真的返回 true 才算写进去了；流拿不到（null）同样算失败，
        // 否则账本会记下「已存过」，而相册里其实只有一条打不开的空图。
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
    } catch (e: Exception) {
        // 写流失败时把刚插入的空条目删掉，别在相册里留一条打不开的图。
        runCatching { ctx.contentResolver.delete(uri, null, null) }
        throw e
    }
}

/** API 24–28：直接写公共 Pictures 目录（需要 WRITE_EXTERNAL_STORAGE，见函数 KDoc）。 */
private fun saveQrToPublicPictures(
    ctx: Context,
    bitmap: Bitmap,
    fileName: String,
): Boolean {
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
    return true
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

actual fun openWebview(url: String, title: String?) {
    val ctx = ctxOrNull ?: return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {}
}


actual suspend fun downloadUpdate(url: String, fileName: String): Boolean =
    downloadAndInstallUpdate(
        url, fileName, expectedSha256 = null, expectedSize = null,
        beforeInstall = { true },
    )

actual suspend fun downloadAndInstallUpdate(
    url: String,
    fileName: String,
    expectedSha256: String?,
    expectedSize: Long?,
    beforeInstall: suspend () -> Boolean,
): Boolean {
    val ctx = ctxOrNull ?: return false
    val safeFileName = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "CPPlayer-update.apk" }
    try {
        val uniqueFileName = if (safeFileName.endsWith(".apk", ignoreCase = true)) {
            "${safeFileName.dropLast(4)}-${System.currentTimeMillis()}.apk"
        } else {
            "$safeFileName-${System.currentTimeMillis()}"
        }
        val request = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(uniqueFileName)
            .setDescription("CPPlayer 更新包")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, uniqueFileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val downloadId = manager.enqueue(request)
        sendPlatformToast("正在下载更新，完成后将打开系统安装界面")
        while (true) {
            val cursor = manager.query(android.app.DownloadManager.Query().setFilterById(downloadId))
                ?: return false
            val status = cursor.use {
                if (!it.moveToFirst()) return false
                it.getInt(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
            }
            when (status) {
                android.app.DownloadManager.STATUS_SUCCESSFUL -> break
                android.app.DownloadManager.STATUS_FAILED -> {
                    sendPlatformToast("更新下载失败，错误码：${downloadFailureReason(manager, downloadId)}")
                    return false
                }
            }
            delay(750)
        }
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return false
        if (!verifyDownloadedUpdate(ctx, uri, expectedSha256, expectedSize)) {
            manager.remove(downloadId)
            sendPlatformToast("更新包完整性校验失败，已阻止安装")
            return false
        }
        if (!beforeInstall()) {
            sendPlatformToast("更新前清理任务未能保存，已阻止安装；现有模块未删除")
            return false
        }
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(installIntent)
        sendPlatformToast("系统安装界面已打开，请按提示确认")
        return true
    } catch (e: Exception) {
        sendPlatformToast("更新下载失败: ${e.message ?: "未知错误"}")
        return false
    }
}

private fun downloadFailureReason(manager: android.app.DownloadManager, downloadId: Long): Int =
    runCatching {
        manager.query(android.app.DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON)) else -1
        } ?: -1
    }.getOrDefault(-1)

private fun verifyDownloadedUpdate(
    context: Context,
    uri: Uri,
    expectedSha256: String?,
    expectedSize: Long?,
): Boolean = runCatching {
    val input = context.contentResolver.openInputStream(uri) ?: return false
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    input.use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
            total += count
        }
    }
    if (total <= 0L || (expectedSize != null && total != expectedSize)) return false
    val expectedHash = expectedSha256?.removePrefix("sha256:")?.takeIf { it.isNotBlank() }
    if (expectedHash != null) {
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(expectedHash, ignoreCase = true)) return false
    }
    true
}.getOrDefault(false)

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

actual fun imageCacheSizeBytes(): Long {
    val ctx = ctxOrNull ?: return -1L
    return try {
        coil3.SingletonImageLoader.get(ctx).diskCache?.size ?: -1L
    } catch (_: Exception) {
        -1L
    }
}

/**
 * 在系统文件管理器中定位 [path]（目录或文件）。
 *
 * 此前这里是 `= false` 的占位实现 —— 本地歌曲行的「在文件管理器中打开」在
 * Android 上**任何输入都返回失败**，点下去没有任何反馈。
 *
 * Android 没有「选中某个文件」的统一 Intent，因此按目标类型分层：
 *
 * 1. **`content://`**（SAF 导入）：用 DocumentsUI 打开该文档 / 目录树。
 * 2. **主外部存储下的绝对路径**：映射成 ExternalStorageProvider 的
 *    `primary:<相对路径>` 文档 URI，让 DocumentsUI 列出父目录。
 * 3. **兜底**（应用私有目录）：经 FileProvider 授权，让用户自选应用打开文件。
 *
 * ⚠️ 应用私有目录（`Android/data/<pkg>/files/...`，即下载产物）在 Android 11+
 * 对文件管理器不可见 —— 那是系统限制，没有绕过方式；第 3 步至少能让文件被打开。
 * 两条都失败时返回 false，由调用方给用户一条明确提示，而不是静默无反应。
 */
actual fun openInFileManager(path: String): Boolean {
    val ctx = ctxOrNull ?: return false
    if (path.isBlank()) return false
    return runCatching {
        if (path.startsWith("content://")) {
            revealContentUri(ctx, Uri.parse(path))
        } else {
            revealFilePath(ctx, path)
        }
    }.getOrDefault(false)
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * SAF 文档 / 目录树 → DocumentsUI。
 *
 * - 树 URI：先换成根文档 URI，DocumentsUI 会列出该目录；
 * - 普通文档 URI（导入的单个文件）：ExternalStorageProvider 的 documentId 是分层路径
 *   （`primary:Music/专辑/1.flac`），去掉最后一段就是父目录 —— 打开父目录才算「在文件
 *   管理器中定位」，否则 ACTION_VIEW 会直接拉一个播放器把歌放起来。
 */
private fun revealContentUri(ctx: Context, uri: Uri): Boolean {
    val type = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
    if (runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) {
        val rootUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
        }.getOrNull() ?: uri
        if (launchDocumentsUi(ctx, rootUri)) return true
        return launchView(ctx, rootUri, type ?: DocumentsContract.Document.MIME_TYPE_DIR)
    }
    if (uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val parentId = documentId?.substringBeforeLast('/', missingDelimiterValue = "")
        if (!parentId.isNullOrEmpty() && parentId != documentId) {
            val parentUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, parentId)
            if (launchDocumentsUi(ctx, parentUri)) return true
        }
    }
    return launchView(ctx, uri, type)
}

/** 绝对路径 → 主外部存储的父目录；私有目录不可达时回退到 FileProvider。 */
private fun revealFilePath(ctx: Context, path: String): Boolean {
    val file = File(path)
    val dir = if (file.isDirectory) file else file.parentFile
    val root = Environment.getExternalStorageDirectory()?.absolutePath
    if (dir != null && !root.isNullOrBlank()) {
        val dirPath = dir.absolutePath
        if (dirPath == root || dirPath.startsWith("$root/")) {
            val relative = dirPath.removePrefix(root).trimStart('/')
            val documentId = if (relative.isEmpty()) "primary:" else "primary:$relative"
            val documentUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
            if (launchDocumentsUi(ctx, documentUri)) return true
        }
    }
    // 应用私有目录（下载产物）文件管理器看不到，只能把文件本身交出去。
    return launchSharedFile(ctx, file)
}

/**
 * 私有目录文件的最后手段：FileProvider + `ACTION_VIEW`（用户自选应用打开）。
 *
 * Android 11+ 的文件管理器打不开 `Android/data/…`，但**别的应用**仍可以通过
 * 一次性 URI 授权读取。白名单见 `app-android/src/main/res/xml/file_paths.xml`；
 * 不在白名单里的路径 FileProvider 会抛 `IllegalArgumentException`，由外层降级为失败。
 */
private fun launchSharedFile(ctx: Context, file: File): Boolean = runCatching {
    if (!file.exists()) return@runCatching false
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val mimeType = ctx.contentResolver.getType(uri) ?: guessMimeType(file.name)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    tryStartActivity(ctx, intent)
}.getOrDefault(false)

/** 兜底 MIME：ContentResolver 给不出类型时按扩展名猜（只覆盖本项目会下载的格式）。 */
private fun guessMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a", "aac" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> "*/*"
}

/**
 * 显式点名系统「文件」应用（DocumentsUI）。
 *
 * ⚠️ 目录 URI**必须**这样发：`ACTION_VIEW` 的隐式解析会把 `vnd.android.document/directory`
 * 交给任意声明了该 MIME 的应用，实测会被播放器接走 —— 用户点「在文件管理器中打开」，
 * 结果歌直接开始播。点名组件在 AOSP / 主流 ROM 上稳定存在；自定义 ROM 上找不到时
 * 返回 false，由调用方降级。
 */
private fun launchDocumentsUi(ctx: Context, uri: Uri): Boolean = tryStartActivity(
    ctx,
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        setClassName(
            "com.android.documentsui",
            "com.android.documentsui.files.FilesActivity",
        )
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
)

/** [mimeType] 为 null 时用通配 MIME：系统仍会按 provider 给出的实际类型挑应用。 */
private fun launchView(ctx: Context, uri: Uri, mimeType: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return tryStartActivity(ctx, intent)
}

/**
 * 直接 startActivity + 捕获异常，不用 `resolveActivity`：
 * Android 11+ 的包可见性会让 `resolveActivity` 对未声明的 `<queries>` 返回 null，
 * 把「其实能打开」误判成失败（隐式 Intent 的启动本身不受该限制）。
 */
private fun tryStartActivity(ctx: Context, intent: Intent): Boolean = try {
    ctx.startActivity(intent)
    true
} catch (_: Exception) {
    false
}

// ============ 媒体扫描运行时权限（app 模块不依赖 app-android，经回调桥接 MainActivity） ============

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

// ============ 电池优化白名单（熄屏后台保活的系统层前提） ============

actual fun isIgnoringBatteryOptimizations(): Boolean {
    val ctx = ctxOrNull ?: return true
    if (Build.VERSION.SDK_INT < 23) return true
    return try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) {
        // 拿不到判据时按「已忽略」处理，避免设置页把用户往无效方向引导。
        true
    }
}

actual fun requestIgnoreBatteryOptimizations() {
    val ctx = ctxOrNull ?: return
    if (isIgnoringBatteryOptimizations()) return
    try {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    } catch (_: Exception) {
        // 部分 ROM 阉割了直达入口，退回电池优化总列表。
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            sendPlatformToast("请到系统设置的电池页面关闭 CPPlayer 的电池优化")
        }
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
actual fun PlatformRenderTuningContent() {
    // Android 的渲染完全交给系统（SurfaceFlinger / HWUI），没有可切换的 Skiko 后端，
    // 该设置入口在 Android 上也不会出现在设置列表里（见 SettingsScreen.settingsEntries）。
}

@Composable
actual fun PlatformCloseBehaviorSetting(index: Int, total: Int) {
    // Android 没有「关闭窗口」这个动作（Activity 生命周期由系统管），
    // 「最小化到托盘 / 直接退出」两档都没有对应概念 ⇒ 空实现。
    // 该行只在桌面端有意义，`MessageNotifySettingsScreen` 里由这个 actual 决定渲染与否。
}

// ============ 激进保活（Wi-Fi 高性能锁 + 组播锁） ============

/**
 * 持锁状态收在模块级：`applyAggressiveStandby` 会在「启动恢复」「用户切换开关」两处被调用，
 * 每次新建锁对象会让上一把锁永久泄漏（系统侧一直认为有应用在抓着 Wi-Fi）。
 */
@Volatile
private var standbyWifiLock: android.net.wifi.WifiManager.WifiLock? = null

@Volatile
private var standbyMulticastLock: android.net.wifi.WifiManager.MulticastLock? = null

private fun wifiManagerOrNull(): android.net.wifi.WifiManager? =
    ctxOrNull?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager

actual fun applyAggressiveStandby(enabled: Boolean) {
    val wm = wifiManagerOrNull()
    if (wm == null) {
        // 取不到系统服务（部分 ROM 阉割 / 权限被拒）时如实清空状态，
        // 让 isAggressiveStandbyActive() 返回 false —— 不假装成功。
        releaseStandbyLocks()
        return
    }
    if (enabled) {
        if (standbyWifiLock == null) {
            standbyWifiLock = runCatching {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    // 低延迟模式是 API 29+ 为「实时音视频 / 流媒体」保留的档位：
                    // 既保 Wi-Fi 常开，又不会像 HIGH_PERF 那样被新版本当作已废弃行为。
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL
                }
                wm.createWifiLock(mode, "cpplayer-standby").apply {
                    // 非引用计数：重复 enable/disable 时不会因为计数不平衡而把锁漏在那里。
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
        if (standbyMulticastLock == null) {
            standbyMulticastLock = runCatching {
                wm.createMulticastLock("cpplayer-multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    } else {
        releaseStandbyLocks()
    }
}

private fun releaseStandbyLocks() {
    // 逐项 runCatching：一把锁释放失败不该阻止另一把被释放。
    runCatching { standbyMulticastLock?.takeIf { it.isHeld }?.release() }
    runCatching { standbyWifiLock?.takeIf { it.isHeld }?.release() }
    standbyMulticastLock = null
    standbyWifiLock = null
}

actual fun isAggressiveStandbyActive(): Boolean =
    standbyWifiLock?.isHeld == true || standbyMulticastLock?.isHeld == true

// ============ Shizuku（超级岛歌词的 XMSF 断网隔离） ============

actual fun shizukuState(): ShizukuState {
    val ctx = ctxOrNull ?: return ShizukuState.NOT_INSTALLED
    if (!cp.player.app.platform.lyricpush.ShizukuPermissions.isInstalled(ctx)) {
        return ShizukuState.NOT_INSTALLED
    }
    return if (cp.player.app.platform.lyricpush.ShizukuPermissions.isGranted()) {
        ShizukuState.READY
    } else {
        // 包含「装了但没启动」与「启动了但未授权」两种：对用户而言都是「还没就绪」。
        ShizukuState.UNAUTHORIZED
    }
}

actual fun requestShizukuPermission(onResult: (ShizukuState) -> Unit) {
    val ctx = ctxOrNull ?: return onResult(ShizukuState.NOT_INSTALLED)
    cp.player.app.platform.lyricpush.ShizukuPermissions.requestPermission { granted ->
        if (granted) {
            // 授权成功后立刻把 keepalive 绑上，别等下一次播放（移植指南 §4.2）。
            cp.player.app.platform.lyricpush.ShizukuKeepAlive.ensureBound(ctx)
        }
        onResult(shizukuState())
    }
}

actual fun platformMemoryInfo(): PlatformMemoryInfo {
    val rt = Runtime.getRuntime()
    val total = rt.totalMemory()
    val free = rt.freeMemory()
    val max = rt.maxMemory()
    return PlatformMemoryInfo(
        usedBytes = total - free,
        maxBytes = max,
        totalBytes = total,
        freeBytes = free,
    )
}

@Composable
actual fun SyncSystemBarsEffect(darkTheme: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

