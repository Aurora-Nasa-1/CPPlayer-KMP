package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面端文件与目录选择器。
 *
 * 全部走 [DesktopFileDialog]：Windows 用 Win32 COM `IFileDialog`（Windows 10/11
 * 现代资源管理器界面，选目录用 `FOS_PICKFOLDERS`），其它平台用系统原生选择器命令。
 *
 * ⚠️ 这里**刻意不引用任何 `java.awt` / `javax.swing` 类型**。历史上的实现用
 * AWT `FileDialog` + JBR 反射桥，JBR 不可用时回落到 Swing `JFileChooser`；
 * 后者在 GraalVM 原生镜像里会连崩两次（`ShellFolder.<clinit>` 反射加载
 * `Win32ShellFolderManager2` 失败、`UIManager` 拿不到 `WindowsFileChooserUI`）。
 * 而且 AWT `FileDialog` 弹的是 Win95 风格旧对话框，本来就不满足现代体验。
 * 详见 `app/graalvm/README.md`。
 */

@Composable
actual fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit =
    remember(onPicked) {
        {
            onPicked(DesktopFileDialog.openZip("选择音源模块 (.zip)"))
        }
    }

@Composable
actual fun rememberZipSaver(fileName: String, onWriteTo: (destPath: String?) -> Boolean): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(fileName, onWriteTo) {
        {
            val picked = DesktopFileDialog.saveZip("导出音源模块 (.zip)", fileName)
            // 系统保存框不保证补扩展名，缺 .zip 时补上。
            val chosen = picked?.let { if (it.lowercase().endsWith(".zip")) it else "$it.zip" }
            if (chosen != null) {
                // 写 zip 不阻塞 UI 线程；结果经 model 的 message StateFlow 反馈。
                scope.launch(Dispatchers.IO) { onWriteTo(chosen) }
            } else {
                onWriteTo(null)
            }
        }
    }
}

actual fun sendPlatformToast(message: String): Unit = Unit

@Composable
actual fun rememberDirectoryPicker(onPicked: (dirPath: String?) -> Unit): () -> Unit =
    remember(onPicked) {
        {
            onPicked(DesktopFileDialog.pickDirectory("选择目录"))
        }
    }
