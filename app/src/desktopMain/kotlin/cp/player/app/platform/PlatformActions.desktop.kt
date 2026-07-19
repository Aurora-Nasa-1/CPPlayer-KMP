package cp.player.app.platform

import java.awt.Desktop
import java.net.URI

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
