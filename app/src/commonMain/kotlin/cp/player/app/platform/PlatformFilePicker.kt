package cp.player.app.platform

import androidx.compose.runtime.Composable

/**
 * 平台 zip 模块选择器。
 * 返回一个触发选择文件的函数；选择完成后回调 [onPicked] 给出 zip 文件绝对路径（取消时为 null）。
 */
@Composable
expect fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit

/**
 * 平台 zip 模块保存选择器（音源导出用）。
 *
 * 触发系统「保存文件」对话框；用户选定目标后回调 [onWriteTo]，参数是**可写的目标路径**：
 * - Desktop：用户选择的 zip 绝对路径，直接写入（回调可能被切到 IO 线程执行，写入不阻塞 UI）。
 * - Android：cache 下的临时文件路径 —— 回调**同步**把 zip 写进该路径（此调用已在 IO 线程上），
 *   返回 true 表示写入成功，平台层随后把它拷贝到用户通过 SAF 选定的目标并删除临时文件；
 *   返回 false / 抛异常都视为导出失败。
 * 用户取消时回调参数为 null（返回值被忽略）。
 */
@Composable
expect fun rememberZipSaver(fileName: String, onWriteTo: (destPath: String?) -> Boolean): () -> Unit

/**
 * 平台目录选择器。
 *
 * 返回一个触发选择目录的函数；选择完成后回调 [onPicked]：
 * - Desktop：目录绝对路径
 * - Android：SAF 树 URI 字符串（已 takePersistableUriPermission）
 * 取消时为 null。
 */
@Composable
expect fun rememberDirectoryPicker(onPicked: (dirPath: String?) -> Unit): () -> Unit

/**
 * 平台 Toast/Snackbar 短提示的能力标记（桌面用窗口标题/状态；Android 用 Toast）。
 * 由各平台 actual 实现具体行为。
 */
expect fun sendPlatformToast(message: String)