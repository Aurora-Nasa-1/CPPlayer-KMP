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
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.runtime.Composable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

actual fun downloadUpdate(url: String, fileName: String) {
    val ctx = ctxOrNull ?: return
    try {
        val request = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("CPPlayer 更新包")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        manager.enqueue(request)
        sendPlatformToast("已开始下载更新")
    } catch (e: Exception) {
        openUrl(url)
    }
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

actual fun imageCacheSizeBytes(): Long {
    val ctx = ctxOrNull ?: return -1L
    return try {
        coil3.SingletonImageLoader.get(ctx).diskCache?.size ?: -1L
    } catch (_: Exception) {
        -1L
    }
}

actual fun openInFileManager(path: String): Boolean = false

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
    if (!cp.player.core.lyricpush.ShizukuPermissions.isInstalled(ctx)) {
        return ShizukuState.NOT_INSTALLED
    }
    return if (cp.player.core.lyricpush.ShizukuPermissions.isGranted()) {
        ShizukuState.READY
    } else {
        // 包含「装了但没启动」与「启动了但未授权」两种：对用户而言都是「还没就绪」。
        ShizukuState.UNAUTHORIZED
    }
}

actual fun requestShizukuPermission(onResult: (ShizukuState) -> Unit) {
    val ctx = ctxOrNull ?: return onResult(ShizukuState.NOT_INSTALLED)
    cp.player.core.lyricpush.ShizukuPermissions.requestPermission { granted ->
        if (granted) {
            // 授权成功后立刻把 keepalive 绑上，别等下一次播放（移植指南 §4.2）。
            cp.player.core.lyricpush.ShizukuKeepAlive.ensureBound(ctx)
        }
        onResult(shizukuState())
    }
}
