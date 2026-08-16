package cp.player.app.platform

import androidx.compose.runtime.Composable

/**
 * 当前是否运行在 Android 平台。
 *
 * 用于能力差异判别（如 SAF 树 URI 不能作为下载写入目录，
 * Android 下载固定保存到应用私有目录）。
 */
expect fun isAndroidPlatform(): Boolean

/** Current desktop distribution family (`windows` or `linux`). */
expect fun desktopPlatform(): String

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

/** Download a release asset using the platform's native download flow. */
expect fun downloadUpdate(url: String, fileName: String)

/**
 * 清空应用图片缓存（Coil 内存 + 磁盘）。
 *
 * @return 是否执行了清理
 */
expect fun clearImageCache(): Boolean

/**
 * 申请本地媒体扫描所需的运行时读取权限。
 * Android 端触发系统授权弹窗（READ_MEDIA_AUDIO/VIDEO 或 READ_EXTERNAL_STORAGE），
 * Desktop 端无需权限，为空操作。
 */
expect fun requestMediaScanPermission()

/**
 * 媒体读取权限授予结果的回调钩子（UI 层注册，用于授权后自动重试扫描）。
 * 由平台层在权限授予后调用；默认空实现。
 */
expect fun setOnMediaPermissionGranted(callback: (() -> Unit)?)

/**
 * 处理返回键事件。
 * Android 端使用 BackHandler，Desktop 端为空操作。
 *
 * @param enabled 是否启用返回键处理
 * @param onBack 返回键按下时的回调
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

