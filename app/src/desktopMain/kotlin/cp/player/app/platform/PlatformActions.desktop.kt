package cp.player.app.platform

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.net.URI

actual fun isAndroidPlatform(): Boolean = false

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

actual fun clearImageCache(): Boolean = true

actual fun requestMediaScanPermission() {
    // 桌面无需运行时媒体读取权限，空实现
}

actual fun setOnMediaPermissionGranted(callback: (() -> Unit)?) {
    // 桌面无授权流程，无需保存回调
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op for Desktop
}
