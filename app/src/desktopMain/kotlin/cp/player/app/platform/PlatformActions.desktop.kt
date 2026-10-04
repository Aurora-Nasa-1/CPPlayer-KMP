package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.awt.Desktop
import java.net.URI

actual fun isAndroidPlatform(): Boolean = false
actual fun desktopPlatform(): String = when {
    System.getProperty("os.name", "").contains("win", ignoreCase = true) -> "windows"
    System.getProperty("os.name", "").contains("linux", ignoreCase = true) -> "linux"
    else -> "desktop"
}

/** 桌面端无「计费网络」概念，恒按非计费（WiFi）处理。 */
actual fun isNetworkMetered(): Boolean = false

/** 桌面端网络不变化，先发一次 false 后不再发射。 */
actual fun networkMeteredChanges(): Flow<Boolean> = flowOf(false)

/** 桌面端没有「系统圆滑度」一说，跟随字体文件自带的默认实例（ROND = 0，方正）。 */
actual fun defaultFontRoundness(): Int = 0

actual fun saveQrCodeToGallery(base64Image: String, fileName: String) {}

actual fun openTargetApp(packageName: String) {}

actual fun isPackageInstalled(packageName: String): Boolean = false

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {}
}

actual fun downloadUpdate(url: String, fileName: String) = openUrl(url)

/**
 * 清空 Coil 的内存 / 磁盘缓存。
 *
 * 原先这里是 `= true` 的空实现 —— 设置页点了「清除图片缓存」会显示成功，
 * 实际什么都没清。加了封面取色之后这个谎言更明显：封面换不掉，主题就跟着换不掉。
 *
 * 同时清掉 [cp.player.app.ui.theme.CoverSeedCache]：它按封面 URL 缓存种子色，
 * 不清的话「清完缓存重新取色」的预期不成立。
 */
actual fun clearImageCache(): Boolean {
    cp.player.app.ui.theme.CoverSeedCache.clear()
    return runCatching {
        val loader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        true
    }.getOrDefault(false)
}

actual fun imageCacheSizeBytes(): Long = runCatching {
    coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE).diskCache?.size ?: -1L
}.getOrDefault(-1L)

actual fun openInFileManager(path: String): Boolean = runCatching {
    val dir = java.io.File(path)
    if (!dir.isDirectory) return@runCatching false
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        Desktop.getDesktop().open(dir)
        true
    } else {
        false
    }
}.getOrDefault(false)

actual fun requestMediaScanPermission() {
    // 桌面无需运行时媒体读取权限，空实现
}

actual fun setOnMediaPermissionGranted(callback: (() -> Unit)?) {
    // 桌面无授权流程，无需保存回调
}

/** 桌面无电池优化策略，按「已忽略」处理，设置页不引导。 */
actual fun isIgnoringBatteryOptimizations(): Boolean = true

actual fun requestIgnoreBatteryOptimizations() {
    // 桌面空操作
}

/**
 * 桌面端用 Esc 承担安卓返回键的角色。
 *
 * 这里只负责「注册/注销」，真正的派发在窗口级按键回调里（见 `Main.kt`）——
 * 因为 Compose 的按键回调只能挂在 Window 上，组件内部拿不到全局按键流。
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val latest by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val token = DesktopBackDispatcher.register { latest() }
        onDispose { DesktopBackDispatcher.unregister(token) }
    }
}
