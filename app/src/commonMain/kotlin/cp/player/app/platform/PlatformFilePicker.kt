package cp.player.app.platform

import androidx.compose.runtime.Composable

/**
 * 平台 zip 模块选择器。
 * 返回一个触发选择文件的函数；选择完成后回调 [onPicked] 给出 zip 文件绝对路径（取消时为 null）。
 */
@Composable
expect fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit

/**
 * 平台 Toast/Snackbar 短提示的能力标记（桌面用窗口标题/状态；Android 用 Toast）。
 * 由各平台 actual 实现具体行为。
 */
expect fun sendPlatformToast(message: String)