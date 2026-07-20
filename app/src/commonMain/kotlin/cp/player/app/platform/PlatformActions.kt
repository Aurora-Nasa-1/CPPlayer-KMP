package cp.player.app.platform

import androidx.compose.runtime.Composable

/**
 * 保存 Base64 编码的图片到系统相册。
 * 仅 Android 端生效，Desktop 端为空操作。
 *
 * @param base64Image Base64 编码的图片数据（不含 data:image/... 前缀）
 * @param fileName 保存的文件名（不含扩展名）
 */
expect fun saveQrCodeToGallery(base64Image: String, fileName: String)

/**
 * 通过包名打开目标 App。
 * 仅 Android 端生效。
 *
 * @param packageName 目标 App 的 Android 包名
 */
expect fun openTargetApp(packageName: String)

/**
 * 检查目标 App 是否已安装。
 * 仅 Android 端生效，Desktop 端始终返回 false。
 *
 * @param packageName Android 包名
 */
expect fun isPackageInstalled(packageName: String): Boolean

/**
 * 用系统浏览器打开 URL。
 *
 * @param url 要打开的链接
 */
expect fun openUrl(url: String)

/**
 * 清空应用图片缓存（Coil 内存 + 磁盘）。
 *
 * @return 是否执行了清理
 */
expect fun clearImageCache(): Boolean

/**
 * 处理返回键事件。
 * Android 端使用 BackHandler，Desktop 端为空操作。
 *
 * @param enabled 是否启用返回键处理
 * @param onBack 返回键按下时的回调
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
