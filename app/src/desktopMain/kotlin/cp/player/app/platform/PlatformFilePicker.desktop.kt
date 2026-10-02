package cp.player.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.awt.FileDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
actual fun rememberZipPicker(onPicked: (zipPath: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val frame = JFrame().apply { isVisible = false }
            try {
                val dialog = FileDialog(frame, "选择音源模块 (.zip)", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.lowercase().endsWith(".zip") }
                    isVisible = true
                }
                val file = dialog.file
                val dir = dialog.directory
                if (file != null && dir != null) {
                    onPicked(java.io.File(dir, file).absolutePath)
                } else {
                    onPicked(null)
                }
            } finally {
                frame.dispose()
            }
        }
    }
}

@Composable
actual fun rememberZipSaver(fileName: String, onWriteTo: (destPath: String?) -> Boolean): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(fileName, onWriteTo) {
        {
            val frame = JFrame().apply { isVisible = false }
            try {
                val dialog = FileDialog(frame, "导出音源模块 (.zip)", FileDialog.SAVE).apply {
                    setFile(fileName)
                    isVisible = true
                }
                val file = dialog.file
                val dir = dialog.directory
                if (file != null && dir != null) {
                    // FileDialog.SAVE 在部分平台（Windows）不强制扩展名，缺 .zip 时补上。
                    val chosen = if (file.lowercase().endsWith(".zip")) file else "$file.zip"
                    val dest = java.io.File(dir, chosen).absolutePath
                    // 写 zip 不阻塞 UI 线程（EDT）；结果经 model 的 message StateFlow 反馈。
                    scope.launch(Dispatchers.IO) { onWriteTo(dest) }
                } else {
                    onWriteTo(null)
                }
            } finally {
                frame.dispose()
            }
        }
    }
}

actual fun sendPlatformToast(message: String): Unit = Unit

@Composable
actual fun rememberDirectoryPicker(onPicked: (dirPath: String?) -> Unit): () -> Unit {
    return remember(onPicked) {
        {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "选择目录"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                onPicked(chooser.selectedFile?.absolutePath)
            } else {
                onPicked(null)
            }
        }
    }
}